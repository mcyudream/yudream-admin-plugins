package online.yudream.base.plugin.mcnews.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import online.yudream.base.plugin.mcnews.domain.NewsArticle;
import online.yudream.base.plugin.mcnews.domain.NewsFingerprint;
import online.yudream.base.plugin.mcnews.domain.NewsPushLog;
import online.yudream.base.plugin.mcnews.infrastructure.McNewsStore;
import online.yudream.base.plugin.mcnews.infrastructure.NewsCacheStore;

/**
 * 管理端查询与维护：新闻动态（展示缓存切片分页 + 筛选 + 删除/清空）与推送记录（后端分页）。
 * 展示缓存天然有界（cacheSize 上限 200），在服务端切片后仍以 records+total 形式分页返回。
 *
 * <p>删除/清空只影响展示列表与轮询行为：删除写入忽略名单（标题/链接指纹），
 * 该新闻既不推送也不会被回填；去重记忆（seen）不受影响，因此清空动态后旧新闻只会回填列表而不会重新推送。
 *
 * <p>清空动态有两个可选项（默认均为否）：是否连轮询缓存（去重记忆 + 未送达队列）一并清空；
 * 清空缓存时，下一次轮询是否把源里现存内容重新推送一轮。
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

    /** 从动态中删除一条：写入忽略名单（含标题/链接指纹），既不重新推送也不会被下轮回填。 */
    public void deleteArticle(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("新闻 ID 不能为空");
        }
        NewsCacheStore.Snapshot snapshot = cache.read();
        List<NewsArticle> remaining = new ArrayList<>(snapshot.items());
        NewsArticle removed = null;
        for (NewsArticle item : remaining) {
            if (id.equals(item.id())) {
                removed = item;
                break;
            }
        }
        if (removed == null) {
            throw new IllegalArgumentException("该新闻不在动态缓存中，可能已被删除");
        }
        remaining.remove(removed);
        List<String> tombstones = new ArrayList<>(snapshot.tombstones());
        tombstones.add(NewsFingerprint.entry(removed));
        cache.write(remaining, NewsCacheStore.capTombstones(tombstones));
    }

    /** 清空动态：仅清空列表显示（默认），不写忽略名单、不清去重记忆；下轮只回填列表，已推送过的不会重复推送。 */
    public ClearResult clearArticles() {
        return clearArticles(false, false);
    }

    /**
     * 清空动态：
     * <ul>
     *   <li>{@code clearCache=false}（默认）：只清列表显示，去重记忆与未送达队列保留，
     *       下轮轮询把源里现存内容回填进列表但不推送（{@code pushOnNextPoll} 无意义，忽略）；</li>
     *   <li>{@code clearCache=true}：展示列表、去重记忆、未送达队列一并清空（忽略名单保留）。
     *       {@code pushOnNextPoll=false} 时下一次轮询只重建基线（回填列表 + 重建去重记录，一条都不推），
     *       为 true 时下一次轮询把源里现存内容当作新内容重新推送一轮。</li>
     * </ul>
     */
    public ClearResult clearArticles(boolean clearCache, boolean pushOnNextPoll) {
        NewsCacheStore.Snapshot snapshot = cache.read();
        long cleared = snapshot.items().size();
        if (!clearCache) {
            cache.write(List.of(), snapshot.tombstones());
            return new ClearResult(cleared, false, false);
        }
        cache.clearPollCache(pushOnNextPoll);
        return new ClearResult(cleared, true, pushOnNextPoll);
    }

    /**
     * 清空忽略名单：这些新闻重新参与轮询——同时把它们从去重记忆中移除，
     * 否则它们会因「已见过」而永远不再推送。返回清除的忽略名单条数。
     */
    public long clearTombstones() {
        NewsCacheStore.Snapshot snapshot = cache.read();
        Set<String> ignoredIds = NewsFingerprint.entryIds(snapshot.tombstones());
        List<String> seen = new ArrayList<>();
        for (String entry : snapshot.seen()) {
            if (!ignoredIds.contains(NewsFingerprint.entryId(entry))) {
                seen.add(entry);
            }
        }
        cache.write(snapshot.items(), List.of(), seen);
        return snapshot.tombstones().size();
    }

    /** 未送达队列条数（推送失败或当时没有可用目标的内容，后续轮询会补推）。 */
    public long pendingCount() {
        return cache.read().pending().size();
    }

    public long tombstoneCount() {
        return cache.read().tombstones().size();
    }

    /** 长期去重记忆条数（标题/链接指纹）。 */
    public long seenCount() {
        return cache.read().seen().size();
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

    /** 清空结果：清掉的动态条数 + 是否连轮询缓存一并清空 + 下一次轮询是否重新推送。 */
    public record ClearResult(long cleared, boolean cacheCleared, boolean pushOnNextPoll) {
    }
}
