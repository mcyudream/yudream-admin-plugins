package online.yudream.base.plugin.minecraft.bootstrap;

import online.yudream.base.plugin.minecraft.application.service.MinecraftServerAppService;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftPlayerActivityDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftServerDTO;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftService;
import online.yudream.base.plugin.minecraft.infrastructure.repository.MinecraftServerDocumentRepository;
import online.yudream.base.plugin.minecraft.infrastructure.service.MinecraftStatusScheduler;
import online.yudream.base.plugin.minecraft.infrastructure.service.MinecraftStatusService;
import online.yudream.base.plugin.minecraft.interfaces.controller.MinecraftServerAdminController;
import online.yudream.base.plugin.minecraft.interfaces.controller.MinecraftServerReportController;
import online.yudream.base.plugin.minecraft.interfaces.controller.MinecraftServerUserController;
import online.yudream.base.plugin.minecraft.interfaces.http.MinecraftServerHttpFacade;
import online.yudream.base.plugin.skin.api.PluginSkinService;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginCommand;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.spi.system.command.PluginCommandContext;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiTool;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolCall;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolDescriptor;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolResult;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolRisk;

import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@PluginSpec(
        code = MinecraftServerPlugin.CODE,
        name = "minecraft-server",
        version = "1.0.0",
        description = "管理 Minecraft 服务器列表、多线地址、在线状态与周目展示。"
)
@PluginPermissions({
        @PluginPermission(code = MinecraftServerPlugin.VIEW_PERMISSION, name = "查看 MC 服务器", module = "平台插件", description = "查看 Minecraft 服务器列表、详情与在线状态"),
        @PluginPermission(code = MinecraftServerPlugin.MANAGE_PERMISSION, name = "管理 MC 服务器", module = "平台插件", description = "维护 Minecraft 服务器、线路地址和周目信息"),
        @PluginPermission(code = MinecraftServerPlugin.REPORT_PERMISSION, name = "上报 MC 玩家事件", module = "平台插件", description = "Minecraft 服务器插件上报玩家加入、退出和挂机事件")
})
@PluginFrontend(
        moduleName = "minecraftServer",
        menuTitle = "MC 服务器",
        menuIcon = "i-ri:gamepad-line",
        menuSort = 45,
        routes = {
                @PluginRoute(
                        path = "/platform/plugins/minecraft-server",
                        name = "platform-plugin-minecraft-server-list",
                        title = "服务器列表",
                        icon = "i-ri:server-line",
                        component = "minecraft-server/List",
                        permission = MinecraftServerPlugin.VIEW_PERMISSION,
                        sort = 10
                ),
                @PluginRoute(
                        path = "/platform/plugins/minecraft-server/detail",
                        name = "platform-plugin-minecraft-server-detail",
                        title = "服务器详情",
                        icon = "i-ri:file-info-line",
                        component = "minecraft-server/Detail",
                        permission = MinecraftServerPlugin.VIEW_PERMISSION,
                        sort = 20,
                        hideInMenu = true
                ),
                @PluginRoute(
                        path = "/platform/plugins/minecraft-server/admin",
                        name = "platform-plugin-minecraft-server-admin",
                        title = "服务器管理",
                        icon = "i-ri:settings-3-line",
                        component = "minecraft-server/Admin",
                        permission = MinecraftServerPlugin.MANAGE_PERMISSION,
                        sort = 30
                ),
                @PluginRoute(
                        path = "/platform/plugins/minecraft-server/admin/editor",
                        name = "platform-plugin-minecraft-server-editor",
                        title = "服务器编辑",
                        icon = "i-ri:edit-line",
                        component = "minecraft-server/Editor",
                        permission = MinecraftServerPlugin.MANAGE_PERMISSION,
                        sort = 31,
                        hideInMenu = true
                ),
                @PluginRoute(
                        path = "/platform/plugins/minecraft-server/admin/seasons",
                        name = "platform-plugin-minecraft-server-seasons",
                        title = "周目管理",
                        icon = "i-ri:calendar-event-line",
                        component = "minecraft-server/Seasons",
                        permission = MinecraftServerPlugin.MANAGE_PERMISSION,
                        sort = 32,
                        hideInMenu = true
                ),
                @PluginRoute(
                        path = "/platform/plugins/minecraft-server/admin/operations",
                        name = "platform-plugin-minecraft-server-operations",
                        title = "周目操作记录",
                        icon = "i-ri:history-line",
                        component = "minecraft-server/Operations",
                        permission = MinecraftServerPlugin.MANAGE_PERMISSION,
                        sort = 33,
                        hideInMenu = true
                ),
                @PluginRoute(
                        path = "/platform/plugins/minecraft-server/admin/players",
                        name = "platform-plugin-minecraft-server-players",
                        title = "玩家时长统计",
                        icon = "i-ri:bar-chart-box-line",
                        component = "minecraft-server/Players",
                        permission = MinecraftServerPlugin.MANAGE_PERMISSION,
                        sort = 34,
                        hideInMenu = true
                )
        }
)
public class MinecraftServerPlugin implements YuDreamPlugin {

    public static final String CODE = "minecraft-server";
    public static final String VIEW_PERMISSION = "plugin:minecraft-server:view";
    public static final String MANAGE_PERMISSION = "plugin:minecraft-server:manage";
    public static final String REPORT_PERMISSION = "plugin:minecraft-server:report";
    private volatile MinecraftServerAppService appService;

    @Override
    public void onEnable(PluginContext context) {
        MinecraftStatusService statusService = new MinecraftStatusService();
        appService = new MinecraftServerAppService(
                new MinecraftServerDocumentRepository(context.documents()),
                statusService,
                context
        );
        MinecraftStatusScheduler statusScheduler = new MinecraftStatusScheduler(appService);
        statusScheduler.start();
        context.onDispose(statusScheduler);
        context.exposeService(PluginMinecraftService.class, appService);
        MinecraftServerHttpFacade http = new MinecraftServerHttpFacade(appService);
        context.registerHttpController(new MinecraftServerUserController(http));
        context.registerHttpController(new MinecraftServerAdminController(http));
        context.registerHttpController(new MinecraftServerReportController(http));
        context.registerAiTool(new PluginAiTool() {
            @Override public PluginAiToolDescriptor descriptor() { return new PluginAiToolDescriptor("minecraft.server.status", "查询服务器状态", "查询已启用 Minecraft 服务器的在线状态", VIEW_PERMISSION, PluginAiToolRisk.READ, false, java.util.Set.of("MENTION", "RANDOM"), Map.of()); }
            @Override public PluginAiToolResult execute(online.yudream.base.plugin.spi.system.ai.PluginAiExecutionContext execution, PluginAiToolCall call) { return new PluginAiToolResult("status", "已查询 Minecraft 服务器状态", Map.of("servers", appService.listServers(false, true))); }
        });
    }

    @PluginCommand(code = "minecraft-server.servers", command = "服务器", name = "查询 Minecraft 服务器", description = "查询服务器状态；可选参数为服务器 ID")
    public void servers(PluginCommandContext command, PluginContext context) {
        try {
            if (command.arguments().size() > 1) { reply(command, context, "用法：/服务器 [服务器ID]"); return; }
            if (command.arguments().size() == 1) {
                String selector = command.arguments().getFirst();
                String serverId = resolveServerId(selector);
                var server = appService.userDetail(serverId, true);
                renderServers(command, context, List.of(server));
                return;
            }
            var servers = appService.listServers(false, true);
            if (servers.isEmpty()) { reply(command, context, "当前没有可用服务器。"); return; }
            renderServers(command, context, servers);
        } catch (RuntimeException e) { reply(command, context, e.getMessage() == null ? "服务器查询失败" : e.getMessage()); }
    }

    @PluginCommand(code = "minecraft-server.my-online-time", command = "我的在线时长", name = "查询 Minecraft 在线时长", description = "查询绑定账号对应玩家的累计在线时长")
    public void onlineTime(PluginCommandContext command, PluginContext context) {
        if (command.userId() == null) { reply(command, context, "当前机器人账号尚未绑定系统账号，请先完成绑定。"); return; }
        var profile = context.framework().users().findById(command.userId()).orElse(null);
        String username = profile == null || profile.username() == null ? "" : profile.username();
        String userId = String.valueOf(command.userId());
        Set<String> playerIds = playerIdsForUser(context, userId);
        var servers = appService.listServers(false, false);
        Map<String, String> serverNames = servers.stream().collect(java.util.stream.Collectors.toMap(
                MinecraftServerDTO::id, MinecraftServerDTO::name, (first, ignored) -> first));
        var matches = servers.stream().flatMap(server -> appService.allPlayerActivities(server.id()).stream())
                .filter(activity -> playerIds.contains(normalizePlayerId(activity.playerId()))
                        || activity.playerName().equalsIgnoreCase(username)).toList();
        if (matches.isEmpty()) { reply(command, context, "未找到关联的 Minecraft 玩家活动记录。"); return; }
        List<Map<String, Object>> activities = matches.stream().map(activity -> activityView(
                activity, serverNames.getOrDefault(activity.serverId(), activity.serverId()))).toList();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("playerName", matches.getFirst().playerName());
        variables.put("activities", activities);
        variables.put("totalOnline", duration(matches.stream().mapToLong(MinecraftPlayerActivityDTO::totalOnlineMillis).sum()));
        variables.put("totalAfk", duration(matches.stream().mapToLong(MinecraftPlayerActivityDTO::totalAfkMillis).sum()));
        render(command, context, "player-online-time", variables, "在线时长查询失败");
    }

    private Set<String> playerIdsForUser(PluginContext context, String userId) {
        Set<String> playerIds = new HashSet<>();
        playerIds.add(normalizePlayerId(userId));
        context.service("yudream-skin", PluginSkinService.class)
                .ifPresent(service -> service.findProfilesByOwner(userId).forEach(profile ->
                        playerIds.add(normalizePlayerId(profile.uuid()))));
        return playerIds;
    }

    private String normalizePlayerId(String value) {
        return value == null ? "" : value.trim().replace("-", "").toLowerCase(Locale.ROOT);
    }

    private void renderServers(PluginCommandContext command, PluginContext context, List<MinecraftServerDTO> servers) {
        java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger(1);
        List<Map<String, Object>> serverViews = servers.stream().map(server -> serverView(server, index.getAndIncrement())).toList();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("servers", serverViews);
        variables.put("serverCount", serverViews.size());
        variables.put("totalOnline", servers.stream().map(MinecraftServerDTO::status).filter(java.util.Objects::nonNull)
                .mapToInt(status -> status.onlinePlayers()).sum());
        render(command, context, "server-list", variables, "服务器状态渲染失败");
    }

    private String resolveServerId(String selector) {
        if (selector != null && selector.matches("\\d+")) {
            int index = Integer.parseInt(selector);
            List<MinecraftServerDTO> servers = appService.listServers(false, false);
            if (index < 1 || index > servers.size()) {
                throw new IllegalArgumentException("服务器编号不存在，请先发送 /服务器 查看列表。");
            }
            return servers.get(index - 1).id();
        }
        return selector;
    }

    private Map<String, Object> serverView(MinecraftServerDTO server, int index) {
        var status = server.status();
        var primary = server.endpoints().stream().filter(MinecraftServerDTO.EndpointDTO::primaryLine).findFirst()
                .orElse(server.endpoints().isEmpty() ? null : server.endpoints().getFirst());
        var endpointStatus = status == null ? null : status.endpoints().stream()
                .filter(item -> primary != null && item.endpointId().equals(primary.id())).findFirst()
                .orElse(status.endpoints().isEmpty() ? null : status.endpoints().getFirst());
        List<String> players = appService.onlinePlayerActivities(server.id()).stream()
                .map(MinecraftPlayerActivityDTO::playerName).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("index", index);
        view.put("name", server.name());
        view.put("id", server.id());
        view.put("online", status != null && "ONLINE".equalsIgnoreCase(status.status()));
        view.put("onlinePlayers", status == null ? 0 : status.onlinePlayers());
        view.put("maxPlayers", status == null ? 0 : status.maxPlayers());
        view.put("address", primary == null ? "未配置线路" : primary.host() + (primary.port() == 25565 ? "" : ":" + primary.port()));
        view.put("edition", primary == null ? "" : primary.edition());
        view.put("version", endpointStatus == null || endpointStatus.versionName() == null ? "版本未知" : endpointStatus.versionName());
        view.put("ping", endpointStatus == null || endpointStatus.ping() == null ? "--" : endpointStatus.ping() + " ms");
        view.put("motd", endpointStatus == null || endpointStatus.motd() == null || endpointStatus.motd().isBlank()
                ? "A Minecraft Server" : MinecraftStatusService.plainMotd(endpointStatus.motd()));
        view.put("favicon", endpointStatus == null ? null : endpointStatus.favicon());
        view.put("players", players);
        view.put("unreportedPlayers", Math.max(0, (status == null ? 0 : status.onlinePlayers()) - players.size()));
        return view;
    }

    private Map<String, Object> activityView(MinecraftPlayerActivityDTO activity, String serverName) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("serverName", serverName);
        view.put("playerName", activity.playerName());
        view.put("online", activity.online());
        view.put("afk", activity.afk());
        view.put("onlineTime", duration(activity.totalOnlineMillis()));
        view.put("afkTime", duration(activity.totalAfkMillis()));
        return view;
    }

    private String duration(long millis) {
        long minutes = Math.max(0, millis / 60_000);
        long hours = minutes / 60;
        long remainingMinutes = minutes % 60;
        return hours > 0 ? hours + " 小时 " + remainingMinutes + " 分钟" : remainingMinutes + " 分钟";
    }

    private void render(PluginCommandContext command, PluginContext context, String template,
                        Map<String, Object> variables, String fallback) {
        context.templateRenderer().render(template, variables, "#minecraft-card").whenComplete((image, error) -> {
            if (error != null || image == null || image.content() == null || image.content().length == 0) {
                reply(command, context, fallback);
                return;
            }
            String uri = "base64://" + Base64.getEncoder().encodeToString(image.content());
            send(command, context, new PluginMessageContent(PluginMessageContent.Type.IMAGE, uri, null, replyReferrer(command)));
        });
    }

    private void reply(PluginCommandContext command, PluginContext context, String text) {
        send(command, context, new PluginMessageContent(PluginMessageContent.Type.TEXT, text, null, replyReferrer(command)));
    }

    private Map<String, Object> replyReferrer(PluginCommandContext command) {
        return command.event().messageId() == null ? Map.of() : Map.of("message_id", command.event().messageId());
    }

    private void send(PluginCommandContext command, PluginContext context, PluginMessageContent content) {
        if (command.event().channelId() == null || command.event().channelId().isBlank()) return;
        context.framework().messaging().send(new PluginMessageRequest(command.event().connectionId(), command.event().platform(), command.event().selfId(),
                command.event().channelId(), content));
    }
}
