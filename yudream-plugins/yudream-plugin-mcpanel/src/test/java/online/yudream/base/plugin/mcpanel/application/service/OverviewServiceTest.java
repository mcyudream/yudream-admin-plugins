package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.valobj.NodeStatsSnapshot;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentMcpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 总览统计的实例状态口径：节点 stats 快照优先（与 stats 单向纠偏、实例列表页
 * stateOf 同语义），快照缺失回退面板 DB——防止统计页与实例列表互相矛盾。
 */
class OverviewServiceTest {

    private static final long NOW = 1_700_000_000_000L;

    private McpanelInstanceRepository instances;
    private McpanelNodeRepository nodes;
    private MetricsHistoryService metrics;
    private OverviewService service;

    @BeforeEach
    void setUp() {
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        instances = new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
        nodes = new DocumentNodeRepository(documents, McpanelJson.mapper());
        metrics = new MetricsHistoryService(documents);
        service = new OverviewService(new InstanceStateResolver(nodes), nodes, instances, metrics);
    }

    private static McpanelInstance instance(String id, String nodeId, String state) {
        return McpanelInstance.create(id, nodeId, "inst-" + id, "paper", "1.21", "", "img",
                List.of("java", "-jar", "server.jar"), Map.of(), 512, 500, 1024,
                List.of(), Map.of(), null, "", NOW).withState(state, null, NOW);
    }

    private void node(String id, NodeStatsSnapshot stats) {
        McpanelNode base = McpanelNode.create(id, "节点" + id, "wss://" + id + ".example.com:7000",
                "pkix", null, false, "", true, NOW);
        nodes.save(new McpanelNode(base.id(), base.name(), base.endpoint(), base.tlsMode(),
                base.pinSha256(), base.localDevelopment(), base.remark(), base.enabled(),
                base.tenantId(), "online", base.agentVersion(), base.reportedHost(), base.sessionId(),
                base.caps(), base.dockerVersion(), base.reportedCertSha256(), stats,
                base.enrolledAtMs(), base.lastSeenAtMs(), base.createdAtMs(), base.updatedAtMs(),
                base.portRangeStart(), base.portRangeEnd(), base.reservedPorts(), base.sftpHost(),
                base.accessMode(), base.accessHost()));
    }

    private static NodeStatsSnapshot statsWith(NodeStatsSnapshot.ContainerStat... containers) {
        return new NodeStatsSnapshot(12.5, 1024, 8192, 20, 100, null,
                List.of(containers), "27.0", "0.3.2", NOW);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> instanceCounts(Map<String, Object> overview) {
        return (Map<String, Object>) overview.get("instances");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> recentInstances(Map<String, Object> overview) {
        return (List<Map<String, Object>>) overview.get("recentInstances");
    }

    @Test
    void countsLiveSnapshotStateOverStaleDb() {
        node("nodeA", statsWith(new NodeStatsSnapshot.ContainerStat("i1", "running", 1.0, 64)));
        instances.save(instance("i1", "nodeA", "exited"));

        Map<String, Object> overview = service.overview();

        assertEquals(1, instanceCounts(overview).get("running"));
        assertEquals(0, instanceCounts(overview).get("exited"));
        assertEquals("running", recentInstances(overview).get(0).get("state"),
                "recent 列表也要用实时状态，避免与实例列表页矛盾");
    }

    @Test
    void normalizesStoppedSnapshotAsExited() {
        node("nodeA", statsWith(new NodeStatsSnapshot.ContainerStat("i1", "stopped", 0.0, 0)));
        instances.save(instance("i1", "nodeA", "running"));

        Map<String, Object> overview = service.overview();

        assertEquals(0, instanceCounts(overview).get("running"));
        assertEquals(1, instanceCounts(overview).get("exited"));
    }

    @Test
    void fallsBackToDbWhenSnapshotMissing() {
        node("nodeA", statsWith(new NodeStatsSnapshot.ContainerStat("i1", "running", 1.0, 64)));
        node("nodeB", null);
        instances.save(instance("i1", "nodeA", "running"));
        instances.save(instance("i2", "nodeA", "exited"));
        instances.save(instance("i3", "nodeB", "running"));
        instances.save(instance("i4", "nodeB", "installing"));

        Map<String, Object> overview = service.overview();

        assertEquals(2, instanceCounts(overview).get("running"), "i1 快照 running + i3 无快照回退 DB");
        assertEquals(1, instanceCounts(overview).get("exited"), "i2 不在快照中，不推断，回退 DB");
        assertEquals(1, instanceCounts(overview).get("other"), "i4 installing 回退 DB 计入其他");
    }

    @Test
    void ignoresUnknownSnapshotStateAndFallsBackToDb() {
        node("nodeA", statsWith(new NodeStatsSnapshot.ContainerStat("i1", "unknown", 0.0, 0)));
        instances.save(instance("i1", "nodeA", "exited"));

        Map<String, Object> overview = service.overview();

        assertEquals(1, instanceCounts(overview).get("exited"), "unknown 不作依据，回退 DB");
        assertEquals("exited", recentInstances(overview).get(0).get("state"));
    }

    @Test
    void nodeHistoryComesFromBackgroundCollection() {
        node("nodeA", null);
        instances.save(instance("i1", "nodeA", "running"));
        // 后台采样（页面从未打开过）：节点整机 + 实例容器各两点。
        metrics.onNodeStats("nodeA", Map.of("cpuPercent", 10, "memUsedMb", 1024, "memTotalMb", 8192));
        metrics.onStats("nodeA", Map.of("containers", List.of(Map.of(
                "instanceId", "i1", "state", "running", "cpuPercent", 20, "memUsedMb", 256))));

        Map<String, Object> overview = service.overview();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodeRows = (List<Map<String, Object>>) overview.get("nodeDetails");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodeHistory = (List<Map<String, Object>>) nodeRows.get(0).get("history");
        assertEquals(1, nodeHistory.size(), "节点曲线来自后台采集，而不是本次请求才开始攒点");
        assertEquals(10.0, nodeHistory.get(0).get("cpuPercent"));
        assertEquals(1024.0, nodeHistory.get(0).get("memUsedMb"));
        assertEquals(8192.0, nodeHistory.get(0).get("memTotalMb"));

        Map<String, Object> instanceRow = recentInstances(overview).get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> instanceHistory = (List<Map<String, Object>>) instanceRow.get("history");
        assertEquals(1, instanceHistory.size());
        assertEquals(20.0, instanceHistory.get(0).get("cpuPercent"));
        assertEquals(512.0, instanceHistory.get(0).get("memTotalMb"), "内存曲线分母=实例内存规格");
    }

    @Test
    void emptyHistoryStillReturnsCurrentSample() {
        node("nodeA", statsWith(new NodeStatsSnapshot.ContainerStat("i1", "running", 33.0, 2048)));
        instances.save(instance("i1", "nodeA", "running"));

        Map<String, Object> overview = service.overview();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodeRows = (List<Map<String, Object>>) overview.get("nodeDetails");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodeHistory = (List<Map<String, Object>>) nodeRows.get(0).get("history");
        assertEquals(1, nodeHistory.size(), "无历史时至少给出当前快照，页面不空白");
        assertEquals(12.5, nodeHistory.get(0).get("cpuPercent"));
    }
}
