package online.yudream.base.plugin.mcpanel.infrastructure.backup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.spi.system.backup.BackupExportOptions;
import online.yudream.base.plugin.spi.system.backup.BackupSink;
import online.yudream.base.plugin.spi.system.backup.BackupSource;
import online.yudream.base.plugin.spi.system.backup.PluginBackupConflictStrategy;
import online.yudream.base.plugin.spi.system.backup.PluginBackupProvider;
import online.yudream.base.plugin.spi.system.backup.ScopedBackupFile;
import online.yudream.base.plugin.spi.system.secret.PluginSecretStore;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面板数据备份范围（宿主 SPI {@code PluginBackupProvider} 扩展点）：
 * 把 mcpanel 元数据主数据集合（{@code mongo/{collection}.ndjson}）、节点注册密钥
 * （{@code secrets/nodes-{id}.bin}）与 {@code meta.json} 写入宿主归档；
 * 合并导入时按冲突策略回写宿主文档/密钥存储（本地为准只补缺，备份为准覆盖）。
 * 节点上的实例世界数据由 mcpanel-node 持有，待节点协议提供归档流式下载后
 * 可按同一扩展点追加 server-data 范围。
 * 导出内容含节点注册密钥等敏感凭据，备份归档请妥善保管。
 */
public class McpanelPanelDataBackupProvider implements PluginBackupProvider {

    /** 随备份迁移的主数据集合；audit/metrics 等可重建遥测不入档。 */
    private static final List<String> COLLECTIONS = List.of(
            "nodes",
            "mcpanel_instances",
            "enroll-tokens",
            "mcpanel_port_allocations",
            "mcpanel_settings",
            "mcpanel_templates",
            "mcpanel_docker_images",
            "mcpanel_schedules",
            "mcpanel_proxy_groups",
            "mcpanel_sync_links",
            "mcpanel_install_plan",
            "mcpanel_contributions",
            "mcpanel_quickstart_packages",
            "mcpanel_trash");

    private static final int PAGE_SIZE = 200;
    private static final String NODE_SECRET_PREFIX = "nodes/";
    private static final String NODE_SECRET_SUFFIX = "/secret";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final PluginDocumentStore documents;
    private final PluginSecretStore secrets;
    private final ObjectMapper mapper;

    public McpanelPanelDataBackupProvider(PluginDocumentStore documents, PluginSecretStore secrets, ObjectMapper mapper) {
        this.documents = documents;
        this.secrets = secrets;
        this.mapper = mapper;
    }

    @Override
    public String scopeCode() {
        return "panel-data";
    }

    @Override
    public String displayName() {
        return "MC 面板数据";
    }

    @Override
    public String description() {
        return "面板元数据与节点注册密钥（含敏感凭据，归档请妥善保管）";
    }

    @Override
    public String defaultSchedule() {
        return "0 0 4 * * *";
    }

    @Override
    public ExportReport export(BackupSink sink, BackupExportOptions options) throws Exception {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String collection : COLLECTIONS) {
            StringBuilder lines = new StringBuilder();
            int total = 0;
            for (int page = 1; ; page++) {
                List<Map<String, Object>> batch = documents.findAll(collection, page, PAGE_SIZE);
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                for (Map<String, Object> document : batch) {
                    lines.append(mapper.writeValueAsString(stripReserved(document))).append('\n');
                    total++;
                }
            }
            counts.put(collection, total);
            sink.putText("mongo/" + collection + ".ndjson", lines.toString());
        }
        int secretCount = 0;
        for (Map<String, Object> node : iterateAll("nodes")) {
            Object id = node.get("id");
            if (id == null) {
                continue;
            }
            byte[] secret = secrets.get(NODE_SECRET_PREFIX + id + NODE_SECRET_SUFFIX).orElse(null);
            if (secret != null) {
                sink.putFile("secrets/nodes-" + sanitize(id.toString()) + ".bin", secret.length,
                        new ByteArrayInputStream(secret));
                secretCount++;
            }
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("plugin", "mcpanel");
        meta.put("scope", "panel-data");
        meta.put("exportedAt", Instant.now().toString());
        meta.put("counts", counts);
        meta.put("nodeSecrets", secretCount);
        sink.putText("meta.json", mapper.writeValueAsString(meta));
        return ExportReport.none();
    }

    @Override
    public void restore(BackupSource source, PluginBackupConflictStrategy strategy) throws Exception {
        boolean archiveWins = strategy == PluginBackupConflictStrategy.ARCHIVE_WINS;
        for (ScopedBackupFile file : source.files()) {
            String path = file.path();
            if (path.startsWith("mongo/") && path.endsWith(".ndjson")) {
                String collection = path.substring("mongo/".length(), path.length() - ".ndjson".length());
                restoreCollection(source.open(path), collection, archiveWins);
            }
            else if (path.startsWith("secrets/nodes-") && path.endsWith(".bin")) {
                String nodeId = path.substring("secrets/nodes-".length(), path.length() - ".bin".length());
                String key = NODE_SECRET_PREFIX + nodeId + NODE_SECRET_SUFFIX;
                if (archiveWins || secrets.get(key).isEmpty()) {
                    try (InputStream in = source.open(path)) {
                        secrets.put(key, in.readAllBytes());
                    }
                }
            }
        }
    }

    private void restoreCollection(InputStream in, String collection, boolean archiveWins) throws Exception {
        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> document = stripReserved(mapper.readValue(line, MAP_TYPE));
            Object id = document.get("id");
            if (id == null) {
                continue;
            }
            String idText = String.valueOf(id);
            if (!archiveWins && documents.findById(collection, idText).isPresent()) {
                continue;
            }
            documents.save(collection, idText, document);
        }
    }

    private List<Map<String, Object>> iterateAll(String collection) {
        java.util.ArrayList<Map<String, Object>> all = new java.util.ArrayList<>();
        for (int page = 1; ; page++) {
            List<Map<String, Object>> batch = documents.findAll(collection, page, PAGE_SIZE);
            if (batch == null || batch.isEmpty()) {
                return all;
            }
            all.addAll(batch);
        }
    }

    /** 宿主文档存储的保留字段（_id/pluginCode）由存储层维护，出入档前剔除。 */
    private static Map<String, Object> stripReserved(Map<String, Object> document) {
        Map<String, Object> copy = new LinkedHashMap<>(document);
        copy.remove("_id");
        copy.remove("pluginCode");
        return copy;
    }

    private static String sanitize(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
