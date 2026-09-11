package online.yudream.base.plugin.mcnews.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import online.yudream.base.plugin.mcnews.domain.NewsArticle;
import online.yudream.base.plugin.mcnews.domain.NewsPushLog;
import online.yudream.base.plugin.mcnews.infrastructure.McNewsStore;
import online.yudream.base.plugin.mcnews.infrastructure.NewsCacheStore;

/**
 * 管理端查询与维护：新闻动态（已见缓存切片分页 + 筛选 + 删除/清空）与推送记录（后端分页）。
 * 缓存条目天然有界（cacheSize 上限 200），在服务端切片后仍以 records+total 形式分页返回。
 * 删除/清空的条目进入墓碑名单，轮询对比时跳过，不会在下轮重新推送。
 */
public final class NewsFeedService {
    private final McNewsStore store;
    private final NewsCacheStore cache;

    public NewsFeedService(McNewsStore store) {
        this.store = store;
        this.cache = new NewsCacheStore(store);
    }

    public PageResult<NewsArticle> pageNews(int page, int size, String sourceId, String keyword) {
        List<NewsArticle> filtered = new ArrayList<>();
        String lowered = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        for (NewsArticle item : cache.read().items()) {
            if (sourceId != null && !sourceId.isBlank() && !sourceId.equals(item.sourceId())) {
                continue;
            }
            if (!lowered.isEmpty()
                    && !(item.title() + " " + item.summary() + " " + item.aiSummary()).toLowerCase(Locale.ROOT)
                            .contains(lowered)) {
                continue;
            }
            filtered.add(item);
        }
        return slice(filtered, page, size);
    }

    /** 从动态中删除一条：同时写入墓碑，防止下轮轮询重新推送。 */
    public void deleteArticle(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("新闻 ID 不能为空");
        }
        NewsCacheStore.Snapshot snapshot = cache.read();
        List<NewsArticle> remaining = new ArrayList<>(snapshot.items());
        boolean removed = remaining.removeIf(item -> id.equals(item.id()));
        if (!removed) {
            throw new IllegalArgumentException("该新闻不在动态缓存中，可能已被删除");
        }
        List<String> tombstones = new ArrayList<>(snapshot.tombstones());
        tombstones.add(id);
        cache.write(remaining, NewsCacheStore.capTombstones(tombstones));
    }

    /** 清空动态：仅清空列表显示，不写墓碑；下轮轮询将重建缓存基线（默认不重新推送）。 */
    public long clearArticles() {
        NewsCacheStore.Snapshot snapshot = cache.read();
        cache.write(new ArrayList<>(), snapshot.tombstones());
        return snapshot.items().size();
    }

    /** 清空忽略名单：被单条删除拦截的新闻恢复参与发现（下轮可能作为新内容推送）；返回清除数量。 */
    public long clearTombstones() {
        NewsCacheStore.Snapshot snapshot = cache.read();
        long removed = snapshot.tombstones().size();
        cache.write(snapshot.items(), new ArrayList<>());
        return removed;
    }

    public long tombstoneCount() {
        return cache.read().tombstones().size();
    }

    public PageResult<NewsPushLog> pageLogs(int page, int size) {
        long total = store.count(McNewsStore.COL_LOGS);
        List<NewsPushLog> logs = new ArrayList<>();
        for (Map<String, Object> doc : store.page(McNewsStore.COL_LOGS, page, size)) {
            NewsPushLog log = logFromDoc(doc);
            if (log != null) {
                logs.add(log);
            }
        }
        return new PageResult<>(logs, total);
    }

    private <T> PageResult<T> slice(List<T> records, int page, int size) {
        int from = Math.min((page - 1) * size, records.size());
        int to = Math.min(from + size, records.size());
        return new PageResult<>(records.subList(from, to), records.size());
    }

    public static NewsPushLog logFromDoc(Map<String, Object> doc) {
        String id = McNewsStore.str(doc, "id");
        if (id.isBlank()) {
            return null;
        }
        List<NewsPushLog.TargetResult> results = new ArrayList<>();
        for (Map<String, Object> entry : McNewsStore.mapList(doc, "results")) {
            results.add(new NewsPushLog.TargetResult(
                    McNewsStore.str(entry, "targetId"),
                    McNewsStore.str(entry, "targetName"),
                    McNewsStore.str(entry, "targetType"),
                    McNewsStore.bool(entry, "ok", false),
                    McNewsStore.str(entry, "error")));
        }
        return new NewsPushLog(id,
                McNewsStore.longOr(doc, "createdAt", 0),
                McNewsStore.strOr(doc, "mode", "poll"),
                McNewsStore.str(doc, "articleId"),
                McNewsStore.str(doc, "title"),
                McNewsStore.str(doc, "url"),
                McNewsStore.str(doc, "sourceName"),
                (int) McNewsStore.longOr(doc, "total", 0),
                (int) McNewsStore.longOr(doc, "okCount", 0),
                results);
    }

    public record PageResult<T>(List<T> records, long total) {
    }
}
