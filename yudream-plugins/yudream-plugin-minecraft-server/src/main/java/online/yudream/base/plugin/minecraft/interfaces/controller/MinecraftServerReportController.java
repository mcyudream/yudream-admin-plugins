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

    /**
     * Compatibility endpoint for reporting clients released before the /report
     * namespace was introduced.
     */
    @PluginHttpEndpoint(method = "POST", path = "/servers/{serverId}/players/join", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse playerJoinLegacy(PluginHttpRequest request) { return http.playerJoin(request); }

    @PluginHttpEndpoint(method = "POST", path = "/report/servers/{serverId}/players/quit", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse playerQuit(PluginHttpRequest request) { return http.playerQuit(request); }

    @PluginHttpEndpoint(method = "POST", path = "/servers/{serverId}/players/quit", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse playerQuitLegacy(PluginHttpRequest request) { return http.playerQuit(request); }

    @PluginHttpEndpoint(method = "POST", path = "/report/servers/{serverId}/players/afk/start", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse playerAfkStart(PluginHttpRequest request) { return http.playerAfkStart(request); }

    @PluginHttpEndpoint(method = "POST", path = "/servers/{serverId}/players/afk/start", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse playerAfkStartLegacy(PluginHttpRequest request) { return http.playerAfkStart(request); }

    @PluginHttpEndpoint(method = "POST", path = "/report/servers/{serverId}/players/afk/end", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse playerAfkEnd(PluginHttpRequest request) { return http.playerAfkEnd(request); }

    @PluginHttpEndpoint(method = "POST", path = "/servers/{serverId}/players/afk/end", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse playerAfkEndLegacy(PluginHttpRequest request) { return http.playerAfkEnd(request); }

    @PluginHttpEndpoint(method = "POST", path = "/report/servers/{serverId}/players/snapshot", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse playerSnapshot(PluginHttpRequest request) { return http.playerSnapshot(request); }

    @PluginHttpEndpoint(method = "POST", path = "/servers/{serverId}/players/snapshot", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse playerSnapshotLegacy(PluginHttpRequest request) { return http.playerSnapshot(request); }

    /** Topology bound to an explicit server id, for a bridge whose config already carries one. */
    @PluginHttpEndpoint(method = "POST", path = "/report/servers/{serverId}/topology", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse topology(PluginHttpRequest request) { return http.reportTopology(request); }

    @PluginHttpEndpoint(method = "POST", path = "/servers/{serverId}/topology", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse topologyLegacy(PluginHttpRequest request) { return http.reportTopology(request); }

    /**
     * Topology matched by the proxy's own address, so the proxy does not have to know its Admin
     * server id. This is what makes installing the bridge enough to resolve a group server.
     */
    @PluginHttpEndpoint(method = "POST", path = "/report/topology", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse topologyByAddress(PluginHttpRequest request) { return http.reportTopologyByAddress(request); }
}
