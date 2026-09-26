package online.yudream.base.plugin.mcpanel.support;

import online.yudream.base.plugin.mcpanel.application.port.NodeControlPlane;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.valobj.NodeStatsSnapshot;
import online.yudream.base.plugin.spi.http.PluginSseStream;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;
import online.yudream.base.plugin.spi.system.security.PluginSecurityService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 测试桩（无 JUnit 依赖，验收 fixture 亦可复用）。
 */
public final class TestStubs {

    private TestStubs() {
    }

    public static PluginSecurityService security() {
        return new PluginSecurityService() {
            @Override
            public boolean hasPermission(PluginPrincipal principal, String permission) {
                return principal != null && principal.permissions() != null
                        && principal.permissions().contains(permission);
            }

            @Override
            public void requirePermission(PluginPrincipal principal, String permission) {
                if (!hasPermission(principal, permission)) {
                    throw new IllegalStateException("denied");
                }
            }
        };
    }

    public static PluginPrincipal principal(Long userId, String... permissions) {
        return new PluginPrincipal(userId, List.of(permissions));
    }

    /** 记录型控制面桩：记录 sync/remove 调用，可注入在线状态。 */
    public static class RecordingControlPlane implements NodeControlPlane {

        public final ConcurrentLinkedQueue<String> synced = new ConcurrentLinkedQueue<>();
        public final ConcurrentLinkedQueue<String> removed = new ConcurrentLinkedQueue<>();
        public volatile boolean online;
        public volatile boolean connected;
        public volatile NodeStatsSnapshot stats;

        @Override
        public void syncNode(McpanelNode node) {
            synced.add(node.id());
        }

        @Override
        public void removeNode(String nodeId) {
            removed.add(nodeId);
        }

        @Override
        public NodeRuntime runtime(String nodeId) {
            return new NodeRuntime(connected, online, online ? System.currentTimeMillis() : 0L, stats);
        }

        @Override
        public PluginSseStream openEventStream(String nodeId, Long afterEventId) {
            return new PluginSseStream() {
                @Override
                public void subscribe(PluginSseStream.Subscriber subscriber) {
                    // 测试桩不支持长连接
                }

                @Override
                public void unsubscribe(PluginSseStream.Subscriber subscriber) {
                    // no-op
                }
            };
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
