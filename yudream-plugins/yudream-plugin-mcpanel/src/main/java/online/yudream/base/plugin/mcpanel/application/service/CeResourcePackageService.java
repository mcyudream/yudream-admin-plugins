package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Validates mcpanel-ce/1 packages before exposing a staged installation. */
public final class CeResourcePackageService implements AutoCloseable {
    private static final long MAX_ZIP = 64L * 1024 * 1024;
    private static final long MAX_UNPACKED = 512L * 1024 * 1024;
    private final InstanceDeploymentService deployments;
    private final Set<Path> installing = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(16);
    private final Map<String, Draft> drafts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "mcpanel-ce-draft-expiry"); thread.setDaemon(true); return thread;
    });
    private record Draft(String actor, String instanceId, Path root, String title,
            List<InstanceDeploymentService.FileSpec> files, Map<String, Path> sources,
            String fingerprint, long at) { }

    public CeResourcePackageService(InstanceDeploymentService deployments) {
        this.deployments = deployments;
        expiry.scheduleWithFixedDelay(this::sweep, 1, 1, TimeUnit.MINUTES);
    }

    public Map<String, Object> inspect(String actor, String scope, String targetId, byte[] zip) {
        if (zip == null || zip.length == 0 || zip.length > MAX_ZIP) throw McpanelBusinessException.invalid("CE 安装包需为不超过 64MiB 的 ZIP");
        sweep();
        if (!slots.tryAcquire()) throw McpanelBusinessException.invalid("待安装资源包过多，请稍后再试");
        Path root = null;
        try {
            root = Files.createTempDirectory("mcpanel-ce-");
            Map<String, Path> extracted = new HashMap<>();
            Map<String, String> hashes = new HashMap<>();
            Map<String, Long> sizes = new HashMap<>();
            Set<String> names = new HashSet<>();
            long total = 0;
            try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
                for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                    String name = InstanceDeploymentService.path(entry.getName().replaceAll("/$", ""));
                    if (!names.add(name.toLowerCase(Locale.ROOT))) throw McpanelBusinessException.invalid("ZIP 路径重复：" + name);
                    if (names.size() > 20_000) throw McpanelBusinessException.invalid("ZIP 条目过多");
                    if (entry.isDirectory()) continue;
                    if (!name.equals("mcpanel-package.json") && !name.startsWith("payload/")) throw McpanelBusinessException.invalid("ZIP 只允许清单与 payload/ 文件：" + name);
                    Path file = root.resolve(name);
                    Files.createDirectories(file.getParent());
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    long size = 0;
                    try (OutputStream output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer = new byte[64 * 1024];
                        for (int read; (read = input.read(buffer)) != -1;) {
                            total += read; size += read;
                            if (total > MAX_UNPACKED || (name.equals("mcpanel-package.json") && size > 1024 * 1024)) throw McpanelBusinessException.invalid("ZIP 解压后超过上限（512MiB；清单 1MiB）");
                            output.write(buffer, 0, read); digest.update(buffer, 0, read);
                        }
                    }
                    extracted.put(name, file); hashes.put(name, HexFormat.of().formatHex(digest.digest())); sizes.put(name, size);
                }
            }
            Path manifest = extracted.remove("mcpanel-package.json");
            if (manifest == null) throw McpanelBusinessException.invalid("缺少 mcpanel-package.json，请按 MC 面板 CE 安装包规范打包");
            var json = McpanelJson.mapper().readTree(Files.readString(manifest));
            if (!"mcpanel-ce/1".equals(json.path("format").asText())) throw McpanelBusinessException.invalid("不支持的安装包格式，需要 mcpanel-ce/1");
            String namespace = json.path("namespace").asText();
            String plugin = json.path("pluginDirectory").asText();
            String title = json.path("name").asText();
            String version = json.path("version").asText();
            if (!namespace.matches("[a-z][a-z0-9_]{0,63}") || title.isBlank() || title.length() > 128 || version.isBlank() || version.length() > 64) throw McpanelBusinessException.invalid("资源包名称、版本或 CE 命名空间无效");
            if (!plugin.isEmpty() && (!plugin.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || plugin.equalsIgnoreCase("CraftEngine"))) throw McpanelBusinessException.invalid("插件配置目录无效，不能覆盖 CraftEngine 全局配置");
            var entries = json.path("files");
            if (!entries.isArray() || entries.isEmpty() || entries.size() > 10_000) throw McpanelBusinessException.invalid("清单 files 必须包含 1-10000 项");
            List<InstanceDeploymentService.FileSpec> files = new ArrayList<>();
            Map<String, Path> sources = new HashMap<>();
            Set<String> targets = new HashSet<>();
            boolean ce = false;
            for (var item : entries) {
                String source = InstanceDeploymentService.path(item.path("source").asText());
                String target = InstanceDeploymentService.path(item.path("target").asText());
                String expected = item.path("sha256").asText();
                String cePrefix = "plugins/CraftEngine/contents/" + namespace + "/";
                boolean isCe = target.startsWith(cePrefix);
                boolean pluginConfig = !plugin.isEmpty() && target.startsWith("plugins/" + plugin + "/");
                boolean pluginJar = !plugin.isEmpty() && target.matches("plugins/" + java.util.regex.Pattern.quote(plugin) + "(?:-[A-Za-z0-9._+-]+)?\\.jar");
                if (!isCe && !pluginConfig && !pluginJar) throw McpanelBusinessException.invalid("目标不在本包的 CE 命名空间或插件目录中：" + target);
                if (!targets.add(target.toLowerCase(Locale.ROOT))) throw McpanelBusinessException.invalid("目标路径重复：" + target);
                if (!expected.matches("[a-fA-F0-9]{64}") || !expected.equalsIgnoreCase(hashes.get(source)) || !extracted.containsKey(source)) throw McpanelBusinessException.invalid("文件缺失或 SHA-256 不匹配：" + source);
                for (String other : sources.keySet()) if (target.toLowerCase(Locale.ROOT).startsWith(other.toLowerCase(Locale.ROOT) + "/") || other.toLowerCase(Locale.ROOT).startsWith(target.toLowerCase(Locale.ROOT) + "/")) throw McpanelBusinessException.invalid("目标存在文件与目录冲突：" + target);
                files.add(new InstanceDeploymentService.FileSpec(target, sizes.get(source), expected.toLowerCase(Locale.ROOT)));
                sources.put(target, extracted.remove(source));
                ce |= isCe;
            }
            if (!ce || !extracted.isEmpty()) throw McpanelBusinessException.invalid("资源包必须包含 CE contents 文件，且所有 payload 文件必须列入清单");
            Map<String, Object> preview = deployments.packagePreview(scope, targetId, files);
            String id = UUID.randomUUID().toString();
            drafts.put(id, new Draft(actor, targetId, root, title + " " + version, List.copyOf(files), Map.copyOf(sources), String.valueOf(preview.remove("fingerprint")), System.currentTimeMillis()));
            preview.put("packageId", id); preview.put("name", title); preview.put("version", version); preview.put("namespace", namespace);
            return preview;
        } catch (McpanelBusinessException error) { slots.release(); cleanup(root); throw error; }
        catch (Exception error) { slots.release(); cleanup(root); throw McpanelBusinessException.invalid("CE 安装包无法解析：" + error.getClass().getSimpleName()); }
    }

    public Map<String, Object> install(String actor, String scope, String targetId, String id) {
        if (id == null || id.isBlank()) throw McpanelBusinessException.invalid("请先选择并预览资源包");
        Draft draft = drafts.get(id);
        if (draft == null || !draft.actor.equals(actor) || !draft.instanceId.equals(targetId)) throw McpanelBusinessException.notFound("安装预览不存在或已过期");
        if (System.currentTimeMillis() - draft.at > 15 * 60_000L) { if (drafts.remove(id, draft)) { slots.release(); cleanup(draft.root); } throw McpanelBusinessException.invalid("安装预览已过期，请重新选择资源包"); }
        Map<String, Object> current = deployments.packagePreview(scope, targetId, draft.files);
        if (!draft.fingerprint.equals(current.get("fingerprint"))) throw McpanelBusinessException.invalid("目标文件已变化，请重新上传并预览资源包");
        if (!drafts.remove(id, draft)) throw McpanelBusinessException.invalid("该包已开始安装");
        slots.release(); installing.add(draft.root);
        return deployments.installPackage(actor, scope, targetId, "CE 资源包：" + draft.title, draft.files,
                path -> (offset, length) -> {
                    try (RandomAccessFile file = new RandomAccessFile(draft.sources.get(path).toFile(), "r")) {
                        file.seek(offset); byte[] bytes = new byte[length]; file.readFully(bytes); return bytes;
                    } catch (IOException error) { throw new IllegalStateException("资源包暂存文件读取失败", error); }
                }, draft.fingerprint, () -> { installing.remove(draft.root); cleanup(draft.root); });
    }

    private void sweep() {
        for (var item : drafts.entrySet()) if (System.currentTimeMillis() - item.getValue().at > 15 * 60_000L && drafts.remove(item.getKey(), item.getValue())) { slots.release(); cleanup(item.getValue().root); }
    }
    private static void cleanup(Path root) {
        if (root == null) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }
    @Override public void close() { expiry.shutdownNow(); drafts.values().forEach(draft -> cleanup(draft.root)); drafts.clear(); installing.forEach(CeResourcePackageService::cleanup); installing.clear(); }
}
