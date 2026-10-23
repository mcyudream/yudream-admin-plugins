package online.yudream.base.plugin.ymcl.interfaces.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.ymcl.application.service.YmclDownloadSourceService;
import online.yudream.base.plugin.ymcl.application.service.YmclDownloadStatsService;
import online.yudream.base.plugin.ymcl.application.service.YmclDownloadTokenService;
import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.util.List;
import java.util.Map;

/**
 * YAP 下载分发扩展：玩家/网关协议面（裸 JSON，与 YAP face 一致）。
 *
 * <ul>
 * <li>{@code GET /v1/download/config}：分发配置（匿名可达；未配置时
 *     {@code managed:false}，启动器据此回退本地设置）</li>
 * <li>{@code POST /v1/download/token}：为当前域会话玩家签发短效下载令牌
 *     （VIEW 权限，身份取自 principal）</li>
 * <li>{@code POST /v1/download/token/verify}：镜像网关回调校验下载令牌，
 *     须携带 {@code X-YDA-Gateway-Secret}；返回玩家身份</li>
 * <li>{@code POST /v1/download/stats/report}：镜像网关批量回传下载事件，
 *     同样须携带网关共享密钥；玩家归因只取令牌校验结果</li>
 * </ul>
 */
public class YmclDownloadController {

    /** 镜像网关回调鉴权头（契约见 docs/ymcl-download-gateway-v1.md）。 */
    public static final String GATEWAY_SECRET_HEADER = "X-YDA-Gateway-Secret";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> BODY_TYPE =
            new TypeReference<>() {
            };

    private final YmclDownloadSourceService sources;
    private final YmclDownloadTokenService tokens;
    private final YmclDownloadStatsService stats;

    public YmclDownloadController(
            YmclDownloadSourceService sources,
            YmclDownloadTokenService tokens,
            YmclDownloadStatsService stats
    ) {
        this.sources = sources;
        this.tokens = tokens;
        this.stats = stats;
    }

    /** 分发配置（snake_case，与 YAP face 一致）；匿名可达，无配置时降级。 */
    @PluginHttpEndpoint(method = "GET", path = "/v1/download/config", wrapResult = false)
    public PluginHttpResponse config(PluginHttpRequest request) {
        return PluginHttpResponse.rawJson(200, sources.protocolView());
    }

    /** 为当前域会话玩家签发下载令牌。 */
    @PluginHttpEndpoint(method = "POST", path = "/v1/download/token",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse issueToken(PluginHttpRequest request) {
        var principal = request.principal();
        if (principal == null || principal.userId() == null) {
            return PluginHttpResponse.rawJson(401, YmclSessionController.error(
                    "unauthenticated", "A domain session is required to obtain a download token"));
        }
        return PluginHttpResponse.rawJson(200, tokens.issue(principal.userId()));
    }

    /** 镜像网关校验下载令牌（body {@code {"token": "..."}}）。 */
    @PluginHttpEndpoint(method = "POST", path = "/v1/download/token/verify", wrapResult = false)
    public PluginHttpResponse verifyToken(PluginHttpRequest request) {
        PluginHttpResponse gatewayError = requireGatewaySecret(request);
        if (gatewayError != null) {
            return gatewayError;
        }
        String token = bodyField(request, "token");
        if (token == null) {
            return PluginHttpResponse.rawJson(400, YmclSessionController.error(
                    "invalid_body", "Request body must be JSON with a token field"));
        }
        return PluginHttpResponse.rawJson(200, tokens.verify(token));
    }

    /** 镜像网关批量回传下载事件（body {@code {"events": [...]}}）。 */
    @PluginHttpEndpoint(method = "POST", path = "/v1/download/stats/report", wrapResult = false)
    public PluginHttpResponse reportStats(PluginHttpRequest request) {
        PluginHttpResponse gatewayError = requireGatewaySecret(request);
        if (gatewayError != null) {
            return gatewayError;
        }
        Map<String, Object> body;
        try {
            body = MAPPER.readValue(request.body(), BODY_TYPE);
        } catch (Exception error) {
            return PluginHttpResponse.rawJson(400, YmclSessionController.error(
                    "invalid_body", "Request body must be a JSON report"));
        }
        if (!(body.get("events") instanceof List<?> events) || events.isEmpty()) {
            return PluginHttpResponse.rawJson(400, YmclSessionController.error(
                    "invalid_body", "events must be a non-empty array"));
        }
        // 玩家归因只信任令牌：网关必须回传它从 verify 拿到的 token，适配器
        // 重新校验出 userId，绝不采信网关自报的 playerId 字段。
        String token = bodyField(request, "token");
        Long playerId = tokens.userIdOf(token);
        if (playerId == null) {
            return PluginHttpResponse.rawJson(401, YmclSessionController.error(
                    "unknown_player", "Report token did not verify; attribute events to a verified player"));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> payload = (List<Map<String, Object>>) (List<?>) events;
        int recorded = stats.recordBatch(playerId, payload);
        return PluginHttpResponse.rawJson(200, Map.of("recorded", recorded));
    }

    /** 网关共享密钥校验；通过返回 null，否则返回待发送的错误响应。 */
    private PluginHttpResponse requireGatewaySecret(PluginHttpRequest request) {
        String candidate = firstHeader(request, GATEWAY_SECRET_HEADER);
        if (candidate == null) {
            candidate = queryParam(request, "gatewaySecret");
        }
        if (candidate == null) {
            return PluginHttpResponse.rawJson(401, YmclSessionController.error(
                    "gateway_secret_required",
                    "Provide the gateway secret via " + GATEWAY_SECRET_HEADER));
        }
        if (!sources.gatewaySecretMatches(candidate)) {
            return PluginHttpResponse.rawJson(403, YmclSessionController.error(
                    "gateway_secret_mismatch", "Gateway secret does not match"));
        }
        return null;
    }

    private static String bodyField(PluginHttpRequest request, String field) {
        try {
            Map<String, Object> body = MAPPER.readValue(request.body(), BODY_TYPE);
            Object value = body.get(field);
            return value == null ? null : String.valueOf(value).trim();
        } catch (Exception error) {
            return null;
        }
    }

    static String firstHeader(PluginHttpRequest request, String name) {
        Map<String, List<String>> headers = request.headers();
        if (headers == null) {
            return null;
        }
        List<String> values = headers.get(name);
        if (values != null && !values.isEmpty()) {
            return values.get(0);
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                    && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private static String queryParam(PluginHttpRequest request, String name) {
        List<String> values = request.query() == null ? null : request.query().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
