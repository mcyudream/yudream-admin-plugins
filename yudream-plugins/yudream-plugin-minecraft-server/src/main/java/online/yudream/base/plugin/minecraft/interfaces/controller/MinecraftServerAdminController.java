package online.yudream.base.plugin.minecraft.interfaces.controller;

import online.yudream.base.plugin.minecraft.bootstrap.MinecraftServerPlugin;
import online.yudream.base.plugin.minecraft.interfaces.http.MinecraftServerHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class MinecraftServerAdminController {
    private final MinecraftServerHttpFacade http;

    public MinecraftServerAdminController(MinecraftServerHttpFacade http) { this.http = http; }

    @PluginHttpEndpoint(method = "GET", path = "/admin/servers", permission = MinecraftServerPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse list(PluginHttpRequest request) { return http.adminList(request); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/servers/{serverId}", permission = MinecraftServerPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse detail(PluginHttpRequest request) { return http.adminDetail(request); }

    @PluginHttpEndpoint(method = "POST", path = "/admin/servers", permission = MinecraftServerPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse save(PluginHttpRequest request) { return http.save(request); }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/servers/{serverId}", permission = MinecraftServerPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) { return http.delete(request); }

    @PluginHttpEndpoint(method = "POST", path = "/admin/servers/{serverId}/status/refresh", permission = MinecraftServerPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse refreshStatus(PluginHttpRequest request) { return http.refreshStatus(request); }

    @PluginHttpEndpoint(method = "POST", path = "/admin/servers/{serverId}/seasons/preview", permission = MinecraftServerPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse previewOpenSeason(PluginHttpRequest request) { return http.previewOpenSeason(request); }

    @PluginHttpEndpoint(method = "POST", path = "/admin/servers/{serverId}/seasons/open", permission = MinecraftServerPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse openSeason(PluginHttpRequest request) { return http.openSeason(request); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/servers/{serverId}/operations", permission = MinecraftServerPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse operations(PluginHttpRequest request) { return http.operations(request); }

    @PluginHttpEndpoint(method = "POST", path = "/admin/operations/{operationId}/rollback", permission = MinecraftServerPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse rollbackSeason(PluginHttpRequest request) { return http.rollbackSeason(request); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/servers/{serverId}/players", permission = MinecraftServerPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse playerActivities(PluginHttpRequest request) { return http.playerActivities(request); }
}
