package online.yudream.base.plugin.ymcl.bootstrap;

import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.ymcl.application.service.YmclContributionAggregator;
import online.yudream.base.plugin.ymcl.application.service.YmclEventBus;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclActionController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclBundlesController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclChromeHomeController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclEventsController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclMipController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclMipPacksController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclDataController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclCapabilitiesController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclManifestController;
import online.yudream.base.plugin.ymcl.interfaces.controller.YmclSessionController;

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
                name = "设计 YMCL 域首页",
                module = "平台插件",
                description = "配置域下发给成员的首页卡片布局"
        )
})
public class YmclAdapterPlugin implements YuDreamPlugin {

    public static final String CODE = "ymcl-adapter";
    public static final String VERSION = "0.1.0";

    public static final String VIEW_PERMISSION = "plugin:ymcl-adapter:view";
    public static final String PUBLISH_PERMISSION = "plugin:ymcl-adapter:publish";
    public static final String DESIGN_PERMISSION = "plugin:ymcl-adapter:design";

    /** OAuth 公开客户端 id，与启动器侧硬编码一致（YAP §6.4）。 */
    public static final String LAUNCHER_CLIENT_ID = "ymcl";

    @Override
    public void onEnable(PluginContext context) {
        YmclContributionAggregator aggregator = new YmclContributionAggregator(context);
        YmclEventBus eventBus = new YmclEventBus();
        PluginDocumentStore documents = context.documents();
        PluginFileStore files = context.files();

        context.registerHttpController(new YmclCapabilitiesController(context.pluginCode(), VERSION));
        context.registerHttpController(
                new YmclSessionController(context.framework().users(), context.documents()));
        context.registerHttpController(new YmclManifestController(aggregator, documents));
        context.registerHttpController(new YmclDataController(aggregator));
        context.registerHttpController(new YmclActionController(aggregator));
        context.registerHttpController(new YmclChromeHomeController(documents, eventBus));
        context.registerHttpController(new YmclEventsController(eventBus));
        context.registerHttpController(new YmclMipController(aggregator, documents, eventBus));
        context.registerHttpController(new YmclMipPacksController(files, documents, eventBus));
        context.registerHttpController(new YmclBundlesController(files, documents));
    }
}
