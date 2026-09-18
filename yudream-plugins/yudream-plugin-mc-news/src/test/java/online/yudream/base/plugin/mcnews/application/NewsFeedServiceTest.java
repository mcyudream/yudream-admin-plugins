package online.yudream.base.plugin.mcnews.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import online.yudream.base.plugin.mcnews.domain.NewsArticle;
import online.yudream.base.plugin.mcnews.domain.NewsFingerprint;
import online.yudream.base.plugin.mcnews.infrastructure.NewsCacheStore;
import org.junit.jupiter.api.Test;

class NewsFeedServiceTest {

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final NewsFeedService feed = new NewsFeedService(new online.yudream.base.plugin.mcnews.infrastructure.McNewsStore(documents));

    private static NewsArticle article(String id) {
        return new NewsArticle(id, "src", "源", "标题 " + id, "摘要", "", "News",
                "https://example.com/" + id, "", 0, 1, 0, NewsArticle.STATE_PUSHED);
    }

    private NewsCacheStore cache() {
        return new NewsCacheStore(new online.yudream.base.plugin.mcnews.infrastructure.McNewsStore(documents));
    }

    private void seed(String... ids) {
        NewsCacheStore cache = new NewsCacheStore(new online.yudream.base.plugin.mcnews.infrastructure.McNewsStore(documents));
        List<NewsArticle> items = new ArrayList<>();
        for (String id : ids) {
            items.add(article(id));
        }
        cache.write(items, List.of());
    }

    @Test
    void deleteRemovesItemAndWritesTombstone() {
        seed("a", "b", "c");
        feed.deleteArticle("b");
        var page = feed.pageNews(1, 10, "", "");
        assertEquals(2, page.total());
        assertEquals("a", page.records().get(0).id());
        // 墓碑已写入缓存文档（按标题/链接指纹存，第一段仍是原始 id）
        NewsCacheStore cache = new NewsCacheStore(new online.yudream.base.plugin.mcnews.infrastructure.McNewsStore(documents));
        assertEquals(1, cache.read().tombstones().size());
        assertEquals("b", NewsFingerprint.entryId(cache.read().tombstones().get(0)));
        assertTrue(NewsFingerprint.flatten(cache.read().tombstones()).contains("t:标题 b"));
    }

    @Test
    void deleteUnknownIdThrows() {
        seed("a");
        assertThrows(IllegalArgumentException.class, () -> feed.deleteArticle("missing"));
        assertThrows(IllegalArgumentException.class, () -> feed.deleteArticle(" "));
    }

    @Test
    void deleteTwiceThrowsOnSecond() {
        seed("a", "b");
        feed.deleteArticle("a");
        assertThrows(IllegalArgumentException.class, () -> feed.deleteArticle("a"));
    }

    @Test
    void clearEmptiesItemsWithoutTombstones() {
        seed("a", "b", "c");
        NewsFeedService.ClearResult result = feed.clearArticles();
        assertEquals(3, result.cleared());
        assertFalse(result.cacheCleared(), "默认不清轮询缓存");
        assertFalse(result.pushOnNextPoll());
        var page = feed.pageNews(1, 10, "", "");
        assertEquals(0, page.total());
        NewsCacheStore cache = cache();
        assertTrue(cache.read().items().isEmpty());
        assertTrue(cache.read().tombstones().isEmpty());
    }

    /** 默认（不清缓存）：去重记忆与未送达队列原样保留，也不给下一次轮询留「只重建基线」标记。 */
    @Test
    void clearWithoutCacheKeepsDedupMemoryAndQueue() {
        seed("a", "b");
        NewsCacheStore cache = cache();
        cache.writePoll(cache.read().items(), List.of("t:旧标题"), List.of("old-id"));
        assertEquals(1, feed.pendingCount());

        NewsFeedService.ClearResult result = feed.clearArticles();
        assertEquals(2, result.cleared());
        assertFalse(result.cacheCleared());

        var snapshot = cache.read();
        assertTrue(snapshot.items().isEmpty());
        assertTrue(snapshot.seen().contains("t:旧标题"), "去重记忆保留");
        assertTrue(snapshot.pending().contains("old-id"), "未送达队列保留");
        assertEquals(1, feed.pendingCount());
        assertFalse(snapshot.baselineOnly(), "只清列表时不需要基线标记");
    }

    /** 勾选「同时清空缓存」但默认不推送：去重记忆与未送达队列一并清空，并留下只重建基线的标记。 */
    @Test
    void clearWithCacheMarksBaselineOnlyByDefault() {
        seed("a", "b");
        feed.deleteArticle("a");
        NewsCacheStore cache = cache();
        cache.writePoll(cache.read().items(), List.of("t:旧标题"), List.of("old-id"));

        NewsFeedService.ClearResult result = feed.clearArticles(true, false);
        assertEquals(1, result.cleared());
        assertTrue(result.cacheCleared());
        assertFalse(result.pushOnNextPoll());

        var snapshot = cache.read();
        assertTrue(snapshot.items().isEmpty());
        assertTrue(snapshot.seen().isEmpty(), "去重记忆一并清空");
        assertTrue(snapshot.pending().isEmpty(), "未送达队列一并清空");
        assertEquals(0, feed.pendingCount());
        assertEquals(1, snapshot.tombstones().size(), "忽略名单保留：被删的新闻不会被重建带回");
        assertTrue(snapshot.baselineOnly(), "默认给下一次轮询留下「只重建基线、不推送」标记");
        assertFalse(snapshot.fresh(), "基线时间已置为当前，与「装好后第一次轮询」区分开");
    }

    /** 勾选「同时清空缓存 + 下次轮询推送」：不置基线标记，下一次轮询照常推送。 */
    @Test
    void clearWithCacheAndPushLeavesNoBaselineMark() {
        seed("a");
        NewsCacheStore cache = cache();
        NewsFeedService.ClearResult result = feed.clearArticles(true, true);
        assertTrue(result.cacheCleared());
        assertTrue(result.pushOnNextPoll());
        assertFalse(cache.read().baselineOnly());
        assertFalse(cache.read().fresh());
        assertTrue(cache.read().items().isEmpty());
    }

    @Test
    void clearTombstonesRestoresDiscovery() {
        seed("a", "b");
        feed.deleteArticle("a");
        assertEquals(1, feed.tombstoneCount());
        long cleared = feed.clearTombstones();
        assertEquals(1, cleared);
        assertEquals(0, feed.tombstoneCount());
    }

    @Test
    void writeItemsAlwaysPreservesCurrentTombstones() {
        seed("a", "b");
        feed.deleteArticle("a");
        NewsCacheStore cache = new NewsCacheStore(new online.yudream.base.plugin.mcnews.infrastructure.McNewsStore(documents));
        // 模拟轮询侧只关心 items 的写盘：墓碑必须保留当前值，不被覆盖丢失
        cache.writeItems(List.of(article("c")));
        var snapshot = cache.read();
        assertEquals(1, snapshot.tombstones().size());
        assertEquals("a", NewsFingerprint.entryId(snapshot.tombstones().get(0)));
        assertEquals(1, snapshot.items().size());
        assertEquals("c", snapshot.items().get(0).id());
    }

    @Test
    void capTombstonesKeepsLatestTail() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < NewsCacheStore.MAX_TOMBSTONES + 50; i++) {
            ids.add("id-" + i);
        }
        List<String> capped = NewsCacheStore.capTombstones(ids);
        assertEquals(NewsCacheStore.MAX_TOMBSTONES, capped.size());
        assertEquals("id-" + (NewsCacheStore.MAX_TOMBSTONES + 49), capped.get(capped.size() - 1));
        assertEquals("id-50", capped.get(0));
    }
}
