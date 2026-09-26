package online.yudream.base.plugin.playtimepoints.interfaces.controller;

import online.yudream.base.plugin.playtimepoints.bootstrap.PlaytimePointsPlugin;
import online.yudream.base.plugin.playtimepoints.interfaces.http.PlaytimePointsHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/** 用户端：只读自己的积分汇总与结算流水，归属一律取自登录主体。 */
public class PlaytimePointsUserController {

    private final PlaytimePointsHttpFacade http;

    public PlaytimePointsUserController(PlaytimePointsHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/summary", permission = PlaytimePointsPlugin.VIEW_PERMISSION)
    public PluginHttpResponse summary(PluginHttpRequest request) {
        return http.mySummary(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/settlements", permission = PlaytimePointsPlugin.VIEW_PERMISSION)
    public PluginHttpResponse settlements(PluginHttpRequest request) {
        return http.mySettlements(request);
    }
}
