package online.yudream.base.plugin.mcnews.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import online.yudream.base.plugin.mcnews.domain.NewsArticle;
import online.yudream.base.plugin.mcnews.domain.NewsPushLog;
import online.yudream.base.plugin.mcnews.domain.NewsSource;
import online.yudream.base.plugin.mcnews.domain.NewsTarget;
import online.yudream.base.plugin.mcnews.infrastructure.McNewsStore;
import online.yudream.base.plugin.mcnews.infrastructure.NewsCacheStore;

/**
 * 轮询编排：抓取全部启用源 → 与已见缓存对比 → 新条目经 AI 整合、模板渲染后推送到
 * 全局目标（群聊/Webhook）、用户私信订阅与个人 Webhook → 回写缓存与推送日志。
 * 缓存只保留最近 cacheSize 条；首次运行默认只建缓存不推送（pushOnFirstPoll 可改）。
 */
public final class NewsPipeline {
    private static final Logger LOGGER = Logger.getLogger(NewsPipeline.class.getName());

    private final McNewsStore store;
    private final McNewsSettings settings;
    private final NewsSourceService sources;
    private final NewsTargetService targets;
    private final NewsSubscriptionService subscriptions;
    private final NewsFetchService fetch;
    private final NewsTemplateService templates;
    private final NewsAiSummarizer ai;
    private final NewsPushService push;
    private final NewsCacheStore cache;

    private final AtomicBoolean polling = new AtomicBoolean(false);

    public NewsPipeline(McNewsStore store, McNewsSettings settings, NewsSourceService sources,
                        NewsTargetService targets, NewsSubscriptionService subscriptions,
                        NewsFetchService fetch, NewsTemplateService templates, NewsAiSummarizer ai,
                        NewsPushService push) {
        this.store = store;
        this.settings = settings;
        this.sources = sources;
        this.targets = targets;
        this.subscriptions = subscriptions;
        this.fetch = fetch;
        this.templates = templates;
        this.ai = ai;
        this.push = push;
        this.cache = new NewsCacheStore(store);
    }

    public boolean polling() {
        return polling.get();
    }

    /** 用最新缓存新闻（无则用示例数据）向指定目标发送测试推送，并记录 test 日志。 */
    public NewsPushLog.TargetResult testTarget(NewsTarget target) {
        NewsArticle sample = latestOrSample();
        String content = templates.render(settings.messageTemplate(), sample);
        NewsPushLog.TargetResult result = push.sendToTarget(target, content,
                push.webhookPayload("mc.news.test", sample, content));
        recordTestLog(sample, result);
        return result;
    }

    /** 向指定用户发送测试私信（验证绑定与订阅链路），并记录 test 日志。 */
    public NewsPushLog.TargetResult testDirect(String userId) {
        NewsArticle sample = latestOrSample();
        String content = templates.render(settings.messageTemplate(), sample);
        NewsPushLog.TargetResult result = push.sendDirect(userId, content);
        recordTestLog(sample, result);
        return result;
    }

    private NewsArticle latestOrSample() {
        List<NewsArticle> cached = cache.read().items();
        if (!cached.isEmpty()) {
            return cached.get(0);
        }
        return new NewsArticle("sample", "mcnet-news", "Minecraft 官网新闻",
                "Minecraft Java 版测试新闻",
                "A Minecraft Java Release",
                "这是一条测试推送：当前缓存还没有新闻，使用示例数据展示模板效果。",
                "News", "https://www.minecraft.net/zh-hans/article", "", 0,
                System.currentTimeMillis(), 0, NewsArticle.STATE_PENDING);
    }

    private void recordTestLog(NewsArticle sample, NewsPushLog.TargetResult result) {
        saveLog(new NewsPushLog(logId(System.currentTimeMillis()), System.currentTimeMillis(), "test",
                sample.id(), sample.title(), sample.url(), sample.sourceName(), 1, result.ok() ? 1 : 0,
                List.of(result)));
    }

    /** 触发一次轮询；已有轮询进行中时抛出业务错误（手动触发场景提示重复）。 */
    public PollOutcome pollOnce(String mode) {
        if (!polling.compareAndSet(false, true)) {
            throw new IllegalStateException("已有一次轮询正在进行，请稍后再试");
        }
        try {
            return doPoll(mode);
        }
        finally {
            polling.set(false);
        }
    }

    private PollOutcome doPoll(String mode) {
        long startedAt = System.currentTimeMillis();
        List<NewsSource> enabledSources = sources.listEnabled();
        if (enabledSources.isEmpty()) {
            settings.recordPoll(startedAt, "没有启用的新闻源，跳过轮询");
            return new PollOutcome(0, 0, 0);
        }

        List<NewsArticle> candidates = new ArrayList<>();
        int fetched = 0;
        List<String> failures = new ArrayList<>();
        for (NewsSource source : enabledSources) {
            try {
                List<NewsArticle> items = fetch.fetch(source);
                fetched += items.size();
                candidates.addAll(items);
            }
            catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "[MC News] 新闻源抓取失败：" + source.name(), e);
                failures.add(source.name() + "：" + e.getMessage());
            }
        }

        NewsCacheStore.Snapshot snapshot = cache.read();
        List<NewsArticle> cached = snapshot.items();
        // 墓碑 ID 直接预置进已知集合：被手动删除的新闻不再视为新内容
        Set<String> knownIds = new HashSet<>(snapshot.tombstones());
        for (NewsArticle item : cached) {
            knownIds.add(item.id());
        }
        List<NewsArticle> newItems = new ArrayList<>();
        for (NewsArticle item : candidates) {
            if (knownIds.add(item.id())) {
                newItems.add(item);
            }
        }

        long ignoredCount = snapshot.tombstones().isEmpty() ? 0
                : candidates.stream().filter(item -> snapshot.tombstones().contains(item.id())).count();
        if (newItems.isEmpty()) {
            String summary = "检查了 " + fetched + " 条内容，没有新新闻"
                    + (ignoredCount > 0 ? "（" + ignoredCount + " 条在忽略名单中，不会推送）" : "")
                    + (failures.isEmpty() ? "" : "；源失败：" + String.join("、", failures));
            settings.recordPoll(startedAt, summary);
            return new PollOutcome(0, 0, fetched);
        }

        int pushCount = 0;
        int pushOk = 0;
        if (cached.isEmpty() && !settings.pushOnFirstPoll()) {
            // 首次运行只建立基线，避免装好即轰炸
            cache.writeItems(annotate(newItems, NewsArticle.STATE_SKIPPED, 0, null));
            settings.recordPoll(startedAt, "首次运行：建立缓存 " + newItems.size() + " 条基线，未推送"
                    + (failures.isEmpty() ? "" : "；源失败：" + String.join("、", failures)));
            return new PollOutcome(newItems.size(), 0, fetched);
        }

        List<NewsArticle> oldestFirst = new ArrayList<>(newItems);
        java.util.Collections.reverse(oldestFirst);
        List<NewsTarget> globalTargets = targets.listGlobalEnabled();
        List<NewsTarget> userWebhooks = targets.listAllEnabled().stream()
                .filter(target -> target.ownerUserId() != null).toList();
        List<String> directUsers = subscriptions.directSubscribers();
        int aiQuota = settings.aiMaxItems();

        for (NewsArticle item : oldestFirst) {
            String traceId = "poll-" + UUID.randomUUID().toString().substring(0, 8);
            NewsArticle enriched = item;
            if (settings.aiEnabled() && aiQuota > 0) {
                aiQuota--;
                // 仅对进入 AI 整合的条目抓正文：修复项、变更点等具体信息从正文提炼
                String content = fetch.fetchArticleContent(item);
                enriched = withAiSummary(item, ai.summarize(item, content, mode, traceId).orElse(""));
            }
            String content = templates.render(settings.messageTemplate(), enriched);
            List<NewsPushLog.TargetResult> results = new ArrayList<>();
            for (NewsTarget target : globalTargets) {
                results.add(push.sendToTarget(target, content, push.webhookPayload("mc.news.new", enriched, content)));
            }
            for (NewsTarget webhook : userWebhooks) {
                results.add(push.sendToTarget(webhook, content, push.webhookPayload("mc.news.new", enriched, content)));
            }
            for (String userId : directUsers) {
                results.add(push.sendDirect(userId, content));
            }
            int okCount = (int) results.stream().filter(NewsPushLog.TargetResult::ok).count();
            String state = results.isEmpty() ? NewsArticle.STATE_SKIPPED
                    : (okCount > 0 ? NewsArticle.STATE_PUSHED : NewsArticle.STATE_FAILED);
            saveLog(new NewsPushLog(logId(startedAt), System.currentTimeMillis(), mode,
                    item.id(), item.title(), item.url(), item.sourceName(), results.size(), okCount, results));
            pushCount++;
            if (okCount > 0) {
                pushOk++;
            }
            enriched = new NewsArticle(enriched.id(), enriched.sourceId(), enriched.sourceName(), enriched.title(),
                    enriched.summary(), enriched.aiSummary(), enriched.category(), enriched.url(), enriched.imageUrl(),
                    enriched.publishedAt(), enriched.discoveredAt(), System.currentTimeMillis(), state);
            cached.add(0, enriched);
            // 推送一条立即落库一条：轮询中途被打断（重载/重启）时已推送内容不会重复
            cache.writeItems(cached);
        }
        // 裁剪到配置上限后写最终状态
        while (cached.size() > settings.cacheSize()) {
            cached.remove(cached.size() - 1);
        }
        cache.writeItems(cached);
        settings.recordPoll(startedAt, "发现 " + newItems.size() + " 条新内容，推送成功 " + pushOk + "/" + pushCount
                + (failures.isEmpty() ? "" : "；源失败：" + String.join("、", failures)));
        return new PollOutcome(newItems.size(), pushOk, fetched);
    }

    private NewsArticle withAiSummary(NewsArticle item, String aiSummary) {
        return new NewsArticle(item.id(), item.sourceId(), item.sourceName(), item.title(), item.summary(),
                aiSummary, item.category(), item.url(), item.imageUrl(), item.publishedAt(), item.discoveredAt(),
                item.pushedAt(), item.pushState());
    }

    private List<NewsArticle> annotate(List<NewsArticle> items, String state, long pushedAt, String aiSummary) {
        List<NewsArticle> result = new ArrayList<>();
        for (NewsArticle item : items) {
            result.add(new NewsArticle(item.id(), item.sourceId(), item.sourceName(), item.title(), item.summary(),
                    aiSummary == null ? item.aiSummary() : aiSummary, item.category(), item.url(), item.imageUrl(),
                    item.publishedAt(), item.discoveredAt(), pushedAt, state));
        }
        return result;
    }

    private void saveLog(NewsPushLog log) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", log.id());
        doc.put("createdAt", log.createdAt());
        doc.put("mode", log.mode());
        doc.put("articleId", log.articleId());
        doc.put("title", log.title());
        doc.put("url", log.url());
        doc.put("sourceName", log.sourceName());
        doc.put("total", log.total());
        doc.put("okCount", log.okCount());
        List<Map<String, Object>> results = new ArrayList<>();
        for (NewsPushLog.TargetResult result : log.results()) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("targetId", result.targetId());
            entry.put("targetName", result.targetName());
            entry.put("targetType", result.targetType());
            entry.put("ok", result.ok());
            if (result.error() != null && !result.error().isBlank()) {
                entry.put("error", result.error());
            }
            results.add(entry);
        }
        doc.put("results", results);
        store.save(McNewsStore.COL_LOGS, log.id(), doc);
    }

    /** 字典序即时间倒序，findAll 第一页即最新记录。 */
    static String logId(long timestamp) {
        return String.format("%019d", Long.MAX_VALUE - timestamp) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static NewsArticle articleFromDoc(Map<String, Object> doc) {
        String id = McNewsStore.str(doc, "id");
        if (id.isBlank()) {
            return null;
        }
        return new NewsArticle(id,
                McNewsStore.str(doc, "sourceId"),
                McNewsStore.str(doc, "sourceName"),
                McNewsStore.str(doc, "title"),
                McNewsStore.str(doc, "summary"),
                McNewsStore.str(doc, "aiSummary"),
                McNewsStore.str(doc, "category"),
                McNewsStore.str(doc, "url"),
                McNewsStore.str(doc, "imageUrl"),
                McNewsStore.longOr(doc, "publishedAt", 0),
                McNewsStore.longOr(doc, "discoveredAt", 0),
                McNewsStore.longOr(doc, "pushedAt", 0),
                McNewsStore.strOr(doc, "pushState", NewsArticle.STATE_PENDING));
    }

    public static Map<String, Object> articleToDoc(NewsArticle item) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", item.id());
        doc.put("sourceId", item.sourceId());
        doc.put("sourceName", item.sourceName());
        doc.put("title", item.title());
        if (item.summary() != null && !item.summary().isBlank()) {
            doc.put("summary", item.summary());
        }
        if (item.aiSummary() != null && !item.aiSummary().isBlank()) {
            doc.put("aiSummary", item.aiSummary());
        }
        if (item.category() != null && !item.category().isBlank()) {
            doc.put("category", item.category());
        }
        doc.put("url", item.url());
        if (item.imageUrl() != null && !item.imageUrl().isBlank()) {
            doc.put("imageUrl", item.imageUrl());
        }
        doc.put("publishedAt", item.publishedAt());
        doc.put("discoveredAt", item.discoveredAt());
        doc.put("pushedAt", item.pushedAt());
        doc.put("pushState", item.pushState());
        return doc;
    }

    /** 一次轮询的统计结果。 */
    public record PollOutcome(int newCount, int pushOk, int fetched) {
    }
}
