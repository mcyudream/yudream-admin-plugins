package online.yudream.base.plugin.ymcl.interfaces.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.ymcl.application.service.YmclStatsService;
import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.util.List;
import java.util.Map;

/**
 * YAP 扩展端点：域启动统计。
 *
 * <ul>
 * <li>{@code POST /v1/stats/launch}：启动器上报（VIEW 权限，需域会话——
 *     归因用户取自 principal，启动器不携带身份；响应裸 JSON，与 YAP 面一致）
 * <li>{@code GET /v1/admin/stats/summary|launches}：宿主管理端查询
 *     （PUBLISH 权限，信封响应，与其它 /v1/admin/** 一致）
 * </ul>
 */
public class YmclStatsController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final YmclStatsService stats;

    public YmclStatsController(YmclStatsService stats) {
        this.stats = stats;
    }

    /**
     * 启动器在域整合包实例启动成功后上报一次。body（camelCase，与 YAP 数据
     * 字段一致）：packId、packVersion、serverId、seasonId、quickPlay
     * （server/singleplayer/null）、launcherVersion、platform、arch。
     */
    @PluginHttpEndpoint(method = "POST", path = "/v1/stats/launch",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse reportLaunch(PluginHttpRequest request) {
        var principal = request.principal();
        if (principal == null || principal.userId() == null) {
            return PluginHttpResponse.rawJson(401, YmclSessionController.error(
                    "unauthenticated", "launch reporting requires a domain session"));
        }
        Map<String, Object> body;
        try {
            body = MAPPER.readValue(request.body(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception error) {
            return PluginHttpResponse.rawJson(400, YmclSessionController.error(
                    "invalid_body", "Request body must be a JSON launch report"));
        }
        stats.record(principal.userId(), body);
        return PluginHttpResponse.rawJson(200, Map.of("recorded", true));
    }

    /** 管理端摘要：?days=14（1–60）。 */
    @PluginHttpEndpoint(method = "GET", path = "/v1/admin/stats/summary",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse summary(PluginHttpRequest request) {
        return PluginHttpResponse.json(200, stats.summary(intParam(request, "days", 14)));
    }

    /** 管理端最近启动动态：?limit=20（1–100）。 */
    @PluginHttpEndpoint(method = "GET", path = "/v1/admin/stats/launches",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse launches(PluginHttpRequest request) {
        return PluginHttpResponse.json(200, stats.recentLaunches(intParam(request, "limit", 20)));
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
