package online.yudream.base.plugin.mcnews.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import online.yudream.base.plugin.mcnews.domain.NewsArticle;
import online.yudream.base.plugin.mcnews.infrastructure.NewsCacheStore;
import org.junit.jupiter.api.Test;

class NewsFeedServiceTest {

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final NewsFeedService feed = new NewsFeedService(new online.yudream.base.plugin.mcnews.infrastructure.McNewsStore(documents));

    private static NewsArticle article(String id) {
        return new NewsArticle(id, "src", "源", "标题 " + id, "摘要", "", "News",
                "https://example.com/" + id, "", 0, 1, 0, NewsArticle.STATE_PUSHED);
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
        // 墓碑已写入缓存文档
        NewsCacheStore cache = new NewsCacheStore(new online.yudream.base.plugin.mcnews.infrastructure.McNewsStore(documents));
        assertEquals(List.of("b"), cache.read().tombstones());
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
        long cleared = feed.clearArticles();
        assertEquals(3, cleared);
        var page = feed.pageNews(1, 10, "", "");
        assertEquals(0, page.total());
        NewsCacheStore cache = new NewsCacheStore(new online.yudream.base.plugin.mcnews.infrastructure.McNewsStore(documents));
        assertTrue(cache.read().items().isEmpty());
        assertTrue(cache.read().tombstones().isEmpty());
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
        assertEquals(List.of("a"), snapshot.tombstones());
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
