package online.yudream.base.plugin.minecraft.bootstrap;

import online.yudream.base.plugin.minecraft.application.service.MinecraftServerAppService;
import online.yudream.base.plugin.minecraft.infrastructure.repository.MinecraftServerDocumentRepository;
import online.yudream.base.plugin.minecraft.infrastructure.service.MinecraftStatusScheduler;
import online.yudream.base.plugin.minecraft.infrastructure.service.MinecraftStatusService;
import online.yudream.base.plugin.minecraft.interfaces.controller.MinecraftServerAdminController;
import online.yudream.base.plugin.minecraft.interfaces.controller.MinecraftServerReportController;
import online.yudream.base.plugin.minecraft.interfaces.controller.MinecraftServerUserController;
import online.yudream.base.plugin.minecraft.interfaces.http.MinecraftServerHttpFacade;
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
import online.yudream.base.plugin.spi.system.minecraft.PluginMinecraftService;

import java.util.Map;

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
                        parentPath = "/platform/plugins/minecraft-server/admin",
                        parentTitle = "服务器管理",
                        parentIcon = "i-ri:settings-3-line",
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
                        parentPath = "/platform/plugins/minecraft-server/admin",
                        parentTitle = "服务器管理",
                        parentIcon = "i-ri:settings-3-line",
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
                        parentPath = "/platform/plugins/minecraft-server/admin",
                        parentTitle = "服务器管理",
                        parentIcon = "i-ri:settings-3-line",
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
                        parentPath = "/platform/plugins/minecraft-server/admin",
                        parentTitle = "服务器管理",
                        parentIcon = "i-ri:settings-3-line",
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
                context.framework()
        );
        MinecraftStatusScheduler statusScheduler = new MinecraftStatusScheduler(appService);
        statusScheduler.start();
        context.onDispose(statusScheduler);
        context.registerExtension(PluginMinecraftService.class, appService);
        MinecraftServerHttpFacade http = new MinecraftServerHttpFacade(appService);
        context.registerHttpController(new MinecraftServerUserController(http));
        context.registerHttpController(new MinecraftServerAdminController(http));
        context.registerHttpController(new MinecraftServerReportController(http));
    }

    @PluginCommand(code = "minecraft-server.servers", command = "服务器", name = "查询 Minecraft 服务器", description = "查询服务器状态；可选参数为服务器 ID")
    public void servers(PluginCommandContext command, PluginContext context) {
        try {
            if (command.arguments().size() > 1) { reply(command, context, "用法：/服务器 [服务器ID]"); return; }
            if (command.arguments().size() == 1) {
                var server = appService.userDetail(command.arguments().getFirst(), true);
                reply(command, context, serverText(server));
                return;
            }
            var servers = appService.listServers(false, true);
            reply(command, context, servers.isEmpty() ? "当前没有可用服务器。" : servers.stream().map(this::serverText).reduce((a, b) -> a + "\n" + b).orElse(""));
        } catch (RuntimeException e) { reply(command, context, e.getMessage() == null ? "服务器查询失败" : e.getMessage()); }
    }

    @PluginCommand(code = "minecraft-server.my-online-time", command = "我的在线时长", name = "查询 Minecraft 在线时长", description = "查询绑定账号对应玩家的累计在线时长")
    public void onlineTime(PluginCommandContext command, PluginContext context) {
        if (command.userId() == null) { reply(command, context, "当前机器人账号尚未绑定系统账号，请先完成绑定。"); return; }
        var profile = context.framework().users().findById(command.userId()).orElse(null);
        String username = profile == null || profile.username() == null ? "" : profile.username();
        var matches = appService.listServers(false, false).stream().flatMap(server -> appService.playerActivities(server.id(), 1, 200).records().stream())
                .filter(activity -> activity.playerId().equals(String.valueOf(command.userId())) || activity.playerName().equalsIgnoreCase(username)).toList();
        reply(command, context, matches.isEmpty() ? "未找到关联的 Minecraft 玩家活动记录。" : matches.stream()
                .map(activity -> "- " + activity.playerName() + "：" + (activity.totalOnlineMillis() / 60000) + " 分钟在线，" + (activity.totalAfkMillis() / 60000) + " 分钟挂机")
                .reduce((a, b) -> a + "\n" + b).orElse(""));
    }

    private String serverText(online.yudream.base.plugin.minecraft.application.dto.MinecraftServerDTO server) {
        var status = server.status();
        String online = status == null ? "状态未知" : status.status() + "，在线 " + status.onlinePlayers() + "/" + status.maxPlayers();
        return server.name() + "（" + server.id() + "）：" + online;
    }

    private void reply(PluginCommandContext command, PluginContext context, String text) {
        if (command.event().channelId() == null || command.event().channelId().isBlank()) return;
        context.framework().messaging().send(new PluginMessageRequest(command.event().connectionId(), command.event().platform(), command.event().selfId(),
                command.event().channelId(), new PluginMessageContent(PluginMessageContent.Type.TEXT, text, null,
                command.event().messageId() == null ? Map.of() : Map.of("message_id", command.event().messageId()))));
    }
}
