package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService.AuditRecorder;
import online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService.NodeCallGateway;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.TrashRepository;
import online.yudream.base.plugin.mcpanel.domain.valobj.TrashRecord;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeCallException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 回收站（实例删除软保护）：删除实例时节点把数据目录移入 data/trash/，本服务
 * 保存快照记录并提供 分页/找回/立即删除。找回 = 以快照规格 + 原实例 id 重建实例，
 * 节点侧 instance.create.fromTrash 先把目录改名回 instances/<id> 再挂载；
 * 端口、mc-server 绑定、域名一律重置，不随快照复用。
 */
public class McpanelTrashAppService {

    /** 节点确认 trashed 后由实例删除流程回调（bootstrap 装配，避免构造环）。 */
    public interface TrashSink {
        void record(McpanelInstance instance, String deletedBy);
    }

    private static final long CALL_TIMEOUT_MS = 30_000L;

    private final TrashRepository trashRepository;
    private final McpanelNodeRepository nodeRepository;
    private final McpanelInstanceAppService instanceService;
    private final NodeCallGateway nodeCalls;
    private final AuditRecorder audit;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public McpanelTrashAppService(TrashRepository trashRepository, McpanelNodeRepository nodeRepository,
                                  McpanelInstanceAppService instanceService, NodeCallGateway nodeCalls,
                                  AuditRecorder audit, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.trashRepository = trashRepository;
        this.nodeRepository = nodeRepository;
        this.instanceService = instanceService;
        this.nodeCalls = nodeCalls;
        this.audit = audit;
        this.mapper = mapper;
    }

    /** 实例删除（未 purge）且节点确认目录已进回收站时落快照。 */
    public void record(McpanelInstance instance, String deletedBy) {
        Map<String, Object> snapshot = mapper.convertValue(instance,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
        trashRepository.save(new TrashRecord(instance.id(), instance.nodeId(), instance.id(),
                instance.name(), instance.kind(), instance.mcVersion(), instance.remark(),
                instance.tenantId(), snapshot, deletedBy, System.currentTimeMillis()));
    }

    /** 分页 + 节点装饰：节点名/在线/能力，在线且支持回收站时附带目录在位与保留期。 */
    public Map<String, Object> page(int page, int size, String keyword) {
        TrashRepository.PageResult<TrashRecord> result = trashRepository.page(page, size, keyword);
        List<Map<String, Object>> rows = result.records().stream().map(record -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("trashId", record.trashId());
            row.put("instanceId", record.instanceId());
            row.put("instanceName", record.instanceName());
            row.put("kind", record.kind());
            row.put("mcVersion", record.mcVersion());
            row.put("remark", record.remark());
            row.put("nodeId", record.nodeId());
            row.put("deletedAtMs", record.deletedAtMs());
            row.put("deletedBy", record.deletedBy());
            decorateNode(record, row);
            return row;
        }).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("records", rows);
        payload.put("total", result.total());
        payload.put("page", result.page());
        payload.put("size", result.size());
        return payload;
    }

    /** 找回：以快照规格重建实例（名称可改），成功后移除回收站记录。 */
    public Map<String, Object> restore(String actor, String scopeKey, String trashId, String nameOverride) {
        TrashRecord record = trashRepository.findById(trashId)
                .orElseThrow(() -> McpanelBusinessException.notFound("回收站条目不存在或已被清除"));
        McpanelNode node = nodeRepository.findById(record.nodeId())
                .orElseThrow(() -> new McpanelBusinessException("node.missing", 409,
                        "原节点已删除：数据目录在节点机上，节点不存在时无法找回"));
        String name = nameOverride == null || nameOverride.isBlank() ? record.instanceName() : nameOverride.trim();
        McpanelInstance snapshot = mapper.convertValue(record.specSnapshot(), McpanelInstance.class);
        long now = System.currentTimeMillis();
        // 运行态字段全部重置：端口走正常分配、绑定/域名/回归历史不复用（删除时已释放）。
        // startDetect 从快照保留（0.17.2+ 快照才有；老快照为空 = 节点按类型默认）。
        McpanelInstance spec = new McpanelInstance(trashId, record.nodeId(), name,
                snapshot.kind(), snapshot.mcVersion(), snapshot.templateKey(), snapshot.image(),
                snapshot.command(), snapshot.env(), snapshot.memoryMb(), snapshot.cpuMillis(),
                snapshot.diskMb(), List.of(), snapshot.config(), "installing", null, "",
                snapshot.tenantId(), snapshot.remark(), "", false, false, List.of(),
                snapshot.nodeTrust(), snapshot.modpack(), List.of(), snapshot.startDetect(),
                false, false, now, now);
        Map<String, Object> created;
        try {
            created = instanceService.create(actor, scopeKey, spec, Map.of("fromTrash", trashId));
        } catch (McpanelBusinessException error) {
            // 同名冲突等业务失败保持回收站记录，便于改名重试。
            throw error;
        }
        trashRepository.delete(trashId);
        audit.record(actor, "trash.restore", "trash", trashId,
                "从回收站找回实例「" + name + "」@节点 " + node.name(), record.tenantId());
        return created;
    }

    /** 立即永久删除：代理节点 trash.delete（RemoveAll 回收目录）并移除记录。 */
    public Map<String, Object> remove(String actor, String trashId) {
        TrashRecord record = trashRepository.findById(trashId)
                .orElseThrow(() -> McpanelBusinessException.notFound("回收站条目不存在或已被清除"));
        boolean nodeRemoved = false;
        String nodeError = null;
        if (nodeRepository.findById(record.nodeId()).isPresent()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("trashId", trashId);
            try {
                callNode(record.nodeId(), "trash.delete", payload);
                nodeRemoved = true;
            } catch (McpanelBusinessException error) {
                // 节点离线/能力不足时保留记录，管理员可在节点恢复后再清。
                nodeError = error.getMessage();
            }
        }
        if (nodeRemoved) {
            trashRepository.delete(trashId);
            audit.record(actor, "trash.delete", "trash", trashId,
                    "永久删除回收目录（实例「" + record.instanceName() + "」）", record.tenantId());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", nodeRemoved);
        result.put("nodeRemoved", nodeRemoved);
        if (nodeError != null) {
            result.put("error", nodeError);
        }
        return result;
    }

    // ---------- 内部 ----------

    private void decorateNode(TrashRecord record, Map<String, Object> row) {
        McpanelNode node = nodeRepository.findById(record.nodeId()).orElse(null);
        if (node == null) {
            row.put("nodeName", null);
            row.put("nodeOnline", false);
            row.put("nodeMissing", true);
            row.put("trashCapable", false);
            row.put("dirPresent", null);
            row.put("retentionDays", null);
            return;
        }
        row.put("nodeName", node.name());
        row.put("nodeOnline", node.online());
        row.put("nodeMissing", false);
        boolean capable = node.caps() != null && node.caps().contains("trash.list");
        row.put("trashCapable", capable);
        row.put("retentionDays", null);
        row.put("dirPresent", null);
        if (!node.online() || !capable) {
            return;
        }
        try {
            Map<String, Object> listed = callNode(record.nodeId(), "trash.list", Map.of());
            if (listed.get("retentionDays") instanceof Number days) {
                row.put("retentionDays", days.intValue());
            }
            if (listed.get("items") instanceof List<?> items) {
                // 节点侧目录主名 = 实例 id（= trashId）；兼容带毫秒后缀的旧残留。
                String nodeTrashId = null;
                for (Object item : items) {
                    if (item instanceof Map<?, ?> one && one.get("trashId") instanceof String id) {
                        if (id.equals(record.trashId())) {
                            nodeTrashId = id;
                            break;
                        }
                        if (nodeTrashId == null && id.startsWith(record.trashId() + "-")) {
                            nodeTrashId = id;
                        }
                    }
                }
                row.put("nodeTrashId", nodeTrashId);
                row.put("dirPresent", nodeTrashId != null);
            }
        } catch (McpanelBusinessException error) {
            // 装饰失败不阻断列表：dirPresent 保持未知（null）。
        }
    }

    private Map<String, Object> callNode(String nodeId, String method, Map<String, Object> payload) {
        try {
            return nodeCalls.call(nodeId, method, payload).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new McpanelBusinessException("internal.error", 500, "节点调用被中断");
        } catch (java.util.concurrent.ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof NodeCallException nodeCall) {
                throw new McpanelBusinessException(nodeCall.code() == null ? "node.error" : nodeCall.code(),
                        409, nodeCall.getMessage());
            }
            throw new McpanelBusinessException("node.unreachable", 502, "节点调用失败：" + cause.getMessage());
        } catch (java.util.concurrent.TimeoutException error) {
            throw new McpanelBusinessException("node.timeout", 504, "节点响应超时：" + method);
        }
    }
}
