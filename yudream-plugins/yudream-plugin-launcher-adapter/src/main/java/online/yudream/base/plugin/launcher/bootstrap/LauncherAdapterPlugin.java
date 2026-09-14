package online.yudream.base.plugin.launcher.bootstrap;

import online.yudream.base.plugin.launcher.application.service.AuthSessionAppService;
import online.yudream.base.plugin.launcher.application.service.LauncherOAuthClientService;
import online.yudream.base.plugin.launcher.application.service.LauncherProviderAggregator;
import online.yudream.base.plugin.launcher.application.service.ManifestAppService;
import online.yudream.base.plugin.launcher.application.service.ManifestV2AppService;
import online.yudream.base.plugin.launcher.application.service.PackAppService;
import online.yudream.base.plugin.launcher.application.service.YmclChromeAppService;
import online.yudream.base.plugin.launcher.api.YmclPublicOAuth;
import online.yudream.base.plugin.launcher.infrastructure.provider.YmclPacksLauncherProvider;
import online.yudream.base.plugin.launcher.infrastructure.repository.FilePackRepository;
import online.yudream.base.plugin.launcher.api.LauncherProvider;
import online.yudream.base.plugin.launcher.interfaces.controller.AdminController;
import online.yudream.base.plugin.launcher.interfaces.controller.AuthV2Controller;
import online.yudream.base.plugin.launcher.interfaces.controller.LauncherDataController;
import online.yudream.base.plugin.launcher.interfaces.controller.LauncherDataV2Controller;
import online.yudream.base.plugin.launcher.interfaces.controller.ManifestController;
import online.yudream.base.plugin.launcher.interfaces.controller.ManifestV2Controller;
import online.yudream.base.plugin.launcher.interfaces.controller.PackV2Controller;
import online.yudream.base.plugin.launcher.interfaces.controller.PublishController;
import online.yudream.base.plugin.launcher.interfaces.controller.YggExchangeController;
import online.yudream.base.plugin.launcher.interfaces.http.LauncherHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginDashboardCard;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;

@PluginSpec(
        code = LauncherAdapterPlugin.CODE,
        name = "启动器适配器",
        version = "0.6.0",
        description = "为 YMCL 启动器提供版本化 manifest、LauncherProvider 页面聚合、站点外观/导航配置与 mrpack 整合包分发通道。"
)
@PluginPermissions({
        @PluginPermission(
                code = LauncherAdapterPlugin.VIEW_PERMISSION,
                name = "查看启动器适配器",
                module = "启动器适配器",
                description = "拉取启动器 manifest、连接 YMCL 并查看 pack 数据"
        ),
        @PluginPermission(
                code = LauncherAdapterPlugin.MANAGE_PERMISSION,
                name = "管理整合包",
                module = "启动器适配器",
                description = "推送整合包新版本与回滚"
        )
})
@PluginDashboardCard(
        code = "ymcl-connect",
        title = "连接 YMCL",
        description = "一键向 YMCL 添加本域授权：点击拉起启动器，或拖动卡片自动填充域站基址。",
        icon = "i-ri:rocket-2-line",
        category = "启动器",
        permission = LauncherAdapterPlugin.VIEW_PERMISSION,
        component = "launcher-adapter/YmclConnectCard",
        actionPath = "/platform/plugins/launcher-adapter/connect",
        dragPayloadTemplate = "ymcl://add-site?url={encodedUrl}",
        tone = "cyan",
        defaultW = 4,
        defaultH = 3,
        minW = 3,
        minH = 2,
        sort = 15,
        defaultOnFirstVisit = true
)
@PluginFrontend(
        moduleName = "launcherAdapter",
        menuTitle = "启动器",
        menuIcon = "i-ri:rocket-2-line",
        menuSort = 37,
        styles = {"style.css"},
        routes = {
                @PluginRoute(
                        path = "/platform/plugins/launcher-adapter/connect",
                        name = "platform-plugin-launcher-adapter-connect",
                        title = "连接 YMCL",
                        icon = "i-ri:links-line",
                        component = "launcher-adapter/Connect",
                        permission = LauncherAdapterPlugin.VIEW_PERMISSION,
                        sort = 10
                ),
                @PluginRoute(
                        path = "/platform/plugins/launcher-adapter/admin/packs",
                        name = "platform-plugin-launcher-adapter-admin-packs",
                        title = "整合包管理",
                        icon = "i-ri:box-3-line",
                        component = "launcher-adapter/Packs",
                        permission = LauncherAdapterPlugin.MANAGE_PERMISSION,
                        sort = 20
                ),
                @PluginRoute(
                        path = "/platform/plugins/launcher-adapter/admin/chrome",
                        name = "platform-plugin-launcher-adapter-admin-chrome",
                        title = "启动器外观",
                        icon = "i-ri:palette-line",
                        component = "launcher-adapter/Chrome",
                        permission = LauncherAdapterPlugin.MANAGE_PERMISSION,
                        sort = 30
                )
        }
)
public class LauncherAdapterPlugin implements YuDreamPlugin {

    public static final String CODE = "launcher-adapter";
    public static final String VERSION = "0.6.0";
    public static final String VIEW_PERMISSION = "plugin:launcher-adapter:view";
    public static final String MANAGE_PERMISSION = "plugin:launcher-adapter:manage";

    @Override
    public void onEnable(PluginContext context) {
        LauncherOAuthClientService oauthClientService = new LauncherOAuthClientService(context);
        oauthClientService.ensureRegistered();
        FilePackRepository repository = new FilePackRepository(context.files());
        PackAppService packAppService = new PackAppService(repository, context.files());
        LauncherProviderAggregator aggregator = new LauncherProviderAggregator(context);
        YmclChromeAppService chromeAppService = new YmclChromeAppService(context.documents());
        ManifestAppService manifestAppService = new ManifestAppService(context, aggregator, packAppService, chromeAppService);
        AuthSessionAppService authSessionAppService = new AuthSessionAppService(context, oauthClientService);
        ManifestV2AppService manifestV2AppService = new ManifestV2AppService(context, aggregator, chromeAppService, authSessionAppService);
        LauncherHttpFacade http = new LauncherHttpFacade(context, manifestAppService, packAppService, chromeAppService, aggregator);
        context.registerHttpController(new ManifestController(http));
        context.registerHttpController(new PublishController(http));
        context.registerHttpController(new LauncherDataController(http));
        context.registerHttpController(new YggExchangeController(http));
        context.registerHttpController(new AdminController(http));
        context.registerHttpController(new AuthV2Controller(authSessionAppService));
        context.registerHttpController(new ManifestV2Controller(manifestV2AppService));
        context.registerHttpController(new LauncherDataV2Controller(http));
        context.registerHttpController(new PackV2Controller(http));
        YmclPacksLauncherProvider packsProvider = new YmclPacksLauncherProvider(packAppService);
        context.registerExtension(LauncherProvider.class, packsProvider);
        YmclPublicOAuth.contribute(context, packsProvider);
        oauthClientService.mergeRegisteredProviders();
    }

    @Override
    public void onDisable(PluginContext context) {
        // 无外部连接或线程池需要释放
    }
}
