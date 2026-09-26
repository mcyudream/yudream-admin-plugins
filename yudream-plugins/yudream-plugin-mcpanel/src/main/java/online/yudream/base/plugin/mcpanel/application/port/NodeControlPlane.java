package online.yudream.base.plugin.mcpanel.application.port;

import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.valobj.NodeStatsSnapshot;
import online.yudream.base.plugin.spi.http.PluginSseStream;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 控制信道端口：application 层对节点连接管理器的唯一视图。
 */
public interface NodeControlPlane {

    void syncNode(McpanelNode node);

    void removeNode(String nodeId);

    NodeRuntime runtime(String nodeId);

    /** 打开某节点的 SSE 事件流（connected/heartbeat/node.state/node.stats，支持 Last-Event-ID）。 */
    PluginSseStream openEventStream(String nodeId, Long afterEventId);

    /** 面板→节点通用调用（caps 门禁 + 30s 超时）；失败以 CompletionException 携带 NodeCallException。 */
    CompletableFuture<Map<String, Object>> call(String nodeId, String method, Map<String, Object> payload);

    /** 同上，但可指定更长超时（镜像拉取等长操作，如 instance.create 内联拉取）。 */
    default CompletableFuture<Map<String, Object>> call(String nodeId, String method, Map<String, Object> payload,
                                                        long timeoutMs) {
        return call(nodeId, method, payload);
    }

    /** 节点当前声明的 caps（离线为空表）。 */
    List<String> caps(String nodeId);

    record NodeRuntime(boolean connected, boolean online, long lastFrameAtMs, NodeStatsSnapshot stats) {

        public static NodeRuntime empty() {
            return new NodeRuntime(false, false, 0L, null);
        }
    }
}
