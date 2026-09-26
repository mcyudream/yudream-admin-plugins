package online.yudream.base.plugin.mcpanel.infrastructure.backup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeCallException;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeConnectionManager;
import online.yudream.base.plugin.spi.system.backup.BackupExportOptions;
import online.yudream.base.plugin.spi.system.backup.BackupSink;
import online.yudream.base.plugin.spi.system.backup.BackupSource;
import online.yudream.base.plugin.spi.system.backup.PluginBackupConflictStrategy;
import online.yudream.base.plugin.spi.system.backup.PluginBackupProvider;
import online.yudream.base.plugin.spi.system.backup.ScopedBackupFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务器世界数据备份范围（宿主 SPI {@code PluginBackupProvider} 扩展点）：
 * 逐实例在节点侧执行 {@code backup.create} 生成整包 ZIP，经分块通道
 * {@code backup.download.chunk} 拉取归档写入宿主备份；恢复时把归档经
 * {@code backup.upload.*} 回灌节点备份目录，停机后 {@code backup.restore}
 * 覆盖实例数据，最后清掉回灌用的临时副本。
 * 旧节点（caps 无分块备份通道）或离线节点自动跳过并记录告警，不阻塞整体备份。
 * 冲突策略：{@code LOCAL_WINS} 只恢复数据目录为空的实例；{@code ARCHIVE_WINS}
 * 停机后无条件覆盖实例数据（实例记录缺失时跳过，请先合并导入 MC 面板数据范围）。
 * 归档内 {@code instances.json} 记录每条目 {instanceId,name,nodeId,file,size} 与跳过原因。
 */
public class McpanelServerDataBackupProvider implements PluginBackupProvider {

    /** 分块拉取/回灌的单帧原始字节数（节点侧单帧上限 96KiB，留余量）。 */
    private static final int CHUNK_BYTES = 64 * 1024;
    private static final long STOP_POLL_TIMEOUT_MS = 60_000L;
    private static final long STOP_POLL_INTERVAL_MS = 2_000L;
    private static final Set<String> RUNNING_STATES = Set.of("running", "starting");

    private final McpanelInstanceRepository instances;
    private final NodeConnectionManager nodes;
    private final ObjectMapper mapper;
    private final BackupPolicyLookup policyLookup;

    /** 备份保留策略查询（bootstrap 挂接策略存储）：返回 [keepCount, keepDays]，0=不限。 */
    public interface BackupPolicyLookup {
        int[] retentionOf(String instanceId);
    }

    public McpanelServerDataBackupProvider(McpanelInstanceRepository instances, NodeConnectionManager nodes,
                                           ObjectMapper mapper, BackupPolicyLookup policyLookup) {
        this.instances = instances;
        this.nodes = nodes;
        this.mapper = mapper;
        this.policyLookup = policyLookup;
    }

    @Override
    public String scopeCode() {
        return "server-data";
    }

    @Override
    public String displayName() {
        return "MC 服务器世界数据";
    }

    @Override
    public String description() {
        return "逐实例整包备份节点上的世界/配置数据（需节点支持分块备份通道，旧节点自动跳过并在任务消息说明）";
    }

    @Override
    public String defaultSchedule() {
        return "0 0 5 * * *";
    }

    @Override
    public ExportReport export(BackupSink sink, BackupExportOptions options) throws Exception {
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        // 插件发起的单实例备份（如实例计划任务）经 options.instanceId 过滤；空=全量
        String instanceFilter = options == null ? null : options.get("instanceId");
        for (McpanelInstance instance : instances.findAll()) {
            if (instanceFilter != null && !instanceFilter.isBlank() && !instanceFilter.equals(instance.id())) {
                continue;
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("instanceId", instance.id());
            record.put("name", instance.name());
            record.put("nodeId", instance.nodeId());
            try {
                requireTransferCapability(instance.nodeId(), instance.name());
                Map<String, Object> createPayload = payload(instance.id(), "backup-create");
                // 面板侧保留策略随打包下发：节点生成整包 ZIP 后立即清理该实例超额/过期旧档
                int[] retention = policyLookup == null ? null : policyLookup.retentionOf(instance.id());
                if (retention != null && retention.length >= 2) {
                    createPayload.put("keepCount", retention[0]);
                    createPayload.put("keepDays", retention[1]);
                }
                Map<String, Object> created = call(instance.nodeId(), "backup.create", createPayload);
                String archive = String.valueOf(created.get("file"));
                long size = ((Number) created.get("size")).longValue();
                byte[] data = downloadArchive(instance.nodeId(), archive);
                String path = "instances/" + instance.id() + "/" + archive;
                sink.putFile(path, size, new ByteArrayInputStream(data));
                record.put("status", "ok");
                record.put("file", archive);
                record.put("archivePath", path);
                record.put("size", size);
            } catch (NodeCallException e) {
                record.put("status", "skipped");
                record.put("reason", e.getMessage());
                warnings.add("实例「" + instance.name() + "」跳过：" + e.getMessage());
            } catch (Exception e) {
                record.put("status", "skipped");
                record.put("reason", e.getMessage());
                warnings.add("实例「" + instance.name() + "」备份拉取失败：" + e.getMessage());
            }
            entries.add(record);
        }
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("exportedAt", Instant.now().toString());
        index.put("entries", entries);
        sink.putText("instances.json", mapper.writeValueAsString(index));
        return new ExportReport(warnings);
    }

    @Override
    public void restore(BackupSource source, PluginBackupConflictStrategy strategy) throws Exception {
        boolean archiveWins = strategy == PluginBackupConflictStrategy.ARCHIVE_WINS;
        Map<String, ScopedBackupFile> files = new LinkedHashMap<>();
        for (ScopedBackupFile file : source.files()) {
            files.put(file.path(), file);
        }
        ScopedBackupFile indexFile = files.get("instances.json");
        if (indexFile == null) {
            throw new IllegalStateException("归档缺少 instances.json，无法识别实例数据条目");
        }
        List<Map<String, Object>> entries;
        try (InputStream in = source.open("instances.json")) {
            Map<String, Object> index = mapper.readValue(in, new TypeReference<Map<String, Object>>() {
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parsed = (List<Map<String, Object>>) (Object) index.get("entries");
            entries = parsed;
        }
        if (entries == null) {
            return;
        }
        // 尽力而为逐条恢复，全部条目处理完后把失败聚合抛出，宿主会把任务标记失败并透出原因
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            String instanceId = String.valueOf(entry.get("instanceId"));
            String name = String.valueOf(entry.getOrDefault("name", instanceId));
            try {
                if (!"ok".equals(entry.get("status"))) {
                    failures.add("实例「" + name + "」在备份时已被跳过（" + entry.get("reason") + "）");
                    continue;
                }
                McpanelInstance instance = instances.findById(instanceId).orElse(null);
                if (instance == null) {
                    failures.add("实例「" + name + "」在本面板不存在，请先合并导入 MC 面板数据范围（实例记录）后再恢复数据");
                    continue;
                }
                String nodeId = String.valueOf(entry.getOrDefault("nodeId", instance.nodeId()));
                requireTransferCapability(nodeId, name);
                String archivePath = String.valueOf(entry.get("archivePath"));
                ScopedBackupFile archive = files.get(archivePath);
                if (archive == null) {
                    failures.add("实例「" + name + "」的归档条目缺失（" + archivePath + "）");
                    continue;
                }
                if (!archiveWins && !nodeDataEmpty(nodeId, instanceId)) {
                    failures.add("实例「" + name + "」已有数据，本地为准策略保留现网数据，未恢复");
                    continue;
                }
                restoreInstance(nodeId, instanceId, archive, source);
            } catch (Exception e) {
                failures.add("实例「" + name + "」恢复失败：" + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException(String.join("；", failures));
        }
    }

    private void restoreInstance(String nodeId, String instanceId, ScopedBackupFile archive,
                                 BackupSource source) throws Exception {
        ensureStopped(nodeId, instanceId);
        String staging = instanceId + "-restore-" + Long.toString(System.currentTimeMillis(), 36) + ".zip";
        String uploadId = null;
        try {
            Map<String, Object> begin = call(nodeId, "backup.upload.begin", mapOf(
                    "file", staging,
                    "size", archive.size(),
                    "sha256", archive.sha256() == null ? "" : archive.sha256()));
            uploadId = String.valueOf(begin.get("uploadId"));
            long offset = 0;
            try (InputStream in = source.open(archive.path())) {
                byte[] buffer = new byte[CHUNK_BYTES];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    call(nodeId, "backup.upload.chunk", mapOf(
                            "uploadId", uploadId,
                            "offset", offset,
                            "content", Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(buffer, read))));
                    offset += read;
                }
            }
            call(nodeId, "backup.upload.commit", mapOf("uploadId", uploadId));
            Map<String, Object> restorePayload = payload(instanceId, "restore");
            restorePayload.put("file", staging);
            call(nodeId, "backup.restore", restorePayload);
        } catch (Exception e) {
            if (uploadId != null) {
                try {
                    call(nodeId, "backup.upload.abort", mapOf("uploadId", uploadId));
                } catch (Exception ignored) {
                    // 中止失败不掩盖原始异常
                }
            }
            throw e;
        }
        // 回灌副本只为恢复服务，成功后即删，避免节点备份目录被临时档堆积
        try {
            Map<String, Object> deletePayload = payload(instanceId, "backup-delete");
            deletePayload.put("file", staging);
            call(nodeId, "backup.delete", deletePayload);
        } catch (Exception ignored) {
            // 清理失败不影响恢复结果
        }
    }

    private void ensureStopped(String nodeId, String instanceId) throws Exception {
        String state = inspectState(nodeId, instanceId);
        if (state == null || !RUNNING_STATES.contains(state)) {
            return;
        }
        call(nodeId, "instance.stop", payload(instanceId, "stop"));
        long deadline = System.currentTimeMillis() + STOP_POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(STOP_POLL_INTERVAL_MS);
            state = inspectState(nodeId, instanceId);
            if (state == null || !RUNNING_STATES.contains(state)) {
                return;
            }
        }
        throw new IllegalStateException("停止实例超时，请手动停止后重试");
    }

    private String inspectState(String nodeId, String instanceId) throws Exception {
        Map<String, Object> inspected = call(nodeId, "instance.inspect", mapOf("instanceId", instanceId));
        Object state = inspected.get("state");
        return state == null ? null : String.valueOf(state);
    }

    private boolean nodeDataEmpty(String nodeId, String instanceId) throws Exception {
        Map<String, Object> listing = call(nodeId, "file.list",
                mapOf("instanceId", instanceId, "path", "/", "page", 1, "size", 1));
        Object total = listing.get("total");
        return total == null || ((Number) total).longValue() == 0;
    }

    private byte[] downloadArchive(String nodeId, String archive) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long offset = 0;
        while (true) {
            Map<String, Object> chunk = call(nodeId, "backup.download.chunk", mapOf(
                    "file", archive,
                    "offset", offset,
                    "length", (long) CHUNK_BYTES));
            byte[] data = Base64.getDecoder().decode(String.valueOf(chunk.get("content")));
            bytes.writeBytes(data);
            offset += data.length;
            if (Boolean.TRUE.equals(chunk.get("eof")) || data.length == 0) {
                return bytes.toByteArray();
            }
        }
    }

    /** 校验节点在线且支持分块备份通道；不满足抛 NodeCallException（node.capability/offline）。 */
    private void requireTransferCapability(String nodeId, String name) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new NodeCallException("node.offline", "实例未绑定节点");
        }
        List<String> caps = nodes.caps(nodeId);
        if (!caps.contains("backup.download.chunk")) {
            throw new NodeCallException("node.capability",
                    "节点 " + nodeId + " 不支持分块备份通道（节点版本过旧或备份目录未配置），请升级节点后重试");
        }
    }

    private Map<String, Object> call(String nodeId, String method, Map<String, Object> payload) throws Exception {
        try {
            return nodes.call(nodeId, method, payload).join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    private Map<String, Object> payload(String instanceId, String operation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", instanceId);
        payload.put("ik", "panel-srvbackup-" + operation + "-" + instanceId + "-" + System.currentTimeMillis());
        return payload;
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}
