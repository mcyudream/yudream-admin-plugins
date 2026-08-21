package online.yudream.base.plugin.pony.interfaces.controller;

import online.yudream.base.plugin.pony.bootstrap.PonyPlugin;
import online.yudream.base.plugin.pony.interfaces.http.PonyHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class PonyAdminController {

    private final PonyHttpFacade http;

    public PonyAdminController(PonyHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/overview", permission = PonyPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse overview(PluginHttpRequest request) {
        return http.overview();
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/games", permission = PonyPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse games(PluginHttpRequest request) {
        return http.games(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/players", permission = PonyPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse players(PluginHttpRequest request) {
        return http.players(request);
    }
}
