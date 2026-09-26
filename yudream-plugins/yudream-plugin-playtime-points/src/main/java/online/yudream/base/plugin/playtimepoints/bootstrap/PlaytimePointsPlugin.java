package online.yudream.base.plugin.playtimepoints.bootstrap;

import online.yudream.base.plugin.playtimepoints.application.service.PlaytimePointsAppService;
import online.yudream.base.plugin.playtimepoints.infrastructure.repository.PlaytimePointsDocumentRepository;
import online.yudream.base.plugin.playtimepoints.infrastructure.service.SettlementScheduler;
import online.yudream.base.plugin.playtimepoints.infrastructure.support.ProviderPorts;
import online.yudream.base.plugin.playtimepoints.interfaces.controller.PlaytimePointsAdminController;
import online.yudream.base.plugin.playtimepoints.interfaces.controller.PlaytimePointsUserController;
import online.yudream.base.plugin.playtimepoints.interfaces.http.PlaytimePointsHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;

@PluginSpec(
        code = PlaytimePointsPlugin.CODE,
        name = "playtime-points",
        version = "1.0.0",
        description = "按服务器权重把玩家的有效在线时长（扣除挂机）结算为钱包积分，每次退出服务器结算一次。"
)
@PluginPermissions({
        @PluginPermission(code = PlaytimePointsPlugin.VIEW_PERMISSION, name = "查看我的积分", module = "平台插件",
                description = "查看自己的在线时长积分汇总与结算流水"),
        @PluginPermission(code = PlaytimePointsPlugin.MANAGE_PERMISSION, name = "管理在线积分", module = "平台插件",
                description = "配置货币类型、每积分时长与服务器权重，查看全部结算记录并手动结算")
})
@PluginFrontend(
        moduleName = "playtimePoints",
        menuTitle = "在线时长积分",
        menuIcon = "i-ri:coins-line",
        menuSort = 46,
        styles = {"style.css"},
        routes = {
                @PluginRoute(
                        path = "/platform/plugins/playtime-points",
                        name = "platform-plugin-playtime-points-my",
                        title = "我的积分",
                        icon = "i-ri:coins-line",
                        component = "playtime-points/My",
                        permission = PlaytimePointsPlugin.VIEW_PERMISSION,
                        sort = 10
                ),
                @PluginRoute(
                        path = "/platform/plugins/playtime-points/settlements",
                        name = "platform-plugin-playtime-points-settlements",
                        title = "结算记录",
                        icon = "i-ri:file-list-3-line",
                        component = "playtime-points/Settlements",
                        permission = PlaytimePointsPlugin.MANAGE_PERMISSION,
                        sort = 20
                ),
                @PluginRoute(
                        path = "/platform/plugins/playtime-points/settings",
                        name = "platform-plugin-playtime-points-settings",
                        title = "积分设置",
                        icon = "i-ri:settings-3-line",
                        component = "playtime-points/Settings",
                        permission = PlaytimePointsPlugin.MANAGE_PERMISSION,
                        sort = 30
                )
        }
)
public class PlaytimePointsPlugin implements YuDreamPlugin {

    public static final String CODE = "playtime-points";
    public static final String VIEW_PERMISSION = "plugin:playtime-points:view";
    public static final String MANAGE_PERMISSION = "plugin:playtime-points:manage";

    @Override
    public void onEnable(PluginContext context) {
        ProviderPorts ports = new ProviderPorts(context);
        PlaytimePointsDocumentRepository repository = new PlaytimePointsDocumentRepository(context.documents());
        PlaytimePointsAppService appService = new PlaytimePointsAppService(repository, ports, ports, ports,
                System::currentTimeMillis);
        SettlementScheduler scheduler = new SettlementScheduler(appService);
        scheduler.start();
        context.onDispose(scheduler);
        PlaytimePointsHttpFacade http = new PlaytimePointsHttpFacade(appService);
        context.registerHttpController(new PlaytimePointsUserController(http));
        context.registerHttpController(new PlaytimePointsAdminController(http));
    }
}
