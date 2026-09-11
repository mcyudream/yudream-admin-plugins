package online.yudream.base.plugin.mcnews.bootstrap;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import online.yudream.base.plugin.mcnews.application.McNewsSettings;
import online.yudream.base.plugin.mcnews.application.NewsAiSummarizer;
import online.yudream.base.plugin.mcnews.application.NewsFeedService;
import online.yudream.base.plugin.mcnews.application.NewsFetchService;
import online.yudream.base.plugin.mcnews.application.NewsPipeline;
import online.yudream.base.plugin.mcnews.application.NewsPushService;
import online.yudream.base.plugin.mcnews.application.NewsSourceService;
import online.yudream.base.plugin.mcnews.application.NewsSubscriptionService;
import online.yudream.base.plugin.mcnews.application.NewsTargetService;
import online.yudream.base.plugin.mcnews.application.NewsTemplateService;
import online.yudream.base.plugin.mcnews.infrastructure.McNewsStore;
import online.yudream.base.plugin.mcnews.interfaces.McNewsAdminController;
import online.yudream.base.plugin.mcnews.interfaces.McNewsHttpFacade;
import online.yudream.base.plugin.mcnews.interfaces.McNewsUserController;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;

/**
 * MC 新闻推送：轮询 Minecraft 官网新闻与反馈隧道版本文章，与已见缓存对比，
 * 新内容经宿主 AI 整合后推送到机器人群聊、Webhook 与订阅用户。
 */
@PluginSpec(
        code = McNewsPlugin.CODE,
        name = "MC 新闻推送",
        version = McNewsPlugin.VERSION,
        description = "轮询 Minecraft 官网新闻与反馈隧道版本文章，AI 整合后推送到机器人群聊、Webhook 与订阅用户"
)
@PluginPermissions({
        @PluginPermission(code = McNewsPlugin.MANAGE_PERMISSION, name = "管理 MC 新闻推送", module = "平台插件",
                description = "管理新闻源、推送目标、消息模板与推送记录"),
        @PluginPermission(code = McNewsPlugin.USE_PERMISSION, name = "使用 MC 新闻订阅", module = "平台插件",
                description = "开启新闻私信订阅并维护个人 Webhook 推送目标")
})
@PluginFrontend(
        moduleName = "mc-news",
        menuTitle = "MC 新闻推送",
        menuIcon = "i-ri:newspaper-line",
        menuSort = 76,
        styles = {"style.css"},
        routes = {
                @PluginRoute(path = "/platform/plugins/mc-news/news", name = "mc-news-news", title = "新闻动态",
                        icon = "i-ri:newspaper-line", component = "mc-news/NewsList",
                        permission = McNewsPlugin.MANAGE_PERMISSION, sort = 10),
                @PluginRoute(path = "/platform/plugins/mc-news/sources", name = "mc-news-sources", title = "新闻源",
                        icon = "i-ri:rss-line", component = "mc-news/Sources",
                        permission = McNewsPlugin.MANAGE_PERMISSION, sort = 20),
                @PluginRoute(path = "/platform/plugins/mc-news/targets", name = "mc-news-targets", title = "推送目标",
                        icon = "i-ri:send-plane-line", component = "mc-news/Targets",
                        permission = McNewsPlugin.MANAGE_PERMISSION, sort = 30),
                @PluginRoute(path = "/platform/plugins/mc-news/settings", name = "mc-news-settings", title = "推送设置",
                        icon = "i-ri:quill-pen-line", component = "mc-news/Settings",
                        permission = McNewsPlugin.MANAGE_PERMISSION, sort = 40),
                @PluginRoute(path = "/platform/plugins/mc-news/logs", name = "mc-news-logs", title = "推送记录",
                        icon = "i-ri:history-line", component = "mc-news/PushLog",
                        permission = McNewsPlugin.MANAGE_PERMISSION, sort = 50),
                @PluginRoute(path = "/mc-news/subscribe", name = "mc-news-subscribe", title = "我的新闻订阅",
                        icon = "i-ri:notification-3-line", component = "mc-news/MySubscription",
                        permission = McNewsPlugin.USE_PERMISSION, sort = 60)
        }
)
public class McNewsPlugin implements YuDreamPlugin {

    public static final String CODE = "mc-news";
    public static final String VERSION = "1.0.0";
    public static final String MANAGE_PERMISSION = "plugin:mc-news:manage";
    public static final String USE_PERMISSION = "plugin:mc-news:use";

    private static final Logger LOGGER = Logger.getLogger(McNewsPlugin.class.getName());
    private static final long TICK_SECONDS = 60;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService manualPollExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onEnable(PluginContext context) {
        McNewsStore store = new McNewsStore(context.documents());
        McNewsSettings settings = new McNewsSettings(store);
        NewsSourceService sources = new NewsSourceService(store);
        NewsTargetService targets = new NewsTargetService(store);
        NewsSubscriptionService subscriptions = new NewsSubscriptionService(store);
        NewsTemplateService templates = new NewsTemplateService();
        NewsAiSummarizer ai = new NewsAiSummarizer(context.framework(), settings);

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        NewsFetchService fetch = new NewsFetchService(http, new com.fasterxml.jackson.databind.ObjectMapper());
        NewsPushService push = new NewsPushService(context.framework(), http, new com.fasterxml.jackson.databind.ObjectMapper());
        NewsPipeline pipeline = new NewsPipeline(store, settings, sources, targets, subscriptions,
                fetch, templates, ai, push);
        NewsFeedService feed = new NewsFeedService(store);

        sources.seedBuiltinsIfEmpty();

        McNewsHttpFacade facade = new McNewsHttpFacade(settings, sources, targets, subscriptions,
                pipeline, feed, templates, context.framework(), store, manualPollExecutor, fetch);
        context.registerHttpController(new McNewsAdminController(facade));
        context.registerHttpController(new McNewsUserController(facade));

        // 每分钟检查一次：到期的轮询按当前设置的间隔触发，间隔调整无需重排定时器
        scheduler.scheduleWithFixedDelay(() -> tick(pipeline, settings), TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
        context.onDispose(scheduler::shutdownNow);
        context.onDispose(manualPollExecutor::shutdownNow);
        context.onDispose(http::close);
    }

    private void tick(NewsPipeline pipeline, McNewsSettings settings) {
        try {
            if (!settings.enabled()) {
                return;
            }
            long last = settings.lastPollAt();
            if (System.currentTimeMillis() - last < settings.pollIntervalMinutes() * 60_000L) {
                return;
            }
            pipeline.pollOnce("poll");
        }
        catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "[MC News] 定时轮询执行失败", exception);
        }
    }
}
