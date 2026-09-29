package online.yudream.base.plugin.playtimepoints.interfaces.controller;

import online.yudream.base.plugin.playtimepoints.bootstrap.PlaytimePointsPlugin;
import online.yudream.base.plugin.playtimepoints.interfaces.http.PlaytimePointsHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/** 管理端：结算设置（含打卡积分联动）、服务器权重、结算流水、打卡积分流水与手动结算。 */
public class PlaytimePointsAdminController {

    private final PlaytimePointsHttpFacade http;

    public PlaytimePointsAdminController(PlaytimePointsHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/options", permission = PlaytimePointsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse options(PluginHttpRequest request) {
        return http.adminOptions(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/settings", permission = PlaytimePointsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse settings(PluginHttpRequest request) {
        return http.adminSettings(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/settings", permission = PlaytimePointsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveSettings(PluginHttpRequest request) {
        return http.saveSettings(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/settlements", permission = PlaytimePointsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse settlements(PluginHttpRequest request) {
        return http.adminSettlements(request);
    }

    /** 打卡积分发放流水（跨用户，可按项目/用户筛选）。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/check-in-rewards", permission = PlaytimePointsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse checkInRewards(PluginHttpRequest request) {
        return http.adminCheckInRewards(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/run", permission = PlaytimePointsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse run(PluginHttpRequest request) {
        return http.adminRun(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/last-scan", permission = PlaytimePointsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse lastScan(PluginHttpRequest request) {
        return http.adminLastScan(request);
    }
}
