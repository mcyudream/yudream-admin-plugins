package online.yudream.base.plugin.mcpanel.application;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.application.service.*;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.*;
import online.yudream.base.plugin.mcpanel.domain.repo.*;
import online.yudream.base.plugin.mcpanel.domain.service.PortAllocator;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeCallException;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.*;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.mcpanel.interfaces.http.InstanceDeploymentFacade;
import online.yudream.base.plugin.mcpanel.support.TestStubs;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

/** Filesystem-backed protocol tests: bytes, interrupted replies and durable recovery. */
class InstanceDeploymentServiceTest {
    @TempDir Path root;
    private static final String SCOPE = "user:1";

    @Test void overlaysSelectedFilesAndRollsBackWithoutTouchingTargetOnlyContent() throws Exception {
        try (Fixture f = new Fixture(root)) {
            f.write("source", "plugins/a.yml", "new"); f.write("source", "plugins/b.yml", "added");
            f.write("target", "plugins/a.yml", "old"); f.write("target", "plugins/local.yml", "keep");
            var preview = f.deployments.preview(SCOPE, "source", "target", List.of("plugins"));
            assertEquals(1, preview.get("replaceCount")); assertEquals(1, preview.get("addCount"));
            var job = f.deployments.deploy("1", SCOPE, "source", (String) preview.get("planId"));
            f.await(job, "done");
            assertEquals("new", f.read("target", "plugins/a.yml"));
            assertEquals("keep", f.read("target", "plugins/local.yml"));
            f.deployments.rollback("1", SCOPE, "target", (String) job.get("id"));
            f.await(job, "rolled-back");
            assertEquals("old", f.read("target", "plugins/a.yml"));
            assertFalse(Files.exists(f.path("target", "plugins/b.yml")));
            assertEquals("keep", f.read("target", "plugins/local.yml"));
        }
    }

    @Test void stagingFailureLeavesAllOriginalsAndReleasesMaintenance() throws Exception {
        try (Fixture f = new Fixture(root)) {
            f.write("source", "a", "new"); f.write("target", "a", "old");
            f.node.before = (method, payload) -> { if (method.equals("file.upload.commit")) throw McpanelBusinessException.invalid("disk full"); return null; };
            var job = f.deploy(); f.await(job, "rolled-back");
            assertEquals("old", f.read("target", "a"));
            assertEquals("", f.app.detail(SCOPE, "target").get("maintenanceJobId"));
        }
    }

    @Test void submissionStorageFailureReleasesReservationsBeforeAnyFileMutation() throws Exception {
        try (Fixture f = new Fixture(root)) {
            f.write("source", "a", "new"); f.write("target", "a", "old");
            f.documents.fail = (collection, document) -> collection.equals("mcpanel_deployments");
            assertThrows(IllegalStateException.class, f::deploy);
            assertEquals("old", f.read("target", "a"));
            assertEquals("", f.app.detail(SCOPE, "source").get("maintenanceJobId"));
            assertEquals("", f.app.detail(SCOPE, "target").get("maintenanceJobId"));
        }
    }

    @Test void initialWorkerStorageFailureRecoversAndReleasesTarget() throws Exception {
        try (Fixture f = new Fixture(root)) {
            f.write("source", "a", "new"); f.write("target", "a", "old");
            AtomicBoolean once = new AtomicBoolean();
            f.documents.fail = (collection, document) -> collection.equals("mcpanel_deployments")
                    && document.containsKey("fileCount") && once.compareAndSet(false, true);
            var job = f.deploy(); f.await(job, "rolled-back");
            assertEquals("old", f.read("target", "a"));
            assertEquals("", f.app.detail(SCOPE, "target").get("maintenanceJobId"));
        }
    }

    @Test void failureHalfwayRestoresReplacementsAndRemovesAdditions() throws Exception {
        try (Fixture f = new Fixture(root)) {
            f.write("source", "a", "new-a"); f.write("source", "b", "new-b"); f.write("source", "c", "new-c");
            f.write("target", "a", "old-a"); f.write("target", "c", "old-c");
            f.node.before = (method, p) -> {
                if (method.equals("file.rename") && p.get("to").equals("c") && String.valueOf(p.get("from")).contains("/staged/")) throw McpanelBusinessException.invalid("rename denied");
                return null;
            };
            var job = f.deploy(); f.await(job, "rolled-back");
            assertEquals("old-a", f.read("target", "a")); assertEquals("old-c", f.read("target", "c"));
            assertFalse(Files.exists(f.path("target", "b")));
        }
    }

    @Test void uncertainInstallReplyRemainsLockedAndRecoversAfterServiceRestart() throws Exception {
        try (Fixture f = new Fixture(root)) {
            f.write("source", "a", "new"); f.write("target", "a", "old");
            AtomicBoolean once = new AtomicBoolean();
            f.node.after = (method, p) -> {
                if (method.equals("file.rename") && p.get("to").equals("a") && once.compareAndSet(false, true)) throw new NodeCallException("node.timeout", "lost reply");
                return null;
            };
            var job = f.deploy(); f.await(job, "recovery-required");
            assertEquals("new", f.read("target", "a"));
            assertThrows(McpanelBusinessException.class, () -> f.app.files(SCOPE, "target", "write", Map.of("path", "a", "content", "x")));
            f.deployments.close();
            f.deployments = new InstanceDeploymentService(f.app, f.documents, f.node, f.audit);
            assertEquals(true, f.deployments.status(SCOPE, "target", (String) job.get("id")).get("rollbackAvailable"));
            f.deployments.rollback("1", SCOPE, "target", (String) job.get("id")); f.await(job, "rolled-back");
            assertEquals("old", f.read("target", "a"));
        }
    }

    @Test void lostRestoreReplyCanBeRetriedUsingOriginalChecksum() throws Exception {
        try (Fixture f = new Fixture(root)) {
            f.write("source", "a", "new"); f.write("target", "a", "old");
            var job = f.deploy(); f.await(job, "done");
            AtomicBoolean once = new AtomicBoolean();
            f.node.after = (method, p) -> {
                if (method.equals("file.rename") && String.valueOf(p.get("from")).contains("/before/") && once.compareAndSet(false, true)) throw new NodeCallException("node.timeout", "lost restore reply");
                return null;
            };
            f.deployments.rollback("1", SCOPE, "target", (String) job.get("id")); f.await(job, "recovery-required");
            assertEquals("old", f.read("target", "a"));
            f.deployments.rollback("1", SCOPE, "target", (String) job.get("id")); f.await(job, "rolled-back");
            assertEquals("old", f.read("target", "a"));
        }
    }

    @Test void missingOriginalAndBackupCannotBeReportedAsRestored() throws Exception {
        try (Fixture f = new Fixture(root)) {
            f.write("source", "a", "new"); f.write("target", "a", "old");
            var job = f.deploy(); f.await(job, "done");
            Files.delete(f.path("target", ".mcpanel-deployments/" + job.get("id") + "/before/a"));
            f.deployments.rollback("1", SCOPE, "target", (String) job.get("id")); f.await(job, "recovery-required");
            assertEquals("new", f.read("target", "a"));
            assertFalse(String.valueOf(f.app.detail(SCOPE, "target").get("maintenanceJobId")).isEmpty());
        }
    }

    @Test void previewsRejectDriftRunningInstancesAndCrossScopeAccess() throws Exception {
        try (Fixture f = new Fixture(root)) {
            f.write("source", "a", "new"); f.write("target", "a", "old");
            var plan = f.deployments.preview(SCOPE, "source", "target", List.of("a"));
            f.write("target", "a", "longer old");
            assertThrows(McpanelBusinessException.class, () -> f.deployments.deploy("1", SCOPE, "source", (String) plan.get("planId")));
            assertThrows(McpanelBusinessException.class, () -> f.deployments.preview("blocked", "source", "target", List.of("a")));
            f.node.states.put("source", "running");
            assertThrows(McpanelBusinessException.class, () -> f.deployments.copy("1", SCOPE, "source", "test"));
        }
    }

    @Test void rejectsSymlinkSourcesAndFileParentConflicts() throws Exception {
        try (Fixture f = new Fixture(root)) {
            f.write("source", "dir/a", "new"); f.write("target", "dir", "file");
            assertThrows(McpanelBusinessException.class, () -> f.deployments.preview(SCOPE, "source", "target", List.of("dir/a")));
            Files.createSymbolicLink(f.path("source", "link"), f.path("target", "dir"));
            assertThrows(McpanelBusinessException.class, () -> f.deployments.preview(SCOPE, "source", "target", List.of("link")));
        }
    }

    @Test void oldSftpNodeCannotBeginDeploymentOrCreateTestInstance() throws Exception {
        try (Fixture f = new Fixture(root)) {
            f.write("source", "a", "new");
            var old = f.nodes.findById("node").orElseThrow().withRuntime(
                    online.yudream.base.plugin.mcpanel.domain.enumerate.NodeStatus.ONLINE,
                    "0.7.7", "host", "session", List.of("ftp.close"), "", null, 1, 1);
            f.nodes.save(old);
            var error = assertThrows(McpanelBusinessException.class, () -> f.deployments.preview(SCOPE, "source", "target", List.of("a")));
            assertTrue(error.getMessage().contains("0.7.9"));
            assertThrows(McpanelBusinessException.class, () -> f.deployments.copy("1", SCOPE, "source", "test"));
            assertEquals(2, f.app.allInstances().size());
            assertEquals("", f.app.detail(SCOPE, "source").get("maintenanceJobId"));
        }
    }

    @Test void reservationCannotRaceAnInFlightWriteAndRecoveryCanStopInstance() throws Exception {
        try (Fixture f = new Fixture(root)) {
            var pending = new CompletableFuture<Map<String, Object>>();
            CountDownLatch entered = new CountDownLatch(1);
            f.node.pendingWrite = pending; f.node.writeEntered = entered;
            var writing = CompletableFuture.supplyAsync(() -> f.app.files(SCOPE, "target", "write", Map.of("path", "a", "content", "a")));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertThrows(McpanelBusinessException.class, () -> f.app.reserveMaintenance("target", "job"));
            pending.complete(Map.of()); writing.get(3, TimeUnit.SECONDS);
            f.app.reserveMaintenance("target", "job");
            assertThrows(McpanelBusinessException.class, () -> f.app.action("1", SCOPE, "target", "start", null));
            assertThrows(CompletionException.class, () -> f.app.callAutomatic("node", "instance.start", Map.of("instanceId", "target"), 1000).join());
            f.app.action("1", SCOPE, "target", "stop", null);
            f.app.releaseMaintenance("target", "job");
        }
    }

    @Test void copyCreatesStoppedIsolatedInstanceWithNewPorts() throws Exception {
        try (Fixture f = new Fixture(root)) {
            f.write("source", "world/level.dat", "world");
            f.instances.save(f.instances.findById("source").orElseThrow().withBinding("9223372036854775000", 2).withDomain("live", true, 2).withEventTask(true, true, 2));
            var job = f.deployments.copy("1", SCOPE, "source", "test-copy"); f.await(job, "done");
            var source = f.instances.findById("source").orElseThrow();
            var target = f.instances.findById((String) job.get("targetId")).orElseThrow();
            assertNotEquals(source.id(), target.id()); assertNotEquals(source.ports(), target.ports());
            assertTrue(target.mcServerId() == null || target.mcServerId().isBlank());
            assertFalse(target.autoStart()); assertFalse(target.autoRestart()); assertFalse(target.domainEnabled()); assertFalse(target.p2pEnabled());
            assertEquals("created", target.state()); assertEquals("world", f.read(target.id(), "world/level.dat"));
        }
    }

    @Test void historyPaginatesAndOlderDeploymentWaitsForNewerRollback() throws Exception {
        try (Fixture f = new Fixture(root)) {
            f.write("source", "a", "v1"); f.write("target", "a", "v0");
            var first = f.deploy(); f.await(first, "done");
            f.write("source", "a", "v2-long"); var second = f.deploy(); f.await(second, "done");
            assertThrows(McpanelBusinessException.class, () -> f.deployments.rollback("1", SCOPE, "target", (String) first.get("id")));
            f.deployments.rollback("1", SCOPE, "target", (String) second.get("id")); f.await(second, "rolled-back");
            f.deployments.rollback("1", SCOPE, "target", (String) first.get("id")); f.await(first, "rolled-back");
            assertEquals("v0", f.read("target", "a"));
            for (int i = 0; i < 110; i++) f.documents.save("mcpanel_deployments", "old-" + i, Map.of("id", "old-" + i, "targetId", "target", "createdAt", i, "state", "failed"));
            var page = f.deployments.list(SCOPE, "target", 12, 10);
            assertEquals(112, page.get("total")); assertEquals(2, ((List<?>) page.get("records")).size());
        }
    }

    @Test void cePackageValidatesManifestAndInstallsWithRollback() throws Exception {
        try (Fixture f = new Fixture(root)) {
            String target = "plugins/CraftEngine/contents/town/items.yml";
            f.write("target", target, "old");
            var preview = f.packages.inspect("1", SCOPE, "target", ceZip(target, false, false));
            assertThrows(McpanelBusinessException.class, () -> f.packages.install("other", SCOPE, "target", (String) preview.get("packageId")));
            var job = f.packages.install("1", SCOPE, "target", (String) preview.get("packageId")); f.await(job, "done");
            assertEquals("new", f.read("target", target));
            f.deployments.rollback("1", SCOPE, "target", (String) job.get("id")); f.await(job, "rolled-back");
            assertEquals("old", f.read("target", target));
        }
    }

    @Test void cePackageRejectsTraversalGlobalConfigHashMismatchAndUnlistedFiles() throws Exception {
        try (Fixture f = new Fixture(root)) {
            for (String path : List.of("../outside", "plugins/CraftEngine/config.yml", "plugins/CraftEngine/contents/other/items.yml")) {
                assertThrows(McpanelBusinessException.class, () -> f.packages.inspect("1", SCOPE, "target", ceZip(path, false, false)));
            }
            String valid = "plugins/CraftEngine/contents/town/items.yml";
            assertThrows(McpanelBusinessException.class, () -> f.packages.inspect("1", SCOPE, "target", ceZip(valid, true, false)));
            assertThrows(McpanelBusinessException.class, () -> f.packages.inspect("1", SCOPE, "target", ceZip(valid, false, true)));
            assertFalse(Files.exists(f.path("target", "plugins")));
        }
    }

    @Test void deploymentHttpMutationsRequireAuthenticationAndManagePermission() {
        var facade = new InstanceDeploymentFacade(null, null, TestStubs.security());
        var anonymous = new PluginHttpRequest("POST", "/admin/instances/x/copy", Map.of(), Map.of(), "{}", null);
        assertEquals(401, facade.copy(anonymous).status());
        var viewer = new PluginHttpRequest("POST", "/admin/instances/x/copy", Map.of(), Map.of(), "{}", TestStubs.principal(1L, "plugin:mcpanel:view"));
        assertEquals(403, facade.copy(viewer).status()); assertEquals(403, facade.preview(viewer).status());
        assertEquals(403, facade.deploy(viewer).status()); assertEquals(403, facade.rollback(viewer).status());
        assertEquals(403, facade.inspectPackage(viewer).status()); assertEquals(403, facade.installPackage(viewer).status());
    }

    private byte[] ceZip(String target, boolean badHash, boolean extra) throws Exception {
        var manifest = Map.of("format", "mcpanel-ce/1", "name", "Town", "version", "1.0", "namespace", "town", "files", List.of(Map.of("source", "payload/items.yml", "target", target, "sha256", badHash ? "0".repeat(64) : hash("new".getBytes()))));
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("mcpanel-package.json")); zip.write(McpanelJson.mapper().writeValueAsBytes(manifest)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("payload/items.yml")); zip.write("new".getBytes()); zip.closeEntry();
            if (extra) { zip.putNextEntry(new ZipEntry("payload/undeclared")); zip.write(1); zip.closeEntry(); }
        }
        return bytes.toByteArray();
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    static final class Fixture implements AutoCloseable {
        final FailingDocumentStore documents = new FailingDocumentStore();
        final McpanelInstanceRepository instances = new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
        final McpanelNodeRepository nodes = new DocumentNodeRepository(documents, McpanelJson.mapper());
        final PortAllocationRepository ports = new DocumentPortAllocationRepository(documents, McpanelJson.mapper());
        final FileNode node;
        final McpanelInstanceAppService.AuditRecorder audit = (a,b,c,d,e,f) -> { };
        final McpanelInstanceAppService app;
        InstanceDeploymentService deployments;
        final CeResourcePackageService packages;
        Fixture(Path root) throws Exception {
            node = new FileNode(root);
            nodes.save(McpanelNode.create("node", "Node", "wss://127.0.0.1:9701", "pinned", "a".repeat(64), true, "", true, 1).withReported("0.7.9", "host", "a".repeat(64), 1));
            app = new McpanelInstanceAppService(instances, nodes, ports, node, new PortAllocator(ports), audit, new McpanelInstanceAppService.TenancyScope() {
                public boolean canAccess(String scope, McpanelInstance i) { return !scope.equals("blocked"); }
                public String tenantOf(Object context, McpanelNode n) { return null; }
            }, new MinecraftLinkService(Optional::empty), null);
            for (String id : List.of("source", "target")) app.create("1", SCOPE, McpanelInstance.create(id, "node", id, "paper", "1.21", "", "eclipse-temurin:21-jre", List.of("java", "-jar", "server.jar"), Map.of(), 1024, 0, 2048, List.of(), Map.of(), null, "", 1));
            deployments = new InstanceDeploymentService(app, documents, node, audit); packages = new CeResourcePackageService(deployments);
        }
        Path path(String id, String rel) { return node.root.resolve(id).resolve(rel); }
        void write(String id, String rel, String value) throws IOException { Path p = path(id, rel); Files.createDirectories(p.getParent()); Files.writeString(p, value); }
        String read(String id, String rel) throws IOException { return Files.readString(path(id, rel)); }
        Map<String, Object> deploy() {
            var plan = deployments.preview(SCOPE, "source", "target", nodePaths());
            return deployments.deploy("1", SCOPE, "source", (String) plan.get("planId"));
        }
        List<String> nodePaths() { try (var paths = Files.list(path("source", ""))) { return paths.map(p -> p.getFileName().toString()).toList(); } catch (IOException e) { throw new UncheckedIOException(e); } }
        void await(Map<String, Object> job, String state) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            Map<String, Object> result;
            do {
                result = deployments.status(SCOPE, (String) job.get("targetId"), (String) job.get("id"));
                if (state.equals(result.get("state"))) { Thread.sleep(15); return; }
                if (List.of("done", "rolled-back", "recovery-required", "failed").contains(result.get("state"))) fail("Expected " + state + ", received " + result);
                Thread.sleep(10);
            } while (System.nanoTime() < until);
            fail("Timeout: " + result);
        }
        public void close() { deployments.close(); packages.close(); }
    }

    static final class FailingDocumentStore extends InMemoryDocumentStore {
        volatile BiPredicate<String, Map<String, Object>> fail = (collection, document) -> false;
        @Override public synchronized Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            if (fail.test(collection, document)) throw new IllegalStateException("document store unavailable");
            return super.save(collection, id, document);
        }
    }

    static final class FileNode implements McpanelInstanceAppService.NodeCallGateway {
        final Path root;
        final Map<String, String> states = new ConcurrentHashMap<>();
        final Map<String, Map<String, Object>> uploads = new ConcurrentHashMap<>();
        volatile BiFunction<String, Map<String, Object>, Map<String, Object>> before = (m,p) -> null, after = (m,p) -> null;
        volatile CompletableFuture<Map<String, Object>> pendingWrite;
        volatile CountDownLatch writeEntered;
        FileNode(Path root) { this.root = root; }
        public CompletableFuture<Map<String, Object>> call(String nodeId, String method, Map<String, Object> payload) {
            if (method.equals("file.write") && pendingWrite != null) { writeEntered.countDown(); return pendingWrite; }
            try {
                var replacement = before.apply(method, payload);
                var result = replacement != null ? replacement : apply(method, payload);
                after.apply(method, payload);
                return CompletableFuture.completedFuture(result);
            } catch (Exception error) { return CompletableFuture.failedFuture(error); }
        }
        private Map<String, Object> apply(String method, Map<String, Object> p) throws Exception {
            String id = String.valueOf(p.get("instanceId")); Path base = root.resolve(id);
            switch (method) {
                case "instance.create": Files.createDirectories(base); states.put(id, "created"); return Map.of("state", "created");
                case "instance.inspect": return Map.of("state", states.getOrDefault(id, "created"));
                case "instance.stop": states.put(id, "exited"); return Map.of("state", "exited");
                case "file.list": {
                    List<Map<String, Object>> entries = new ArrayList<>();
                    try (var paths = Files.list(base.resolve(String.valueOf(p.get("path"))))) {
                        for (Path file : paths.sorted().toList()) entries.add(Map.of("name", file.getFileName().toString(), "isDir", Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS), "size", Files.size(file), "modTime", Files.getLastModifiedTime(file).toMillis(), "mode", Files.isSymbolicLink(file) ? "symlink" : Files.isDirectory(file) ? "dir" : "file"));
                    }
                    int page = ((Number) p.getOrDefault("page", 1)).intValue(), size = ((Number) p.getOrDefault("size", 500)).intValue(), start = Math.min(entries.size(), (page - 1) * size);
                    return Map.of("entries", entries.subList(start, Math.min(start + size, entries.size())), "total", entries.size());
                }
                case "file.download.chunk": {
                    try (var file = new RandomAccessFile(base.resolve((String) p.get("path")).toFile(), "r")) {
                        file.seek(((Number) p.get("offset")).longValue()); byte[] bytes = new byte[((Number) p.get("length")).intValue()]; int read = file.read(bytes);
                        return Map.of("content", Base64.getEncoder().encodeToString(read < 0 ? new byte[0] : Arrays.copyOf(bytes, read)));
                    }
                }
                case "file.mkdir": Files.createDirectories(base.resolve((String) p.get("path"))); return Map.of();
                case "file.rename": Files.move(base.resolve((String) p.get("from")), base.resolve((String) p.get("to")), StandardCopyOption.REPLACE_EXISTING); return Map.of();
                case "file.delete": Files.deleteIfExists(base.resolve((String) p.get("path"))); return Map.of();
                case "file.write": Files.write(base.resolve((String) p.get("path")), Base64.getDecoder().decode((String) p.get("content"))); return Map.of();
                case "file.upload.begin": {
                    String upload = UUID.randomUUID().toString(); var session = new HashMap<String, Object>(p); session.put("bytes", new ByteArrayOutputStream()); uploads.put(upload, session); return Map.of("uploadId", upload);
                }
                case "file.upload.chunk": ((ByteArrayOutputStream) uploads.get(p.get("uploadId")).get("bytes")).write(Base64.getDecoder().decode((String) p.get("content"))); return Map.of();
                case "file.upload.commit": {
                    var session = uploads.remove(p.get("uploadId")); byte[] bytes = ((ByteArrayOutputStream) session.get("bytes")).toByteArray();
                    if (!hash(bytes).equals(session.get("sha256"))) throw McpanelBusinessException.invalid("hash mismatch");
                    Files.write(root.resolve((String) session.get("instanceId")).resolve((String) session.get("path")), bytes); return Map.of();
                }
                case "file.upload.abort": uploads.remove(p.get("uploadId")); return Map.of();
                default: return Map.of();
            }
        }
    }
}
