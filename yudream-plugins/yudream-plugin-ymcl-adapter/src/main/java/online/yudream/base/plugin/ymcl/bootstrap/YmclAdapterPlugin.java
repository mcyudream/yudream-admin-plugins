package online.yudream.base.plugin.ymcl.bootstrap;

import online.yudream.base.plugin.spi.annotation.PluginDashboardCard;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.security.PluginOAuthPublicClientSpec;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;

import java.util.List;
import online.yudream.base.plugin.ymcl.application.service.YmclContributionAggregator;
import online.yudream.base.plugin.ymcl.application.service.YmclEventBus;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclActionController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclBundlesController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclChromeHomeController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclCustomPagesController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclEventsController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclExternalAuthController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclMipController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclMipPacksController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclDataController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclCapabilitiesController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclManifestController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclSessionController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclSkinWardrobeController;

/**
 * YMCL 适配器插件（YAP — YMCL Adapter Protocol v1）。
 *
 * 域对启动器的唯一协议入口：聚合本插件的 capabilities、认证会话、manifest、
 * 数据信封、chrome 配置与 MIP 整合包分发端点。字段命名与启动器侧 serde
 * 结构严格一致（snake_case），协议契约见 YMCL 仓 docs/ymcl-adapter-protocol.md。
 */
@PluginSpec(
        code = YmclAdapterPlugin.CODE,
        name = "ymcl-adapter",
        version = YmclAdapterPlugin.VERSION,
        description = "YMCL 启动器域适配协议入口：加入域、登录会话、页面内容与整合包分发。"
)
@PluginPermissions({
        @PluginPermission(
                code = YmclAdapterPlugin.VIEW_PERMISSION,
                name = "查看 YMCL 域内容",
                module = "平台插件",
                description = "访问 YMCL 适配器下发的域页面与数据"
        ),
        @PluginPermission(
                code = YmclAdapterPlugin.PUBLISH_PERMISSION,
                name = "发布 YMCL 整合包",
                module = "平台插件",
                description = "通过启动器发布控制台推送初始包与增量包"
        ),
        @PluginPermission(
                code = YmclAdapterPlugin.DESIGN_PERMISSION,
                name = "设计 YMCL 域外观",
                module = "平台插件",
                description = "配置域下发给成员的首页卡片布局与外观主题"
        )
})
@PluginDashboardCard(
        code = "ymcl-connect",
        title = "连接 YMCL",
        description = "一键唤起 YMCL-Axolotl 启动器并弹出添加域确认；点击卡片打开域概览。",
        icon = "i-ri:gamepad-line",
        category = "启动器",
        permission = YmclAdapterPlugin.VIEW_PERMISSION,
        component = "ymcl-adapter/YmclConnectCard",
        actionPath = "/platform/plugins/ymcl-adapter/overview",
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
        moduleName = "ymclAdapter",
        menuTitle = "YMCL 适配器",
        menuIcon = "i-ri:gamepad-line",
        menuSort = 38,
        styles = {"style.css"},
        routes = {
                @PluginRoute(
                        path = "/platform/plugins/ymcl-adapter/overview",
                        name = "platform-plugin-ymcl-adapter-overview",
                        title = "域概览",
                        icon = "i-ri:dashboard-3-line",
                        component = "ymcl-adapter/Overview",
                        permission = YmclAdapterPlugin.VIEW_PERMISSION,
                        sort = 10
                ),
                @PluginRoute(
                        path = "/platform/plugins/ymcl-adapter/admin/packs",
                        name = "platform-plugin-ymcl-adapter-admin-packs",
                        title = "整合包分发",
                        icon = "i-ri:box-3-line",
                        component = "ymcl-adapter/Packs",
                        permission = YmclAdapterPlugin.PUBLISH_PERMISSION,
                        sort = 20
                ),
                @PluginRoute(
                        path = "/platform/plugins/ymcl-adapter/admin/servers",
                        name = "platform-plugin-ymcl-adapter-admin-servers",
                        title = "服务器绑定",
                        icon = "i-ri:server-line",
                        component = "ymcl-adapter/Servers",
                        permission = YmclAdapterPlugin.PUBLISH_PERMISSION,
                        sort = 30
                ),
                @PluginRoute(
                        path = "/platform/plugins/ymcl-adapter/admin/chrome-home",
                        name = "platform-plugin-ymcl-adapter-admin-chrome-home",
                        title = "首页布局",
                        icon = "i-ri:layout-masonry-line",
                        component = "ymcl-adapter/ChromeHome",
                        permission = YmclAdapterPlugin.DESIGN_PERMISSION,
                        sort = 40
                ),
                @PluginRoute(
                        path = "/platform/plugins/ymcl-adapter/admin/chrome-theme",
                        name = "platform-plugin-ymcl-adapter-admin-chrome-theme",
                        title = "域主题",
                        icon = "i-ri:palette-line",
                        component = "ymcl-adapter/ChromeTheme",
                        permission = YmclAdapterPlugin.DESIGN_PERMISSION,
                        sort = 42
                ),
                @PluginRoute(
                        path = "/platform/plugins/ymcl-adapter/admin/navigation",
                        name = "platform-plugin-ymcl-adapter-admin-navigation",
                        title = "域导航",
                        icon = "i-ri:menu-line",
                        component = "ymcl-adapter/Navigation",
                        permission = YmclAdapterPlugin.DESIGN_PERMISSION,
                        sort = 45
                ),
                @PluginRoute(
                        path = "/platform/plugins/ymcl-adapter/admin/bundles",
                        name = "platform-plugin-ymcl-adapter-admin-bundles",
                        title = "扩展页面包",
                        icon = "i-ri:file-zip-line",
                        component = "ymcl-adapter/Bundles",
                        permission = YmclAdapterPlugin.PUBLISH_PERMISSION,
                        sort = 50
                )
        }
)
public class YmclAdapterPlugin implements YuDreamPlugin {

    public static final String CODE = "ymcl-adapter";
    public static final String VERSION = "0.6.1";

    public static final String VIEW_PERMISSION = "plugin:ymcl-adapter:view";
    public static final String PUBLISH_PERMISSION = "plugin:ymcl-adapter:publish";
    public static final String DESIGN_PERMISSION = "plugin:ymcl-adapter:design";

    /** OAuth 公开客户端 id，与启动器侧硬编码一致（YAP §6.4）。 */
    public static final String LAUNCHER_CLIENT_ID = "ymcl";

    @Override
    public void onEnable(PluginContext context) {
        YmclContributionAggregator aggregator = new YmclContributionAggregator(context);
        YmclEventBus eventBus = new YmclEventBus();
        // YAP §6.10：启动器按 45s 读超时维持 SSE，必须周期性心跳，否则连接被掐断
        // 并在宿主侧表现为 AsyncRequestTimeoutException。
        eventBus.startHeartbeat();
        context.onDispose(eventBus::stopHeartbeat);
        PluginDocumentStore documents = context.documents();
        PluginFileStore files = context.files();

        YmclCapabilitiesController capabilitiesController = new YmclCapabilitiesController(
                context.pluginCode(), VERSION, documents,
                // 域显示名称未配置时回退宿主站点名称（settings.siteName）。
                () -> context.framework().setting("siteName").orElse(null),
                // 自报 origin 首选宿主配置的站点地址（APP_WEB_URL）。
                () -> context.framework().setting("APP_WEB_URL").orElse(null),
                // 皮肤站插件可用时才宣告 skins 能力（YAP §6.11）；懒求值，
                // LinkageError = 皮肤站类不可达，同样视为不可用。
                () -> {
                    try {
                        return context.service("yudream-skin",
                                online.yudream.base.plugin.skin.api.PluginSkinService.class).isPresent();
                    } catch (LinkageError error) {
                        return false;
                    }
                });
        context.registerHttpController(capabilitiesController);
        context.registerHttpController(
                new YmclSessionController(context.framework().users(), documents));
        context.registerHttpController(new YmclManifestController(aggregator, documents));
        context.registerHttpController(new YmclDataController(aggregator));
        context.registerHttpController(new YmclActionController(aggregator));
        context.registerHttpController(new YmclChromeHomeController(documents, eventBus, aggregator));
        context.registerHttpController(new YmclCustomPagesController(documents, eventBus, aggregator));
        context.registerHttpController(new YmclEventsController(eventBus));
        context.registerHttpController(new YmclMipController(aggregator, documents, eventBus));
        context.registerHttpController(new YmclMipPacksController(files, documents, eventBus));
        context.registerHttpController(new YmclBundlesController(files, documents, aggregator));
        context.registerHttpController(new YmclSkinWardrobeController(context, capabilitiesController));
        context.registerHttpController(new YmclExternalAuthController(documents, capabilitiesController));

        // 自主登记启动器公开 OAuth 客户端（幂等）：authMethod NONE，scope 随
        // 插件权限点自动合并更新；回调地址登记不带端口的回环地址，由宿主按
        // RFC 8252 放行任意回环端口（启动器每次登录监听动态端口）。插件停用/
        // 卸载时仅禁用登记，不删除配置，重新启用即恢复。
        context.framework().oauth().ensurePublicClient(new PluginOAuthPublicClientSpec(
                LAUNCHER_CLIENT_ID,
                "YMCL 启动器",
                List.of("http://127.0.0.1/auth/callback", "http://localhost/auth/callback"),
                List.of("openid", "profile", VIEW_PERMISSION, PUBLISH_PERMISSION, DESIGN_PERMISSION)));
    }

    @Override
    public void onDisable(PluginContext context) {
        context.framework().oauth().disableClient(LAUNCHER_CLIENT_ID);
    }

    @Override
    public void onUnload(PluginContext context) {
        context.framework().oauth().disableClient(LAUNCHER_CLIENT_ID);
    }
}
