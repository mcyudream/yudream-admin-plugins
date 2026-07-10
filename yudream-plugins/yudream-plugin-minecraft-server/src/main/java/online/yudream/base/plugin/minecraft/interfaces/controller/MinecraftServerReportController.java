package online.yudream.base.plugin.minecraft.interfaces.controller;

import online.yudream.base.plugin.minecraft.bootstrap.MinecraftServerPlugin;
import online.yudream.base.plugin.minecraft.interfaces.http.MinecraftServerHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class MinecraftServerReportController {
    private final MinecraftServerHttpFacade http;

    public MinecraftServerReportController(MinecraftServerHttpFacade http) { this.http = http; }

    @PluginHttpEndpoint(method = "POST", path = "/report/servers/{serverId}/players/join", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse playerJoin(PluginHttpRequest request) { return http.playerJoin(request); }

    @PluginHttpEndpoint(method = "POST", path = "/report/servers/{serverId}/players/quit", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse playerQuit(PluginHttpRequest request) { return http.playerQuit(request); }

    @PluginHttpEndpoint(method = "POST", path = "/report/servers/{serverId}/players/afk/start", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse playerAfkStart(PluginHttpRequest request) { return http.playerAfkStart(request); }

    @PluginHttpEndpoint(method = "POST", path = "/report/servers/{serverId}/players/afk/end", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse playerAfkEnd(PluginHttpRequest request) { return http.playerAfkEnd(request); }
}
