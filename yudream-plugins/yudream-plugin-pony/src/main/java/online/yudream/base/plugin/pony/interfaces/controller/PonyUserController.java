package online.yudream.base.plugin.pony.interfaces.controller;

import online.yudream.base.plugin.pony.bootstrap.PonyPlugin;
import online.yudream.base.plugin.pony.interfaces.http.PonyHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class PonyUserController {

    private final PonyHttpFacade http;

    public PonyUserController(PonyHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/stats", permission = PonyPlugin.USE_PERMISSION)
    public PluginHttpResponse myStats(PluginHttpRequest request) {
        return http.myStats(request);
    }
}
