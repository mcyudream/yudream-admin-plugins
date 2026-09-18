package online.yudream.base.plugin.mcnews.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import online.yudream.base.plugin.mcnews.domain.NewsArticle;
import online.yudream.base.plugin.mcnews.domain.NewsFingerprint;
import online.yudream.base.plugin.mcnews.domain.NewsPushLog;
import online.yudream.base.plugin.mcnews.domain.NewsSource;
import online.yudream.base.plugin.mcnews.domain.NewsTarget;
import online.yudream.base.plugin.mcnews.infrastructure.McNewsStore;
import online.yudream.base.plugin.mcnews.infrastructure.NewsCacheStore;

/**
 * 轮询编排：抓取全部启用源 → 与去重记忆（标题 / 链接指纹）对比 → 新条目经 AI 整合、模板渲染后推送到
 * 全局目标（群聊/Webhook）、用户私信订阅与个人 Webhook → 回写展示缓存、去重记忆与推送日志。
 *
 * <p>去重规则：候选条目的原始 id、规范化标题、规范化链接<strong>任一</strong>命中历史记录即视为同一篇，不再推送。
 * 但「见过」不等于「推过」：推送失败、或推送时没有任何可用目标的条目会记入未送达队列
 * （{@code pending}，见 {@link NewsCacheStore}），后续轮询继续补推，直到至少一个目标送达成功。
 * 历史记录（{@code seen}）独立于展示缓存（{@code items}）：展示缓存按 cacheSize 裁剪，
 * 去重记忆上限 {@link NewsCacheStore#MAX_SEEN_ENTRIES}，因此旧新闻不会因为展示缓存被裁剪而重新推送。
 * 源里仍在、但已从展示列表消失的旧条目只回填列表（标记重复未推），不会推送；
 * 若它还在未送达队列里，则仍然会补推。
 *
 * <p>推送目标按「端点」去重：全局目标与个人 Webhook 指向同一地址（或同一群聊频道）时只投递一次，
 * 否则同一篇新闻会在一次轮询里对同一接收端重复送达。
 *
 * <p>「手动推送」（{@link #pushArticle}）复用轮询同一套消息模板与目标（同样按端点去重）：
 * 日志记为 {@code push}，可按 id 勾选只投给部分目标，也可选是否附带发送给私信订阅用户；
 * 结果同样写回列表状态与未送达队列。
 *
 * <p>「手动轮询」可关闭推送（{@link #pollOnce(String, boolean)}）：本轮只回填列表与重建去重记录，
 * 一条都不发送——用于只想看看各源当前有什么内容的场景。
 *
 * <p>首次运行（展示缓存与去重记忆都为空）默认只建基线不推送（pushOnFirstPoll 可改）；
 * 管理端清空轮询缓存时同样可以选择下一次轮询是否推送：不推送则只重建基线（存量内容回填列表 + 重建去重记录），
 * 推送则存量内容被当作新内容重新推送一轮。基线时间与 baselineOnly 标记见 {@link NewsCacheStore}。
 */
public final class NewsPipeline {
    private static final Logger LOGGER = Logger.getLogger(NewsPipeline.class.getName());

    /** 单轮最多补推的未送达条目数：目标长时间不可用时，避免一次补推把整轮轮询拖住。 */
    private static final int MAX_RETRY_PER_POLL = 20;

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

    /** 推送到全部可用目标（管理端未勾选具体目标时的默认行为）。 */
    public PushOutcome pushArticle(String id, boolean includeSubscribers) {
        return pushArticle(id, List.of(), includeSubscribers);
    }

    /**
     * 手动推送单条动态（管理端「新闻动态 → 推送」）：复用轮询同一套消息模板与推送目标
     * （同样按端点去重，同一接收端不会收到两条），推送日志的 mode 记为 {@code push}。
     *
     * <p>{@code targetIds} 为空表示推送到全部可用目标；非空时只推送到勾选的目标。
     * 勾选按端点内部成员判定：同一端点被合并掉的次要目标 id 也认（勾选它与勾选主目标等价），
     * 因此「只勾了被合并的那个」不会变成空推送。
     *
     * <p>{@code includeSubscribers=true} 时额外发送给开启私信订阅的用户；默认不发送——
     * 手动补推的多是历史内容，避免打扰全部订阅者。
     *
     * <p>结果与轮询同源：送达成功就地刷新列表状态并移出未送达队列，失败则记入队列交给后续轮询补推；
     * 列表顺序不变（手动推送的老条目不会被顶到最前面）。没有任何可用目标（或所选目标都已停用）时
     * 直接拒绝（400），而不是静默地什么都不做。
     */
    public PushOutcome pushArticle(String id, List<String> targetIds, boolean includeSubscribers) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("新闻 ID 不能为空");
        }
        NewsCacheStore.Snapshot snapshot = cache.read();
        NewsArticle item = null;
        for (NewsArticle candidate : snapshot.items()) {
            if (id.equals(candidate.id())) {
                item = candidate;
                break;
            }
        }
        if (item == null) {
            throw new IllegalArgumentException("该新闻不在动态缓存中，可能已被删除");
        }
        if (!Collections.disjoint(NewsFingerprint.keysOf(item), snapshot.ignoredKeys())) {
            throw new IllegalArgumentException("该新闻已被删除，不会再推送");
        }
        List<EndpointGroup> available = endpointGroups(targets.listGlobalEnabled(),
                targets.listAllEnabled().stream().filter(target -> target.ownerUserId() != null).toList());
        boolean allTargets = targetIds == null || targetIds.isEmpty();
        Set<String> selectedIds = allTargets ? Set.of() : new HashSet<>(targetIds);
        List<NewsTarget> deliveryTargets = new ArrayList<>();
        for (EndpointGroup group : available) {
            if (allTargets || group.selectedBy(selectedIds)) {
                deliveryTargets.add(group.primary());
            }
        }
        List<String> directUsers = includeSubscribers ? subscriptions.directSubscribers() : List.of();
        if (deliveryTargets.isEmpty() && directUsers.isEmpty()) {
            throw new IllegalStateException(allTargets
                    ? (includeSubscribers
                    ? "没有可用的推送目标，请先在「推送目标」中启用一个目标"
                    : "没有可用的推送目标：请先在「推送目标」中启用目标，或勾选同时发送给私信订阅用户")
                    : "所选推送目标都已停用或不存在，请重新选择");
        }

        String content = templates.render(settings.messageTemplate(), item);
        Map<String, Object> payload = push.webhookPayload("mc.news.new", item, content);
        List<NewsPushLog.TargetResult> results = new ArrayList<>();
        for (NewsTarget target : deliveryTargets) {
            results.add(push.sendToTarget(target, content, payload));
        }
        for (String userId : directUsers) {
            results.add(push.sendDirect(userId, content));
        }
        int okCount = (int) results.stream().filter(NewsPushLog.TargetResult::ok).count();
        long pushedAt = System.currentTimeMillis();
        saveLog(new NewsPushLog(logId(pushedAt), pushedAt, "push", item.id(), item.title(), item.url(),
                item.sourceName(), results.size(), okCount, results));
        List<String> pending = new ArrayList<>(snapshot.pending());
        pending = okCount > 0 ? dropPending(pending, item) : addPending(pending, item);
        cache.writePoll(withState(snapshot.items(), item.id(),
                okCount > 0 ? NewsArticle.STATE_PUSHED : NewsArticle.STATE_FAILED, pushedAt),
                snapshot.seen(), pending);
        return new PushOutcome(item.id(), okCount, results.size(), results);
    }

    /** 触发一次轮询（默认推送）；已有轮询进行中时抛出业务错误（手动触发场景提示重复）。 */
    public PollOutcome pollOnce(String mode) {
        return pollOnce(mode, true);
    }

    /**
     * 触发一次轮询；{@code pushEnabled=false} 时本轮只回填列表与重建去重记录，不发送任何推送
     * （管理端「立即轮询」可关闭推送）。关闭推送的轮询同样会把源里内容登记进去重记忆，
     * 因此这些内容在后续轮询里不会又被当成新内容推一遍——等价于重建一次基线。
     */
    public PollOutcome pollOnce(String mode, boolean pushEnabled) {
        if (!polling.compareAndSet(false, true)) {
            throw new IllegalStateException("已有一次轮询正在进行，请稍后再试");
        }
        try {
            return doPoll(mode, pushEnabled);
        }
        finally {
            polling.set(false);
        }
    }

    private PollOutcome doPoll(String mode, boolean pushEnabled) {
        long startedAt = System.currentTimeMillis();
        List<NewsSource> enabledSources = sources.listEnabled();
        if (enabledSources.isEmpty()) {
            settings.recordPoll(startedAt, "没有启用的新闻源");
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
        List<NewsArticle> cached = new ArrayList<>(snapshot.items());
        List<String> seen = new ArrayList<>(snapshot.seen());
        Set<String> known = snapshot.knownKeys();
        // 展示列表内的条目一并计入已知：升级前的历史缓存没有记进去重记忆，不能误判为新内容重推
        for (NewsArticle item : cached) {
            known.addAll(NewsFingerprint.keysOf(item));
        }
        Set<String> ignored = snapshot.ignoredKeys();
        boolean firstRun = snapshot.fresh() && cached.isEmpty();
        // 清空轮询缓存时选择「下一次轮询不推送」留下的标记：只重建基线，与 pushOnFirstPoll 设置无关
        boolean baselineOnly = snapshot.baselineOnly() && cached.isEmpty();
        // 未送达队列：推送失败、或推送时没有可用目标的条目，本轮继续补推
        Set<String> undelivered = snapshot.pendingKeys();
        List<String> pending = new ArrayList<>(snapshot.pending());

        List<NewsArticle> newItems = new ArrayList<>();
        List<NewsArticle> repeats = new ArrayList<>();
        List<NewsArticle> retries = new ArrayList<>();
        Set<String> retryIds = new HashSet<>();
        int ignoredCount = 0;
        for (NewsArticle item : candidates) {
            Set<String> keys = NewsFingerprint.keysOf(item);
            if (!Collections.disjoint(keys, ignored)) {
                ignoredCount++;
                continue;
            }
            if (Collections.disjoint(keys, known)) {
                newItems.add(item);
            }
            else {
                repeats.add(item);
                // 见过、但没送达成功：本轮补推（同一批内按 id 折叠一次）
                if (item.id() != null && !item.id().isBlank() && !Collections.disjoint(keys, undelivered)
                        && retryIds.add(item.id())) {
                    retries.add(item);
                }
            }
            // 同一批候选内的重复（多语言前缀、多源同 feed）也一并折叠
            known.addAll(keys);
        }
        // 去重记忆独立于展示缓存：本轮见到的条目全部登记（含随后被展示缓存裁掉的），
        // 按注册先后追加在尾部（尾部最新，超限时先淘汰最旧记忆）
        Set<String> registeredIds = NewsFingerprint.entryIds(seen);
        for (int i = candidates.size() - 1; i >= 0; i--) {
            NewsArticle item = candidates.get(i);
            if (item.id() == null || item.id().isBlank() || !registeredIds.add(item.id())) {
                continue;
            }
            if (Collections.disjoint(NewsFingerprint.keysOf(item), ignored)) {
                seen.add(NewsFingerprint.entry(item));
            }
        }

        if (newItems.isEmpty() && retries.isEmpty()) {
            cache.writePoll(mergeDisplay(List.of(), cached, backfill(repeats, cached), settings.cacheSize()), seen,
                    pending);
            settings.recordPoll(startedAt, summary("抓取 " + fetched + " 条", "无新内容",
                    countSegment(repeats.size(), "跳过重复"), countSegment(ignoredCount, "忽略"),
                    failureSegment(failures)));
            return new PollOutcome(0, 0, fetched);
        }

        List<NewsArticle> backfill = backfill(repeats, cached);
        if (!pushEnabled) {
            // 手动轮询关闭推送：新条目只回填列表 + 登记去重记忆，一条都不发送；
            // 未送达队列原样保留（关掉推送不代表放弃补推，下一次正常轮询仍会继续补）
            List<NewsArticle> kept = annotate(newItems, NewsArticle.STATE_SKIPPED, 0, null);
            cache.writePollBaseline(mergeDisplay(kept, cached, backfill, settings.cacheSize()), seen, pending);
            settings.recordPoll(startedAt, summary("抓取 " + fetched + " 条",
                    "新增 " + newItems.size() + " 条", "未推送（本次已关闭推送）",
                    countSegment(repeats.size(), "跳过重复"), countSegment(ignoredCount, "忽略"),
                    failureSegment(failures)));
            return new PollOutcome(newItems.size(), 0, fetched);
        }
        if (baselineOnly || (firstRun && !settings.pushOnFirstPoll())) {
            // 只建立基线（展示缓存 + 去重记忆）：首次运行避免装好即轰炸；
            // 清空轮询缓存且选择「不推送」时同理，且不受 pushOnFirstPoll 影响
            List<NewsArticle> baseline = annotate(newItems, NewsArticle.STATE_SKIPPED, 0, null);
            if (baselineOnly) {
                cache.writePollBaseline(baseline, seen, pending);
            }
            else {
                cache.writePoll(baseline, seen, pending);
            }
            settings.recordPoll(startedAt, summary(
                    (baselineOnly ? "清空缓存后仅重建基线 " : "首次运行仅建立基线 ") + newItems.size()
                            + " 条（未推送）",
                    failureSegment(failures)));
            return new PollOutcome(newItems.size(), 0, fetched);
        }

        // 先补推此前未送达的旧条目（单轮限量：目标长时间不可用时不会把整轮轮询拖住），
        // 再按时间正序推送本轮新内容；两类可能在同一次轮询里同时出现
        List<NewsArticle> retryQueue = retries.size() > MAX_RETRY_PER_POLL
                ? new ArrayList<>(retries.subList(0, MAX_RETRY_PER_POLL))
                : new ArrayList<>(retries);
        Collections.reverse(retryQueue);
        Set<String> queueRetryIds = new HashSet<>();
        for (NewsArticle item : retryQueue) {
            queueRetryIds.add(item.id());
        }
        List<NewsArticle> queue = new ArrayList<>(retryQueue);
        List<NewsArticle> oldestFirst = new ArrayList<>(newItems);
        Collections.reverse(oldestFirst);
        queue.addAll(oldestFirst);

        List<NewsTarget> globalTargets = targets.listGlobalEnabled();
        List<NewsTarget> userWebhooks = targets.listAllEnabled().stream()
                .filter(target -> target.ownerUserId() != null).toList();
        // 同一篇新闻一轮只往「同一个端点」投一次：全局目标与个人 Webhook 指向同一地址（或同一群聊频道）时
        // 各投一条，接收端会收到两条一模一样的推送，看起来就是「重复推送」
        List<NewsTarget> deliveryTargets = distinctEndpoints(globalTargets, userWebhooks);
        int duplicateTargets = globalTargets.size() + userWebhooks.size() - deliveryTargets.size();
        List<String> directUsers = subscriptions.directSubscribers();
        int aiQuota = settings.aiMaxItems();
        List<NewsArticle> pushed = new ArrayList<>();

        int pushCount = 0;
        int pushOk = 0;
        int retryOk = 0;
        for (NewsArticle item : queue) {
            // 推送前复查忽略名单：轮询途中被手动删除的条目不推送（只删一次即彻底生效）
            if (!Collections.disjoint(NewsFingerprint.keysOf(item), cache.ignoredKeys())) {
                continue;
            }
            // 补推的条目此前已进过列表：成功后只刷新状态，不再把它顶到列表最前面
            boolean retry = queueRetryIds.contains(item.id());
            String traceId = "poll-" + UUID.randomUUID().toString().substring(0, 8);
            NewsArticle enriched = item;
            if (settings.aiEnabled() && aiQuota > 0) {
                aiQuota--;
                // 仅对进入 AI 整合的条目抓正文：修复项、变更点等具体信息从正文提炼
                String articleContent = fetch.fetchArticleContent(item);
                enriched = withAiSummary(item, ai.summarize(item, articleContent, mode, traceId).orElse(""));
            }
            String content = templates.render(settings.messageTemplate(), enriched);
            // 载荷按条目生成一次：同一篇投给多个目标时内容完全一致，接收端可据此判重
            Map<String, Object> payload = push.webhookPayload("mc.news.new", enriched, content);
            List<NewsPushLog.TargetResult> results = new ArrayList<>();
            for (NewsTarget target : deliveryTargets) {
                results.add(push.sendToTarget(target, content, payload));
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
            long pushedAt = System.currentTimeMillis();
            if (okCount > 0) {
                // 至少一个目标送达成功：移出未送达队列，下轮不再补推
                pending = dropPending(pending, item);
                pushOk++;
                if (retry) {
                    retryOk++;
                }
            }
            else {
                // 推送失败，或当前没有任何可用推送目标（results 为空）：记住「未送达」，下轮继续尝试。
                // 判定依据是实际送达结果而不是「有没有见过」，所以不会再出现未推送却永久跳过的情况
                pending = addPending(pending, item);
            }
            if (retry) {
                cached = withState(cached, item.id(), state, pushedAt);
                backfill = withState(backfill, item.id(), state, pushedAt);
            }
            else {
                pushed.add(0, annotate(List.of(enriched), state, pushedAt, null).get(0));
            }
            // 推送一条立即落库一条：轮询中途被打断（重载/重启）时已推送内容不会重复推送
            cache.writePoll(mergeDisplay(pushed, cached, backfill, settings.cacheSize()), seen, pending);
        }
        settings.recordPoll(startedAt, summary("抓取 " + fetched + " 条",
                "新增 " + newItems.size() + " 条", "推送 " + pushOk + "/" + pushCount,
                retrySegment(retryOk, retryQueue.size()), countSegment(repeats.size(), "跳过重复"),
                countSegment(ignoredCount, "忽略"), duplicateTargetSegment(duplicateTargets),
                failureSegment(failures)));
        return new PollOutcome(newItems.size(), pushOk, fetched);
    }

    /**
     * 目标端点标识：同类型 + 同地址（Webhook 地址 / 群聊连接与频道）+ 同自定义 Header 才算同一端点。
     * 自定义 Header 不同意味着接收端行为可能不同（如不同的鉴权令牌），仍按两个端点分别投递。
     */
    static String endpointKey(NewsTarget target) {
        StringBuilder key = new StringBuilder(target.type());
        if (target.messaging()) {
            key.append("|conn:").append(trimmed(target.connectionId()))
                    .append("|chan:").append(trimmed(target.channelId()));
        }
        else {
            key.append("|url:").append(trimmed(target.webhookUrl()));
        }
        List<String> headers = new ArrayList<>();
        for (NewsTarget.WebhookHeader header : target.headers()) {
            headers.add(trimmed(header.key()).toLowerCase(Locale.ROOT) + "=" + trimmed(header.value()));
        }
        Collections.sort(headers);
        return key.append('|').append(String.join(";", headers)).toString();
    }

    /** 端点相同的目标只保留第一个（全局目标在前、优先），避免同一份载荷重复送达同一接收端。 */
    @SafeVarargs
    static List<NewsTarget> distinctEndpoints(List<NewsTarget>... groups) {
        List<NewsTarget> result = new ArrayList<>();
        for (EndpointGroup group : endpointGroups(groups)) {
            result.add(group.primary());
        }
        return result;
    }

    /**
     * 按端点分组：同端点的目标归为一组，{@code primary} 是实际投递目标（全局目标在前、优先），
     * {@code memberIds} 含被合并的其它目标。手动推送的目标选择需要它——被合并的次要目标也应当能被勾选。
     */
    @SafeVarargs
    public static List<EndpointGroup> endpointGroups(List<NewsTarget>... groups) {
        Map<String, List<NewsTarget>> grouped = new LinkedHashMap<>();
        for (List<NewsTarget> group : groups) {
            for (NewsTarget target : group) {
                grouped.computeIfAbsent(endpointKey(target), key -> new ArrayList<>()).add(target);
            }
        }
        List<EndpointGroup> result = new ArrayList<>();
        for (List<NewsTarget> sameEndpoint : grouped.values()) {
            List<String> ids = new ArrayList<>();
            for (NewsTarget target : sameEndpoint) {
                ids.add(target.id());
            }
            result.add(new EndpointGroup(sameEndpoint.get(0), List.copyOf(ids)));
        }
        return result;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 回填：源里仍在、但已不在展示列表里的旧条目——清空动态、展示缓存被裁剪后重建列表用。
     * 标记为重复未推，不发送任何推送。
     */
    private static List<NewsArticle> backfill(List<NewsArticle> repeats, List<NewsArticle> cached) {
        Set<String> displayed = new HashSet<>();
        for (NewsArticle item : cached) {
            displayed.addAll(NewsFingerprint.keysOf(item));
        }
        List<NewsArticle> result = new ArrayList<>();
        for (NewsArticle item : repeats) {
            Set<String> keys = NewsFingerprint.keysOf(item);
            if (Collections.disjoint(keys, displayed)) {
                displayed.addAll(keys);
                result.add(new NewsArticle(item.id(), item.sourceId(), item.sourceName(), item.title(), item.summary(),
                        item.aiSummary(), item.category(), item.url(), item.imageUrl(), item.publishedAt(),
                        item.discoveredAt(), 0, NewsArticle.STATE_DEDUPED));
            }
        }
        return result;
    }

    /**
     * 合并展示列表：本轮新条目（新→旧）+ 现有缓存 + 回填条目，按 id 与指纹去重后裁到上限。
     * 回填放在最后：优先保留当前仍在列表里的条目，超限时先淘汰回填进来的旧条目（列表长度稳定、不来回滚动）。
     */
    static List<NewsArticle> mergeDisplay(List<NewsArticle> head, List<NewsArticle> base,
                                         List<NewsArticle> backfill, int limit) {
        List<NewsArticle> merged = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (List<NewsArticle> batch : List.of(head, base, backfill)) {
            for (NewsArticle item : batch) {
                if (item.id() == null || item.id().isBlank() || !ids.add(item.id())) {
                    continue;
                }
                Set<String> itemKeys = NewsFingerprint.keysOf(item);
                if (!Collections.disjoint(itemKeys, keys)) {
                    continue;
                }
                keys.addAll(itemKeys);
                merged.add(item);
            }
        }
        while (merged.size() > limit && !merged.isEmpty()) {
            merged.remove(merged.size() - 1);
        }
        return merged;
    }

    /**
     * 记入未送达队列（按原始 id 去重，超出 {@link NewsCacheStore#MAX_PENDING_ENTRIES} 淘汰最旧）。
     * 条目在源里消失后无法再补推，由容量上限自然淘汰。
     */
    static List<String> addPending(List<String> pending, NewsArticle item) {
        if (item.id() == null || item.id().isBlank() || NewsFingerprint.entryIds(pending).contains(item.id())) {
            return pending;
        }
        List<String> next = new ArrayList<>(pending);
        next.add(NewsFingerprint.entry(item));
        return NewsCacheStore.capPending(next);
    }

    /** 送达成功：把该条目（连同同篇的标题/链接指纹变体）从队列里摘掉。 */
    static List<String> dropPending(List<String> pending, NewsArticle item) {
        Set<String> keys = NewsFingerprint.keysOf(item);
        List<String> next = new ArrayList<>();
        for (String entry : pending) {
            if (Collections.disjoint(NewsFingerprint.keysOfEntry(entry), keys)) {
                next.add(entry);
            }
        }
        return next.size() == pending.size() ? pending : next;
    }

    /** 就地刷新展示列表里某条目的推送状态（补推老条目时用，避免旧条目被顶到列表最前面）。 */
    private static List<NewsArticle> withState(List<NewsArticle> items, String id, String state, long pushedAt) {
        List<NewsArticle> result = new ArrayList<>(items.size());
        for (NewsArticle item : items) {
            if (id != null && id.equals(item.id())) {
                result.add(new NewsArticle(item.id(), item.sourceId(), item.sourceName(), item.title(), item.summary(),
                        item.aiSummary(), item.category(), item.url(), item.imageUrl(), item.publishedAt(),
                        item.discoveredAt(), pushedAt, state));
            }
            else {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 轮询摘要：`·` 分隔的短句片段，零值片段由各 segment 方法自行省略。
     * 比原来一句到底的长文案更容易一眼扫完（如「抓取 30 条 · 新增 8 条 · 推送 8/8 · 跳过重复 14 条」）。
     */
    private static String summary(String head, String... extras) {
        StringBuilder text = new StringBuilder(head);
        for (String extra : extras) {
            if (extra != null && !extra.isEmpty()) {
                text.append(" · ").append(extra);
            }
        }
        return text.toString();
    }

    /** 「跳过重复 3 条」这类计数片段：为 0 时整段不出现。 */
    private static String countSegment(int count, String label) {
        return count > 0 ? label + " " + count + " 条" : "";
    }

    /** 补推片段：本轮补推成功条数 / 计划条数；没有补推计划时不出现。 */
    private static String retrySegment(int retried, int planned) {
        return planned > 0 ? "补推 " + retried + "/" + planned : "";
    }

    /** 端点重复（全局目标与个人 Webhook 指向同一地址/频道）而被合并的目标数。 */
    private static String duplicateTargetSegment(int duplicated) {
        return duplicated > 0 ? "合并重复目标 " + duplicated + " 个" : "";
    }

    /** 抓取失败的源清单：失败才出现，成功时摘要保持干净。 */
    private static String failureSegment(List<String> failures) {
        return failures.isEmpty() ? "" : "源失败：" + String.join("、", failures);
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

    /** 一次手动推送的结果：okCount 为送达成功的目标数，明细供管理端即时回显。 */
    public record PushOutcome(String articleId, int okCount, int total,
                              List<NewsPushLog.TargetResult> results) {
    }

    /** 同一端点的目标分组：primary 为实际投递目标，memberIds 为含它在内的全部同端点目标 id。 */
    public record EndpointGroup(NewsTarget primary, List<String> memberIds) {
        /** 勾选集合里出现该端点任一成员即视为选中（被合并的次要目标 id 也认）。 */
        public boolean selectedBy(Set<String> selectedIds) {
            for (String id : memberIds) {
                if (selectedIds.contains(id)) {
                    return true;
                }
            }
            return false;
        }
    }
}
