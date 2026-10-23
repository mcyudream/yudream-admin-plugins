package online.yudream.base.plugin.ymcl.interfaces.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.ymcl.application.service.YmclDownloadSourceService;
import online.yudream.base.plugin.ymcl.application.service.YmclDownloadStatsService;
import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YAP 下载分发扩展：宿主管理面（信封响应，与其它 /v1/admin/** 一致，
 * PUBLISH 权限）。
 *
 * <ul>
 * <li>{@code GET/PUT /v1/admin/download/config}：分发配置读取与保存</li>
 * <li>{@code GET /v1/admin/download/gateway}：网关对接信息（回调端点与
 *     共享密钥——管理员据此在镜像网关侧完成适配）</li>
 * <li>{@code POST /v1/admin/download/gateway-secret/rotate}：轮换网关密钥
 *     （旧密钥与全部已签发下载令牌立即失效）</li>
 * <li>{@code GET /v1/admin/download/stats/summary|events|players}：下载统计</li>
 * </ul>
 */
public class YmclDownloadAdminController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> BODY_TYPE =
            new TypeReference<>() {
            };

    private final YmclDownloadSourceService sources;
    private final YmclDownloadStatsService stats;

    public YmclDownloadAdminController(YmclDownloadSourceService sources, YmclDownloadStatsService stats) {
        this.sources = sources;
        this.stats = stats;
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/admin/download/config",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse getConfig(PluginHttpRequest request) {
        return PluginHttpResponse.json(200, sources.protocolView());
    }

    /** 保存分发配置；校验失败返回 400 信封（data.error 为原因）。 */
    @PluginHttpEndpoint(method = "PUT", path = "/v1/admin/download/config",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse putConfig(PluginHttpRequest request) {
        Map<String, Object> body;
        try {
            body = MAPPER.readValue(request.body(), BODY_TYPE);
        } catch (Exception error) {
            return PluginHttpResponse.json(400, YmclSessionController.error(
                    "invalid_body", "Request body must be a JSON config object"));
        }
        String violation = sources.save(body);
        if (violation != null) {
            return PluginHttpResponse.json(400, YmclSessionController.error("invalid_config", violation));
        }
        return PluginHttpResponse.json(200, sources.protocolView());
    }

    /** 网关对接信息：verify/report 端点与共享密钥（管理员配置网关用）。 */
    @PluginHttpEndpoint(method = "GET", path = "/v1/admin/download/gateway",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse gatewayInfo(PluginHttpRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("verifyEndpoint", "/api/plugins/ymcl-adapter/v1/download/token/verify");
        payload.put("reportEndpoint", "/api/plugins/ymcl-adapter/v1/download/stats/report");
        payload.put("secretHeader", YmclDownloadController.GATEWAY_SECRET_HEADER);
        payload.put("gatewaySecret", sources.gatewaySecret());
        payload.put("config", sources.protocolView());
        return PluginHttpResponse.json(200, payload);
    }

    /** 轮换网关密钥；新密钥只在此响应中完整返回一次。 */
    @PluginHttpEndpoint(method = "POST", path = "/v1/admin/download/gateway-secret/rotate",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse rotateSecret(PluginHttpRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("gatewaySecret", sources.rotateSecrets());
        return PluginHttpResponse.json(200, payload);
    }

    /** 下载统计摘要：?days=14（1–60）。 */
    @PluginHttpEndpoint(method = "GET", path = "/v1/admin/download/stats/summary",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse statsSummary(PluginHttpRequest request) {
        return PluginHttpResponse.json(200, stats.summary(intParam(request, "days", 14)));
    }

    /** 最近下载动态：?limit=20（1–100）。 */
    @PluginHttpEndpoint(method = "GET", path = "/v1/admin/download/stats/events",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse statsEvents(PluginHttpRequest request) {
        return PluginHttpResponse.json(200, stats.recentEvents(intParam(request, "limit", 20)));
    }

    /** 玩家下载档案 Top：?limit=20（1–100）。 */
    @PluginHttpEndpoint(method = "GET", path = "/v1/admin/download/stats/players",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse statsPlayers(PluginHttpRequest request) {
        return PluginHttpResponse.json(200, stats.topPlayers(intParam(request, "limit", 20)));
    }

    private static int intParam(PluginHttpRequest request, String name, int fallback) {
        List<String> values = request.query() == null ? null : request.query().get(name);
        if (values == null || values.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(values.get(0));
        } catch (NumberFormatException error) {
            return fallback;
        }
    }
}
