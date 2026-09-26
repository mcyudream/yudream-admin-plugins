package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 性能历史环形窗口：节流采样、窗口查询、持久化回读与过期裁剪。 */
class MetricsHistoryServiceTest {

    private static Map<String, Object> stats(String instanceId, String state, double cpu, double mem) {
        return Map.of("containers", List.of(Map.of(
                "instanceId", instanceId, "state", state,
                "cpuPercent", cpu, "memUsedMb", mem)));
    }

    @Test
    void samplesAreThrottledPerInstance() {
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        try (MetricsHistoryService metrics = new MetricsHistoryService(documents)) {
            metrics.onStats("node-1", stats("i1", "running", 10, 100));
            metrics.onStats("node-1", stats("i1", "running", 50, 200)); // 30s 内：不采样
            metrics.onStats("node-1", stats("i2", "running", 30, 300));
            metrics.onStats("node-1", stats("i3", "exited", 90, 900));  // 非运行不采样
            @SuppressWarnings("unchecked")
            List<Object> i1 = (List<Object>) metrics.view("i1", 60_000).get("points");
            @SuppressWarnings("unchecked")
            List<Object> i2 = (List<Object>) metrics.view("i2", 60_000).get("points");
            @SuppressWarnings("unchecked")
            List<Object> i3 = (List<Object>) metrics.view("i3", 60_000).get("points");
            assertEquals(1, i1.size());
            assertEquals(1, i2.size());
            assertEquals(0, i3.size());
        }
    }

    @Test
    void survivesRestartViaDocumentRoundTripAndExpiresOldPoints() {
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        try (MetricsHistoryService metrics = new MetricsHistoryService(documents)) {
            metrics.onStats("node-1", stats("i1", "running", 42, 512));
            // close() 触发刷盘。
        }
        try (MetricsHistoryService restored = new MetricsHistoryService(documents)) {
            Map<String, Object> view = restored.view("i1", 60_000);
            @SuppressWarnings("unchecked")
            List<Object> points = (List<Object>) view.get("points");
            assertEquals(1, points.size());
        }
        // 手写一条过期点：回读时必须被 24h TTL 裁掉。
        documents.save("mcpanel_metrics", "i2", Map.of(
                "points", List.of(List.of(System.currentTimeMillis() - 25 * 3600_000L, 5, 5)),
                "updatedAt", System.currentTimeMillis()));
        try (MetricsHistoryService restored = new MetricsHistoryService(documents)) {
            @SuppressWarnings("unchecked")
            List<Object> points = (List<Object>) restored.view("i2", 24 * 3600_000L).get("points");
            assertTrue(points.isEmpty(), "过期点不得出现在查询结果");
        }
    }

    @Test
    void evictClearsMemoryAndDocument() {
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        try (MetricsHistoryService metrics = new MetricsHistoryService(documents)) {
            metrics.onStats("node-1", stats("i1", "running", 10, 100));
            metrics.evict("i1");
            @SuppressWarnings("unchecked")
            List<Object> points = (List<Object>) metrics.view("i1", 60_000).get("points");
            assertTrue(points.isEmpty());
            assertTrue(documents.findById("mcpanel_metrics", "i1").isEmpty());
        }
    }

    @Test
    void restartMergesWithPersistedHistoryInsteadOfOverwriting() {
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        // 磁盘上已有历史（10 分钟前的点，模拟面板重启前采样过）。
        long persistedAt = System.currentTimeMillis() - 10 * 60_000L;
        documents.save("mcpanel_metrics", "i1", Map.of(
                "points", List.of(List.of(persistedAt, 10.0, 100.0)), "updatedAt", persistedAt));

        try (MetricsHistoryService restored = new MetricsHistoryService(documents)) {
            restored.onStats("node-1", stats("i1", "running", 55, 512));
            List<double[]> points = restored.points("i1", 24 * 3600_000L);
            assertEquals(2, points.size(), "采样前先回读磁盘历史，重启不得覆盖");
            assertEquals(10.0, points.get(0)[1]);
            assertEquals(55.0, points.get(1)[1]);
        }
        try (MetricsHistoryService third = new MetricsHistoryService(documents)) {
            List<double[]> points = third.points("i1", 24 * 3600_000L);
            assertEquals(2, points.size(), "刷盘后新旧两点都在");
            assertEquals(10.0, points.get(0)[1]);
        }
    }

    @Test
    void nodeDimensionSamplesAreThrottledAndSurviveRestart() {        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        try (MetricsHistoryService metrics = new MetricsHistoryService(documents)) {
            metrics.onNodeStats("node-1", Map.of("cpuPercent", 12.5, "memUsedMb", 1024, "memTotalMb", 8192));
            metrics.onNodeStats("node-1", Map.of("cpuPercent", 90, "memUsedMb", 2048, "memTotalMb", 8192));
            metrics.onNodeStats("node-2", Map.of("containers", List.of()));
            assertEquals(1, metrics.nodePoints("node-1", 60_000).size(), "30s 内只采一点");
            assertTrue(metrics.nodePoints("node-2", 60_000).isEmpty(), "未上报 cpu/mem 的节点不采样");
        }
        try (MetricsHistoryService restored = new MetricsHistoryService(documents)) {
            List<double[]> points = restored.nodePoints("node-1", 24 * 3600_000L);
            assertEquals(1, points.size());
            assertEquals(12.5, points.get(0)[1]);
            assertEquals(1024.0, points.get(0)[2]);
            assertEquals(8192.0, points.get(0)[3]);
            assertTrue(documents.findById("mcpanel_node_metrics", "node-1").isPresent());
        }
    }

    @Test
    void evictNodeClearsMemoryAndDocument() {
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        try (MetricsHistoryService metrics = new MetricsHistoryService(documents)) {
            metrics.onNodeStats("node-1", Map.of("cpuPercent", 5, "memUsedMb", 512, "memTotalMb", 4096));
            metrics.evictNode("node-1");
            assertTrue(metrics.nodePoints("node-1", 60_000).isEmpty());
            assertTrue(documents.findById("mcpanel_node_metrics", "node-1").isEmpty());
        }
    }
}
