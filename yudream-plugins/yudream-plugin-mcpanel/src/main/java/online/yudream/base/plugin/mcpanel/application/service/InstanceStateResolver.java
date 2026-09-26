package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.valobj.NodeStatsSnapshot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 实例状态展示口径的唯一来源：节点最近一次 stats 快照优先（docker events 断流期间
 * 面板 DB 可能停留在旧状态），快照缺失（节点离线/未上报/容器不在快照中）回退 DB。
 *
 * <p>列表页（page）、详情页（detail）、总览统计（OverviewService）一律经本类取值，
 * 前端读装饰字段 {@code liveState}、回退 {@code state}；禁止各端自行拼接，
 * 避免口径漂移（同一语义也用于 stats 落库纠偏的归一）。
 */
public class InstanceStateResolver {

    private final McpanelNodeRepository nodes;

    public InstanceStateResolver(McpanelNodeRepository nodes) {
        this.nodes = nodes;
    }

    /** 单实例展示状态：快照优先、DB 回退。 */
    public String resolve(McpanelInstance instance) {
        return nodes.findById(instance.nodeId())
                .map(McpanelNode::lastStats)
                .map(stats -> fromSnapshot(instance.id(), stats))
                .filter(live -> live != null)
                .orElse(String.valueOf(instance.state()));
    }

    /** 列表结果装饰：每条 record 追加 liveState（节点查询按 nodeId 去重）。 */
    public void decoratePage(Map<String, Object> pageResult) {
        if (pageResult == null || !(pageResult.get("records") instanceof List<?> records)) {
            return;
        }
        Map<String, NodeStatsSnapshot> statsByNode = new HashMap<>();
        for (Object row : records) {
            if (row instanceof Map<?, ?> record) {
                decorate(asMutableMap(record), record, statsByNode);
            }
        }
    }

    /** 详情 DTO 装饰：追加 liveState。 */
    public void decorateDetail(Map<String, Object> dto) {
        if (dto == null || dto.get("id") == null) {
            return;
        }
        decorate(dto, dto, new HashMap<>());
    }

    private void decorate(Map<String, Object> target, Map<?, ?> source, Map<String, NodeStatsSnapshot> cache) {
        Object id = source.get("id");
        Object nodeId = source.get("nodeId");
        if (id == null || target == null) {
            return;
        }
        String live = null;
        if (nodeId != null) {
            NodeStatsSnapshot stats = cache.computeIfAbsent(String.valueOf(nodeId), key ->
                    nodes.findById(key).map(McpanelNode::lastStats).orElse(null));
            live = stats == null ? null : fromSnapshot(String.valueOf(id), stats);
        }
        target.put("liveState", live != null ? live : String.valueOf(source.get("state")));
    }

    /**
     * 快照中的实例状态（stopped 归一 exited，unknown/缺失返回 null 不作依据）——
     * 与 stats 落库纠偏同一归一规则。
     */
    static String fromSnapshot(String instanceId, NodeStatsSnapshot stats) {
        if (stats == null || stats.containers() == null) {
            return null;
        }
        for (NodeStatsSnapshot.ContainerStat container : stats.containers()) {
            if (instanceId.equals(container.instanceId())) {
                return McpanelInstanceAppService.normalizeStatsState(container.state());
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMutableMap(Map<?, ?> record) {
        return (Map<String, Object>) record;
    }
}
