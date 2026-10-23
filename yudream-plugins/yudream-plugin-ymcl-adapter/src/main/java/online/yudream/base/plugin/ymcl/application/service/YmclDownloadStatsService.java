package online.yudream.base.plugin.ymcl.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;
import online.yudream.base.plugin.spi.system.user.PluginUserService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 域下载统计（YAP 下载分发扩展）：YDA 适配的镜像网关在玩家鉴权下载后
 * 批量回传事件（{@code POST /v1/download/stats/report}，网关共享密钥），
 * 适配器落明细并维护日聚合、累计与玩家档案，供宿主管理端「下载统计」页
 * 查询。玩家归因来自下载令牌校验结果，网关不上报自造身份。
 *
 * <p>文档形态（{@link PluginDocumentStore}，落 {@code plugin_ymcl_adapter__*}，
 * 与 {@link YmclStatsService} 四集合模式一致）：
 * <ul>
 * <li>{@code ymcl_download_events} 明细事件；id 零填充毫秒时间戳+序号，
 *     findAll 的 _id 升序即时间升序，翻到末页即最近事件
 * <li>{@code ymcl_download_daily} id {@code d:{yyyyMMdd}}：当日请求数、
 *     完成下载数、字节量与当日活跃玩家
 * <li>{@code ymcl_download_totals} id {@code all} / {@code c:{category}} /
 *     {@code s:{source}}：累计请求/下载/字节与末次下载时间
 * <li>{@code ymcl_download_users} id {@code u:{userId}}：玩家档案
 * </ul>
 *
 * <p>计数为读-改-写，同一毫秒并发上报可能丢计数，事件明细兜底可重算；
 * 网关侧按批限流上报，v1 接受该窗口。
 */
public class YmclDownloadStatsService {

    private static final String EVENTS_COLLECTION = "ymcl_download_events";
    private static final String DAILY_COLLECTION = "ymcl_download_daily";
    private static final String TOTALS_COLLECTION = "ymcl_download_totals";
    private static final String USERS_COLLECTION = "ymcl_download_users";

    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final int PAGE_SIZE = 200;
    private static final int MAX_BATCH = 500;
    /** 明细保留上限：滚动清到该值以内，防止无界增长。 */
    private static final long MAX_EVENTS = 20_000;

    private final PluginDocumentStore documents;
    private final PluginUserService users;
    private final java.util.concurrent.atomic.AtomicLong eventSequence =
            new java.util.concurrent.atomic.AtomicLong();

    public YmclDownloadStatsService(PluginDocumentStore documents, PluginUserService users) {
        this.documents = documents;
        this.users = users;
    }

    /**
     * 记录一批网关上报的下载事件。playerId 来自令牌校验结果（YDA userId），
     * 事件字段：category、source、host、path、bytes、status、occurredAt。
     * 返回实际入库条数。
     */
    public int recordBatch(Long playerId, List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        int recorded = 0;
        for (Map<String, Object> event : events.subList(0, Math.min(events.size(), MAX_BATCH))) {
            record(playerId, event);
            recorded++;
        }
        trimEvents();
        return recorded;
    }

    private void record(Long playerId, Map<String, Object> event) {
        long now = System.currentTimeMillis();
        String userKey = String.valueOf(playerId);
        String category = str(event.get("category"));
        String source = str(event.get("source"));
        long bytes = longValue(event.get("bytes"));
        long occurredAt = longValue(event.get("occurredAt"));
        if (occurredAt <= 0 || occurredAt > now) {
            occurredAt = now;
        }
        boolean downloaded = isDownloadStatus(event.get("status"));

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("userId", userKey);
        doc.put("category", category);
        doc.put("source", source);
        doc.put("host", str(event.get("host")));
        doc.put("path", str(event.get("path")));
        doc.put("bytes", bytes);
        doc.put("status", longValue(event.get("status")));
        doc.put("occurredAt", occurredAt);
        documents.save(EVENTS_COLLECTION, eventId(occurredAt), doc);

        bumpDaily(dayKey(occurredAt), userKey, bytes, downloaded);
        bumpTotal("all", null, bytes, downloaded, occurredAt);
        if (category != null) {
            bumpTotal("category", category, bytes, downloaded, occurredAt);
        }
        if (source != null) {
            bumpTotal("source", source, bytes, downloaded, occurredAt);
        }
        upsertUser(playerId, category, source, bytes, downloaded, occurredAt);
    }

    /** 管理端摘要：总量/玩家、按日序列、类别与来源 Top。 */
    public Map<String, Object> summary(int days) {
        int span = Math.max(1, Math.min(days, 60));
        LocalDate today = LocalDate.now(ZONE);

        Map<String, Object> all = documents.findById(TOTALS_COLLECTION, "all").orElse(null);

        List<Map<String, Object>> series = new ArrayList<>();
        long todayRequests = 0;
        long todayBytes = 0;
        int todayUsers = 0;
        for (int offset = span - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            Map<String, Object> daily = documents.findById(DAILY_COLLECTION, "d:" + DAY_KEY.format(date))
                    .orElse(null);
            long requests = daily == null ? 0 : longValue(daily.get("total"));
            long bytes = daily == null ? 0 : longValue(daily.get("bytes"));
            int dayUsers = daily == null || daily.get("users") == null
                    ? 0 : mapValue(daily.get("users")).size();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("day", DAY_LABEL.format(date));
            entry.put("requests", requests);
            entry.put("downloads", daily == null ? 0 : longValue(daily.get("downloads")));
            entry.put("bytes", bytes);
            entry.put("users", dayUsers);
            series.add(entry);
            if (offset == 0) {
                todayRequests = requests;
                todayBytes = bytes;
                todayUsers = dayUsers;
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalRequests", all == null ? 0 : longValue(all.get("total")));
        payload.put("totalDownloads", all == null ? 0 : longValue(all.get("downloads")));
        payload.put("totalBytes", all == null ? 0 : longValue(all.get("bytes")));
        payload.put("totalPlayers", documents.count(USERS_COLLECTION));
        payload.put("todayRequests", todayRequests);
        payload.put("todayBytes", todayBytes);
        payload.put("todayActivePlayers", todayUsers);
        payload.put("days", series);
        payload.put("topCategories", topScopes("category", "downloads"));
        payload.put("topSources", topScopes("source", "downloads"));
        payload.put("generatedAt", System.currentTimeMillis());
        return payload;
    }

    /** 管理端最近下载动态（新到旧），带玩家名快照。 */
    public Map<String, Object> recentEvents(int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        long total = documents.count(EVENTS_COLLECTION);
        List<Map<String, Object>> events = new ArrayList<>();
        if (total > 0) {
            int lastPage = (int) ((total - 1) / PAGE_SIZE) + 1;
            List<Map<String, Object>> batch = documents.findAll(EVENTS_COLLECTION, lastPage, PAGE_SIZE);
            for (int index = batch.size() - 1; index >= 0 && events.size() < size; index--) {
                events.add(eventView(batch.get(index)));
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("events", events);
        payload.put("total", total);
        return payload;
    }

    /** 管理端玩家下载档案 Top（按累计字节），带用户名快照。 */
    public Map<String, Object> topPlayers(int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        List<Map<String, Object>> players = new ArrayList<>();
        int page = 1;
        while (players.size() < 2_000) {
            List<Map<String, Object>> batch = documents.findAll(USERS_COLLECTION, page, PAGE_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            players.addAll(batch);
            page++;
        }
        players.sort(Comparator.comparingLong((Map<String, Object> doc) -> longValue(doc.get("bytes")))
                .reversed());
        List<Map<String, Object>> top = new ArrayList<>();
        for (Map<String, Object> doc : players.subList(0, Math.min(players.size(), size))) {
            Map<String, Object> view = new LinkedHashMap<>(doc);
            String userId = String.valueOf(doc.get("userId"));
            documents.findById(USERS_COLLECTION, "u:" + userId).ifPresent(stored -> {
                Object nickname = stored.get("nickname");
                view.put("displayName", nickname != null && !String.valueOf(nickname).isBlank()
                        ? nickname
                        : stored.get("username"));
            });
            top.add(view);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("players", top);
        return payload;
    }

    // ── 内部：聚合文档读-改-写 ─────────────────────────────────────────────

    private void bumpDaily(String day, String userKey, long bytes, boolean downloaded) {
        String id = "d:" + day;
        Map<String, Object> doc = new LinkedHashMap<>(documents.findById(DAILY_COLLECTION, id).orElse(Map.of()));
        doc.put("scope", "day");
        doc.put("day", day);
        doc.put("total", longValue(doc.get("total")) + 1);
        doc.put("downloads", longValue(doc.get("downloads")) + (downloaded ? 1 : 0));
        doc.put("bytes", longValue(doc.get("bytes")) + bytes);
        Map<String, Object> dayUsers = new LinkedHashMap<>(mapValue(doc.get("users")));
        dayUsers.put(userKey, longValue(dayUsers.get(userKey)) + 1);
        doc.put("users", dayUsers);
        doc.put("updatedAt", System.currentTimeMillis());
        documents.save(DAILY_COLLECTION, id, doc);
    }

    private void bumpTotal(String kind, String refId, long bytes, boolean downloaded, long now) {
        String id = switch (kind) {
            case "category" -> "c:" + refId;
            case "source" -> "s:" + refId;
            default -> "all";
        };
        Map<String, Object> doc = new LinkedHashMap<>(documents.findById(TOTALS_COLLECTION, id).orElse(Map.of()));
        doc.put("kind", kind);
        doc.put("refId", refId);
        doc.put("total", longValue(doc.get("total")) + 1);
        doc.put("downloads", longValue(doc.get("downloads")) + (downloaded ? 1 : 0));
        doc.put("bytes", longValue(doc.get("bytes")) + bytes);
        doc.put("lastDownloadAt", now);
        doc.put("updatedAt", now);
        documents.save(TOTALS_COLLECTION, id, doc);
    }

    private void upsertUser(Long playerId, String category, String source, long bytes,
            boolean downloaded, long now) {
        String id = "u:" + playerId;
        Map<String, Object> doc = new LinkedHashMap<>(documents.findById(USERS_COLLECTION, id).orElse(Map.of()));
        boolean first = doc.isEmpty();
        PluginUserProfile profile = users.findById(playerId).orElse(null);
        if (profile != null) {
            doc.put("username", profile.username());
            doc.put("nickname", profile.nickname());
        }
        doc.put("userId", String.valueOf(playerId));
        doc.put("firstSeenAt", first ? now : longValue(doc.get("firstSeenAt")));
        doc.put("lastSeenAt", now);
        doc.put("requests", longValue(doc.get("requests")) + 1);
        doc.put("downloads", longValue(doc.get("downloads")) + (downloaded ? 1 : 0));
        doc.put("bytes", longValue(doc.get("bytes")) + bytes);
        doc.put("lastCategory", category);
        doc.put("lastSource", source);
        documents.save(USERS_COLLECTION, id, doc);
    }

    private List<Map<String, Object>> topScopes(String kind, String metric) {
        List<Map<String, Object>> scopes = new ArrayList<>();
        int page = 1;
        while (scopes.size() < 2_000) {
            List<Map<String, Object>> batch = documents.findByField(TOTALS_COLLECTION, "kind", kind, page, PAGE_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            scopes.addAll(batch);
            page++;
        }
        scopes.sort(Comparator.comparingLong((Map<String, Object> doc) -> longValue(doc.get(metric)))
                .reversed());
        List<Map<String, Object>> top = new ArrayList<>();
        for (Map<String, Object> doc : scopes.subList(0, Math.min(scopes.size(), 10))) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("refId", String.valueOf(doc.get("refId")));
            entry.put("requests", longValue(doc.get("total")));
            entry.put("downloads", longValue(doc.get("downloads")));
            entry.put("bytes", longValue(doc.get("bytes")));
            entry.put("lastDownloadAt", longValue(doc.get("lastDownloadAt")));
            top.add(entry);
        }
        return top;
    }

    private Map<String, Object> eventView(Map<String, Object> event) {
        Map<String, Object> view = new LinkedHashMap<>(event);
        String userId = event.get("userId") == null ? null : String.valueOf(event.get("userId"));
        if (userId != null) {
            Optional<Map<String, Object>> user = documents.findById(USERS_COLLECTION, "u:" + userId);
            user.ifPresent(stored -> {
                Object nickname = stored.get("nickname");
                view.put("username", nickname != null && !String.valueOf(nickname).isBlank()
                        ? nickname
                        : stored.get("username"));
            });
        }
        return view;
    }

    /** 明细超限时按 id 升序翻页删除最旧事件（_id 即时间序）。 */
    private void trimEvents() {
        long total = documents.count(EVENTS_COLLECTION);
        int excess = (int) Math.min(total - MAX_EVENTS, 1_000);
        if (excess <= 0) {
            return;
        }
        int deleted = 0;
        int page = 1;
        while (deleted < excess) {
            List<Map<String, Object>> batch = documents.findAll(EVENTS_COLLECTION, page, PAGE_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            for (Map<String, Object> event : batch) {
                if (deleted >= excess) {
                    break;
                }
                Object id = event.get("id");
                if (id != null) {
                    documents.delete(EVENTS_COLLECTION, String.valueOf(id));
                    deleted++;
                }
            }
            page++;
        }
    }

    private static boolean isDownloadStatus(Object status) {
        long value = longValue(status);
        return value >= 200 && value < 300;
    }

    /** 事件 id：零填充毫秒时间戳 + 进程序号，_id 升序即时间序。 */
    private String eventId(long now) {
        return String.format("%019d-%06d", now, eventSequence.incrementAndGet() % 1_000_000);
    }

    private static String dayKey(long epochMillis) {
        return DAY_KEY.format(java.time.Instant.ofEpochMilli(epochMillis).atZone(ZONE).toLocalDate());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object stored) {
        if (!(stored instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        return (Map<String, Object>) map;
    }

    private static long longValue(Object stored) {
        return stored instanceof Number number ? number.longValue() : 0L;
    }

    private static String str(Object stored) {
        if (stored == null) {
            return null;
        }
        String value = String.valueOf(stored).trim();
        return value.isEmpty() ? null : value;
    }
}
