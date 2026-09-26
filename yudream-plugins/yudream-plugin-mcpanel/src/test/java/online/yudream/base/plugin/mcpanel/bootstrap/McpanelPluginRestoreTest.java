package online.yudream.base.plugin.mcpanel.bootstrap;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.application.port.NodeControlPlane;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.spi.http.PluginSseStream;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * >200 节点跨页恢复回归：restore 批次必须与 repo 派生上限（100）一致，
 * 否则 "&lt; 批次" 终止条件在第 1 页即退出，>100 节点重启后只有前 100 恢复拨号。
 */
class McpanelPluginRestoreTest {

    @Test
    void restoreTraversesAllPagesAndSyncsEveryEnabledNode() {
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        McpanelNodeRepository repository = new DocumentNodeRepository(documents, McpanelJson.mapper());
        RecordingControlPlaneStub controlPlane = new RecordingControlPlaneStub();
        int enabledNodes = 0;
        for (int i = 0; i < 250; i++) {
            boolean enabled = i % 5 != 0; // 250 个中 200 个启用、50 个停用
            McpanelNode node = McpanelNode.create("n" + i, "node-" + i,
                    "wss://node" + i + ".example.com:9701", "pkix", null, false, null, enabled, i);
            repository.save(node);
            if (enabled) {
                enabledNodes++;
            }
        }
        int restored = McpanelPlugin.restoreEnabledNodes(repository, controlPlane);
        assertEquals(enabledNodes, restored, "跨页恢复必须覆盖全部启用节点（>200 节点）");
        assertEquals(enabledNodes, controlPlane.synced.size(), "syncNode 调用次数 = 启用节点数");
        assertTrue(controlPlane.synced.contains("n1"));
        assertTrue(controlPlane.synced.contains("n249"));
    }

    /** 轻量控制面桩（仅记录 syncNode；不复用 TestStubs 避免跨包耦合变更）。 */
    private static final class RecordingControlPlaneStub implements NodeControlPlane {

        private final List<String> synced = new CopyOnWriteArrayList<>();

        @Override
        public void syncNode(McpanelNode node) {
            synced.add(node.id());
        }

        @Override
        public void removeNode(String nodeId) {
            // no-op
        }

        @Override
        public NodeRuntime runtime(String nodeId) {
            return NodeRuntime.empty();
        }

        @Override
        public PluginSseStream openEventStream(String nodeId, Long afterEventId) {
            return null;
        }

        @Override
        public java.util.concurrent.CompletableFuture<Map<String, Object>> call(String nodeId, String method,
                                                                               Map<String, Object> payload) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new UnsupportedOperationException("测试桩不支持节点调用"));
        }

        @Override
        public List<String> caps(String nodeId) {
            return List.of();
        }
    }
}
