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

    // ---------------------------------------------------------------- 群服互联

    /** 游戏内聊天上报：命中群服互联配置时转发到绑定的群聊。 */
    @PluginHttpEndpoint(method = "POST", path = "/report/servers/{serverId}/events/chat", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse gameChat(PluginHttpRequest request) { return http.gameChat(request); }

    /** 玩家死亡消息上报。 */
    @PluginHttpEndpoint(method = "POST", path = "/report/servers/{serverId}/events/death", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse gameDeath(PluginHttpRequest request) { return http.gameDeath(request); }

    /** 玩家成就（进度）达成上报。 */
    @PluginHttpEndpoint(method = "POST", path = "/report/servers/{serverId}/events/advancement", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse gameAdvancement(PluginHttpRequest request) { return http.gameAdvancement(request); }

    /** 群消息增量拉取：MC 端桥接按 after 游标轮询并游戏内广播。 */
    @PluginHttpEndpoint(method = "GET", path = "/report/servers/{serverId}/chat/inbound", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse chatInbound(PluginHttpRequest request) { return http.chatInbound(request); }

    /**
     * Compatibility endpoints for bridge builds released before the /report namespace
     * covered the game events and the inbound pull; they keep polling and reporting
     * against the bare /servers paths.
     */
    @PluginHttpEndpoint(method = "POST", path = "/servers/{serverId}/events/chat", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse gameChatLegacy(PluginHttpRequest request) { return http.gameChat(request); }

    @PluginHttpEndpoint(method = "POST", path = "/servers/{serverId}/events/death", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse gameDeathLegacy(PluginHttpRequest request) { return http.gameDeath(request); }

    @PluginHttpEndpoint(method = "POST", path = "/servers/{serverId}/events/advancement", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse gameAdvancementLegacy(PluginHttpRequest request) { return http.gameAdvancement(request); }

    @PluginHttpEndpoint(method = "GET", path = "/servers/{serverId}/chat/inbound", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse chatInboundLegacy(PluginHttpRequest request) { return http.chatInbound(request); }

    /** 群消息实时推送（SSE）：连接即重放缓冲增量，此后随队列实时下发。 */
    @PluginHttpEndpoint(method = "GET", path = "/report/servers/{serverId}/chat/inbound/stream", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse chatInboundStream(PluginHttpRequest request) { return http.chatInboundStream(request); }

    @PluginHttpEndpoint(method = "GET", path = "/servers/{serverId}/chat/inbound/stream", permission = MinecraftServerPlugin.REPORT_PERMISSION)
    public PluginHttpResponse chatInboundStreamLegacy(PluginHttpRequest request) { return http.chatInboundStream(request); }
}
