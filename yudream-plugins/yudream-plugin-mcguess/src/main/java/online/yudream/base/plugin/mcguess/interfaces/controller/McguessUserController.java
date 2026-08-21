package online.yudream.base.plugin.mcguess.interfaces.controller;

import online.yudream.base.plugin.mcguess.bootstrap.McguessPlugin;
import online.yudream.base.plugin.mcguess.interfaces.http.McguessHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class McguessUserController {

    private final McguessHttpFacade http;

    public McguessUserController(McguessHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/stats", permission = McguessPlugin.USE_PERMISSION)
    public PluginHttpResponse myStats(PluginHttpRequest request) {
        return http.myStats(request);
    }
}
