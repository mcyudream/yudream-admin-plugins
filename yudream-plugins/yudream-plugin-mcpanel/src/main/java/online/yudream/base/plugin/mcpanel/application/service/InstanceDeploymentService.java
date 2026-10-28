package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Staged file deployment with a durable, per-file undo journal. */
public final class InstanceDeploymentService implements AutoCloseable {
    private static final String JOBS = "mcpanel_deployments";
    private static final String ENTRIES = "mcpanel_deployment_entries";
    private static final String PLANS = "mcpanel_deployment_plans";
    private static final int CHUNK = 96 * 1024;
    private static final int MAX_FILES = 20_000;
    private static final long MAX_BYTES = 100L * 1024 * 1024 * 1024;
    private static final String RESERVED = ".mcpanel-deployments";
    private final McpanelInstanceAppService instances;
    private final PluginDocumentStore documents;
    private final McpanelInstanceAppService.NodeCallGateway gateway;
    private final McpanelInstanceAppService.AuditRecorder audit;
    private final ExecutorService worker;
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public InstanceDeploymentService(McpanelInstanceAppService instances, PluginDocumentStore documents,
            McpanelInstanceAppService.NodeCallGateway gateway, McpanelInstanceAppService.AuditRecorder audit) {
        this.instances = instances;
        this.documents = documents;
        this.gateway = gateway;
        this.audit = audit;
        worker = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), task -> {
                    Thread thread = new Thread(task, "mcpanel-deployment");
                    thread.setDaemon(true);
                    return thread;
                });
        // No I/O to nodes on enable: reserve interrupted targets; an administrator
        // can recover them using the durable journal after the node reconnects.
        for (Map<String, Object> job : all(JOBS, null, null)) {
            if (List.of("running", "rolling-back", "recovery-required").contains(text(job.get("state")))) {
                job.put("state", "recovery-required");
                job.put("rollbackAvailable", true);
                job.put("error", "任务中断，请停止实例并执行回退恢复");
                save(job);
                instances.reserveMaintenance(text(job.get("targetId")), text(job.get("id")));
            }
        }
    }

    public record FileSpec(String path, long size, String modified) { }

    public Map<String, Object> preview(String scope, String sourceId, String targetId, List<String> paths) {
        if (sourceId == null || sourceId.isBlank() || targetId == null || targetId.isBlank()) throw McpanelBusinessException.invalid("请选择源实例与目标实例");
        if (sourceId.equals(targetId)) throw McpanelBusinessException.invalid("源实例和目标实例不能相同");
        McpanelInstance source = stopped(scope, sourceId);
        McpanelInstance target = stopped(scope, targetId);
        List<String> selected = validatePaths(paths);
        List<FileSpec> files = collect(source, selected);
        if (files.isEmpty()) throw McpanelBusinessException.invalid("所选路径没有可部署的文件");
        Map<String, FileSpec> before = targetFiles(target, files);
        String token = fingerprint(files, before);
        for (Map<String, Object> expired : all(PLANS, null, null)) {
            if (System.currentTimeMillis() - number(expired.get("createdAt")) > 15 * 60_000L) documents.delete(PLANS, text(expired.get("id")));
        }
        String planId = UUID.randomUUID().toString();
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("id", planId); plan.put("sourceId", sourceId); plan.put("targetId", targetId);
        plan.put("paths", selected); plan.put("fingerprint", token);
        plan.put("createdAt", System.currentTimeMillis());
        documents.save(PLANS, planId, plan);
        Map<String, Object> result = summary(files, before);
        result.put("planId", planId);
        return result;
    }

    public Map<String, Object> deploy(String actor, String scope, String sourceId, String planId) {
        if (planId == null || planId.isBlank()) throw McpanelBusinessException.invalid("请先预览本次覆盖");
        Map<String, Object> plan = documents.findById(PLANS, planId)
                .orElseThrow(() -> McpanelBusinessException.invalid("请先预览本次覆盖"));
        if (!sourceId.equals(plan.get("sourceId")) || System.currentTimeMillis() - number(plan.get("createdAt")) > 15 * 60_000L) {
            throw McpanelBusinessException.invalid("预览已过期，请重新预览");
        }
        String targetId = text(plan.get("targetId"));
        McpanelInstance source = stopped(scope, sourceId);
        McpanelInstance target = stopped(scope, targetId);
        List<FileSpec> files = collect(source, strings(plan.get("paths")));
        if (!Objects.equals(plan.get("fingerprint"), fingerprint(files, targetFiles(target, files)))) {
            throw new McpanelBusinessException("deployment.changed", 409, "源或目标文件已变化，请重新预览");
        }
        Map<String, Object> job = job("覆盖实例文件", sourceId, targetId, actor);
        job.put("sourcePaths", plan.get("paths"));
        submit(job, scope, () -> execute(job, source, target, files, path -> remoteSource(source, path), text(plan.get("fingerprint"))));
        documents.delete(PLANS, planId);
        return view(job);
    }

    public Map<String, Object> copy(String actor, String scope, String sourceId, String name) {
        McpanelInstance source = stopped(scope, sourceId);
        if (name == null || name.isBlank() || name.length() > 128) throw McpanelBusinessException.invalid("请填写 1-128 字的测试服名称");
        List<FileSpec> files = collect(source, List.of(""));
        String id = "test-" + UUID.randomUUID().toString().replace("-", "");
        Map<String, String> config = new LinkedHashMap<>(source.config());
        config.keySet().removeIf(key -> key.startsWith("install") || key.startsWith("rcon")
                || key.startsWith("ping") || key.equals("autoDownloadCore"));
        Map<String, String> env = new LinkedHashMap<>(source.env());
        env.keySet().removeIf(key -> key.startsWith("MCSERVER_") || key.equals("MCPANEL_INSTANCE_ID"));
        McpanelInstance spec = McpanelInstance.create(id, source.nodeId(), name.trim(), source.kind(),
                source.mcVersion(), "", source.image(), source.command(), env, source.memoryMb(),
                source.cpuMillis(), source.diskMb(), List.of(), config, source.tenantId(),
                "测试副本，来源：" + source.name(), System.currentTimeMillis());
        Map<String, Object> job = job("复制测试服", sourceId, id, actor);
        // Reserve the source before async creation; the new instance remains stopped.
        instances.reserveMaintenance(sourceId, text(job.get("id")));
        try {
            instances.create(actor, scope, spec);
            McpanelInstance target = instances.accessibleInstance(scope, id);
            submit(job, scope, () -> execute(job, source, target, files, path -> remoteSource(source, path), null));
        } catch (RuntimeException error) {
            instances.releaseMaintenance(sourceId, text(job.get("id")));
            if (instances.allInstances().stream().anyMatch(row -> row.id().equals(id))) {
                job.put("state", "failed"); job.put("error", "测试服已创建但复制未开始：" + error.getMessage());
                save(job);
                return view(job);
            }
            throw error;
        }
        return view(job);
    }

    /** CE drafts provide validated, regular files; installation shares the undo journal. */
    public interface ContentSource {
        byte[] read(long offset, int length);
    }

    public Map<String, Object> installPackage(String actor, String scope, String targetId, String title,
            List<FileSpec> files, java.util.function.Function<String, ContentSource> sources, String expectedFingerprint, Runnable cleanup) {
        try {
            McpanelInstance target = stopped(scope, targetId);
            Map<String, Object> job = job(title, "", targetId, actor);
            submit(job, scope, () -> {
                try { execute(job, null, target, files, sources, expectedFingerprint); }
                finally { cleanup.run(); }
            });
            return view(job);
        } catch (RuntimeException error) { cleanup.run(); throw error; }
    }

    public Map<String, Object> packagePreview(String scope, String targetId, List<FileSpec> files) {
        McpanelInstance target = stopped(scope, targetId);
        Map<String, FileSpec> before = targetFiles(target, files);
        Map<String, Object> result = summary(files, before);
        result.put("fingerprint", fingerprint(files, before));
        return result;
    }

    public Map<String, Object> list(String scope, String targetId, int page, int size) {
        instances.accessibleInstance(scope, targetId);
        List<Map<String, Object>> jobs = all(JOBS, "targetId", targetId).stream()
                .sorted(Comparator.comparingLong((Map<String, Object> row) -> number(row.get("createdAt"))).reversed()).toList();
        int limit = Math.max(1, Math.min(50, size));
        int from = Math.min(jobs.size(), (Math.max(1, page) - 1) * limit);
        return Map.of("records", jobs.subList(from, Math.min(jobs.size(), from + limit)).stream().map(this::view).toList(),
                "total", jobs.size(), "page", Math.max(1, page), "size", limit);
    }

    public Map<String, Object> status(String scope, String targetId, String jobId) {
        instances.accessibleInstance(scope, targetId);
        return view(requireJob(targetId, jobId));
    }

    public Map<String, Object> rollback(String actor, String scope, String targetId, String jobId) {
        McpanelInstance target = stopped(scope, targetId);
        Map<String, Object> job = requireJob(targetId, jobId);
        if (!Boolean.TRUE.equals(job.get("rollbackAvailable"))) {
            throw McpanelBusinessException.invalid("当前任务不可回退或仍在执行");
        }
        // Older snapshots cannot roll back across a newer deployment.
        boolean newer = all(JOBS, "targetId", targetId).stream().anyMatch(row ->
                number(row.get("createdAt")) > number(job.get("createdAt"))
                && !List.of("rolled-back", "failed").contains(text(row.get("state"))));
        if (newer) throw McpanelBusinessException.invalid("请先回退较新的部署，再处理此记录");
        if (!active.add(jobId)) throw McpanelBusinessException.invalid("回退任务仍在执行");
        try { instances.reserveMaintenance(targetId, jobId); }
        catch (RuntimeException error) { active.remove(jobId); throw error; }
        String previousState = text(job.get("state"));
        try {
            job.put("state", "rolling-back"); save(job);
            worker.execute(() -> {
                try {
                    undo(job, target);
                    recordAudit(actor, "instance.deployment.rollback", target, jobId);
                } finally { active.remove(jobId); }
            });
        } catch (RuntimeException error) {
            active.remove(jobId);
            job.put("state", previousState);
            try { save(job); }
            finally {
                // No file mutation has started. Keep an existing recovery lock,
                // but release a reservation acquired only for this submission.
                if (!"recovery-required".equals(previousState)) instances.releaseMaintenance(targetId, jobId);
            }
            if (error instanceof RejectedExecutionException) throw new McpanelBusinessException("deployment.busy", 429, "部署队列已满，请稍后重试回退");
            throw error;
        }
        return view(job);
    }

    private void submit(Map<String, Object> job, String scope, Runnable run) {
        String jobId = text(job.get("id"));
        String sourceId = text(job.get("sourceId"));
        String targetId = text(job.get("targetId"));
        instances.reserveMaintenance(targetId, jobId);
        try {
            if (!sourceId.isBlank()) instances.reserveMaintenance(sourceId, jobId);
            stopped(scope, targetId);
            if (!sourceId.isBlank()) stopped(scope, sourceId);
            long latest = all(JOBS, "targetId", targetId).stream().mapToLong(row -> number(row.get("createdAt"))).max().orElse(0);
            job.put("createdAt", Math.max(System.currentTimeMillis(), latest + 1));
            save(job); active.add(jobId);
            worker.execute(() -> {
                try { run.run(); }
                finally {
                    active.remove(jobId);
                    if (!sourceId.isBlank()) instances.releaseMaintenance(sourceId, jobId);
                }
            });
        } catch (RuntimeException error) {
            active.remove(jobId);
            try {
                job.put("state", "failed"); job.put("error", text(error.getMessage())); save(job);
            } catch (RuntimeException recording) {
                if (recording != error) error.addSuppressed(recording);
            } finally {
                // Submission failed before a worker could mutate any files.
                instances.releaseMaintenance(targetId, jobId);
                if (!sourceId.isBlank()) instances.releaseMaintenance(sourceId, jobId);
            }
            if (error instanceof RejectedExecutionException) throw new McpanelBusinessException("deployment.busy", 429, "部署队列已满，请稍后重试");
            throw error;
        }
    }

    private void execute(Map<String, Object> job, McpanelInstance source, McpanelInstance target,
            List<FileSpec> files, java.util.function.Function<String, ContentSource> sources, String expectedFingerprint) {
        String id = text(job.get("id"));
        try {
            job.put("fileCount", files.size()); job.put("rollbackAvailable", true); save(job);
            closeSftp(target, id);
            if (source != null) {
                closeSftp(source, id);
                List<FileSpec> current = collect(source, job.containsKey("sourcePaths") ? strings(job.get("sourcePaths")) : List.of(""));
                if (!files.equals(current)) throw McpanelBusinessException.invalid("源文件已变化，请重新预览");
            }
            Map<String, FileSpec> initialTarget = targetFiles(target, files);
            if (expectedFingerprint != null && !expectedFingerprint.equals(fingerprint(files, initialTarget))) {
                throw McpanelBusinessException.invalid("源或目标文件已变化，请重新预览");
            }
            int index = 0;
            // Stage every file before replacing any target file.
            for (FileSpec file : files) {
                checkInterrupted();
                String stage = RESERVED + "/" + id + "/staged/" + file.path();
                mkdirParents(target, stage, id);
                copyFile(target, stage, file, sources.apply(file.path()), id + "-stage-" + index++);
                job.put("stagedFiles", index); save(job);
            }
            if (!initialTarget.equals(targetFiles(target, files))) throw McpanelBusinessException.invalid("暂存期间目标文件已变化，已停止覆盖");
            index = 0;
            for (FileSpec file : files) {
                checkInterrupted();
                Map<String, Object> old = stat(target, file.path());
                if (old != null && Boolean.TRUE.equals(old.get("isDir"))) throw McpanelBusinessException.invalid("目标是目录：" + file.path());
                String key = id + "-" + index;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("jobId", id); entry.put("path", file.path()); entry.put("order", index);
                entry.put("hadOriginal", old != null); entry.put("phase", "backup");
                if (old != null) {
                    entry.put("originalHash", hashFile(target, file.path(), number(old.get("size"))));
                    entry.put("originalSize", number(old.get("size")));
                }
                documents.save(ENTRIES, key, entry); // intent precedes every rename
                String before = RESERVED + "/" + id + "/before/" + file.path();
                if (old != null) {
                    mkdirParents(target, before, id);
                    mutate(target, "file.rename", Map.of("from", file.path(), "to", before), key + "-backup");
                }
                mkdirParents(target, file.path(), id);
                entry.put("phase", "install"); documents.save(ENTRIES, key, entry);
                mutate(target, "file.rename", Map.of("from", RESERVED + "/" + id + "/staged/" + file.path(), "to", file.path()), key + "-install");
                entry.put("phase", "installed"); documents.save(ENTRIES, key, entry);
                job.put("completedFiles", ++index); save(job);
            }
            job.put("state", "done"); job.put("finishedAt", System.currentTimeMillis()); save(job);
            instances.releaseMaintenance(target.id(), id);
        } catch (RuntimeException error) {
            job.put("error", Objects.requireNonNullElse(error.getMessage(), "部署失败"));
            if (Thread.currentThread().isInterrupted() || error instanceof UncertainNodeCall) {
                job.put("state", "recovery-required"); save(job);
            } else {
                job.put("state", "rolling-back"); save(job);
                undo(job, target);
            }
        }
        recordAudit(text(job.get("actor")), "instance.deployment", target, text(job.get("title")) + ":" + id);
    }

    private void undo(Map<String, Object> job, McpanelInstance target) {
        String id = text(job.get("id"));
        try {
            closeSftp(target, id + "-undo");
            List<Map<String, Object>> entries = all(ENTRIES, "jobId", id).stream()
                    .sorted(Comparator.comparingLong((Map<String, Object> row) -> number(row.get("order"))).reversed()).toList();
            for (Map<String, Object> entry : entries) {
                checkInterrupted();
                if ("restored".equals(entry.get("phase"))) continue;
                String path = text(entry.get("path"));
                String before = RESERVED + "/" + id + "/before/" + path;
                String key = id + "-" + number(entry.get("order"));
                Map<String, Object> backup = stat(target, before);
                Map<String, Object> current = stat(target, path);
                if (Boolean.TRUE.equals(entry.get("hadOriginal"))) {
                    String expected = text(entry.get("originalHash"));
                    long size = number(entry.get("originalSize"));
                    if (expected.isBlank()) throw new IllegalStateException("原文件校验信息缺失：" + path);
                    if (backup != null) {
                        if (number(backup.get("size")) != size || !expected.equals(hashFile(target, before, size))) {
                            throw new IllegalStateException("回退副本已变化：" + path);
                        }
                        entry.put("undoPhase", "restoring"); documents.save(ENTRIES, key, entry);
                        if (current != null) {
                            requireRegular(current, path);
                            mutate(target, "file.delete", Map.of("path", path), key + "-undo-delete");
                        }
                        mkdirParents(target, path, id);
                        mutate(target, "file.rename", Map.of("from", before, "to", path), key + "-undo-restore");
                    } else if (current == null || Boolean.TRUE.equals(current.get("isDir")) || number(current.get("size")) != size || !expected.equals(hashFile(target, path, size))) {
                        // A lost rename reply is safe to retry only when the original bytes can be proven.
                        throw new IllegalStateException("找不到可校验的原文件或回退副本：" + path);
                    }
                } else if (!"backup".equals(entry.get("phase")) && current != null) {
                    requireRegular(current, path);
                    mutate(target, "file.delete", Map.of("path", path), key + "-undo-new");
                } else if ("backup".equals(entry.get("phase")) && current != null) {
                    throw new IllegalStateException("未写入的目标路径出现新内容，请人工核对：" + path);
                }
                entry.put("phase", "restored"); documents.save(ENTRIES, key, entry);
            }
            job.put("state", "rolled-back"); job.put("rollbackAvailable", false);
            job.put("finishedAt", System.currentTimeMillis()); save(job);
            instances.releaseMaintenance(target.id(), id);
        } catch (RuntimeException error) {
            job.put("state", "recovery-required"); job.put("rollbackAvailable", true);
            job.put("error", text(job.get("error")) + "；回退未完成：" + error.getMessage()); save(job);
        }
    }

    private String hashFile(McpanelInstance instance, String path, long size) {
        MessageDigest digest = sha256();
        readSource(remoteSource(instance, path), size, (offset, bytes) -> digest.update(bytes));
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void requireRegular(Map<String, Object> item, String path) {
        if (Boolean.TRUE.equals(item.get("isDir")) || Boolean.TRUE.equals(item.get("isSymlink")) || "symlink".equals(item.get("mode"))) {
            throw McpanelBusinessException.invalid("目标必须是普通文件：" + path);
        }
    }
    private void recordAudit(String actor, String operation, McpanelInstance target, String detail) {
        try { audit.record(actor, operation, "instance", target.id(), detail, target.tenantId()); }
        catch (RuntimeException error) { System.err.println("[mcpanel] 部署审计记录失败：" + error.getMessage()); }
    }
    private static final class UncertainNodeCall extends McpanelBusinessException {
        UncertainNodeCall(String message, Throwable cause) { super("deployment.uncertain", 503, message); initCause(cause); }
    }

    private void copyFile(McpanelInstance target, String path, FileSpec file, ContentSource source, String key) {
        MessageDigest digest = sha256();
        readSource(source, file.size(), (offset, bytes) -> digest.update(bytes));
        String hash = HexFormat.of().formatHex(digest.digest());
        if (file.size() == 0) {
            mutate(target, "file.write", Map.of("path", path, "content", "", "encoding", "base64"), key + "-empty");
            return;
        }
        String upload = text(call(target, "file.upload.begin", Map.of("path", path, "size", file.size(), "sha256", hash)).get("uploadId"));
        if (upload.isBlank()) throw McpanelBusinessException.invalid("节点未返回上传会话");
        try {
            readSource(source, file.size(), (offset, bytes) -> call(target, "file.upload.chunk", Map.of("uploadId", upload, "offset", offset, "content", Base64.getEncoder().encodeToString(bytes))));
            call(target, "file.upload.commit", Map.of("uploadId", upload));
        } catch (RuntimeException error) {
            try { call(target, "file.upload.abort", Map.of("uploadId", upload)); } catch (RuntimeException ignored) { }
            throw error;
        }
    }

    private void readSource(ContentSource source, long size, java.util.function.BiConsumer<Long, byte[]> accept) {
        for (long offset = 0; offset < size;) {
            checkInterrupted();
            byte[] bytes = source.read(offset, (int) Math.min(CHUNK, size - offset));
            if (bytes.length == 0 || bytes.length > Math.min(CHUNK, size - offset)) throw McpanelBusinessException.invalid("源文件长度已变化，请重新预览");
            accept.accept(offset, bytes); offset += bytes.length;
        }
    }

    private ContentSource remoteSource(McpanelInstance source, String path) {
        return (offset, length) -> Base64.getDecoder().decode(text(call(source, "file.download.chunk", Map.of("path", path, "offset", offset, "length", length)).get("content")));
    }

    private McpanelInstance stopped(String scope, String id) {
        McpanelInstance instance = instances.accessibleInstance(scope, id);
        if (instances.nodeSupports(instance.nodeId(), "ftp.close") && !instances.nodeSupports(instance.nodeId(), "ftp.close.live")) {
            throw new McpanelBusinessException("deployment.node-upgrade", 409, "请先升级节点至 0.7.9 或更新版本，以便部署时撤销正在使用的 SFTP 连接");
        }
        String state = text(call(instance, "instance.inspect", Map.of()).get("state"));
        if (!List.of("created", "exited", "stopped").contains(state)) {
            throw new McpanelBusinessException("deployment.running", 409, "请先停止实例：" + instance.name());
        }
        return instance;
    }

    private List<FileSpec> collect(McpanelInstance instance, List<String> paths) {
        List<FileSpec> files = new ArrayList<>();
        long[] bytes = {0};
        for (String path : paths) {
            if (path.isEmpty()) walk(instance, "", files, bytes, 0);
            else {
                Map<String, Object> item = stat(instance, path);
                if (item == null) throw McpanelBusinessException.invalid("源路径不存在：" + path);
                if (Boolean.TRUE.equals(item.get("isDir"))) walk(instance, path, files, bytes, 0);
                else add(files, bytes, path, item);
            }
        }
        return files.stream().sorted(Comparator.comparing(FileSpec::path)).toList();
    }

    private void walk(McpanelInstance instance, String path, List<FileSpec> files, long[] bytes, int depth) {
        if (depth > 64) throw McpanelBusinessException.invalid("目录嵌套超过 64 层");
        for (Map<String, Object> item : children(instance, path)) {
            String name = text(item.get("name"));
            if (name.startsWith(".mcpanel") || name.equals("session.lock")) continue;
            String child = path.isEmpty() ? path(name) : path(path + "/" + name);
            if (Boolean.TRUE.equals(item.get("isSymlink")) || "symlink".equals(item.get("mode"))) throw McpanelBusinessException.invalid("不支持符号链接：" + child);
            if (Boolean.TRUE.equals(item.get("isDir"))) walk(instance, child, files, bytes, depth + 1);
            else add(files, bytes, child, item);
        }
    }

    private void add(List<FileSpec> files, long[] bytes, String path, Map<String, Object> item) {
        requireRegular(item, path);
        long size = number(item.get("size"));
        bytes[0] += size;
        if (size < 0 || bytes[0] > MAX_BYTES || files.size() >= MAX_FILES) throw McpanelBusinessException.invalid("单次最多部署 20000 个文件、100GiB");
        files.add(new FileSpec(path, size, text(item.get("modTime"))));
    }

    private Map<String, FileSpec> targetFiles(McpanelInstance target, List<FileSpec> files) {
        Map<String, List<Map<String, Object>>> parents = new HashMap<>();
        Map<String, FileSpec> result = new TreeMap<>();
        for (FileSpec file : files) {
            String parent = parent(file.path());
            if (!parents.containsKey(parent)) {
                Map<String, Object> directory = parent.isEmpty() ? null : stat(target, parent);
                if (!parent.isEmpty() && directory == null) parents.put(parent, List.of());
                else {
                    if (directory != null && !Boolean.TRUE.equals(directory.get("isDir"))) throw McpanelBusinessException.invalid("目标父路径不是目录：" + parent);
                    parents.put(parent, children(target, parent));
                }
            }
            for (Map<String, Object> row : parents.get(parent)) {
                if (text(row.get("name")).equals(name(file.path()))) {
                    requireRegular(row, file.path());
                    result.put(file.path(), new FileSpec(file.path(), number(row.get("size")), text(row.get("modTime"))));
                }
            }
        }
        return result;
    }

    private Map<String, Object> summary(List<FileSpec> files, Map<String, FileSpec> before) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileCount", files.size()); result.put("totalBytes", files.stream().mapToLong(FileSpec::size).sum());
        result.put("replaceCount", before.size()); result.put("addCount", files.size() - before.size());
        result.put("files", files.stream().limit(100).map(file -> Map.of("path", file.path(), "size", file.size(), "action", before.containsKey(file.path()) ? "replace" : "add")).toList());
        return result;
    }

    private String fingerprint(List<FileSpec> files, Map<String, FileSpec> before) {
        return HexFormat.of().formatHex(sha256().digest((files.toString() + before.toString()).getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> stat(McpanelInstance instance, String path) {
        String parent = parent(path);
        if (!parent.isEmpty()) {
            Map<String, Object> ancestor = stat(instance, parent);
            if (ancestor == null) return null;
            if (!Boolean.TRUE.equals(ancestor.get("isDir")) || "symlink".equals(ancestor.get("mode"))) throw McpanelBusinessException.invalid("目标父路径不是普通目录：" + parent);
        }
        Map<String, Object> row = children(instance, parent).stream().filter(item -> name(path).equals(text(item.get("name")))).findFirst().orElse(null);
        if (row != null && "symlink".equals(row.get("mode"))) throw McpanelBusinessException.invalid("不支持符号链接：" + path);
        return row;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> children(McpanelInstance instance, String path) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int page = 1; page <= 100; page++) {
            Map<String, Object> response = call(instance, "file.list", Map.of("path", path, "page", page, "size", 500));
            List<?> rows = response.get("entries") instanceof List<?> list ? list : List.of();
            for (Object row : rows) if (row instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
            if (result.size() > MAX_FILES) throw McpanelBusinessException.invalid("单个目录超过 20000 项");
            if (rows.size() < 500 || !response.containsKey("total") || result.size() >= number(response.get("total"))) break;
        }
        return result;
    }

    private void mkdirParents(McpanelInstance instance, String path, String key) {
        String parent = parent(path);
        if (!parent.isEmpty()) mutate(instance, "file.mkdir", Map.of("path", parent), key + "-mkdir-" + Integer.toUnsignedString(parent.hashCode()));
    }

    private void closeSftp(McpanelInstance instance, String key) {
        if (instances.nodeSupports(instance.nodeId(), "ftp.close")) mutate(instance, "ftp.close", Map.of(), key + "-ftp-" + instance.id());
        instances.revokeSftpGateway(instance.id());
    }

    private Map<String, Object> call(McpanelInstance instance, String method, Map<String, Object> args) {
        Map<String, Object> payload = new LinkedHashMap<>(args); payload.put("instanceId", instance.id());
        try { return gateway.call(instance.nodeId(), method, payload, 60_000).get(60, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("任务中断，保留回退记录", error); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof McpanelBusinessException cause) throw cause;
            if (error.getCause() instanceof online.yudream.base.plugin.mcpanel.infrastructure.node.NodeCallException cause) {
                if (!List.of("node.offline", "node.timeout", "node.disconnected", "timeout", "connection.closed", "operation.uncertain").contains(cause.code())) throw new McpanelBusinessException(cause.code(), 502, cause.getMessage());
            }
            throw new UncertainNodeCall("节点操作失败，结果待确认", error.getCause());
        } catch (TimeoutException error) { throw new UncertainNodeCall("节点操作超时，请检查回退记录", error); }
    }

    private void mutate(McpanelInstance instance, String method, Map<String, Object> args, String key) {
        Map<String, Object> payload = new LinkedHashMap<>(args); payload.put("ik", "deploy-" + key);
        call(instance, method, payload);
    }

    private Map<String, Object> job(String title, String sourceId, String targetId, String actor) {
        Map<String, Object> job = new ConcurrentHashMap<>();
        job.put("id", UUID.randomUUID().toString()); job.put("sourceId", sourceId); job.put("targetId", targetId);
        job.put("title", title); job.put("actor", actor); job.put("state", "running");
        job.put("createdAt", System.currentTimeMillis()); job.put("rollbackAvailable", false);
        return job;
    }

    private Map<String, Object> requireJob(String targetId, String id) {
        if (id == null || id.isBlank()) throw McpanelBusinessException.invalid("任务标识缺失");
        Map<String, Object> job = documents.findById(JOBS, id).map(LinkedHashMap::new)
                .orElseThrow(() -> McpanelBusinessException.notFound("部署记录不存在"));
        if (!targetId.equals(job.get("targetId"))) throw McpanelBusinessException.notFound("部署记录不存在");
        return job;
    }

    private Map<String, Object> view(Map<String, Object> job) { return new LinkedHashMap<>(job); }
    private void save(Map<String, Object> job) { documents.save(JOBS, text(job.get("id")), new LinkedHashMap<>(job)); }
    private List<Map<String, Object>> all(String collection, String field, String value) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int offset = 1;; offset++) {
            List<Map<String, Object>> batch = field == null ? documents.findAll(collection, offset, 100) : documents.findByField(collection, field, value, offset, 100);
            rows.addAll(batch.stream().map(LinkedHashMap::new).toList());
            if (batch.size() < 100) return rows;
        }
    }
    public static String path(String value) {
        if (value == null || value.isBlank() || value.length() > 1024 || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0
                || value.chars().anyMatch(ch -> ch < 32 || ch == 127) || Arrays.stream(value.split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith(".mcpanel"))) {
            throw McpanelBusinessException.invalid("无效的相对文件路径：" + value);
        }
        return value;
    }
    private List<String> validatePaths(List<String> paths) {
        if (paths == null || paths.isEmpty() || paths.size() > 100) throw McpanelBusinessException.invalid("请选择 1-100 个文件或目录");
        List<String> sorted = paths.stream().map(InstanceDeploymentService::path).distinct().sorted().toList();
        return sorted.stream().filter(value -> sorted.stream().noneMatch(other -> !other.equals(value) && value.startsWith(other + "/"))).toList();
    }
    private static String parent(String value) { int i = value.lastIndexOf('/'); return i < 0 ? "" : value.substring(0, i); }
    private static String name(String value) { return value.substring(value.lastIndexOf('/') + 1); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    private static List<String> strings(Object value) { return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(); }
    private static MessageDigest sha256() { try { return MessageDigest.getInstance("SHA-256"); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static void checkInterrupted() { if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("任务中断"); }
    @Override public void close() {
        worker.shutdownNow();
        try { worker.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
