package online.yudream.base.plugin.mcnews.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import online.yudream.base.plugin.mcnews.domain.NewsArticle;
import online.yudream.base.plugin.mcnews.domain.NewsFingerprint;
import online.yudream.base.plugin.mcnews.domain.NewsPushLog;
import online.yudream.base.plugin.mcnews.domain.NewsTarget;
import online.yudream.base.plugin.mcnews.infrastructure.McNewsStore;
import online.yudream.base.plugin.mcnews.infrastructure.NewsCacheStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 去重逻辑端到端验证：判定依据是**标题 + 链接**指纹（规范化后任一命中即同一篇），
 * 用本地 HTTP 假源跑真实的 {@link NewsPipeline} 轮询链路，并用本地 Webhook 服务端统计真实推送次数，
 * 断言「多轮轮询下同一篇新闻只推送一次」以及展示缓存裁剪、清空、忽略名单等窗口下的行为。
 * 不使用宿主能力（AI 关闭），只验证去重、推送次数与缓存记账。
 */
class NewsPipelineDedupTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final McNewsStore store = new McNewsStore(documents);
    private final McNewsSettings settings = new McNewsSettings(store);
    private final NewsSourceService sources = new NewsSourceService(store);
    private final NewsTargetService targets = new NewsTargetService(store);
    private final NewsCacheStore cache = new NewsCacheStore(store);

    private HttpServer server;
    private String feedUrl;
    private String hookUrl;
    private String altHookUrl;
    private NewsPipeline pipeline;

    /** 假源当前返回的文章：[路径, 标题]，路径形态与 minecraft.net 列表 JSON 一致。 */
    private final List<String[]> feed = new ArrayList<>();
    /** Webhook 收到的推送载荷（含被挂起的请求，先记录后响应）。 */
    private final List<String> webhookBodies = Collections.synchronizedList(new ArrayList<>());
    /** 第二个（地址不同）Webhook 收到的推送载荷，用于验证「地址不同就都要投递」。 */
    private final List<String> altBodies = Collections.synchronizedList(new ArrayList<>());
    /** 非空时挂起 Webhook 响应，用于精确制造「轮询推送途中」的窗口。 */
    private volatile CountDownLatch hookGate;
    /** 假 Webhook 当前返回的状态码：置 500 制造「推送失败」，用于验证补推。 */
    private volatile int hookStatus = 200;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed.json", exchange -> respond(exchange, "application/json",
                feedBody().getBytes(StandardCharsets.UTF_8)));
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            webhookBodies.add(body);
            CountDownLatch gate = hookGate;
            if (gate != null) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int status = hookStatus;
            respond(exchange, status, "text/plain", (status == 200 ? "ok" : "boom").getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/hook-alt", exchange -> {
            altBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
        });
        server.start();
        feedUrl = url("/feed.json");
        hookUrl = url("/hook");
        altHookUrl = url("/hook-alt");

        // AI 关闭：避免轮询触发正文抓取（会打真实外网）
        configure(false, null);
        sources.create("本地假源", "mcnet", feedUrl, List.of(), true);
        pipeline = newPipeline();
    }

    @AfterEach
    void tearDown() {
        CountDownLatch gate = hookGate;
        if (gate != null) {
            gate.countDown();
        }
        server.stop(0);
    }

    // ---------- 场景 ----------

    /** 核心断言：多轮轮询下每一篇新闻恰好推送一次。 */
    @Test
    void multiPollNeverPushesTheSameArticleTwice() {
        configure(true, null);
        enableWebhook();
        addArticle("/zh-hans/article/a", "Minecraft 26.3 正式发布");
        addArticle("/zh-hans/article/b", "Minecraft 26.4 快照上线");
        addArticle("/zh-hans/article/c", "反馈隧道版本更新");

        NewsPipeline.PollOutcome first = pipeline.pollOnce("poll");
        assertEquals(3, first.newCount());
        assertEquals(3, first.pushOk(), "首轮推送 3 条");
        assertEquals(3, pushTotal(), "Webhook 收到 3 次推送");

        for (int round = 2; round <= 6; round++) {
            NewsPipeline.PollOutcome outcome = pipeline.pollOnce("poll");
            assertEquals(0, outcome.newCount(), "第 " + round + " 轮不应发现新内容");
            assertEquals(3, outcome.fetched(), "每轮都能从源里抓到同样的 3 条");
            assertEquals(0, outcome.pushOk(), "第 " + round + " 轮不应产生推送");
        }
        assertEquals(3, pushTotal(), "6 轮轮询后同一篇新闻仍然只被推送一次");
        Map<String, Integer> counts = pushedTitleCounts();
        assertEquals(3, counts.size());
        assertTrue(counts.values().stream().allMatch(count -> count == 1), "每篇恰好一次：" + counts);
        assertEquals(3, logs().size(), "推送记录同样只有 3 条");
        assertEquals(3, items().size(), "展示列表不重复膨胀");
    }

    /** 同一链接、标题只有大小写/标点/空白差异 → 同一篇。 */
    @Test
    void titleVariantOfSameLinkIsNotRepushed() {
        configure(true, null);
        enableWebhook();
        addArticle("/zh-hans/article/a", "Minecraft 26.3 Release");
        assertEquals(1, pipeline.pollOnce("poll").newCount());
        assertEquals(1, pushTotal());

        feed.clear();
        addArticle("/zh-hans/article/a", "minecraft  26.3  release!");
        assertEquals(0, pipeline.pollOnce("poll").newCount(), "标题仅装饰差异视为同一篇");
        assertEquals(1, pushTotal(), "不重复推送");
        assertEquals(1, items().size());
    }

    /** 同一文章、链接形态变化（语言站/尾斜杠/统计参数、甚至标题被改写）→ 同一篇。 */
    @Test
    void linkVariantOfSameArticleIsNotRepushed() {
        configure(true, null);
        enableWebhook();
        addArticle("/zh-hans/article/minecraft-26-3", "Minecraft 26.3 Release");
        assertEquals(1, pipeline.pollOnce("poll").newCount());
        assertEquals(1, pushTotal());

        feed.clear();
        addArticle("/en-us/article/minecraft-26-3/", "Minecraft 26.3 Release");
        assertEquals(0, pipeline.pollOnce("poll").newCount(), "换语言站 + 尾斜杠不视为新内容");

        feed.clear();
        addArticle("/zh-hans/article/minecraft-26-3?utm_source=rss", "Minecraft 26.3 正式发布");
        assertEquals(0, pipeline.pollOnce("poll").newCount(), "带统计参数、标题被改写的同一链接不视为新内容");
        assertEquals(1, pushTotal(), "三轮合计只推送一次");
        assertEquals(1, items().size());
    }

    /** 不同链接、标题相同（改链/改版）→ 同一篇。 */
    @Test
    void sameTitleOnAnotherLinkIsNotRepushed() {
        configure(true, null);
        enableWebhook();
        addArticle("/zh-hans/article/spotlight-1", "Minecraft 月度盘点");
        assertEquals(1, pipeline.pollOnce("poll").newCount());

        feed.clear();
        addArticle("/zh-hans/article/spotlight-2", "Minecraft 月度盘点");
        assertEquals(0, pipeline.pollOnce("poll").newCount(), "标题相同即视为同一篇");
        assertEquals(1, pushTotal());
    }

    /** 同一批候选里的多语言链接折叠为一条。 */
    @Test
    void variantLinksWithinOneBatchCollapseToOneArticle() {
        serve("/zh-hans/article/a", "/en-us/article/a", "/zh-hans/article/b");

        NewsPipeline.PollOutcome first = pipeline.pollOnce("poll");
        assertEquals(2, first.newCount(), "同一篇的多语言链接在一批里只算一条");
        assertEquals(3, first.fetched());
        assertEquals(List.of("mcnet:article/a", "mcnet:article/b"), ids());
    }

    /** 展示缓存被裁掉的旧新闻仍在源里：不重推（去重记忆独立于展示缓存）。 */
    @Test
    void evictionFromDisplayCacheDoesNotRepush() {
        configure(true, 5);
        enableWebhook();
        for (int i = 1; i <= 6; i++) {
            addArticle("/zh-hans/article/" + i, "新闻 " + i);
        }
        assertEquals(6, pipeline.pollOnce("poll").newCount());
        assertEquals(5, items().size(), "展示列表裁到 cacheSize");
        assertEquals(6, pushTotal());

        for (int round = 2; round <= 4; round++) {
            assertEquals(0, pipeline.pollOnce("poll").newCount(), "展示缓存裁剪不影响去重记忆");
        }
        assertEquals(6, pushTotal(), "被裁掉的旧新闻不会被当成新内容重推");
        assertEquals(5, items().size());
        assertTrue(NewsFingerprint.flatten(cache.read().seen()).contains("mcnet:article/6"),
                "被展示缓存裁掉的条目仍留在去重记忆里");
    }

    /** 清空动态：旧新闻只回填列表，真正的新内容照常推送。 */
    @Test
    void clearingNewsRebuildsListWithoutRepushingButStillPushesNewOnes() {
        configure(true, null);
        enableWebhook();
        serve("/zh-hans/article/a", "/zh-hans/article/b");
        assertEquals(2, pipeline.pollOnce("poll").newCount());
        assertEquals(2, pushTotal());

        NewsFeedService feedService = new NewsFeedService(store);
        feedService.clearArticles();
        assertTrue(items().isEmpty());

        addArticle("/zh-hans/article/c", "新闻 C");
        NewsPipeline.PollOutcome afterClear = pipeline.pollOnce("poll");
        assertEquals(1, afterClear.newCount(), "清空后只有 c 是新内容");
        assertEquals(1, afterClear.pushOk());
        assertEquals(3, pushTotal(), "a、b 只回填不重推");
        assertEquals(List.of("mcnet:article/c", "mcnet:article/a", "mcnet:article/b"), ids());
        assertEquals(NewsArticle.STATE_PUSHED, items().get(0).pushState());
        assertEquals(NewsArticle.STATE_DEDUPED, items().get(1).pushState(), "回填条目标记为重复未推");
    }

    /** 忽略名单：既不推送也不被回填。 */
    @Test
    void tombstonedArticleIsNeitherPushedNorBackfilled() {
        configure(true, null);
        enableWebhook();
        serve("/zh-hans/article/a", "/zh-hans/article/b");
        assertEquals(2, pipeline.pollOnce("poll").newCount());

        NewsFeedService feedService = new NewsFeedService(store);
        feedService.deleteArticle("mcnet:article/a");
        assertEquals(List.of("mcnet:article/b"), ids());

        assertEquals(0, pipeline.pollOnce("poll").newCount());
        assertEquals(List.of("mcnet:article/b"), ids(), "仍在源里也不推送、不回填");
        assertEquals(2, pushTotal());

        feedService.clearArticles();
        assertEquals(0, pipeline.pollOnce("poll").newCount());
        assertEquals(List.of("mcnet:article/b"), ids(), "清空后重建列表也不会带回被忽略的条目");
        assertEquals(2, pushTotal());
    }

    /** 清空忽略名单是显式恢复：允许该新闻作为新内容再推送一次。 */
    @Test
    void clearingIgnoreListAllowsTheArticleToBePushedAgain() {
        configure(true, null);
        enableWebhook();
        serve("/zh-hans/article/a");
        assertEquals(1, pipeline.pollOnce("poll").newCount());

        NewsFeedService feedService = new NewsFeedService(store);
        feedService.deleteArticle("mcnet:article/a");
        assertEquals(0, pipeline.pollOnce("poll").newCount(), "忽略名单拦截");
        assertEquals(1, pushTotal());

        assertEquals(1, feedService.clearTombstones());
        assertEquals(0, feedService.tombstoneCount());
        NewsPipeline.PollOutcome afterRestore = pipeline.pollOnce("poll");
        assertEquals(1, afterRestore.newCount(), "清空忽略名单后重新参与发现");
        assertEquals(2, pushTotal(), "显式恢复后允许再推一次");
        assertEquals(1, items().size());
    }

    /** 轮询推送途中删除条目：本轮不再推送、也不会被写回列表。 */
    @Test
    void deleteDuringPollIsNeitherPushedNorResurrected() throws Exception {
        configure(true, null);
        enableWebhook();
        serve("/zh-hans/article/a", "/zh-hans/article/b");
        assertEquals(2, pipeline.pollOnce("poll").newCount());
        assertEquals(2, pushTotal());

        addArticle("/zh-hans/article/c", "新闻 C");
        hookGate = new CountDownLatch(1);
        Thread poller = new Thread(() -> pipeline.pollOnce("poll"));
        poller.start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (pushTotal() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(3, pushTotal(), "轮询应已进入推送阶段");

        NewsFeedService feedService = new NewsFeedService(store);
        feedService.deleteArticle("mcnet:article/b");
        assertFalse(ids().contains("mcnet:article/b"), "删除瞬间列表里没有 b");

        hookGate.countDown();
        poller.join(10_000);
        assertFalse(poller.isAlive(), "轮询应已结束");

        assertFalse(ids().contains("mcnet:article/b"), "被删条目不会在本轮写盘时复活");
        assertEquals(3, pushTotal(), "本轮新条目只推送一次");
        assertEquals(0, pipeline.pollOnce("poll").newCount(), "被删条目也不会在下一轮被判为新内容");
        assertEquals(3, pushTotal());
    }

    /** 首次运行：只建基线（展示缓存 + 去重记忆），不推送。 */
    @Test
    void firstRunBuildsBaselineWithoutPushing() {
        configure(false, null);
        enableWebhook();
        serve("/zh-hans/article/a", "/zh-hans/article/b");

        NewsPipeline.PollOutcome first = pipeline.pollOnce("poll");
        assertEquals(2, first.newCount());
        assertEquals(0, pushTotal(), "首次运行不推送");
        assertTrue(logs().isEmpty(), "基线阶段不写推送日志");
        assertEquals(2, cache.read().seen().size(), "同时建立去重记忆");
        assertEquals(NewsArticle.STATE_SKIPPED, items().get(0).pushState());

        assertEquals(0, pipeline.pollOnce("poll").newCount());
        assertEquals(0, pushTotal());
        assertEquals(2, items().size());
    }

    /** 升级兼容：只有展示列表（1.0.0 的历史数据）没有去重记忆时，也不能把已有新闻当成新内容。 */
    @Test
    void legacyDisplayCacheWithoutDedupMemoryIsNotRepushed() {
        configure(false, null);
        enableWebhook();
        Map<String, Object> legacyItem = new HashMap<>();
        legacyItem.put("id", "zendesk:999");
        legacyItem.put("sourceId", "legacy");
        legacyItem.put("sourceName", "历史源");
        legacyItem.put("title", "标题 /zh-hans/article/a");
        legacyItem.put("url", "https://www.minecraft.net/en-us/article/a");
        legacyItem.put("publishedAt", 0L);
        legacyItem.put("discoveredAt", 1L);
        legacyItem.put("pushedAt", 0L);
        legacyItem.put("pushState", NewsArticle.STATE_PUSHED);
        Map<String, Object> doc = new HashMap<>();
        doc.put("items", List.of(legacyItem));
        store.save(McNewsStore.COL_SEEN, McNewsStore.DOC_SEEN, doc);

        serve("/zh-hans/article/a");
        NewsPipeline.PollOutcome outcome = pipeline.pollOnce("poll");
        assertEquals(0, outcome.newCount(), "历史展示缓存按标题/链接指纹视为已见过");
        assertEquals(0, pushTotal());
        assertEquals(List.of("zendesk:999"), ids(), "历史条目保留，不会被同篇的新 id 顶掉");
        assertTrue(NewsFingerprint.flatten(cache.read().seen()).contains("zendesk:999"),
                "轮询会把历史条目补记进去重记忆");
    }

    /** 两个源指向同一 feed：同一篇文章只登记一次。 */
    @Test
    void sameArticleFromTwoSourcesIsDeduplicated() {
        serve("/zh-hans/article/a");
        pipeline.pollOnce("poll");
        sources.create("第二个假源", "mcnet", feedUrl, List.of(), true);

        NewsPipeline.PollOutcome second = pipeline.pollOnce("poll");
        assertEquals(0, second.newCount(), "第二个源返回同一篇文章不应视为新内容");
        assertEquals(1, items().size());
    }

    /**
     * 推送失败不是终态：失败的条目记入未送达队列，目标恢复后下一轮补推成功，
     * 且送达成功后不再重复推送（每篇恰好两次请求：首次失败 + 一次补推）。
     */
    @Test
    void failedPushIsRetriedUntilDelivered() {
        configure(true, null);
        enableWebhook();
        serve("/zh-hans/article/a", "/zh-hans/article/b");

        hookStatus = 500;
        NewsPipeline.PollOutcome first = pipeline.pollOnce("poll");
        assertEquals(2, first.newCount());
        assertEquals(0, first.pushOk(), "目标返回 500，推送全部失败");
        assertEquals(2, pushTotal(), "失败也要真的发出去过（服务端收到了 2 次请求）");
        assertEquals(2, cache.read().pending().size(), "失败的条目记入未送达队列");
        assertEquals(NewsArticle.STATE_FAILED, items().get(0).pushState(), "状态标为推送失败");

        hookStatus = 200;
        NewsPipeline.PollOutcome second = pipeline.pollOnce("poll");
        assertEquals(0, second.newCount(), "补推的不是新内容");
        assertEquals(2, second.pushOk(), "上一轮失败的 2 条本轮补推成功");
        assertEquals(4, pushTotal());
        assertTrue(cache.read().pending().isEmpty(), "送达成功后移出未送达队列");
        assertEquals(NewsArticle.STATE_PUSHED, items().get(0).pushState(), "就地刷新为已推送");

        for (int round = 3; round <= 5; round++) {
            assertEquals(0, pipeline.pollOnce("poll").pushOk(), "第 " + round + " 轮不应再推送");
        }
        assertEquals(4, pushTotal(), "每篇合计恰好两次请求，不会无限重试");
    }

    /** 没有任何推送目标时：内容不算「已推送」，配好目标后下一轮补推。 */
    @Test
    void articleWithoutAnyTargetIsPushedOnceTargetAppears() {
        configure(true, null);
        serve("/zh-hans/article/a", "/zh-hans/article/b");

        NewsPipeline.PollOutcome first = pipeline.pollOnce("poll");
        assertEquals(2, first.newCount());
        assertEquals(0, pushTotal(), "没有可用目标，不产生推送");
        assertEquals(NewsArticle.STATE_SKIPPED, items().get(0).pushState());
        assertEquals(2, cache.read().pending().size(), "没有可用目标同样算未送达");

        enableWebhook();
        assertEquals(0, pipeline.pollOnce("poll").newCount(), "不是新内容");
        assertEquals(2, pushTotal(), "配好目标后补推此前这 2 条");
        assertTrue(cache.read().pending().isEmpty(), "补推成功后清空队列");
        assertEquals(NewsArticle.STATE_PUSHED, items().get(0).pushState());

        assertEquals(0, pipeline.pollOnce("poll").pushOk());
        assertEquals(2, pushTotal(), "补推只发生一次");
    }

    /** 补推队列不把旧条目顶到列表最前面：就地刷新状态，列表顺序保持稳定。 */
    @Test
    void retriedArticleKeepsItsPlaceInTheList() {
        configure(true, null);
        serve("/zh-hans/article/a", "/zh-hans/article/b", "/zh-hans/article/c");
        assertEquals(3, pipeline.pollOnce("poll").newCount(), "无目标：三条未送达");
        assertEquals(List.of("mcnet:article/a", "mcnet:article/b", "mcnet:article/c"), ids());

        enableWebhook();
        assertEquals(0, pipeline.pollOnce("poll").newCount());
        assertEquals(3, pushTotal(), "补推这 3 条");
        assertEquals(List.of("mcnet:article/a", "mcnet:article/b", "mcnet:article/c"), ids(),
                "补推不改变列表顺序");
        assertTrue(cache.read().pending().isEmpty(), "补推成功后队列清空");
    }

    /** 清空轮询缓存（展示列表 + 去重记忆）后，源里现存内容重新参与推送。 */
    @Test
    void clearingPollCacheMakesExistingArticlesPushableAgain() {
        configure(true, null);
        enableWebhook();
        serve("/zh-hans/article/a", "/zh-hans/article/b");
        assertEquals(2, pipeline.pollOnce("poll").newCount());
        assertEquals(2, pushTotal());

        NewsCacheStore.Snapshot snapshot = cache.read();
        cache.write(List.of(), snapshot.tombstones(), List.of());
        assertTrue(items().isEmpty(), "展示列表已清空");
        assertTrue(cache.read().seen().isEmpty(), "去重记忆已清空");

        NewsPipeline.PollOutcome afterReset = pipeline.pollOnce("poll");
        assertEquals(2, afterReset.newCount(), "重置后源里现存的 2 条重新算新内容");
        assertEquals(2, afterReset.pushOk());
        assertEquals(4, pushTotal());
    }

    /**
     * 清空动态 + 同时清空缓存 + 下一次轮询不推送（默认）：存量内容只重建基线——回填列表、重建去重记录，
     * 一条都不推送；该选项不受 pushOnFirstPoll=true 影响，且标记只消费一次。
     */
    @Test
    void clearingPollCacheWithoutPushOnlyRebuildsBaseline() {
        configure(true, null);
        enableWebhook();
        serve("/zh-hans/article/a", "/zh-hans/article/b");
        assertEquals(2, pipeline.pollOnce("poll").newCount());
        assertEquals(2, pushTotal());

        NewsFeedService.ClearResult clear = new NewsFeedService(store).clearArticles(true, false);
        assertEquals(2, clear.cleared());
        assertTrue(items().isEmpty());
        assertTrue(cache.read().baselineOnly(), "留下「只重建基线」标记");

        NewsPipeline.PollOutcome afterClear = pipeline.pollOnce("poll");
        assertEquals(2, afterClear.newCount(), "存量内容重新参与发现");
        assertEquals(0, afterClear.pushOk(), "但一条都不推送（pushOnFirstPoll=true 也不影响该选项）");
        assertEquals(2, pushTotal(), "推送次数不变");
        assertEquals(List.of("mcnet:article/a", "mcnet:article/b"), ids(), "列表已回填");
        assertEquals(NewsArticle.STATE_SKIPPED, items().get(0).pushState());
        assertEquals(2, cache.read().seen().size(), "去重记录已重建");
        assertFalse(cache.read().baselineOnly(), "标记已被消费");
        assertEquals(2, logs().size(), "基线阶段不写推送日志");
        assertTrue(cache.read().pending().isEmpty(), "基线阶段不产生未送达记录");

        assertEquals(0, pipeline.pollOnce("poll").pushOk(), "后续轮询也不会补推这批基线内容");
        assertEquals(2, pushTotal());
    }

    /** 清空动态 + 同时清空缓存 + 下一次轮询推送：存量内容重新推送一轮，之后照常去重。 */
    @Test
    void clearingPollCacheWithPushRepushesExistingArticlesOnce() {
        configure(true, null);
        enableWebhook();
        serve("/zh-hans/article/a", "/zh-hans/article/b");
        assertEquals(2, pipeline.pollOnce("poll").newCount());
        assertEquals(2, pushTotal());

        new NewsFeedService(store).clearArticles(true, true);
        assertFalse(cache.read().baselineOnly(), "选择推送时不置基线标记");

        NewsPipeline.PollOutcome afterClear = pipeline.pollOnce("poll");
        assertEquals(2, afterClear.newCount());
        assertEquals(2, afterClear.pushOk(), "存量内容被当作新内容重新推送一轮");
        assertEquals(4, pushTotal());
        assertEquals(NewsArticle.STATE_PUSHED, items().get(0).pushState());

        assertEquals(0, pipeline.pollOnce("poll").pushOk(), "之后不再重复推送");
        assertEquals(4, pushTotal());
    }

    /** 抓取全失败的那一轮不算「已重建基线」：标记留到下一次真正重建时再消费。 */
    @Test
    void failedFetchDoesNotConsumeBaselineOnlyMark() {
        configure(true, null);
        enableWebhook();
        serve("/zh-hans/article/a");
        assertEquals(1, pipeline.pollOnce("poll").newCount());
        assertEquals(1, pushTotal());

        new NewsFeedService(store).clearArticles(true, false);
        // 把唯一可用的源换成必然拒连的地址（端口 1）：这一轮抓取全失败
        sources.delete(sources.list().get(0).id());
        sources.create("坏源", "mcnet", "http://127.0.0.1:1/feed.json", List.of(), true);
        assertEquals(0, pipeline.pollOnce("poll").newCount());
        assertEquals(1, pushTotal(), "抓取失败的一轮不产生任何推送（累计仍是第一轮那一条）");
        assertTrue(cache.read().baselineOnly(), "抓取失败的一轮不消费标记");
        assertTrue(items().isEmpty());

        // 源恢复：标记生效，只重建基线不推送
        sources.delete(sources.list().get(0).id());
        sources.create("本地假源", "mcnet", feedUrl, List.of(), true);
        assertEquals(1, pipeline.pollOnce("poll").newCount());
        assertEquals(0, pipeline.pollOnce("poll").pushOk());
        assertEquals(1, pushTotal(), "整段过程只推送过第一轮那一次");
        assertEquals(1, items().size());
        assertFalse(cache.read().baselineOnly());
    }

    /** 首次运行建基线是有意跳过，不产生未送达记录；下轮也不会补推。 */
    @Test
    void firstRunBaselineIsNotTreatedAsUndelivered() {
        configure(false, null);
        enableWebhook();
        serve("/zh-hans/article/a", "/zh-hans/article/b");

        assertEquals(2, pipeline.pollOnce("poll").newCount());
        assertTrue(cache.read().pending().isEmpty(), "基线阶段不产生未送达记录");
        assertEquals(0, pushTotal());

        assertEquals(0, pipeline.pollOnce("poll").pushOk());
        assertEquals(0, pushTotal(), "基线内容不会被当成未送达补推");
    }

    /** 补推前被删除：命中忽略名单后不再补推，且从队列里剔除。 */
    @Test
    void deletedArticleIsNotRetried() {
        configure(true, null);
        serve("/zh-hans/article/a");
        assertEquals(1, pipeline.pollOnce("poll").newCount(), "无目标：未送达");
        assertEquals(1, cache.read().pending().size());

        new NewsFeedService(store).deleteArticle("mcnet:article/a");
        enableWebhook();

        assertEquals(0, pipeline.pollOnce("poll").newCount());
        assertEquals(0, pushTotal(), "被删除的条目不再补推");
        assertTrue(cache.read().pending().isEmpty(), "命中忽略名单的条目从队列里剔除");
    }

    /** 补推单轮限量：目标长期不可用时不会一次把整轮轮询拖住。 */
    @Test
    void retryQueueIsCappedPerPoll() {
        configure(true, null);
        for (int i = 1; i <= 25; i++) {
            addArticle("/zh-hans/article/" + i, "新闻 " + i);
        }
        assertEquals(25, pipeline.pollOnce("poll").newCount(), "无目标：25 条未送达");
        assertEquals(25, cache.read().pending().size());

        enableWebhook();
        assertEquals(0, pipeline.pollOnce("poll").newCount());
        assertEquals(20, pushTotal(), "单轮最多补推 20 条");
        assertEquals(5, cache.read().pending().size(), "其余留到下一轮");
    }

    // ---------- 推送目标端点去重 ----------

    /**
     * 端点重复的目标只投递一次：全局 Webhook 与个人 Webhook 指向同一地址时（本地测试接收端常见），
     * 此前每小时/每轮会对同一接收端各发一条一模一样的载荷，接收端看到的就是「同一篇新闻推了两遍」。
     */
    @Test
    void targetsSharingOneEndpointReceiveSingleDelivery() {
        configure(true, null);
        enableWebhook();
        targets.createUserWebhook("u-1001", "个人 Webhook", hookUrl, List.of(), true);
        serve("/zh-hans/article/a", "/zh-hans/article/b");

        NewsPipeline.PollOutcome outcome = pipeline.pollOnce("poll");
        assertEquals(2, outcome.newCount());
        assertEquals(2, pushTotal(), "两个同地址目标合计每条只投递一次");
        assertEquals(2, outcome.pushOk(), "去重不影响推送成功计数");
        List<NewsPushLog> logs = logs();
        assertEquals(2, logs.size(), "每篇一条推送记录");
        assertTrue(logs.stream().allMatch(log -> log.total() == 1), "记录里只记一个目标");
        assertEquals(1, pushedTitleCounts().get("标题 /zh-hans/article/a"), "同一篇恰好送达一次");
        assertEquals(0, cache.read().pending().size(), "送达成功，无需补推");
    }

    /** 地址不同的目标都要投递：端点去重不能把真正独立的目标合并掉。 */
    @Test
    void targetsWithDifferentEndpointsAllReceiveDelivery() {
        configure(true, null);
        enableWebhook();
        targets.createUserWebhook("u-1001", "个人 Webhook", altHookUrl, List.of(), true);
        serve("/zh-hans/article/a");

        NewsPipeline.PollOutcome outcome = pipeline.pollOnce("poll");
        assertEquals(1, outcome.newCount());
        assertEquals(1, pushTotal(), "全局目标收到 1 条");
        assertEquals(1, altPushTotal(), "地址不同的个人目标同样收到 1 条");
        assertEquals(1, logs().size());
        assertEquals(2, logs().get(0).total(), "一条记录里记两个目标");
    }

    /** 端点标识规则：同类型同地址同 Header 才算同一端点；Header 或群聊频道不同仍分别投递。 */
    @Test
    void endpointKeyDistinguishesUrlHeadersAndChannels() {
        NewsTarget plain = target("tgt-a", "webhook", hookUrl, "conn", "chan", List.of());
        NewsTarget padded = target("tgt-b", "webhook", " " + hookUrl + " ", "conn", "chan", List.of());
        assertEquals(NewsPipeline.endpointKey(plain), NewsPipeline.endpointKey(padded),
                "仅地址首尾空白差异视为同一端点（保存时已 trim，这里兜住手改库）");

        NewsTarget withHeader = target("tgt-c", "webhook", hookUrl, "conn", "chan",
                List.of(new NewsTarget.WebhookHeader("Authorization", "Bearer x")));
        assertFalse(NewsPipeline.endpointKey(plain).equals(NewsPipeline.endpointKey(withHeader)),
                "自定义 Header 不同 → 不同端点，分别投递");

        NewsTarget sameHeaderOtherOrder = target("tgt-d", "webhook", hookUrl, "conn", "chan",
                List.of(new NewsTarget.WebhookHeader(" authorization ", "Bearer x")));
        assertEquals(NewsPipeline.endpointKey(withHeader), NewsPipeline.endpointKey(sameHeaderOtherOrder),
                "Header 名大小写/空白差异折叠为同一端点");

        assertEquals(1, NewsPipeline.distinctEndpoints(List.of(plain, padded)).size());
        assertEquals(2, NewsPipeline.distinctEndpoints(List.of(plain, withHeader, sameHeaderOtherOrder)).size(),
                "同地址：无 Header 与带 Header 是两个端点，带 Header 的两个写法折叠为一个");

        NewsTarget channel1 = target("tgt-e", "messaging", "", "conn-1", "chan-1", List.of());
        NewsTarget channel2 = target("tgt-f", "messaging", "", "conn-1", "chan-2", List.of());
        assertEquals(2, NewsPipeline.distinctEndpoints(List.of(channel1, channel2)).size(), "不同群聊频道分别投递");
        assertEquals(1, NewsPipeline.distinctEndpoints(List.of(channel1,
                target("tgt-g", "messaging", "", " conn-1 ", "chan-1", List.of()))).size(),
                "同连接同频道视为同一端点");
        assertFalse(NewsPipeline.endpointKey(channel1).equals(NewsPipeline.endpointKey(plain)),
                "同一地址的 webhook 与群聊目标不是同一端点");
    }

    // ---------- 手动推送 ----------

    /** 手动推送只推选中的那一条，复用轮询同一套目标，且不干扰去重：下一轮仍然什么都不推。 */
    @Test
    void manualPushSendsOnlyTheSelectedArticle() {
        configure(true, null);
        enableWebhook();
        serve("/zh-hans/article/a", "/zh-hans/article/b");
        assertEquals(2, pipeline.pollOnce("poll").newCount());
        assertEquals(2, pushTotal());

        NewsPipeline.PushOutcome outcome = pipeline.pushArticle("mcnet:article/a", false);
        assertEquals(1, outcome.total(), "只有一个启用中的推送目标");
        assertEquals(1, outcome.okCount());
        assertEquals(3, pushTotal(), "只推被选中的那一条");

        NewsPushLog manual = logs().stream().filter(log -> "push".equals(log.mode())).findFirst().orElseThrow();
        assertEquals("mcnet:article/a", manual.articleId());
        assertEquals(1, manual.total());
        assertEquals(1, manual.okCount());
        assertEquals(NewsArticle.STATE_PUSHED, item("mcnet:article/a").pushState());
        assertEquals(List.of("mcnet:article/a", "mcnet:article/b"), ids(), "手动推送不改变列表顺序");
        assertEquals(2, items().size(), "手动推送不会把条目复制进列表");

        assertEquals(0, pipeline.pollOnce("poll").pushOk(), "手动推送不改变去重判定");
        assertEquals(3, pushTotal());
    }

    /** 手动推送没有可用目标：直接拒绝（管理端 400），不写推送记录、不改条目状态。 */
    @Test
    void manualPushWithoutTargetsIsRejected() {
        configure(true, null);
        serve("/zh-hans/article/a");
        assertEquals(1, pipeline.pollOnce("poll").newCount());
        assertEquals(1, cache.read().pending().size());

        int logsBefore = logs().size();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> pipeline.pushArticle("mcnet:article/a", false));
        assertTrue(error.getMessage().contains("没有可用的推送目标"), error.getMessage());
        assertEquals(logsBefore, logs().size(), "拒绝时不写推送记录");
        assertEquals(NewsArticle.STATE_SKIPPED, item("mcnet:article/a").pushState(), "状态保持不变");
        assertEquals(1, cache.read().pending().size(), "未送达队列保持不变");
    }

    /** 手动推送失败：标为推送失败并记入未送达队列，下一轮轮询继续补推。 */
    @Test
    void manualPushFailureIsRetriedByNextPoll() {
        configure(true, null);
        enableWebhook();
        serve("/zh-hans/article/a");
        assertEquals(1, pipeline.pollOnce("poll").pushOk());
        assertTrue(cache.read().pending().isEmpty());

        hookStatus = 500;
        assertEquals(0, pipeline.pushArticle("mcnet:article/a", false).okCount());
        assertEquals(NewsArticle.STATE_FAILED, item("mcnet:article/a").pushState());
        assertEquals(1, cache.read().pending().size(), "失败记入未送达队列");

        hookStatus = 200;
        assertEquals(1, pipeline.pollOnce("poll").pushOk(), "下一轮补推成功");
        assertTrue(cache.read().pending().isEmpty(), "送达后移出队列");
        assertEquals(NewsArticle.STATE_PUSHED, item("mcnet:article/a").pushState());
        assertEquals(3, pushTotal(), "合计：首轮 1 + 手动 1 + 补推 1");
    }

    /** 此前未送达（当时没有目标）的条目被手动推送成功后移出队列，下一轮只补推剩下的。 */
    @Test
    void manualPushClearsUndeliveredQueueEntry() {
        configure(true, null);
        serve("/zh-hans/article/a", "/zh-hans/article/b");
        assertEquals(2, pipeline.pollOnce("poll").newCount(), "没有目标：两条都未送达");
        assertEquals(2, cache.read().pending().size());

        enableWebhook();
        assertEquals(1, pipeline.pushArticle("mcnet:article/a", false).okCount());
        assertEquals(NewsArticle.STATE_PUSHED, item("mcnet:article/a").pushState());
        assertEquals(1, cache.read().pending().size(), "只剩 b 未送达");

        assertEquals(1, pipeline.pollOnce("poll").pushOk(), "下一轮只补推 b");
        assertTrue(cache.read().pending().isEmpty());
        assertEquals(2, pushTotal(), "a 手动 1 次 + b 补推 1 次");
    }

    /** 轮询摘要为紧凑分段文案：`·` 分隔、零值片段不出现。 */
    @Test
    void pollSummaryIsCompact() {
        configure(true, null);
        enableWebhook();
        serve("/zh-hans/article/a", "/zh-hans/article/b");

        assertEquals(2, pipeline.pollOnce("poll").newCount());
        assertEquals("抓取 2 条 · 新增 2 条 · 推送 2/2", settings.lastPollSummary());

        assertEquals(0, pipeline.pollOnce("poll").newCount());
        assertEquals("抓取 2 条 · 无新内容 · 跳过重复 2 条", settings.lastPollSummary());
    }

    /** 手动推送只投递给勾选的目标：未被勾选的目标一条都收不到。 */
    @Test
    void manualPushOnlyDeliversToSelectedTargets() {
        configure(true, null);
        enableWebhook();
        NewsTarget personal = targets.createUserWebhook("u-1001", "个人 Webhook", altHookUrl, List.of(), true);
        serve("/zh-hans/article/a");
        assertEquals(1, pipeline.pollOnce("poll").newCount());
        assertEquals(1, pushTotal());
        assertEquals(1, altPushTotal(), "两个地址不同的目标各收到一条");

        // 只勾全局目标
        NewsPipeline.PushOutcome onlyGlobal = pipeline.pushArticle("mcnet:article/a",
                List.of(targets.listGlobal().get(0).id()), false);
        assertEquals(1, onlyGlobal.total(), "只投勾选的那一个目标");
        assertEquals(1, onlyGlobal.okCount());
        assertEquals(2, pushTotal(), "全局目标再次收到");
        assertEquals(1, altPushTotal(), "未勾选的个人目标不收");
        assertEquals(1, logs().stream().filter(log -> "push".equals(log.mode())).count());

        // 只勾个人目标
        NewsPipeline.PushOutcome onlyPersonal = pipeline.pushArticle("mcnet:article/a", List.of(personal.id()), false);
        assertEquals(1, onlyPersonal.total());
        assertEquals(1, onlyPersonal.okCount());
        assertEquals(2, pushTotal(), "这次没勾全局目标");
        assertEquals(2, altPushTotal());
    }

    /** 勾选的目标都已停用或不存在：直接拒绝（管理端 400），而不是静默地一条都不发。 */
    @Test
    void manualPushWithUnavailableTargetsIsRejected() {
        configure(true, null);
        enableWebhook();
        serve("/zh-hans/article/a");
        assertEquals(1, pipeline.pollOnce("poll").newCount());
        int logsBefore = logs().size();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> pipeline.pushArticle("mcnet:article/a", List.of("tgt-missing"), false));
        assertTrue(error.getMessage().contains("所选推送目标"), error.getMessage());
        assertEquals(logsBefore, logs().size(), "拒绝时不写推送记录");
        assertEquals(1, pushTotal(), "拒绝时没有额外投递");
        assertEquals(NewsArticle.STATE_PUSHED, item("mcnet:article/a").pushState(), "状态保持不变");
    }

    /** 被端点合并掉的次要目标 id 也能勾选：勾它与勾主目标等价，不会变成空推送。 */
    @Test
    void manualPushAcceptsMergedEndpointMemberId() {
        configure(true, null);
        enableWebhook();
        NewsTarget personal = targets.createUserWebhook("u-1001", "个人 Webhook", hookUrl, List.of(), true);
        serve("/zh-hans/article/a");
        assertEquals(1, pipeline.pollOnce("poll").newCount());
        assertEquals(1, pushTotal(), "两个同地址目标，实际只投一条");

        NewsPipeline.PushOutcome outcome = pipeline.pushArticle("mcnet:article/a", List.of(personal.id()), false);
        assertEquals(1, outcome.total(), "勾选被合并的次要目标仍投递到它所在端点");
        assertEquals(1, outcome.okCount());
        assertEquals(2, pushTotal());
    }

    /** 手动轮询关闭推送：新条目只回填列表 + 登记去重，一条都不发送；后续轮询也不会再补推它。 */
    @Test
    void manualPollWithoutPushOnlyBackfillsList() {
        configure(true, null);
        enableWebhook();
        serve("/zh-hans/article/a");
        assertEquals(1, pipeline.pollOnce("poll").pushOk());
        assertEquals(1, pushTotal());

        addArticle("/zh-hans/article/b", "标题 /zh-hans/article/b");
        NewsPipeline.PollOutcome outcome = pipeline.pollOnce("manual", false);
        assertEquals(1, outcome.newCount(), "源里新内容仍被识别为新增");
        assertEquals(0, outcome.pushOk());
        assertEquals(1, pushTotal(), "关闭推送：一条都不发送");
        assertTrue(ids().contains("mcnet:article/b"), "新条目仍然回填进列表");
        assertEquals(NewsArticle.STATE_SKIPPED, item("mcnet:article/b").pushState(), "标记为未推送");
        assertFalse(cache.read().baselineOnly(), "关闭推送这一轮已建立基线，标记被消费");
        assertTrue(settings.lastPollSummary().contains("未推送"), settings.lastPollSummary());

        assertEquals(0, pipeline.pollOnce("manual", true).newCount(), "已登记去重，下次不再当作新内容");
        assertEquals(0, pipeline.pollOnce("poll").pushOk(), "后续轮询同样不补推");
        assertEquals(1, pushTotal(), "始终只有首轮那一条推送");
    }

    /** 端点分组保留全部同端点成员 id，手动推送的目标选择据此判定。 */
    @Test
    void endpointGroupsKeepMergedMembers() {
        NewsTarget plain = target("tgt-a", "webhook", hookUrl, "conn", "chan", List.of());
        NewsTarget padded = target("tgt-b", "webhook", " " + hookUrl + " ", "conn", "chan", List.of());
        NewsTarget other = target("tgt-c", "webhook", altHookUrl, "conn", "chan", List.of());

        List<NewsPipeline.EndpointGroup> groups = NewsPipeline.endpointGroups(List.of(plain, padded, other));
        assertEquals(2, groups.size(), "同端点折叠为一组");
        assertEquals("tgt-a", groups.get(0).primary().id(), "组内第一个（全局目标在前）作为实际投递目标");
        assertEquals(List.of("tgt-a", "tgt-b"), groups.get(0).memberIds());
        assertTrue(groups.get(0).selectedBy(java.util.Set.of("tgt-b")), "次要目标 id 也算选中");
        assertFalse(groups.get(0).selectedBy(java.util.Set.of("tgt-c")), "他组目标不算选中");
        assertEquals(1, groups.get(1).memberIds().size());
    }

    // ---------- 指纹单元用例 ----------

    @Test
    void fingerprintIgnoresTitleAndUrlDecoration() {
        assertEquals("minecraft 26 3 release", NewsFingerprint.titleKey("Minecraft 26.3 Release!"));
        assertEquals(NewsFingerprint.titleKey("Minecraft  26.3 Release!"),
                NewsFingerprint.titleKey("minecraft 26-3 release"));
        assertFalse(NewsFingerprint.titleKey("Minecraft 1.21")
                .equals(NewsFingerprint.titleKey("Minecraft 12.1")), "数字分段保留，版本号不会互相折叠");
        assertEquals("minecraft.net/article/x",
                NewsFingerprint.urlKey("https://www.minecraft.net/zh-hans/article/x?utm_source=rss#top"));
        assertEquals("minecraft.net/article/x", NewsFingerprint.urlKey("http://minecraft.net/en-us/article/x/"));
        assertEquals(NewsFingerprint.urlKey("https://minecraftfeedback.zendesk.com/hc/en-us/articles/123-a?sort=desc"),
                NewsFingerprint.urlKey("https://minecraftfeedback.zendesk.com/hc/en-us/articles/123-a"));
        assertEquals("", NewsFingerprint.titleKey(null));
        assertEquals("", NewsFingerprint.urlKey(" "));
    }

    @Test
    void fingerprintEntryRoundTripsAndKeepsLegacyIds() {
        NewsArticle article = article("mcnet:article/a", "标题 A", "https://www.minecraft.net/zh-hans/article/a");
        Set<String> keys = NewsFingerprint.keysOf(article);
        assertEquals(keys, NewsFingerprint.keysOfEntry(NewsFingerprint.entry(article)));
        assertTrue(keys.contains("mcnet:article/a"), "原始 id 也参与匹配");
        assertTrue(keys.contains("t:标题 a"), "标题键参与匹配");
        assertTrue(keys.contains("u:minecraft.net/article/a"), "链接键参与匹配");
        assertEquals(Set.of("legacy-id"), NewsFingerprint.keysOfEntry("legacy-id"),
                "历史忽略名单只存 id 时按 id 匹配");
        assertEquals("legacy-id", NewsFingerprint.entryId("legacy-id"));
    }

    /** 文章稳定 ID 的归一化范围（ID 仍用于展示与日志，去重不再依赖它）。 */
    @Test
    void articleSlugNormalizationScope() {
        assertEquals("article/minecraft-26-3", NewsFetchService.articleSlug("/zh-hans/article/minecraft-26-3"));
        assertEquals("article/minecraft-26-3", NewsFetchService.articleSlug("/en-us/article/minecraft-26-3"));
        assertEquals("/article/minecraft-26-3", NewsFetchService.articleSlug("/article/minecraft-26-3"),
                "无语言前缀时回退原路径");
        assertEquals("/zh-Hans/article/x", NewsFetchService.articleSlug("/zh-Hans/article/x"),
                "大写语言码不匹配正则，回退原路径");
        assertEquals("article/x?utm=1", NewsFetchService.articleSlug("/zh-hans/article/x?utm=1"),
                "带 query 的 URL 生成不同的 ID（去重改由链接指纹兜住）");
        assertEquals("article/x/", NewsFetchService.articleSlug("/zh-hans/article/x/"),
                "尾斜杠同样生成不同的 ID（去重改由链接指纹兜住）");
    }

    /** 假源可用性自检：确保上面用例失败不是网络/解析问题。 */
    @Test
    void fakeSourceIsReachableAndParsed() {
        serve("/zh-hans/article/a");
        NewsFetchService fetch = new NewsFetchService(HttpClient.newHttpClient(), new ObjectMapper());
        List<NewsArticle> fetched = fetch.fetch(sources.listEnabled().get(0));
        assertNotNull(fetched);
        assertEquals(1, fetched.size());
        assertEquals("mcnet:article/a", fetched.get(0).id());
        assertFalse(fetched.get(0).url().isBlank());
    }

    // ---------- 测试脚手架 ----------

    private NewsPipeline newPipeline() {
        return new NewsPipeline(store, settings, sources, targets,
                new NewsSubscriptionService(store),
                new NewsFetchService(HttpClient.newHttpClient(), new ObjectMapper()),
                new NewsTemplateService(), new NewsAiSummarizer(null, settings),
                new NewsPushService(null, HttpClient.newHttpClient(), new ObjectMapper()));
    }

    private void configure(boolean pushOnFirstPoll, Integer cacheSize) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("aiEnabled", false);
        doc.put("pushOnFirstPoll", pushOnFirstPoll);
        if (cacheSize != null) {
            doc.put("cacheSize", cacheSize);
        }
        store.save(McNewsStore.COL_SETTINGS, McNewsStore.DOC_SETTINGS, doc);
    }

    /** 建一个指向本地假 Webhook 的全局推送目标。 */
    private void enableWebhook() {
        targets.createGlobal("测试 Webhook", "webhook", true, null, null, null, hookUrl, List.of(), false);
    }

    /** 直接构造一个推送目标，用于端点标识的单元断言（不走存储）。 */
    private static NewsTarget target(String id, String type, String webhookUrl, String connectionId,
                                     String channelId, List<NewsTarget.WebhookHeader> headers) {
        return new NewsTarget(id, id, type, true, null, connectionId, channelId, "", webhookUrl, headers, false, 0);
    }

    /** 重设假源内容，标题由路径推导。 */
    private void serve(String... articlePaths) {
        feed.clear();
        for (String path : articlePaths) {
            addArticle(path, "标题 " + path);
        }
    }

    private void addArticle(String path, String title) {
        feed.add(new String[]{path, title});
    }

    private String feedBody() {
        StringBuilder json = new StringBuilder("{\"article_grid\":[");
        for (int i = 0; i < feed.size(); i++) {
            String[] item = feed.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"article_url\":\"").append(item[0]).append("\",")
                    .append("\"primary_category\":\"News\",")
                    .append("\"default_tile\":{\"title\":\"").append(item[1])
                    .append("\",\"sub_header\":\"A Minecraft Java Edition\"}}");
        }
        return json.append("]}").toString();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void respond(HttpExchange exchange, String contentType, byte[] body) throws IOException {
        respond(exchange, 200, contentType, body);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static NewsArticle article(String id, String title, String url) {
        return new NewsArticle(id, "src", "源", title, "", "", "", url, "", 0,
                System.currentTimeMillis(), 0, NewsArticle.STATE_PENDING);
    }

    private List<NewsArticle> items() {
        return cache.read().items();
    }

    private NewsArticle item(String id) {
        return items().stream().filter(article -> id.equals(article.id())).findFirst().orElseThrow();
    }

    private List<String> ids() {
        return items().stream().map(NewsArticle::id).toList();
    }

    private List<NewsPushLog> logs() {
        List<NewsPushLog> result = new ArrayList<>();
        for (Map<String, Object> doc : store.all(McNewsStore.COL_LOGS)) {
            NewsPushLog log = NewsFeedService.logFromDoc(doc);
            if (log != null) {
                result.add(log);
            }
        }
        return result;
    }

    /** Webhook 实际收到的推送次数（发送方成功计数为准）。 */
    private int pushTotal() {
        return webhookBodies.size();
    }

    /** 第二个（地址不同）Webhook 实际收到的推送次数。 */
    private int altPushTotal() {
        return altBodies.size();
    }

    private Map<String, Integer> pushedTitleCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (String body : new ArrayList<>(webhookBodies)) {
            try {
                JsonNode node = MAPPER.readTree(body);
                counts.merge(node.path("title").asText(""), 1, Integer::sum);
            }
            catch (IOException ignored) {
                counts.merge("(无法解析)", 1, Integer::sum);
            }
        }
        return counts;
    }
}
