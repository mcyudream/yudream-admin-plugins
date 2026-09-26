package online.yudream.base.plugin.ymcl.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;
import online.yudream.base.plugin.spi.system.user.PluginUserService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 域启动统计（YAP 扩展）：启动器在域整合包实例启动成功后经
 * {@code POST /v1/stats/launch} 上报，适配器落事件明细并维护日聚合、累计与
 * 用户档案，供宿主管理端「启动统计」页查询。
 *
 * <p>文档形态（{@link PluginDocumentStore}，最终落 {@code plugin_ymcl_adapter__*} 集合）：
 * <ul>
 * <li>{@code ymcl_launch_events} 明细事件；id 为零填充毫秒时间戳+序号，
 *     {@code findAll} 的 _id 升序即时间升序，翻到末页即最近事件
 * <li>{@code ymcl_launch_daily} id {@code d:{yyyyMMdd}}：当日启动数与当日活跃
 *     用户（userId → 次数）
 * <li>{@code ymcl_launch_totals} id {@code all} / {@code s:{serverId}} /
 *     {@code p:{packId}}：累计启动数、平台与启动器版本分布（entry 列表——
 *     Mongo 字段名不允许 "."，版本号不能直接当 map key）、末次启动时间
 * <li>{@code ymcl_launch_users} id {@code u:{userId}}：用户档案（首/末次启动、
 *     累计次数、最近目标、上报时的用户名快照）
 * </ul>
 *
 * <p>计数全部为读-改-写：同一毫秒内的并发上报可能各丢一次计数，事件明细
 * 兜底可重算；启动是低频操作，v1 接受该窗口。
 */
public class YmclStatsService {

    private static final String EVENTS_COLLECTION = "ymcl_launch_events";
    private static final String DAILY_COLLECTION = "ymcl_launch_daily";
    private static final String TOTALS_COLLECTION = "ymcl_launch_totals";
    private static final String USERS_COLLECTION = "ymcl_launch_users";
    /** 服务器/整合包名称来源（展示层，统计自身不依赖）。 */
    private static final String PACK_COLLECTION = "ymcl_packs";

    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final int PAGE_SIZE = 200;

    private final PluginDocumentStore documents;
    private final PluginUserService users;
    private final YmclContributionAggregator aggregator;
    private final AtomicLong eventSequence = new AtomicLong();

    public YmclStatsService(
            PluginDocumentStore documents,
            PluginUserService users,
            YmclContributionAggregator aggregator
    ) {
        this.documents = documents;
        this.users = users;
        this.aggregator = aggregator;
    }

    /** 记录一次启动上报。userId 来自域会话（principal），其余字段由启动器携带。 */
    public void record(long userId, Map<String, Object> launch) {
        long now = System.currentTimeMillis();
        String userKey = String.valueOf(userId);
        String serverId = str(launch.get("serverId"));
        String packId = str(launch.get("packId"));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("userId", userKey);
        event.put("serverId", serverId);
        event.put("packId", packId);
        event.put("packVersion", str(launch.get("packVersion")));
        event.put("seasonId", str(launch.get("seasonId")));
        event.put("quickPlay", str(launch.get("quickPlay")));
        event.put("launcherVersion", str(launch.get("launcherVersion")));
        event.put("platform", str(launch.get("platform")));
        event.put("arch", str(launch.get("arch")));
        event.put("occurredAt", now);
        documents.save(EVENTS_COLLECTION, eventId(now), event);

        bumpDaily(dayKey(now), userKey);
        bumpTotal("all", null, str(launch.get("platform")), str(launch.get("launcherVersion")), now);
        if (serverId != null) {
            bumpTotal("server", serverId, str(launch.get("platform")), str(launch.get("launcherVersion")), now);
        }
        if (packId != null) {
            bumpTotal("pack", packId, str(launch.get("platform")), str(launch.get("launcherVersion")), now);
        }
        upsertUser(userId, serverId, packId, str(launch.get("launcherVersion")), now);
    }

    /** 管理端摘要：总量/活跃用户、按日序列、Top 服务器与整合包、平台与版本分布。 */
    public Map<String, Object> summary(int days) {
        int span = Math.max(1, Math.min(days, 60));
        LocalDate today = LocalDate.now(ZONE);

        Map<String, Object> all = documents.findById(TOTALS_COLLECTION, "all").orElse(null);

        List<Map<String, Object>> series = new ArrayList<>();
        Map<String, Long> recentUsers = new HashMap<>();
        long todayLaunches = 0;
        long todayUsers = 0;
        for (int offset = span - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            Optional<Map<String, Object>> daily = documents.findById(DAILY_COLLECTION, "d:" + DAY_KEY.format(date));
            long total = daily.map(doc -> longValue(doc.get("total"))).orElse(0L);
            Map<String, Object> dayUsers = daily
                    .map(doc -> mapValue(doc.get("users")))
                    .orElse(Map.of());
            series.add(orderedSeriesEntry(DAY_LABEL.format(date), total, dayUsers.size()));
            if (offset < 7) {
                recentUsers.putAll(dayUsers.keySet().stream().collect(Collectors.toMap(key -> key, key -> 1L)));
            }
            if (offset == 0) {
                todayLaunches = total;
                todayUsers = dayUsers.size();
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalLaunches", all == null ? 0L : longValue(all.get("total")));
        payload.put("todayLaunches", todayLaunches);
        payload.put("totalUsers", documents.count(USERS_COLLECTION));
        payload.put("todayActiveUsers", todayUsers);
        payload.put("weekActiveUsers", recentUsers.size());
        payload.put("days", series);
        payload.put("platforms", entryList(all == null ? null : all.get("platforms")));
        payload.put("launcherVersions", entryList(all == null ? null : all.get("launcherVersions")));
        payload.put("topServers", topScopes("server"));
        payload.put("topPacks", topScopes("pack"));
        payload.put("generatedAt", System.currentTimeMillis());
        return payload;
    }

    /** 管理端最近启动动态（新到旧），带用户名快照。 */
    public Map<String, Object> recentLaunches(int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        long total = documents.count(EVENTS_COLLECTION);
        List<Map<String, Object>> launches = new ArrayList<>();
        if (total > 0) {
            int lastPage = (int) ((total - 1) / PAGE_SIZE) + 1;
            List<Map<String, Object>> batch = documents.findAll(EVENTS_COLLECTION, lastPage, PAGE_SIZE);
            for (int index = batch.size() - 1; index >= 0 && launches.size() < size; index--) {
                launches.add(eventView(batch.get(index)));
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("launches", launches);
        payload.put("total", total);
        return payload;
    }

    // ── 内部：聚合文档读-改-写 ─────────────────────────────────────────────

    private void bumpDaily(String day, String userKey) {
        String id = "d:" + day;
        Map<String, Object> doc = new LinkedHashMap<>(documents.findById(DAILY_COLLECTION, id).orElse(Map.of()));
        doc.put("scope", "day");
        doc.put("day", day);
        doc.put("total", longValue(doc.get("total")) + 1);
        Map<String, Object> dayUsers = new LinkedHashMap<>(mapValue(doc.get("users")));
        dayUsers.put(userKey, longValue(dayUsers.get(userKey)) + 1);
        doc.put("users", dayUsers);
        doc.put("updatedAt", System.currentTimeMillis());
        documents.save(DAILY_COLLECTION, id, doc);
    }

    private void bumpTotal(String kind, String refId, String platform, String launcherVersion, long now) {
        String id = switch (kind) {
            case "server" -> "s:" + refId;
            case "pack" -> "p:" + refId;
            default -> "all";
        };
        Map<String, Object> doc = new LinkedHashMap<>(documents.findById(TOTALS_COLLECTION, id).orElse(Map.of()));
        doc.put("kind", kind);
        doc.put("refId", refId);
        doc.put("total", longValue(doc.get("total")) + 1);
        if (platform != null) {
            doc.put("platforms", bumpEntryList(doc.get("platforms"), platform));
        }
        if (launcherVersion != null) {
            doc.put("launcherVersions", bumpEntryList(doc.get("launcherVersions"), launcherVersion));
        }
        doc.put("lastLaunchAt", now);
        doc.put("updatedAt", now);
        documents.save(TOTALS_COLLECTION, id, doc);
    }

    private void upsertUser(long userId, String serverId, String packId, String launcherVersion, long now) {
        String id = "u:" + userId;
        Map<String, Object> doc = new LinkedHashMap<>(documents.findById(USERS_COLLECTION, id).orElse(Map.of()));
        boolean first = doc.isEmpty();
        PluginUserProfile profile = users.findById(userId).orElse(null);
        if (profile != null) {
            doc.put("username", profile.username());
            doc.put("nickname", profile.nickname());
        }
        doc.put("userId", String.valueOf(userId));
        doc.put("firstSeenAt", first ? now : longValue(doc.get("firstSeenAt")));
        doc.put("lastSeenAt", now);
        doc.put("launchCount", longValue(doc.get("launchCount")) + 1);
        doc.put("lastServerId", serverId);
        doc.put("lastPackId", packId);
        doc.put("lastLauncherVersion", launcherVersion);
        documents.save(USERS_COLLECTION, id, doc);
    }

    private List<Map<String, Object>> topScopes(String kind) {
        List<Map<String, Object>> scopes = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findByField(TOTALS_COLLECTION, "kind", kind, page, PAGE_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            scopes.addAll(batch);
            page++;
        }
        scopes.sort(Comparator.comparingLong((Map<String, Object> doc) -> longValue(doc.get("total"))).reversed());
        Map<String, String> serverNames = kind.equals("server") ? serverNames() : Map.of();
        List<Map<String, Object>> top = new ArrayList<>();
        for (Map<String, Object> doc : scopes.subList(0, Math.min(scopes.size(), 10))) {
            String refId = String.valueOf(doc.get("refId"));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("refId", refId);
            entry.put("name", kind.equals("server") ? serverNames.getOrDefault(refId, refId) : packName(refId));
            entry.put("launches", longValue(doc.get("total")));
            entry.put("lastLaunchAt", longValue(doc.get("lastLaunchAt")));
            top.add(entry);
        }
        return top;
    }

    /** mc-server 等贡献方提供的服务器档案名（仅展示，缺档案时回退 id）。 */
    private Map<String, String> serverNames() {
        Map<String, String> names = new HashMap<>();
        for (Object source : aggregator.serverBindings()) {
            if (source instanceof List<?> list) {
                for (Object item : list) {
                    collectServerName(names, item);
                }
            } else {
                collectServerName(names, source);
            }
        }
        return names;
    }

    private void collectServerName(Map<String, String> names, Object item) {
        if (item instanceof Map<?, ?> server && server.get("serverId") != null) {
            Object name = server.get("name");
            names.put(String.valueOf(server.get("serverId")), name == null ? null : String.valueOf(name));
        }
    }

    private String packName(String packId) {
        return documents.findById(PACK_COLLECTION, packId)
                .map(doc -> doc.get("name"))
                .filter(name -> !String.valueOf(name).isBlank())
                .map(String::valueOf)
                .orElse(packId);
    }

    private Map<String, Object> eventView(Map<String, Object> event) {
        Map<String, Object> view = new LinkedHashMap<>(event);
        String userId = event.get("userId") == null ? null : String.valueOf(event.get("userId"));
        if (userId != null) {
            documents.findById(USERS_COLLECTION, "u:" + userId).ifPresent(user -> {
                Object nickname = user.get("nickname");
                view.put("username", nickname != null && !String.valueOf(nickname).isBlank()
                        ? nickname
                        : user.get("username"));
            });
        }
        return view;
    }

    // ── 工具 ───────────────────────────────────────────────────────────────

    /** 事件 id：零填充毫秒时间戳 + 进程序号，_id 升序即时间序。 */
    private String eventId(long now) {
        return String.format("%019d-%06d", now, eventSequence.incrementAndGet() % 1_000_000);
    }

    private static String dayKey(long epochMillis) {
        return DAY_KEY.format(java.time.Instant.ofEpochMilli(epochMillis).atZone(ZONE).toLocalDate());
    }

    private static Map<String, Object> orderedSeriesEntry(String label, long total, long users) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("day", label);
        entry.put("total", total);
        entry.put("users", users);
        return entry;
    }

    /** 平台/启动器版本分布 entry 列表（{value, count}），避免 "." 出现在字段名。 */
    private static List<Map<String, Object>> bumpEntryList(Object stored, String value) {
        List<Map<String, Object>> entries = new ArrayList<>(entryList(stored));
        for (Map<String, Object> entry : entries) {
            if (value.equals(entry.get("value"))) {
                entry.put("count", longValue(entry.get("count")) + 1);
                return entries;
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("value", value);
        entry.put("count", 1L);
        entries.add(entry);
        return entries;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entryList(Object stored) {
        if (!(stored instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                entries.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return entries;
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
