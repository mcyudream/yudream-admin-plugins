package online.yudream.base.plugin.mcguess.interfaces.controller;

import online.yudream.base.plugin.mcguess.bootstrap.McguessPlugin;
import online.yudream.base.plugin.mcguess.interfaces.http.McguessHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class McguessAdminController {

    private final McguessHttpFacade http;

    public McguessAdminController(McguessHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/overview", permission = McguessPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse overview(PluginHttpRequest request) {
        return http.overview();
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/games", permission = McguessPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse games(PluginHttpRequest request) {
        return http.games(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/players", permission = McguessPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse players(PluginHttpRequest request) {
        return http.players(request);
    }
}
