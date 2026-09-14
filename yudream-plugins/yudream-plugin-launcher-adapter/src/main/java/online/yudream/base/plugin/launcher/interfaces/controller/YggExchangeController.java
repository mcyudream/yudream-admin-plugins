package online.yudream.base.plugin.launcher.interfaces.controller;

import online.yudream.base.plugin.launcher.interfaces.http.LauncherHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class YggExchangeController {

    private final LauncherHttpFacade http;

    public YggExchangeController(LauncherHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "POST", path = "/v1/auth/ygg", wrapResult = false)
    public PluginHttpResponse exchange(PluginHttpRequest request) {
        return http.exchangeYgg(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/auth/ygg/profiles", wrapResult = false)
    public PluginHttpResponse profiles(PluginHttpRequest request) {
        return http.listYggProfiles(request);
    }
}
