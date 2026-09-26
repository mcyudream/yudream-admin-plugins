package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.valobj.NodeStatsSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据监控总览（对标 MCSM PanelOverview / NodeOverview / StatusBlock）。
 * 聚合节点在线、实例运行状态、节点资源与版本信息。
 *
 * <p>曲线数据来自 {@link MetricsHistoryService}（后台 30s 采样 + 文档落库）：
 * 页面只读历史，不再"进页面才开始攒点"，面板重启后曲线依然连续。
 */
public class OverviewService {

    public static final String PLUGIN_VERSION = "0.13.0";

    /** 曲线回看窗口：30s 采样下取最近 30 分钟（≈60 点），前端 sparkline 足够。 */
    private static final long HISTORY_WINDOW_MS = 30 * 60_000L;
    /** 单条曲线最多下发点数（防解析旧文档时点数异常）。 */
    private static final int MAX_HISTORY_POINTS = 60;

    private final InstanceStateResolver stateResolver;
    private final McpanelNodeRepository nodes;
    private final McpanelInstanceRepository instances;
    private final MetricsHistoryService metrics;

    public OverviewService(InstanceStateResolver stateResolver, McpanelNodeRepository nodes,
                           McpanelInstanceRepository instances, MetricsHistoryService metrics) {
        this.stateResolver = stateResolver;
        this.nodes = nodes;
        this.instances = instances;
        this.metrics = metrics;
    }

    public Map<String, Object> overview() {
        List<McpanelNode> nodeList = nodes.page(
                new online.yudream.base.plugin.mcpanel.domain.valobj.NodeQuery(1, 200, null, null))
                .records();
        int totalNodes = nodeList.size();
        int onlineNodes = 0;
        List<Map<String, Object>> nodeRows = new ArrayList<>();
        for (McpanelNode node : nodeList) {
            boolean online = "online".equalsIgnoreCase(String.valueOf(node.status()));
            if (online) {
                onlineNodes++;
            }
            Map<String, Object> stats = nodeStats(node);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", node.id());
            row.put("name", node.name());
            row.put("status", node.status());
            row.put("connected", online);
            row.put("endpoint", node.endpoint());
            row.put("agentVersion", node.agentVersion());
            row.put("dockerVersion", node.dockerVersion());
            row.put("cpuPercent", stats.get("cpuPercent"));
            row.put("memUsedMb", stats.get("memUsedMb"));
            row.put("memTotalMb", stats.get("memTotalMb"));
            row.put("containers", stats.get("containerCount"));
            row.put("history", nodeHistory(node.id(), stats));
            nodeRows.add(row);
        }

        int totalInstances = 0;
        int runningInstances = 0;
        int exitedInstances = 0;
        int otherInstances = 0;
        List<Map<String, Object>> recent = new ArrayList<>();
        // 实例 → 容器实时统计（来自所属节点最近一次 stats 快照）
        Map<String, Map<String, Object>> statsByNode = new LinkedHashMap<>();
        for (McpanelNode node : nodeList) {
            Map<String, Object> stats = nodeStats(node);
            statsByNode.put(node.id(), stats);
        }
        var allInstances = instances.findAll();
        totalInstances = allInstances.size();
        for (var inst : allInstances) {
            // 统计与 recent 列表统一走状态解析器（快照优先、DB 回退），与实例列表/详情同口径。
            String state = stateResolver.resolve(inst);
            if ("running".equalsIgnoreCase(state) || "starting".equalsIgnoreCase(state)) {
                // 启动中仍属「运行系」：占资源、算运行实例数。
                runningInstances++;
            }
            else if ("exited".equalsIgnoreCase(state)) {
                exitedInstances++;
            }
            else {
                otherInstances++;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", inst.id());
            item.put("name", inst.name());
            item.put("state", state);
            item.put("nodeId", inst.nodeId());
            item.put("kind", inst.kind());
            item.put("mcVersion", inst.mcVersion());
            item.put("updatedAt", inst.updatedAt());

            // 容器维度实时 CPU/内存 + 页面间共享的采样窗口
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> containers = (List<Map<String, Object>>) statsByNode
                    .getOrDefault(String.valueOf(inst.nodeId()), Map.of())
                    .get("containers");
            Map<String, Object> container = containers == null ? null : containers.stream()
                    .filter(c -> inst.id().equals(String.valueOf(c.get("instanceId"))))
                    .findFirst()
                    .orElse(null);
            if (container != null) {
                item.put("cpuPercent", container.get("cpuPercent"));
                item.put("memUsedMb", container.get("memUsedMb"));
            }
            // 内存曲线按容器上限取百分比：memTotalMb 用实例内存规格（后端补，前端直接算比例）。
            item.put("memTotalMb", inst.memoryMb());
            item.put("history", instanceHistory(inst, container));
            recent.add(item);
        }
        recent.sort((a, b) -> Long.compare(
                (Long) b.getOrDefault("updatedAt", 0L),
                (Long) a.getOrDefault("updatedAt", 0L)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pluginVersion", PLUGIN_VERSION);
        result.put("generatedAt", System.currentTimeMillis());
        result.put("nodes", Map.of(
                "total", totalNodes,
                "online", onlineNodes,
                "offline", Math.max(0, totalNodes - onlineNodes)));
        result.put("instances", Map.of(
                "total", totalInstances,
                "running", runningInstances,
                "exited", exitedInstances,
                "other", otherInstances));
        result.put("nodeDetails", nodeRows);
        result.put("recentInstances", recent.subList(0, Math.min(10, recent.size())));
        return result;
    }

    /**
     * 节点曲线：读后台采集的窗口点（30s 采样、已落库），末点落后于当前快照时补一条实时值，
     * 页面轮询期间曲线尾部仍在动。
     */
    private List<Map<String, Object>> nodeHistory(String nodeId, Map<String, Object> stats) {
        List<Map<String, Object>> samples = new ArrayList<>();
        for (double[] point : metrics.nodePoints(nodeId, HISTORY_WINDOW_MS)) {
            samples.add(sample(point[0], point[1], point[2], point.length > 3 ? point[3] : null));
        }
        appendCurrent(samples, stats.get("cpuPercent"), stats.get("memUsedMb"), stats.get("memTotalMb"));
        return tail(samples);
    }

    /**
     * 实例曲线：容器级历史；内存百分比以实例内存规格为分母（与前端 historyMemSeries 同算法），
     * 因此每个点都带上 memTotalMb。
     */
    private List<Map<String, Object>> instanceHistory(McpanelInstance inst, Map<String, Object> container) {
        List<Map<String, Object>> samples = new ArrayList<>();
        Double limit = (double) inst.memoryMb();
        for (double[] point : metrics.points(inst.id(), HISTORY_WINDOW_MS)) {
            samples.add(sample(point[0], point[1], point[2], limit));
        }
        if (container != null) {
            appendCurrent(samples, container.get("cpuPercent"), container.get("memUsedMb"), limit);
        }
        return tail(samples);
    }

    private static Map<String, Object> sample(double at, double cpu, double memUsed, Double memTotal) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("at", (long) at);
        sample.put("cpuPercent", cpu);
        sample.put("memUsedMb", memUsed);
        if (memTotal != null) {
            sample.put("memTotalMb", memTotal);
        }
        return sample;
    }

    /** 实时值补点：末点距今不足 25s 时不补，避免同一个采样窗口重复入曲线。 */
    private static void appendCurrent(List<Map<String, Object>> samples,
                                      Object cpu, Object memUsed, Object memTotal) {
        if (!(cpu instanceof Number) && !(memUsed instanceof Number)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!samples.isEmpty() && samples.get(samples.size() - 1).get("at") instanceof Number last
                && now - last.longValue() < 25_000L) {
            return;
        }
        double cpuValue = cpu instanceof Number number ? Math.max(0d, Math.min(100d, number.doubleValue())) : 0d;
        double memValue = memUsed instanceof Number number ? Math.max(0d, number.doubleValue()) : 0d;
        Double totalValue = memTotal instanceof Number number ? Math.max(0d, number.doubleValue()) : null;
        samples.add(sample(now, cpuValue, memValue, totalValue));
    }

    private static List<Map<String, Object>> tail(List<Map<String, Object>> samples) {
        int size = samples.size();
        return size <= MAX_HISTORY_POINTS
                ? samples
                : new ArrayList<>(samples.subList(size - MAX_HISTORY_POINTS, size));
    }

    private Map<String, Object> nodeStats(McpanelNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cpuPercent", null);
        out.put("memUsedMb", null);
        out.put("memTotalMb", null);
        out.put("containerCount", 0);
        out.put("containers", List.of());
        var stats = node.lastStats();
        if (stats != null) {
            out.put("cpuPercent", stats.cpuPercent());
            out.put("memUsedMb", stats.memUsedMb());
            out.put("memTotalMb", stats.memTotalMb());
            out.put("containerCount", stats.containers() == null ? 0 : stats.containers().size());
            List<Map<String, Object>> containers = new ArrayList<>();
            if (stats.containers() != null) {
                for (NodeStatsSnapshot.ContainerStat container : stats.containers()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("instanceId", container.instanceId());
                    item.put("state", container.state());
                    item.put("cpuPercent", container.cpuPercent());
                    item.put("memUsedMb", container.memUsedMb());
                    containers.add(item);
                }
            }
            out.put("containers", containers);
        }
        return out;
    }
}
