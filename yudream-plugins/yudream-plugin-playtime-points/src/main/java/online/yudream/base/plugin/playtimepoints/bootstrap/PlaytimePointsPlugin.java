package online.yudream.base.plugin.playtimepoints.bootstrap;

import online.yudream.base.plugin.playtimepoints.application.service.CheckInRewardService;
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
        version = "1.4.0",
        description = "按服务器/子服权重把玩家的有效在线时长（扣除挂机）结算为钱包积分，每次退出服务器结算一次；可选把项目打卡验收通过也折算为积分（每次固定积分、按时薪折算，没有时长的打卡按固定积分发放）。"
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
                        path = "/platform/plugins/playtime-points/check-in-rewards",
                        name = "platform-plugin-playtime-points-check-in-rewards",
                        title = "打卡积分",
                        icon = "i-ri:map-pin-time-line",
                        component = "playtime-points/CheckInRewards",
                        permission = PlaytimePointsPlugin.MANAGE_PERMISSION,
                        sort = 25
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
        CheckInRewardService checkInRewards = new CheckInRewardService(repository, ports, ports,
                System::currentTimeMillis);
        // 启用阶段只做轻量装配：拉取交给定时任务，实时回调只按当前开关决定是否注册。
        checkInRewards.registerRealtimeListener();
        SettlementScheduler scheduler = new SettlementScheduler(appService, checkInRewards);
        scheduler.start();
        context.onDispose(scheduler);
        // 停用后迟到的实时回调与拉取变成空操作（宿主也会回收 registerExtension 注册的监听）
        context.onDispose(checkInRewards);
        PlaytimePointsHttpFacade http = new PlaytimePointsHttpFacade(appService, checkInRewards);
        context.registerHttpController(new PlaytimePointsUserController(http));
        context.registerHttpController(new PlaytimePointsAdminController(http));
    }
}
