package online.yudream.base.plugin.mcpanel.interfaces.controller;

import online.yudream.base.plugin.mcpanel.application.service.P2PSessionService;
import online.yudream.base.plugin.mcpanel.bootstrap.McpanelPlugin;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.mcpanel.interfaces.http.HttpGuards;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.security.PluginSecurityService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动器 P2P 信令端点（玩家面 {@code /me/p2p/**} + 管理端治理端点）。
 *
 * <p>玩家面：启动器（或其 sidecar）用 OAuth 登录态开会话 → 拿票据与节点候选 →
 * 与节点直接完成握手/打洞（面板只中继候选，不经手玩家流量）。管理端：查看活跃会话并强制断开。
 *
 * <p>Controller 只做边界解析与委托，业务在 {@link P2PSessionService}。
 */
public class McpanelP2pController {

    private final P2PSessionService p2p;
    private final PluginSecurityService security;

    public McpanelP2pController(P2PSessionService p2p, PluginSecurityService security) {
        this.p2p = p2p;
        this.security = security;
    }

    /** 开启 P2P 会话：校验授权/限额 → 签发票据 → 通知节点开面。 */
    @PluginHttpEndpoint(method = "POST", path = "/me/p2p/session")
    public PluginHttpResponse openSession(PluginHttpRequest request) {
        return HttpGuards.me(request, () -> {
            long userId = requireUserId(request);
            String instanceId = McpanelJson.readMap(request.body()).string("instanceId");
            return PluginHttpResponse.ok(p2p.open(userId, instanceId));
        });
    }

    /** 中继 ICE 候选（打洞双方互见）；返回节点已上报的候选。 */
    @PluginHttpEndpoint(method = "POST", path = "/me/p2p/session/signal")
    public PluginHttpResponse signal(PluginHttpRequest request) {
        return HttpGuards.me(request, () -> {
            long userId = requireUserId(request);
            McpanelJson.MapReader body = McpanelJson.readMap(request.body());
            return PluginHttpResponse.ok(p2p.signal(userId, body.string("sessionId"), candidatesOf(body)));
        });
    }

    /** 会话状态（启动器轮询：waiting/connecting/direct/relayed/failed + 字节数）。 */
    @PluginHttpEndpoint(method = "GET", path = "/me/p2p/session")
    public PluginHttpResponse sessionStatus(PluginHttpRequest request) {
        return HttpGuards.me(request, () -> {
            long userId = requireUserId(request);
            return PluginHttpResponse.ok(p2p.status(userId, query(request, "sessionId")));
        });
    }

    /** 关闭会话（启动器退出/切服）：通知节点回收监听与连接。 */
    @PluginHttpEndpoint(method = "DELETE", path = "/me/p2p/session")
    public PluginHttpResponse closeSession(PluginHttpRequest request) {
        return HttpGuards.me(request, () -> {
            long userId = requireUserId(request);
            return PluginHttpResponse.ok(p2p.close(userId, query(request, "sessionId"), query(request, "reason")));
        });
    }

    // ---------- 管理端治理（§5.9 红线 5：一键断会话并吊销票据） ----------

    /** 活跃 P2P 会话（不含票据明文）。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/p2p/sessions", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse adminSessions(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(Map.of("records", p2p.activeSessions())));
    }

    /** 强制断开指定会话。 */
    @PluginHttpEndpoint(method = "DELETE", path = "/admin/p2p/sessions/{sessionId}", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse adminCloseSession(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION,
                () -> PluginHttpResponse.ok(p2p.forceClose(segment(request.path(), 4), query(request, "reason"))));
    }

    private static List<Map<String, Object>> candidatesOf(McpanelJson.MapReader body) {
        var node = body.node().path("candidates");
        List<Map<String, Object>> candidates = new ArrayList<>();
        if (node.isArray()) {
            for (var item : node) {
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("proto", item.path("proto").asText("udp"));
                candidate.put("host", item.path("host").asText(""));
                candidate.put("port", item.path("port").asInt(0));
                if (item.hasNonNull("priority")) {
                    candidate.put("priority", item.path("priority").asInt(0));
                }
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private static String query(PluginHttpRequest request, String name) {
        Map<String, List<String>> params = request.query();
        List<String> values = params == null ? null : params.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String segment(String path, int index) {
        String[] parts = path == null ? new String[0] : path.split("/");
        return index < parts.length ? parts[index] : null;
    }

    private static long requireUserId(PluginHttpRequest request) {
        Long id = HttpGuards.principalUserId(request);
        if (id == null) {
            throw new IllegalStateException("未认证请求不得进入玩家面");
        }
        return id;
    }
}
