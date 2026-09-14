package online.yudream.base.plugin.launcher.interfaces.controller;

import online.yudream.base.plugin.launcher.interfaces.http.LauncherHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class LauncherDataController {

    private final LauncherHttpFacade http;

    public LauncherDataController(LauncherHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/pages/{providerCode}/{dataSourceCode}", wrapResult = false)
    public PluginHttpResponse fetch(PluginHttpRequest request) {
        return http.fetchPageData(request);
    }
}
