package online.yudream.plugin.webcard.bootstrap;

import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.spi.system.messaging.PluginInteractionFilter;
import online.yudream.plugin.webcard.application.AgentAuthoringService;
import online.yudream.plugin.webcard.application.WebCardApplicationService;
import online.yudream.plugin.webcard.application.WebCardScheduler;
import online.yudream.plugin.webcard.infrastructure.CardRenderer;
import online.yudream.plugin.webcard.infrastructure.ContentParser;
import online.yudream.plugin.webcard.infrastructure.DocumentWebCardRepository;
import online.yudream.plugin.webcard.infrastructure.SecretHeaderStore;
import online.yudream.plugin.webcard.infrastructure.SecureWebFetcher;
import online.yudream.plugin.webcard.interfaces.JsonSupport;
import online.yudream.plugin.webcard.interfaces.WebCardAdminController;
import online.yudream.plugin.webcard.interfaces.WebCardHttpFacade;

import java.util.Set;

@PluginSpec(code = WebCardPlugin.CODE, name = "web-card", version = "1.0.2", description = "将已配置网站内容解析并渲染为群聊卡片。")
@PluginPermissions(@PluginPermission(code = WebCardPlugin.MANAGE_PERMISSION, name = "管理网站卡片", module = "平台插件", description = "管理网站规则、模板、采集和群投递"))
@PluginFrontend(moduleName = "webCard", menuTitle = "网站卡片", menuIcon = "i-ri:layout-masonry-line", menuSort = 67, routes = {
        @PluginRoute(path = "/platform/plugins/web-card/admin/studio", name = "platform-plugin-web-card-studio", title = "Agent 工作台", icon = "i-ri:sparkling-2-line", component = "web-card/Studio", permission = WebCardPlugin.MANAGE_PERMISSION, sort = 10),
        @PluginRoute(path = "/platform/plugins/web-card/admin/sites", name = "platform-plugin-web-card-sites", title = "站点与解析", icon = "i-ri:global-line", component = "web-card/Sites", permission = WebCardPlugin.MANAGE_PERMISSION, sort = 20),
        @PluginRoute(path = "/platform/plugins/web-card/admin/templates", name = "platform-plugin-web-card-templates", title = "卡片模板", icon = "i-ri:layout-4-line", component = "web-card/TemplateDesigner", permission = WebCardPlugin.MANAGE_PERMISSION, sort = 21),
        @PluginRoute(path = "/platform/plugins/web-card/admin/bindings", name = "platform-plugin-web-card-bindings", title = "定时推送目标", icon = "i-ri:group-line", component = "web-card/GroupBindings", permission = WebCardPlugin.MANAGE_PERMISSION, sort = 22),
        @PluginRoute(path = "/platform/plugins/web-card/admin/jobs", name = "platform-plugin-web-card-jobs", title = "定时任务", icon = "i-ri:timer-line", component = "web-card/CrawlJobs", permission = WebCardPlugin.MANAGE_PERMISSION, sort = 23),
        @PluginRoute(path = "/platform/plugins/web-card/admin/runs", name = "platform-plugin-web-card-runs", title = "运行记录", icon = "i-ri:pulse-line", component = "web-card/Runs", permission = WebCardPlugin.MANAGE_PERMISSION, sort = 30)
})
public final class WebCardPlugin implements YuDreamPlugin {
    public static final String CODE = "web-card";
    public static final String MANAGE_PERMISSION = "plugin:web-card:manage";

    @Override
    public void onEnable(PluginContext context) {
        var repository = new DocumentWebCardRepository(context.documents(), JsonSupport.MAPPER);
        var app = new WebCardApplicationService(repository, new SecretHeaderStore(context.secrets()),
                new SecureWebFetcher(), new ContentParser(), new CardRenderer(context.framework().render()),
                context.framework().messaging());
        var agents = new AgentAuthoringService(repository, app, context.framework().ai(), context.framework());
        context.registerHttpController(new WebCardAdminController(new WebCardHttpFacade(app, agents, context.framework())));
        context.interactions().onMessage(new PluginInteractionFilter(Set.of("message_receive"), null, null, null), app::onMessage);
        var scheduler = new WebCardScheduler(app);
        scheduler.start();
        context.onDispose(scheduler);
        context.onDispose(agents);
        context.onDispose(app);
    }
}
