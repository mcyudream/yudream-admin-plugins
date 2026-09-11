package online.yudream.base.plugin.mcnews.infrastructure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.mcnews.application.NewsPipeline;
import online.yudream.base.plugin.mcnews.domain.NewsArticle;

/**
 * 已见新闻缓存的统一读写入口（mc_news_seen/cache 单文档：items + tombstones）。
 * 轮询写缓存与管理端删除/清空都必须经过这里，避免互相覆盖字段。
 * tombstones 记录被手动删除的新闻 ID，轮询对比时跳过，防止删除后下轮重新推送；上限 500，超出淘汰最旧。
 */
public final class NewsCacheStore {
    public static final int MAX_TOMBSTONES = 500;

    private final McNewsStore store;

    public NewsCacheStore(McNewsStore store) {
        this.store = store;
    }

    public record Snapshot(List<NewsArticle> items, List<String> tombstones) {
    }

    public Snapshot read() {
        Map<String, Object> doc = store.find(McNewsStore.COL_SEEN, McNewsStore.DOC_SEEN).orElse(Map.of());
        List<NewsArticle> items = new ArrayList<>();
        for (Map<String, Object> entry : McNewsStore.mapList(doc, "items")) {
            NewsArticle item = NewsPipeline.articleFromDoc(entry);
            if (item != null) {
                items.add(item);
            }
        }
        return new Snapshot(items, McNewsStore.stringList(doc, "tombstones"));
    }

    public void write(List<NewsArticle> items, List<String> tombstones) {
        List<Map<String, Object>> docs = new ArrayList<>();
        for (NewsArticle item : items) {
            docs.add(NewsPipeline.articleToDoc(item));
        }
        Map<String, Object> doc = new HashMap<>();
        doc.put("items", docs);
        doc.put("tombstones", capTombstones(tombstones));
        doc.put("updatedAt", System.currentTimeMillis());
        store.save(McNewsStore.COL_SEEN, McNewsStore.DOC_SEEN, doc);
    }

    /** 轮询侧写缓存：items 以调用方为准，墓碑始终取当前最新值——管理端可能并发清空/删除，禁止用轮询开始时的旧值覆盖。 */
    public void writeItems(List<NewsArticle> items) {
        write(items, read().tombstones());
    }

    /** 保留最近 MAX_TOMBSTONES 个（列表尾部为最新）。 */
    public static List<String> capTombstones(List<String> ids) {
        if (ids.size() <= MAX_TOMBSTONES) {
            return new ArrayList<>(ids);
        }
        return new ArrayList<>(ids.subList(ids.size() - MAX_TOMBSTONES, ids.size()));
    }
}
