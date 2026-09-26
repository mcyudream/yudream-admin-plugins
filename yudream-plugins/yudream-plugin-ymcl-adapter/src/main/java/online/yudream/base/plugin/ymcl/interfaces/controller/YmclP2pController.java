package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.ymcl.application.service.YmclP2pLink;
import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * YAP：启动器 P2P 直连端点（**玩家侧无感**）。
 *
 * <p>启动器在玩家点「连接」时静默走这三个端点：开会话拿票据与节点候选 → 由随启动器的
 * sidecar（{@code mcpanel-p2p}）与节点握手转发 → 轮询状态、退出时关闭。
 * 玩家不需要看到任何 P2P 入口，也不需要理解「打洞/中继」——MC 客户端连的始终是本地端口。
 *
 * <p>mcpanel 未安装 / 未启用 / 未开 P2P：端点统一降级 501（capabilities 也不宣告 p2p），
 * 启动器自动回退为普通公网连接。业务失败（实例未开 P2P、白名单外、实例未运行等）
 * 按 provider 的机器码原样透出，供启动器给出可读提示。
 */
public class YmclP2pController {

    private final YmclP2pLink p2p;

    public YmclP2pController(YmclP2pLink p2p) {
        this.p2p = p2p;
    }

    // ------------------------------------------------------------------
    // GET /v1/p2p/capabilities — 能力探测（启动器据此决定是否展示 P2P 连接选项）
    // ------------------------------------------------------------------
    @PluginHttpEndpoint(method = "GET", path = "/v1/p2p/capabilities",
            permission = YmclAdapterPlugin.VIEW_PERMISSION, wrapResult = false)
    public PluginHttpResponse capabilities(PluginHttpRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Optional<String> reason = p2p.unavailableReason();
        payload.put("available", reason.isEmpty());
        payload.put("reason", reason.orElse(""));
        payload.put("sidecar", "mcpanel-p2p");
        return PluginHttpResponse.rawJson(200, payload);
    }

    // ------------------------------------------------------------------
    // POST /v1/p2p/session — 开会话（玩家点「连接」时调用，无感）
    // ------------------------------------------------------------------
    @PluginHttpEndpoint(method = "POST", path = "/v1/p2p/session",
            permission = YmclAdapterPlugin.VIEW_PERMISSION, wrapResult = false)
    public PluginHttpResponse open(PluginHttpRequest request) {
        Long userId = principalUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        String instanceId = text(readBody(request.body()).get("instance_id"));
        if (instanceId == null || instanceId.isBlank()) {
            return badRequest(400, "p2p.bad-request", "缺少 instance_id");
        }
        try {
            return PluginHttpResponse.rawJson(200, p2p.open(userId, instanceId));
        }
        catch (YmclP2pLink.Unavailable error) {
            return upstreamError(error);
        }
    }

    // ------------------------------------------------------------------
    // GET /v1/p2p/session?session_id= — 状态轮询（terminal=true 时启动器收隧道）
    // ------------------------------------------------------------------
    @PluginHttpEndpoint(method = "GET", path = "/v1/p2p/session",
            permission = YmclAdapterPlugin.VIEW_PERMISSION, wrapResult = false)
    public PluginHttpResponse status(PluginHttpRequest request) {
        Long userId = principalUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        String sessionId = query(request, "session_id");
        if (sessionId == null || sessionId.isBlank()) {
            return badRequest(400, "p2p.bad-request", "缺少 session_id");
        }
        try {
            return p2p.session(userId, sessionId)
                    .map(payload -> PluginHttpResponse.rawJson(200, payload))
                    .orElseGet(() -> badRequest(404, "p2p.not-found", "P2P 会话不存在或已结束"));
        }
        catch (YmclP2pLink.Unavailable error) {
            return upstreamError(error);
        }
    }

    // ------------------------------------------------------------------
    // POST /v1/p2p/session/signal — 中继本端候选（打洞路径；TCP 直连可省略）
    // ------------------------------------------------------------------
    @PluginHttpEndpoint(method = "POST", path = "/v1/p2p/session/signal",
            permission = YmclAdapterPlugin.VIEW_PERMISSION, wrapResult = false)
    public PluginHttpResponse signal(PluginHttpRequest request) {
        Long userId = principalUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        Map<String, Object> body = readBody(request.body());
        String sessionId = text(body.get("session_id"));
        if (sessionId == null || sessionId.isBlank()) {
            return badRequest(400, "p2p.bad-request", "缺少 session_id");
        }
        List<Map<String, Object>> candidates = new java.util.ArrayList<>();
        if (body.get("candidates") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    Map<String, Object> candidate = new LinkedHashMap<>();
                    map.forEach((key, value) -> candidate.put(String.valueOf(key), value));
                    candidates.add(candidate);
                }
            }
        }
        try {
            return PluginHttpResponse.rawJson(200, p2p.signal(userId, sessionId, candidates));
        }
        catch (YmclP2pLink.Unavailable error) {
            return upstreamError(error);
        }
    }

    // ------------------------------------------------------------------
    // DELETE /v1/p2p/session?session_id=&reason= — 关闭（退出/切服时自动调用）
    // ------------------------------------------------------------------
    @PluginHttpEndpoint(method = "DELETE", path = "/v1/p2p/session",
            permission = YmclAdapterPlugin.VIEW_PERMISSION, wrapResult = false)
    public PluginHttpResponse close(PluginHttpRequest request) {
        Long userId = principalUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        String sessionId = query(request, "session_id");
        if (sessionId == null || sessionId.isBlank()) {
            return badRequest(400, "p2p.bad-request", "缺少 session_id");
        }
        try {
            p2p.close(userId, sessionId, orDefault(query(request, "reason"), "启动器退出"));
            return PluginHttpResponse.rawJson(200, Map.of("closed", true));
        }
        catch (YmclP2pLink.Unavailable error) {
            return upstreamError(error);
        }
    }

    /** provider 不可用 → 501；provider 业务失败 → 409（机器码原样透出，启动器展示 message）。 */
    private static PluginHttpResponse upstreamError(YmclP2pLink.Unavailable error) {
        int status = "p2p.upstream-unavailable".equals(error.code()) ? 501 : 409;
        return badRequest(status, error.code(), error.getMessage());
    }

    private static String query(PluginHttpRequest request, String name) {
        Map<String, List<String>> params = request.query();
        List<String> values = params == null ? null : params.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static Map<String, Object> readBody(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(body, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        }
        catch (Exception error) {
            return Map.of();
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Long principalUserId(PluginHttpRequest request) {
        var principal = request.principal();
        return principal == null ? null : principal.userId();
    }

    private static PluginHttpResponse unauthorized() {
        return badRequest(401, "unauthenticated", "Sign in to this domain to use P2P");
    }

    private static PluginHttpResponse badRequest(int status, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", code);
        payload.put("message", message);
        return PluginHttpResponse.rawJson(status, payload);
    }
}
