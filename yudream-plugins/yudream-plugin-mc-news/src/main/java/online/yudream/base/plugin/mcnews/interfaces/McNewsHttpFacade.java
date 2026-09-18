package online.yudream.base.plugin.mcnews.interfaces;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.mcnews.application.McNewsSettings;
import online.yudream.base.plugin.mcnews.application.NewsFeedService;
import online.yudream.base.plugin.mcnews.application.NewsPipeline;
import online.yudream.base.plugin.mcnews.application.NewsSourceService;
import online.yudream.base.plugin.mcnews.application.NewsSubscriptionService;
import online.yudream.base.plugin.mcnews.application.NewsTargetService;
import online.yudream.base.plugin.mcnews.application.NewsTemplateService;
import online.yudream.base.plugin.mcnews.domain.NewsArticle;
import online.yudream.base.plugin.mcnews.domain.NewsKeywordFilter;
import online.yudream.base.plugin.mcnews.domain.NewsSource;
import online.yudream.base.plugin.mcnews.domain.NewsTarget;
import online.yudream.base.plugin.mcnews.infrastructure.McNewsStore;
import online.yudream.base.plugin.mcnews.interfaces.support.HttpSupport;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingConnection;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingGroup;

/**
 * HTTP 门面：请求解析（request → 调用参数）、视图组装（DTO → 响应），
 * 业务规则全部在 application 层。管理端 /admin/**、用户端 /me/** 分离。
 */
public class McNewsHttpFacade {
    private static final int PAGE_FALLBACK = 10;

    private final McNewsSettings settings;
    private final NewsSourceService sources;
    private final NewsTargetService targets;
    private final NewsSubscriptionService subscriptions;
    private final NewsPipeline pipeline;
    private final NewsFeedService feed;
    private final NewsTemplateService templates;
    private final FrameworkServices framework;
    private final McNewsStore store;
    private final java.util.concurrent.Executor manualPollExecutor;
    private final online.yudream.base.plugin.mcnews.application.NewsFetchService fetch;

    public McNewsHttpFacade(McNewsSettings settings, NewsSourceService sources, NewsTargetService targets,
                            NewsSubscriptionService subscriptions, NewsPipeline pipeline, NewsFeedService feed,
                            NewsTemplateService templates, FrameworkServices framework, McNewsStore store,
                            java.util.concurrent.Executor manualPollExecutor,
                            online.yudream.base.plugin.mcnews.application.NewsFetchService fetch) {
        this.settings = settings;
        this.sources = sources;
        this.targets = targets;
        this.subscriptions = subscriptions;
        this.pipeline = pipeline;
        this.feed = feed;
        this.templates = templates;
        this.framework = framework;
        this.store = store;
        this.manualPollExecutor = manualPollExecutor;
        this.fetch = fetch;
    }

    // ---------- 管理端：设置 ----------

    public PluginHttpResponse getSettings() {
        Map<String, Object> view = new LinkedHashMap<>(settings.view());
        view.put("polling", pipeline.polling());
        return PluginHttpResponse.ok(view);
    }

    public PluginHttpResponse saveSettings(PluginHttpRequest request) {
        var body = Json.read(request.body());
        settings.update(
                body.boolOpt("enabled"),
                body.intOpt("pollIntervalMinutes"),
                body.intOpt("cacheSize"),
                body.boolOpt("pushOnFirstPoll"),
                body.boolOpt("aiEnabled"),
                body.textOpt("aiProviderCode"),
                body.textOpt("aiModelCode"),
                body.intOpt("aiMaxItems"),
                body.intOpt("aiContentMaxChars"),
                body.textOpt("aiSystemPrompt"),
                body.textOpt("messageTemplate"));
        return getSettings();
    }

    // ---------- 管理端：新闻源 ----------

    public PluginHttpResponse listSources() {
        return PluginHttpResponse.ok(pageView(sources.list().stream().map(McNewsHttpFacade::sourceView).toList()));
    }

    public PluginHttpResponse createSource(PluginHttpRequest request) {
        var body = Json.read(request.body());
        return HttpSupport.guard(() -> PluginHttpResponse.ok(sourceView(
                sources.create(body.text("name"), body.text("type"), body.text("url"),
                        body.stringList("keywords"), body.boolOr("enabled", true)))));
    }

    public PluginHttpResponse updateSource(PluginHttpRequest request, String id) {
        var body = Json.read(request.body());
        return HttpSupport.guard(() -> PluginHttpResponse.ok(sourceView(
                sources.update(id, body.textOpt("name"), body.textOpt("type"), body.textOpt("url"),
                        body.has("keywords") ? body.stringList("keywords") : null,
                        body.boolOpt("enabled")))));
    }

    public PluginHttpResponse deleteSource(String id) {
        return HttpSupport.guard(() -> {
            sources.delete(id);
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    // ---------- 管理端：推送目标 ----------

    public PluginHttpResponse listTargets() {
        return PluginHttpResponse.ok(pageView(targets.listGlobal().stream().map(McNewsHttpFacade::targetView).toList()));
    }

    public PluginHttpResponse createTarget(PluginHttpRequest request) {
        var body = Json.read(request.body());
        return HttpSupport.guard(() -> PluginHttpResponse.ok(targetView(targets.createGlobal(
                body.text("name"), body.text("type"), body.boolOpt("enabled"),
                body.textOpt("connectionId"), body.textOpt("channelId"), body.textOpt("channelName"),
                body.textOpt("webhookUrl"), body.headerList("headers"), body.boolOpt("markdown")))));
    }

    public PluginHttpResponse updateTarget(PluginHttpRequest request, String id) {
        var body = Json.read(request.body());
        return HttpSupport.guard(() -> PluginHttpResponse.ok(targetView(targets.updateGlobal(id,
                body.textOpt("name"), body.textOpt("type"), body.boolOpt("enabled"),
                body.textOpt("connectionId"), body.textOpt("channelId"), body.textOpt("channelName"),
                body.textOpt("webhookUrl"), body.has("headers") ? body.headerList("headers") : null,
                body.boolOpt("markdown")))));
    }

    public PluginHttpResponse deleteTarget(String id) {
        return HttpSupport.guard(() -> {
            targets.delete(id);
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    public PluginHttpResponse testTarget(String id) {
        return HttpSupport.guard(() -> {
            NewsTarget target = targets.require(id);
            if (target.ownerUserId() != null) {
                throw new IllegalArgumentException("个人推送目标请由用户本人测试");
            }
            var result = pipeline.testTarget(target);
            return PluginHttpResponse.ok(testView(result));
        });
    }

    // ---------- 管理端：新闻动态 / 推送记录 / 轮询 ----------

    public PluginHttpResponse pageNews(PluginHttpRequest request) {
        var result = feed.pageNews(HttpSupport.pageParam(request), HttpSupport.sizeParam(request, PAGE_FALLBACK),
                HttpSupport.first(request, "sourceId"), HttpSupport.first(request, "keyword"));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("records", result.records().stream().map(McNewsHttpFacade::articleView).toList());
        view.put("total", result.total());
        view.put("ignored", feed.tombstoneCount());
        view.put("seen", feed.seenCount());
        view.put("pending", feed.pendingCount());
        // 手动推送弹窗需要告知「同时发送给私信订阅用户」会影响多少人
        view.put("subscribers", subscriptions.directSubscribers().size());
        return PluginHttpResponse.ok(view);
    }

    public PluginHttpResponse deleteNews(String id) {
        return HttpSupport.guard(() -> {
            feed.deleteArticle(id);
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    /** 删除单条动态（id 走请求体）：新闻 id 含 `/`，放进路径会被网关按 %2F 拒绝（400）。 */
    public PluginHttpResponse deleteNews(PluginHttpRequest request) {
        var body = Json.read(request == null ? null : request.body());
        return deleteNews(body.text("id"));
    }

    /**
     * 清空动态。请求体两个可选项（缺省与非法值一律按 false 处理，兼容旧版前端只发 {}）：
     * {@code clearCache} 是否连轮询缓存（去重记忆 + 未送达队列）一并清空；
     * {@code pushOnNextPoll} 清空缓存后，下一次轮询是否把源里现存内容重新推送一轮。
     */
    public PluginHttpResponse clearNews(PluginHttpRequest request) {
        var body = Json.read(request == null ? null : request.body());
        boolean clearCache = body.boolOr("clearCache", false);
        boolean pushOnNextPoll = clearCache && body.boolOr("pushOnNextPoll", false);
        return HttpSupport.guard(() -> {
            NewsFeedService.ClearResult result = feed.clearArticles(clearCache, pushOnNextPoll);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("cleared", result.cleared());
            view.put("cacheCleared", result.cacheCleared());
            view.put("pushOnNextPoll", result.pushOnNextPoll());
            return PluginHttpResponse.ok(view);
        });
    }

    /**
     * 手动推送单条动态。请求体：{@code id} 必填；{@code targetIds} 可选（缺省或空数组＝全部可用目标，
     * 否则只推勾选的目标）；{@code includeSubscribers} 缺省/非法一律 false。
     *
     * <p>id 走请求体而不是路径：新闻 id 形如 {@code mcnet:article/xxx}，含 `:` 与 `/`，
     * 放进路径会被编码成 {@code %2F} 而被网关直接拒绝（400 Bad Request）。
     */
    public PluginHttpResponse pushNews(PluginHttpRequest request) {
        var body = Json.read(request == null ? null : request.body());
        return pushNews(body.text("id"), targetIds(body), body.boolOr("includeSubscribers", false));
    }

    /**
     * 兼容旧路径端点 {@code POST /admin/news/{id}/push}：id 里含 `/` 时该路径本身就无法通过网关，
     * 新前端一律走 {@link #pushNews(PluginHttpRequest)}。
     */
    public PluginHttpResponse pushNews(PluginHttpRequest request, String id) {
        var body = Json.read(request == null ? null : request.body());
        return pushNews(id, targetIds(body), body.boolOr("includeSubscribers", false));
    }

    private static List<String> targetIds(Json body) {
        return body.has("targetIds") ? body.stringList("targetIds") : List.of();
    }

    /** 手动推送的可选目标：与轮询同一套端点去重规则，被合并的次要目标数一并回传供前端提示。 */
    public PluginHttpResponse pushTargetOptions() {
        Map<String, Object> view = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        List<NewsTarget> globalEnabled = targets.listGlobalEnabled();
        List<NewsTarget> userEnabled = targets.listAllEnabled().stream()
                .filter(target -> target.ownerUserId() != null).toList();
        for (NewsPipeline.EndpointGroup group : NewsPipeline.endpointGroups(globalEnabled, userEnabled)) {
            NewsTarget target = group.primary();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", target.id());
            item.put("name", target.name());
            item.put("type", target.type());
            item.put("owner", target.ownerUserId() == null ? "global" : "user");
            item.put("endpointLabel", target.messaging()
                    ? "群聊 " + target.channelName()
                    : target.webhookUrl());
            item.put("mergedCount", group.memberIds().size() - 1);
            items.add(item);
        }
        view.put("targets", items);
        view.put("subscribers", subscriptions.directSubscribers().size());
        return PluginHttpResponse.ok(view);
    }

    private PluginHttpResponse pushNews(String id, List<String> targetIds, boolean includeSubscribers) {
        return HttpSupport.guard(() -> {
            NewsPipeline.PushOutcome outcome = pipeline.pushArticle(id, targetIds, includeSubscribers);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("ok", outcome.okCount() > 0);
            view.put("okCount", outcome.okCount());
            view.put("total", outcome.total());
            List<Map<String, Object>> results = new ArrayList<>();
            for (online.yudream.base.plugin.mcnews.domain.NewsPushLog.TargetResult result : outcome.results()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("targetId", result.targetId());
                entry.put("targetName", result.targetName());
                entry.put("targetType", result.targetType());
                entry.put("ok", result.ok());
                entry.put("error", result.error());
                results.add(entry);
            }
            view.put("results", results);
            return PluginHttpResponse.ok(view);
        });
    }

    public PluginHttpResponse clearNewsTombstones() {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of("cleared", feed.clearTombstones())));
    }

    public PluginHttpResponse pageLogs(PluginHttpRequest request) {
        var result = feed.pageLogs(HttpSupport.pageParam(request), HttpSupport.sizeParam(request, PAGE_FALLBACK));
        return PluginHttpResponse.ok(pageView(result.records().stream().map(McNewsHttpFacade::logView).toList(), result.total()));
    }

    /** 手动轮询（默认推送）。 */
    public PluginHttpResponse triggerPoll() {
        return triggerPoll(true);
    }

    /** 手动轮询：请求体 {@code push=false} 时本轮只回填列表与重建去重记录，不发送任何推送。 */
    public PluginHttpResponse triggerPoll(PluginHttpRequest request) {
        var body = Json.read(request == null ? null : request.body());
        return triggerPoll(body.boolOr("push", true));
    }

    private PluginHttpResponse triggerPoll(boolean push) {
        return HttpSupport.guard(() -> {
            if (pipeline.polling()) {
                throw new IllegalStateException("已有一次轮询正在进行，请稍后再试");
            }
            manualPollExecutor.execute(() -> {
                try {
                    pipeline.pollOnce("manual", push);
                }
                catch (RuntimeException e) {
                    // 竞态下被并发轮询拒绝属正常，忽略；其余失败已由 pipeline 记录状态
                }
            });
            return PluginHttpResponse.ok(Map.of("started", true));
        });
    }

    public PluginHttpResponse pollStatus() {
        Map<String, Object> view = new LinkedHashMap<>();
        long last = settings.lastPollAt();
        boolean enabled = settings.enabled();
        long next;
        if (!enabled) {
            next = 0;
        }
        else if (last <= 0) {
            // 从未轮询过：启用后首个检查点（1 分钟内）即触发
            next = System.currentTimeMillis();
        }
        else {
            next = last + settings.pollIntervalMinutes() * 60_000L;
        }
        long remainSeconds = enabled && next > 0 ? Math.max(0, (next - System.currentTimeMillis()) / 1000) : -1;
        view.put("lastPollAt", last);
        view.put("lastPollAtLabel", label(last));
        view.put("lastPollSummary", settings.lastPollSummary());
        view.put("polling", pipeline.polling());
        view.put("enabled", enabled);
        view.put("pollIntervalMinutes", settings.pollIntervalMinutes());
        view.put("nextPollAt", next);
        view.put("nextPollAtLabel", enabled && next > 0 ? label(next) : "");
        view.put("nextPollInSeconds", remainSeconds);
        return PluginHttpResponse.ok(view);
    }

    /** 在线抓取该源：返回源原始条数、关键词模板逐条命中情况与非法模板，供管理员调整模板。 */
    public PluginHttpResponse testSource(String id) {
        return HttpSupport.guard(() -> {
            NewsSource source = sources.require(id);
            long start = System.currentTimeMillis();
            List<NewsArticle> raw = fetch.parse(source);
            List<String> invalid = new ArrayList<>();
            NewsKeywordFilter filter = NewsKeywordFilter.lenient(source.keywords(), invalid);
            List<NewsArticle> items = filter.filter(raw);
            NewsKeywordFilter.Stats stats = filter.stats(raw);
            List<Map<String, Object>> titles = new ArrayList<>();
            for (NewsArticle item : items.subList(0, Math.min(5, items.size()))) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("title", item.title());
                entry.put("url", item.url());
                titles.add(entry);
            }
            List<Map<String, Object>> rules = new ArrayList<>();
            for (NewsKeywordFilter.RuleStat rule : stats.rules()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("rule", rule.rule());
                entry.put("kind", rule.kind());
                entry.put("field", rule.field());
                entry.put("exclude", rule.exclude());
                entry.put("hits", rule.hits());
                rules.add(entry);
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("ok", true);
            view.put("count", items.size());
            view.put("beforeFilter", stats.total());
            view.put("excluded", stats.excluded());
            view.put("rejected", stats.rejected());
            view.put("titles", titles);
            view.put("rules", rules);
            view.put("invalid", invalid);
            view.put("elapsedMs", System.currentTimeMillis() - start);
            return PluginHttpResponse.ok(view);
        });
    }

    // ---------- 管理端：选项 ----------

    public PluginHttpResponse connectionOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        for (PluginMessagingConnection connection : framework.messaging().connections()) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", connection.id());
            option.put("name", connection.name());
            option.put("platform", connection.platform());
            options.add(option);
        }
        return PluginHttpResponse.ok(options);
    }

    public PluginHttpResponse groupOptions(PluginHttpRequest request) {
        String connectionId = HttpSupport.first(request, "connectionId");
        if (connectionId.isBlank()) {
            return PluginHttpResponse.ok(List.of());
        }
        List<Map<String, Object>> options = new ArrayList<>();
        for (PluginMessagingGroup group : framework.messaging().groups(connectionId)) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", group.id());
            option.put("name", group.name());
            options.add(option);
        }
        return PluginHttpResponse.ok(options);
    }

    public PluginHttpResponse templateVariables() {
        List<Map<String, Object>> variables = new ArrayList<>();
        for (NewsTemplateService.Variable variable : NewsTemplateService.VARIABLES) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", variable.name());
            entry.put("description", variable.description());
            variables.add(entry);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("variables", variables);
        view.put("defaultTemplate", McNewsSettings.DEFAULT_MESSAGE_TEMPLATE);
        view.put("defaultAiPrompt", McNewsSettings.DEFAULT_AI_SYSTEM_PROMPT);
        view.put("preview", templates.preview(currentTemplate()));
        return PluginHttpResponse.ok(view);
    }

    public PluginHttpResponse previewTemplate(PluginHttpRequest request) {
        var body = Json.read(request.body());
        String template = body.textOr("template", currentTemplate());
        return PluginHttpResponse.ok(Map.of("preview", templates.preview(template)));
    }

    private String currentTemplate() {
        String template = store.find(McNewsStore.COL_SETTINGS, McNewsStore.DOC_SETTINGS)
                .map(doc -> McNewsStore.str(doc, "messageTemplate"))
                .orElse("");
        return template.isBlank() ? McNewsSettings.DEFAULT_MESSAGE_TEMPLATE : template;
    }

    // ---------- 用户端 ----------

    public PluginHttpResponse mySubscription(PluginHttpRequest request) {
        String userId = requireUserId(request);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("directEnabled", subscriptions.directEnabled(userId));
        view.put("webhooks", targets.listByOwner(userId).stream().map(McNewsHttpFacade::targetView).toList());
        view.put("maxWebhooks", NewsTargetService.MAX_USER_TARGETS);
        return PluginHttpResponse.ok(view);
    }

    public PluginHttpResponse setDirectEnabled(PluginHttpRequest request) {
        String userId = requireUserId(request);
        var body = Json.read(request.body());
        subscriptions.setDirectEnabled(userId, body.boolOr("enabled", false));
        return PluginHttpResponse.ok(Map.of("directEnabled", subscriptions.directEnabled(userId)));
    }

    public PluginHttpResponse createMyWebhook(PluginHttpRequest request) {
        String userId = requireUserId(request);
        var body = Json.read(request.body());
        return HttpSupport.guard(() -> PluginHttpResponse.ok(targetView(targets.createUserWebhook(userId,
                body.text("name"), body.text("url"), body.headerList("headers"),
                body.boolOr("enabled", true)))));
    }

    public PluginHttpResponse updateMyWebhook(PluginHttpRequest request, String id) {
        String userId = requireUserId(request);
        var body = Json.read(request.body());
        return HttpSupport.guard(() -> PluginHttpResponse.ok(targetView(targets.updateOwned(id, userId,
                body.textOpt("name"), body.textOpt("url"),
                body.has("headers") ? body.headerList("headers") : null,
                body.boolOpt("enabled")))));
    }

    public PluginHttpResponse deleteMyWebhook(PluginHttpRequest request, String id) {
        String userId = requireUserId(request);
        return HttpSupport.guard(() -> {
            targets.deleteOwned(id, userId);
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    public PluginHttpResponse testMyWebhook(PluginHttpRequest request, String id) {
        String userId = requireUserId(request);
        return HttpSupport.guard(() -> PluginHttpResponse.ok(testView(
                pipeline.testTarget(targets.requireOwned(id, userId)))));
    }

    public PluginHttpResponse testMyDirect(PluginHttpRequest request) {
        String userId = requireUserId(request);
        return HttpSupport.guard(() -> PluginHttpResponse.ok(testView(pipeline.testDirect(userId))));
    }

    // ---------- 视图组装 ----------

    private static Map<String, Object> pageView(List<Map<String, Object>> records) {
        return pageView(records, records.size());
    }

    private static Map<String, Object> pageView(List<Map<String, Object>> records, long total) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("records", records);
        view.put("total", total);
        return view;
    }

    private static Map<String, Object> sourceView(NewsSource source) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", source.id());
        view.put("name", source.name());
        view.put("type", source.type());
        view.put("url", source.url());
        view.put("keywords", source.keywords());
        view.put("enabled", source.enabled());
        view.put("builtin", source.builtin());
        view.put("createdAtLabel", source.createdAt() <= 0 ? "" : label(source.createdAt()));
        return view;
    }

    private static Map<String, Object> targetView(NewsTarget target) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", target.id());
        view.put("name", target.name());
        view.put("type", target.type());
        view.put("enabled", target.enabled());
        view.put("connectionId", target.connectionId());
        view.put("channelId", target.channelId());
        view.put("channelName", target.channelName());
        view.put("webhookUrl", target.webhookUrl());
        List<Map<String, Object>> headers = new ArrayList<>();
        for (NewsTarget.WebhookHeader header : target.headers()) {
            headers.add(Map.of("key", header.key(), "value", header.value()));
        }
        view.put("headers", headers);
        view.put("markdown", target.markdown());
        return view;
    }

    private static Map<String, Object> articleView(online.yudream.base.plugin.mcnews.domain.NewsArticle article) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", article.id());
        view.put("sourceId", article.sourceId());
        view.put("sourceName", article.sourceName());
        view.put("title", article.title());
        view.put("summary", article.summary());
        view.put("aiSummary", article.aiSummary());
        view.put("category", article.category());
        view.put("url", article.url());
        view.put("imageUrl", article.imageUrl());
        view.put("publishedAt", article.publishedAt());
        view.put("publishedAtLabel", label(article.publishedAt()));
        view.put("discoveredAt", article.discoveredAt());
        view.put("discoveredAtLabel", label(article.discoveredAt()));
        view.put("pushedAt", article.pushedAt());
        view.put("pushedAtLabel", label(article.pushedAt()));
        view.put("pushState", article.pushState());
        return view;
    }

    private static Map<String, Object> logView(online.yudream.base.plugin.mcnews.domain.NewsPushLog log) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", log.id());
        view.put("mode", log.mode());
        view.put("title", log.title());
        view.put("url", log.url());
        view.put("sourceName", log.sourceName());
        view.put("total", log.total());
        view.put("okCount", log.okCount());
        view.put("createdAt", log.createdAt());
        view.put("createdAtLabel", label(log.createdAt()));
        List<Map<String, Object>> results = new ArrayList<>();
        for (var result : log.results()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("targetId", result.targetId());
            entry.put("targetName", result.targetName());
            entry.put("targetType", result.targetType());
            entry.put("ok", result.ok());
            entry.put("error", result.error());
            results.add(entry);
        }
        view.put("results", results);
        return view;
    }

    private static Map<String, Object> testView(online.yudream.base.plugin.mcnews.domain.NewsPushLog.TargetResult result) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("ok", result.ok());
        view.put("error", result.error());
        return view;
    }

    private static String label(long epochMillis) {
        if (epochMillis <= 0) {
            return "";
        }
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(epochMillis));
    }

    private static String requireUserId(PluginHttpRequest request) {
        var principal = request.principal();
        if (principal == null || principal.userId() == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return String.valueOf(principal.userId());
    }

    /** 请求体 JSON 的容错读取工具。 */
    static final class Json {
        private final com.fasterxml.jackson.databind.JsonNode node;

        private Json(com.fasterxml.jackson.databind.JsonNode node) {
            this.node = node;
        }

        static Json read(String body) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                return new Json(mapper.readTree(body == null || body.isBlank() ? "{}" : body));
            }
            catch (Exception e) {
                throw new IllegalArgumentException("请求体不是有效的 JSON");
            }
        }

        boolean has(String field) {
            return node.has(field) && !node.get(field).isNull();
        }

        String text(String field) {
            String value = textOpt(field);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("字段 " + field + " 不能为空");
            }
            return value.trim();
        }

        String textOpt(String field) {
            return has(field) ? node.get(field).asText("") : null;
        }

        String textOr(String field, String fallback) {
            String value = textOpt(field);
            return value == null || value.isBlank() ? fallback : value;
        }

        Boolean boolOpt(String field) {
            return has(field) && node.get(field).isBoolean() ? node.get(field).asBoolean() : null;
        }

        boolean boolOr(String field, boolean fallback) {
            Boolean value = boolOpt(field);
            return value == null ? fallback : value;
        }

        Integer intOpt(String field) {
            return has(field) && node.get(field).isNumber() ? node.get(field).asInt() : null;
        }

        List<String> stringList(String field) {
            List<String> result = new ArrayList<>();
            if (has(field) && node.get(field).isArray()) {
                for (var item : node.get(field)) {
                    if (item.isTextual() && !item.asText().isBlank()) {
                        result.add(item.asText().trim());
                    }
                }
            }
            return result;
        }

        List<NewsTarget.WebhookHeader> headerList(String field) {
            List<NewsTarget.WebhookHeader> result = new ArrayList<>();
            if (has(field) && node.get(field).isArray()) {
                for (var item : node.get(field)) {
                    String key = item.path("key").asText("");
                    if (!key.isBlank()) {
                        result.add(new NewsTarget.WebhookHeader(key.trim(), item.path("value").asText("")));
                    }
                }
            }
            return result;
        }
    }
}
