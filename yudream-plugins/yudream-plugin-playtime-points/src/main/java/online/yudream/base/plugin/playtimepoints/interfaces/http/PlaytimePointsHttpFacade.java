package online.yudream.base.plugin.playtimepoints.interfaces.http;

import online.yudream.base.plugin.playtimepoints.application.service.PlaytimePointsAppService;
import online.yudream.base.plugin.playtimepoints.application.assembler.PointsSettlementAssembler;
import online.yudream.base.plugin.playtimepoints.interfaces.assembler.PlaytimePointsSettingsAssembler;
import online.yudream.base.plugin.playtimepoints.interfaces.request.SaveSettingsRequest;
import online.yudream.base.plugin.playtimepoints.interfaces.support.JsonSupport;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.util.List;
import java.util.Map;

/** HTTP 边界：只做参数解析与响应包装，业务在应用服务。 */
public class PlaytimePointsHttpFacade {

    private final PlaytimePointsAppService appService;

    public PlaytimePointsHttpFacade(PlaytimePointsAppService appService) {
        this.appService = appService;
    }

    /* ---------- 管理端 ---------- */

    public PluginHttpResponse adminOptions(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.options());
    }

    public PluginHttpResponse adminSettings(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.settings());
    }

    public PluginHttpResponse saveSettings(PluginHttpRequest request) {
        SaveSettingsRequest body = JsonSupport.read(request.body(), SaveSettingsRequest.class);
        return PluginHttpResponse.ok(appService.saveSettings(PlaytimePointsSettingsAssembler.toSettings(body)));
    }

    public PluginHttpResponse adminSettlements(PluginHttpRequest request) {
        var page = appService.adminSettlements(query(request, "serverId"), query(request, "keyword"),
                page(request), size(request));
        return PluginHttpResponse.ok(Map.of("records", page.records(), "total", page.total()));
    }

    public PluginHttpResponse adminRun(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.scan());
    }

    public PluginHttpResponse adminLastScan(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.lastScan());
    }

    /* ---------- 用户端 ---------- */

    public PluginHttpResponse mySummary(PluginHttpRequest request) {
        return PluginHttpResponse.ok(appService.mySummary(requireUserId(request)));
    }

    public PluginHttpResponse mySettlements(PluginHttpRequest request) {
        var page = appService.mySettlements(requireUserId(request), page(request), size(request));
        return PluginHttpResponse.ok(Map.of("records", page.records(), "total", page.total()));
    }

    /* ---------- 解析辅助 ---------- */

    private static String requireUserId(PluginHttpRequest request) {
        if (request.principal() == null || request.principal().userId() == null) {
            throw new IllegalArgumentException("未登录或身份缺失");
        }
        return String.valueOf(request.principal().userId());
    }

    private static String query(PluginHttpRequest request, String name) {
        List<String> values = request.query().get(name);
        return values == null || values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()
                ? null
                : values.getFirst().trim();
    }

    private static int page(PluginHttpRequest request) {
        String value = query(request, "page");
        try {
            return value == null ? 1 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static int size(PluginHttpRequest request) {
        String value = query(request, "size");
        try {
            return value == null ? 10 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 10;
        }
    }
}
