package online.yudream.base.plugin.mcnews.infrastructure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import online.yudream.base.plugin.mcnews.application.NewsPipeline;
import online.yudream.base.plugin.mcnews.domain.NewsArticle;
import online.yudream.base.plugin.mcnews.domain.NewsFingerprint;

/**
 * 已见新闻状态的统一读写入口
 * （mc_news_seen/cache 单文档：items + tombstones + seen + pending + baselineAt + baselineOnly）。
 * 轮询写缓存与管理端删除/清空都必须经过这里，避免互相覆盖字段。
 *
 * <ul>
 *   <li>items：新闻动态展示列表，按 cacheSize 裁剪；</li>
 *   <li>seen：长期去重记忆（标题/链接指纹条目），上限 {@link #MAX_SEEN_ENTRIES}，远大于展示缓存，
 *       被展示缓存裁掉的旧新闻仍不会重复推送；</li>
 *   <li>pending：未送达队列（同样是指纹条目）。推送失败、或推送时没有任何可用目标的条目记在这里，
 *       后续轮询继续补推，直到至少一个目标送达成功才移出——「见过」不等于「推过」，
 *       不会再出现「内容被判重复、却从未推送成功且永不再推」的情况；</li>
 *   <li>tombstones：忽略名单（同样是指纹条目，兼容只存 id 的历史数据），被手动删除的新闻既不推送也不回填；
 *       pending 中命中忽略名单的条目会一并剔除；</li>
 *   <li>baselineAt：首次轮询建立基线的时间戳。清空轮询缓存后据此区分「装好后第一次轮询」与「显式重置」：
 *       前者只建基线避免装好即轰炸，后者照常推送；</li>
 *   <li>baselineOnly：清空轮询缓存时选择「下一次轮询不推送」留下的标记。置位后下一次轮询只重建基线
 *       （回填列表 + 重建去重记录）而不发送任何推送，与 pushOnFirstPoll 设置无关；基线建立后标记被消费清除，
 *       因此抓取全失败的那一轮不会白白吃掉该标记。</li>
 * </ul>
 */
public final class NewsCacheStore {
    public static final int MAX_TOMBSTONES = 500;
    /** 长期去重记忆上限：cacheSize 最多 200，2000 条指纹足以覆盖数月轮询，超出才淘汰最旧。 */
    public static final int MAX_SEEN_ENTRIES = 2000;
    /** 未送达队列上限：推送目标长期不可用时不会无限堆积，超出先淘汰最旧。 */
    public static final int MAX_PENDING_ENTRIES = 200;

    private final McNewsStore store;

    public NewsCacheStore(McNewsStore store) {
        this.store = store;
    }

    public record Snapshot(List<NewsArticle> items, List<String> tombstones, List<String> seen,
                           List<String> pending, long baselineAt, boolean baselineOnly) {
        /** 忽略名单展开后的匹配键（原始 id / 标题键 / 链接键）。 */
        public Set<String> ignoredKeys() {
            return NewsFingerprint.flatten(tombstones);
        }

        /** 判定「已见过」的全部匹配键：长期去重记忆 + 忽略名单。 */
        public Set<String> knownKeys() {
            Set<String> keys = NewsFingerprint.flatten(seen);
            keys.addAll(ignoredKeys());
            return keys;
        }

        /** 未送达条目的匹配键：候选命中即进入补推队列。 */
        public Set<String> pendingKeys() {
            return NewsFingerprint.flatten(pending);
        }

        /** 是否还没建立过轮询基线（装好后第一次轮询）。 */
        public boolean fresh() {
            return baselineAt <= 0;
        }
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
        return new Snapshot(items, McNewsStore.stringList(doc, "tombstones"), McNewsStore.stringList(doc, "seen"),
                McNewsStore.stringList(doc, "pending"), McNewsStore.longOr(doc, "baselineAt", 0),
                McNewsStore.bool(doc, "baselineOnly", false));
    }

    /** 管理端写盘：items / tombstones 以调用方为准，去重记忆、未送达队列与基线状态保持不变。 */
    public void write(List<NewsArticle> items, List<String> tombstones) {
        Snapshot current = read();
        save(items, tombstones, current.seen(), current.pending(), current.baselineAt(), current.baselineOnly());
    }

    /** 管理端写盘：items / tombstones / seen 以调用方为准，未送达队列与基线状态保持不变。 */
    public void write(List<NewsArticle> items, List<String> tombstones, List<String> seen) {
        Snapshot current = read();
        save(items, tombstones, seen, current.pending(), current.baselineAt(), current.baselineOnly());
    }

    /** 仅展示列表变化的写盘：忽略名单与去重记忆取当前最新值。 */
    public void writeItems(List<NewsArticle> items) {
        writePoll(items, read().seen());
    }

    /** 轮询侧写盘（未送达队列与基线标记维持当前值）：见 {@link #writePoll(List, List, List)}。 */
    public void writePoll(List<NewsArticle> items, List<String> seen) {
        Snapshot current = read();
        writePoll(items, seen, current.pending(), current.baselineOnly());
    }

    /**
     * 轮询侧写盘：items / seen / pending 以调用方为准，忽略名单始终取当前最新值——管理端可能并发删除，
     * 禁止用轮询开始时的旧值覆盖；同时按忽略名单过滤列表与未送达队列，轮询途中被删除的条目
     * 既不会被写回，也不会再进入补推队列。baselineOnly 标记维持当前值（尚未消费）。
     */
    public void writePoll(List<NewsArticle> items, List<String> seen, List<String> pending) {
        writePoll(items, seen, pending, read().baselineOnly());
    }

    /**
     * 建立缓存基线（首次运行，或清空轮询缓存时选择「下一次轮询不推送」）：
     * 与 {@link #writePoll(List, List, List)} 相同，但消费（清除）baselineOnly 标记。
     */
    public void writePollBaseline(List<NewsArticle> items, List<String> seen, List<String> pending) {
        writePoll(items, seen, pending, false);
    }

    private void writePoll(List<NewsArticle> items, List<String> seen, List<String> pending, boolean baselineOnly) {
        Snapshot current = read();
        List<String> tombstones = current.tombstones();
        Set<String> ignored = NewsFingerprint.flatten(tombstones);
        List<NewsArticle> filtered = new ArrayList<>();
        for (NewsArticle item : items) {
            if (ignored.isEmpty() || Collections.disjoint(NewsFingerprint.keysOf(item), ignored)) {
                filtered.add(item);
            }
        }
        List<String> keptPending = new ArrayList<>();
        for (String entry : pending) {
            if (ignored.isEmpty() || Collections.disjoint(NewsFingerprint.keysOfEntry(entry), ignored)) {
                keptPending.add(entry);
            }
        }
        // 基线时间戳由轮询固化：管理端写盘不会把「还没轮询过」变成「已经轮询过」
        save(filtered, tombstones, seen, keptPending,
                current.baselineAt() > 0 ? current.baselineAt() : System.currentTimeMillis(), baselineOnly);
    }

    /**
     * 清空轮询缓存（管理端「清空动态 → 同时清空缓存」）：展示列表、去重记忆、未送达队列一并清空，
     * 忽略名单保留。返回被清空的展示列表条数。
     *
     * <p>{@code pushOnNextPoll=false}（默认）时打上 baselineOnly 标记，下一次轮询只重建基线：
     * 源里现存内容回填列表并重新登记去重记录，但一条都不推送。为 true 时相反——基线时间置为当前、
     * 不置标记，源里现存内容在下一次轮询会被视为新内容而重新推送一轮。
     */
    public long clearPollCache(boolean pushOnNextPoll) {
        Snapshot current = read();
        save(List.of(), current.tombstones(), List.of(), List.of(),
                System.currentTimeMillis(), !pushOnNextPoll);
        return current.items().size();
    }

    private void save(List<NewsArticle> items, List<String> tombstones, List<String> seen,
                      List<String> pending, long baselineAt, boolean baselineOnly) {
        List<Map<String, Object>> docs = new ArrayList<>();
        for (NewsArticle item : items) {
            docs.add(NewsPipeline.articleToDoc(item));
        }
        Map<String, Object> doc = new HashMap<>();
        doc.put("items", docs);
        doc.put("tombstones", capTombstones(tombstones));
        doc.put("seen", capSeen(mergeSeen(seen, items)));
        doc.put("pending", capPending(pending));
        doc.put("baselineAt", baselineAt);
        doc.put("baselineOnly", baselineOnly);
        doc.put("updatedAt", System.currentTimeMillis());
        store.save(McNewsStore.COL_SEEN, McNewsStore.DOC_SEEN, doc);
    }

    /** 当前忽略名单的匹配键：推送前复查，轮询途中被手动删除的条目不会再被推送。 */
    public Set<String> ignoredKeys() {
        return NewsFingerprint.flatten(read().tombstones());
    }

    /** 保留最近 MAX_TOMBSTONES 个（列表尾部为最新）。 */
    public static List<String> capTombstones(List<String> ids) {
        return cap(ids, MAX_TOMBSTONES);
    }

    /** 保留最近 MAX_SEEN_ENTRIES 个（列表尾部为最新）。 */
    public static List<String> capSeen(List<String> entries) {
        return cap(entries, MAX_SEEN_ENTRIES);
    }

    /** 保留最近 MAX_PENDING_ENTRIES 个（列表尾部为最新）。 */
    public static List<String> capPending(List<String> entries) {
        return cap(entries, MAX_PENDING_ENTRIES);
    }

    /** 补齐列表内条目的指纹：升级前写入的历史数据、清空后重建的列表都不会漏记去重信息。 */
    private static List<String> mergeSeen(List<String> seen, List<NewsArticle> items) {
        List<String> result = new ArrayList<>(seen);
        Set<String> ids = new HashSet<>(NewsFingerprint.entryIds(result));
        for (NewsArticle item : items) {
            if (item.id() != null && !item.id().isBlank() && ids.add(item.id())) {
                result.add(NewsFingerprint.entry(item));
            }
        }
        return result;
    }

    private static List<String> cap(List<String> entries, int limit) {
        if (entries.size() <= limit) {
            return new ArrayList<>(entries);
        }
        return new ArrayList<>(entries.subList(entries.size() - limit, entries.size()));
    }
}
