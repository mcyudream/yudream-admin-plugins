package online.yudream.base.plugin.minecraft.interfaces.controller;

import online.yudream.base.plugin.minecraft.bootstrap.MinecraftServerPlugin;
import online.yudream.base.plugin.minecraft.interfaces.http.MinecraftServerHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class MinecraftServerUserController {
    private final MinecraftServerHttpFacade http;

    public MinecraftServerUserController(MinecraftServerHttpFacade http) { this.http = http; }

    @PluginHttpEndpoint(method = "GET", path = "/servers", permission = MinecraftServerPlugin.VIEW_PERMISSION)
    public PluginHttpResponse list(PluginHttpRequest request) { return http.userList(request); }

    @PluginHttpEndpoint(method = "GET", path = "/servers/{serverId}", permission = MinecraftServerPlugin.VIEW_PERMISSION)
    public PluginHttpResponse detail(PluginHttpRequest request) { return http.userDetail(request); }

    @PluginHttpEndpoint(method = "GET", path = "/servers/{serverId}/status/history", permission = MinecraftServerPlugin.VIEW_PERMISSION)
    public PluginHttpResponse statusHistory(PluginHttpRequest request) { return http.statusHistory(request); }

    @PluginHttpEndpoint(method = "GET", path = "/economy/status", permission = MinecraftServerPlugin.VIEW_PERMISSION)
    public PluginHttpResponse economyStatus() { return http.economyStatus(); }

    @PluginHttpEndpoint(method = "GET", path = "/me/servers/{serverId}/records", permission = MinecraftServerPlugin.VIEW_PERMISSION)
    public PluginHttpResponse myRecords(PluginHttpRequest request) { return http.myRecords(request); }
}
