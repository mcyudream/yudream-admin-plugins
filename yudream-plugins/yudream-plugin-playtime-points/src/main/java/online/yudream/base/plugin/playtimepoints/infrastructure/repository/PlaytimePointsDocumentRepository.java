package online.yudream.base.plugin.playtimepoints.infrastructure.repository;

import online.yudream.base.plugin.playtimepoints.domain.aggregate.CheckInReward;
import online.yudream.base.plugin.playtimepoints.domain.aggregate.PointsSettlement;
import online.yudream.base.plugin.playtimepoints.domain.aggregate.SettlementState;
import online.yudream.base.plugin.playtimepoints.domain.repo.PlaytimePointsRepository;
import online.yudream.base.plugin.playtimepoints.domain.valobj.CheckInRewardCursor;
import online.yudream.base.plugin.playtimepoints.domain.valobj.PlaytimePointsSettings;
import online.yudream.base.plugin.playtimepoints.domain.valobj.SubServerBaseline;
import online.yudream.base.plugin.playtimepoints.domain.valobj.SubSettlement;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class PlaytimePointsDocumentRepository implements PlaytimePointsRepository {

    private static final String SETTINGS = "settings";
    private static final String STATES = "settlement-states";
    private static final String SETTLEMENTS = "settlements";
    /** 打卡积分发放流水；同时复用 settings 集合存放单例游标（id = checkInRewardCursor）。 */
    private static final String CHECK_IN_REWARDS = "check-in-rewards";
    private static final String CHECK_IN_REWARD_CURSOR_ID = "checkInRewardCursor";

    /** 宿主文档存储单页上限 200 且按 _id 字典序翻页，超页会静默截断，因此按 200 逐页扫并设窗口上限。 */
    private static final int SCAN_PAGE_SIZE = 200;
    private static final int MAX_SCAN_PAGES = 40;

    private final PluginDocumentStore documents;

    public PlaytimePointsDocumentRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    /* ---------- 设置 ---------- */

    @Override
    public Optional<PlaytimePointsSettings> findSettings() {
        return documents.findById(SETTINGS, "settings").map(this::toSettings);
    }

    @Override
    public void saveSettings(PlaytimePointsSettings settings) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("enabled", settings.enabled());
        doc.put("assetCode", settings.assetCode());
        doc.put("minutesPerPoint", settings.minutesPerPoint());
        doc.put("subtractAfk", settings.subtractAfk());
        Map<String, Object> servers = new LinkedHashMap<>();
        settings.servers().forEach((serverId, rule) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("weight", rule.weight());
            row.put("enabled", rule.enabled());
            servers.put(serverId, row);
        });
        doc.put("servers", servers);
        // 子服规则（键 serverId::subServer）。旧文档没有这个键，读出来是空表，行为与改造前一致。
        Map<String, Object> subServers = new LinkedHashMap<>();
        settings.subServers().forEach((key, rule) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("weight", rule.weight());
            row.put("enabled", rule.enabled());
            subServers.put(key, row);
        });
        doc.put("subServers", subServers);
        // 打卡积分联动。旧文档没有这些键，读出来就是「关闭 + 默认金额 1」，行为与改造前一致。
        doc.put("checkInRewardEnabled", settings.checkInRewardEnabled());
        doc.put("checkInRewardPoints", settings.checkInRewardPoints());
        doc.put("checkInRewardRealtime", settings.checkInRewardRealtime());
        doc.put("checkInRewardProjectPoints", new LinkedHashMap<>(settings.checkInRewardProjectPoints()));
        // 计算方式与时薪，同样是后加的键：旧文档读出来是 FIXED + 1，行为与升级前一致。
        doc.put("checkInRewardMode", settings.checkInRewardMode());
        doc.put("checkInHourlyPoints", settings.checkInHourlyPoints());
        // 非时长打卡积分（图片/文件/定位等没有时长的打卡）：旧文档读出来是 "0"，即不发，行为与 1.3.0 一致。
        doc.put("checkInFixedPoints", settings.checkInFixedPoints());
        documents.save(SETTINGS, "settings", doc);
    }

    private PlaytimePointsSettings toSettings(Map<String, Object> doc) {
        Map<String, PlaytimePointsSettings.ServerRule> servers = new LinkedHashMap<>();
        if (doc.get("servers") instanceof Map<?, ?> raw) {
            raw.forEach((key, value) -> {
                if (key instanceof String serverId && value instanceof Map<?, ?> rule) {
                    servers.put(serverId, new PlaytimePointsSettings.ServerRule(
                            asStr(rule.get("weight")), asBool(rule.get("enabled"), true)));
                }
            });
        }
        Map<String, PlaytimePointsSettings.ServerRule> subServers = new LinkedHashMap<>();
        if (doc.get("subServers") instanceof Map<?, ?> raw) {
            raw.forEach((key, value) -> {
                String canonical = key instanceof String text ? PlaytimePointsSettings.normalizeSubKey(text) : null;
                if (canonical != null && value instanceof Map<?, ?> rule) {
                    subServers.put(canonical, new PlaytimePointsSettings.ServerRule(
                            asStr(rule.get("weight")), asBool(rule.get("enabled"), true)));
                }
            });
        }
        return new PlaytimePointsSettings(
                asBool(doc.get("enabled"), true),
                asStr(doc.get("assetCode")),
                asLong(doc.get("minutesPerPoint"), 60),
                asBool(doc.get("subtractAfk"), true),
                servers,
                subServers,
                // 打卡积分是后加的可选能力：旧文档缺键时读成「关闭 + 默认金额 + 无项目覆盖」，行为不变。
                asBool(doc.get("checkInRewardEnabled"), false),
                asStr(doc.get("checkInRewardPoints")),
                asBool(doc.get("checkInRewardRealtime"), false),
                projectPoints(doc),
                // 计算方式与时薪也是后加的键：旧文档缺键时读成 FIXED + 1（构造器负责兜底归一化）。
                asStr(doc.get("checkInRewardMode")),
                asStr(doc.get("checkInHourlyPoints")),
                // 非时长打卡积分同样是后加的键：旧文档缺键读成空串，构造器归一成 "0"（不发，行为与 1.3.0 一致）。
                asStr(doc.get("checkInFixedPoints")));
    }

    /** 按项目的打卡金额覆盖（键为项目 ID，值为十进制字符串）；旧文档缺键时为空表。 */
    private Map<String, String> projectPoints(Map<String, Object> doc) {
        Map<String, String> points = new LinkedHashMap<>();
        if (doc.get("checkInRewardProjectPoints") instanceof Map<?, ?> raw) {
            raw.forEach((key, value) -> {
                if (key instanceof String projectId && !projectId.isBlank() && value != null) {
                    String amount = String.valueOf(value).trim();
                    if (!amount.isEmpty()) {
                        points.put(projectId.trim(), amount);
                    }
                }
            });
        }
        return points;
    }

    /* ---------- 结算基线 ---------- */

    @Override
    public Optional<SettlementState> findState(String stateId) {
        return documents.findById(STATES, stateId).map(this::toState);
    }

    @Override
    public void saveState(SettlementState state) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("serverId", state.serverId());
        doc.put("playerId", state.playerId());
        doc.put("playerName", state.playerName());
        doc.put("settledOnlineMillis", state.settledOnlineMillis());
        doc.put("settledAfkMillis", state.settledAfkMillis());
        doc.put("settledQuitAt", state.settledQuitAt());
        doc.put("carryPoints", state.carryPoints());
        doc.put("updatedAt", state.updatedAt());
        doc.put("subServers", state.subServers().values().stream()
                .map(this::subServerBaselineDocument)
                .toList());
        documents.save(STATES, state.id(), doc);
    }

    private Map<String, Object> subServerBaselineDocument(SubServerBaseline baseline) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", baseline.name());
        row.put("onlineMillis", baseline.onlineMillis());
        row.put("afkMillis", baseline.afkMillis());
        return row;
    }

    private SettlementState toState(Map<String, Object> doc) {
        return new SettlementState(
                asStr(doc.get("id")),
                asStr(doc.get("serverId")),
                asStr(doc.get("playerId")),
                asStr(doc.get("playerName")),
                asLong(doc.get("settledOnlineMillis"), 0),
                asLong(doc.get("settledAfkMillis"), 0),
                asLongOrNull(doc.get("settledQuitAt")),
                asStr(doc.get("carryPoints")),
                asLong(doc.get("updatedAt"), 0),
                // 改造之前写入的基线文档没有 subServers 键，读出来是空表：按整服口径结算。
                subServerBaselines(doc));
    }

    private Map<String, SubServerBaseline> subServerBaselines(Map<String, Object> doc) {
        Map<String, SubServerBaseline> baselines = new LinkedHashMap<>();
        for (Map<String, Object> row : asList(doc.get("subServers"))) {
            SubServerBaseline baseline = new SubServerBaseline(asStr(row.get("name")),
                    asLong(row.get("onlineMillis"), 0), asLong(row.get("afkMillis"), 0));
            if (!baseline.name().isEmpty()) {
                baselines.put(baseline.name(), baseline);
            }
        }
        return baselines;
    }

    /* ---------- 结算流水 ---------- */

    @Override
    public void saveSettlement(PointsSettlement settlement) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("serverId", settlement.serverId());
        doc.put("serverName", settlement.serverName());
        doc.put("playerId", settlement.playerId());
        doc.put("playerName", settlement.playerName());
        doc.put("userId", settlement.userId());
        doc.put("windowStart", settlement.windowStart());
        doc.put("windowEnd", settlement.windowEnd());
        doc.put("onlineMillis", settlement.onlineMillis());
        doc.put("afkMillis", settlement.afkMillis());
        doc.put("effectiveMillis", settlement.effectiveMillis());
        doc.put("weight", settlement.weight());
        doc.put("points", settlement.points());
        doc.put("credited", settlement.credited());
        doc.put("carryAfter", settlement.carryAfter());
        doc.put("assetCode", settlement.assetCode());
        doc.put("businessNo", settlement.businessNo());
        doc.put("createdAt", settlement.createdAt());
        // 子服明细。整服口径写入空列表；旧文档没有这个键，读出来同样是空列表。
        doc.put("subServers", settlement.subServers().stream()
                .map(this::subSettlementDocument)
                .toList());
        documents.save(SETTLEMENTS, settlement.id(), doc);
    }

    private Map<String, Object> subSettlementDocument(SubSettlement sub) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("subServer", sub.subServer());
        row.put("onlineMillis", sub.onlineMillis());
        row.put("afkMillis", sub.afkMillis());
        row.put("effectiveMillis", sub.effectiveMillis());
        row.put("weight", sub.weight());
        row.put("points", sub.points());
        row.put("enabled", sub.enabled());
        return row;
    }

    @Override
    public SettlementPage settlements(String serverId, String userId, String keyword, int page, int size) {
        String field = serverId != null ? "serverId" : (userId != null ? "userId" : null);
        Object value = serverId != null ? serverId : userId;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int current = 1; current <= MAX_SCAN_PAGES; current++) {
            List<Map<String, Object>> batch = field == null
                    ? documents.findAll(SETTLEMENTS, current, SCAN_PAGE_SIZE)
                    : documents.findByField(SETTLEMENTS, field, value, current, SCAN_PAGE_SIZE);
            rows.addAll(batch);
            if (batch.size() < SCAN_PAGE_SIZE) {
                break;
            }
        }
        String needle = keyword == null ? null : keyword.toLowerCase(Locale.ROOT);
        List<PointsSettlement> matched = rows.stream()
                .map(this::toSettlement)
                .filter(item -> needle == null
                        || (item.playerName() != null && item.playerName().toLowerCase(Locale.ROOT).contains(needle)))
                .sorted(Comparator.comparingLong(PointsSettlement::windowEnd).reversed()
                        .thenComparing(PointsSettlement::id, Comparator.reverseOrder()))
                .toList();
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int from = Math.min((safePage - 1) * safeSize, matched.size());
        int to = Math.min(from + safeSize, matched.size());
        return new SettlementPage(matched.subList(from, to), matched.size());
    }

    private PointsSettlement toSettlement(Map<String, Object> doc) {
        return new PointsSettlement(
                asStr(doc.get("id")),
                asStr(doc.get("serverId")),
                asStr(doc.get("serverName")),
                asStr(doc.get("playerId")),
                asStr(doc.get("playerName")),
                asStr(doc.get("userId")),
                asLongOrNull(doc.get("windowStart")),
                asLong(doc.get("windowEnd"), 0),
                asLong(doc.get("onlineMillis"), 0),
                asLong(doc.get("afkMillis"), 0),
                asLong(doc.get("effectiveMillis"), 0),
                asStr(doc.get("weight")),
                asStr(doc.get("points")),
                asStr(doc.get("credited")),
                asStr(doc.get("carryAfter")),
                asStr(doc.get("assetCode")),
                asStr(doc.get("businessNo")),
                asLong(doc.get("createdAt"), 0),
                // 改造之前写入的流水文档没有 subServers 键，读出来是空列表。
                subSettlements(doc));
    }

    private List<SubSettlement> subSettlements(Map<String, Object> doc) {
        List<SubSettlement> subServers = new ArrayList<>();
        for (Map<String, Object> row : asList(doc.get("subServers"))) {
            subServers.add(new SubSettlement(asStr(row.get("subServer")),
                    asLong(row.get("onlineMillis"), 0), asLong(row.get("afkMillis"), 0),
                    asLong(row.get("effectiveMillis"), 0), asStr(row.get("weight")), asStr(row.get("points")),
                    asBool(row.get("enabled"), true)));
        }
        return subServers;
    }

    /* ---------- 打卡积分发放 ---------- */

    @Override
    public Optional<CheckInRewardCursor> findCheckInRewardCursor() {
        return documents.findById(SETTINGS, CHECK_IN_REWARD_CURSOR_ID).map(this::toCursor);
    }

    @Override
    public void saveCheckInRewardCursor(CheckInRewardCursor cursor) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("lastAcceptedAt", cursor.lastAcceptedAt());
        doc.put("deliveredCount", cursor.deliveredCount());
        doc.put("updatedAt", cursor.updatedAt());
        documents.save(SETTINGS, CHECK_IN_REWARD_CURSOR_ID, doc);
    }

    private CheckInRewardCursor toCursor(Map<String, Object> doc) {
        return new CheckInRewardCursor(asLong(doc.get("lastAcceptedAt"), 0),
                asLong(doc.get("deliveredCount"), 0), asLong(doc.get("updatedAt"), 0));
    }

    @Override
    public Optional<CheckInReward> findCheckInReward(String checkInId) {
        if (checkInId == null || checkInId.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(CHECK_IN_REWARDS, checkInId.trim()).map(this::toCheckInReward);
    }

    @Override
    public void saveCheckInReward(CheckInReward reward) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("detailId", reward.detailId());
        doc.put("detailTitle", reward.detailTitle());
        doc.put("projectId", reward.projectId());
        doc.put("projectName", reward.projectName());
        doc.put("userId", reward.userId());
        doc.put("points", reward.points());
        doc.put("credit", reward.credit());
        doc.put("businessNo", reward.businessNo());
        doc.put("source", reward.source());
        doc.put("acceptedAt", reward.acceptedAt());
        doc.put("createdAt", reward.createdAt());
        // 折算审计字段：旧流水没有这些键，读出来是「固定金额 + 时长 0 + 无说明」，展示与升级前一致。
        doc.put("mode", reward.mode());
        doc.put("effectiveMillis", reward.effectiveMillis());
        doc.put("rate", reward.rate());
        doc.put("note", reward.note());
        documents.save(CHECK_IN_REWARDS, reward.checkInId(), doc);
    }

    private CheckInReward toCheckInReward(Map<String, Object> doc) {
        return new CheckInReward(asStr(doc.get("id")), asStr(doc.get("detailId")), asStr(doc.get("detailTitle")),
                asStr(doc.get("projectId")), asStr(doc.get("projectName")), asStr(doc.get("userId")),
                asStr(doc.get("points")), asStr(doc.get("credit")), asStr(doc.get("businessNo")),
                asStr(doc.get("source")), asLong(doc.get("acceptedAt"), 0), asLong(doc.get("createdAt"), 0),
                // 旧流水的 mode/effectiveMillis/rate/note 都缺键：mode 空串按 FIXED 归一化，
                // rate 空串由聚合用 points 兜底（固定模式下的费率就是当时的配置金额）。
                asStr(doc.get("mode")), asLong(doc.get("effectiveMillis"), 0), asStr(doc.get("rate")),
                asStr(doc.get("note")));
    }

    @Override
    public CheckInRewardPage checkInRewards(String projectId, String userId, int page, int size) {
        String field = projectId != null ? "projectId" : (userId != null ? "userId" : null);
        Object value = projectId != null ? projectId : userId;
        List<CheckInReward> matched = scanCheckInRewards(field, value).stream()
                // 两个维度同时给出时都要生效（上面的扫描只按其中一个字段收窄）
                .filter(item -> projectId == null || projectId.equals(item.projectId()))
                .filter(item -> userId == null || userId.equals(item.userId()))
                .sorted(Comparator.comparingLong(CheckInReward::acceptedAt).reversed()
                        .thenComparing(CheckInReward::checkInId, Comparator.reverseOrder()))
                .toList();
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int from = Math.min((safePage - 1) * safeSize, matched.size());
        int to = Math.min(from + safeSize, matched.size());
        return new CheckInRewardPage(matched.subList(from, to), matched.size());
    }

    @Override
    public List<CheckInReward> allCheckInRewards(String userId) {
        String field = userId != null && !userId.isBlank() ? "userId" : null;
        return scanCheckInRewards(field, field == null ? null : userId).stream()
                .sorted(Comparator.comparingLong(CheckInReward::acceptedAt).reversed()
                        .thenComparing(CheckInReward::checkInId, Comparator.reverseOrder()))
                .toList();
    }

    private List<CheckInReward> scanCheckInRewards(String field, Object value) {
        List<CheckInReward> rows = new ArrayList<>();
        for (int current = 1; current <= MAX_SCAN_PAGES; current++) {
            List<Map<String, Object>> batch = field == null
                    ? documents.findAll(CHECK_IN_REWARDS, current, SCAN_PAGE_SIZE)
                    : documents.findByField(CHECK_IN_REWARDS, field, value, current, SCAN_PAGE_SIZE);
            batch.forEach(document -> rows.add(toCheckInReward(document)));
            if (batch.size() < SCAN_PAGE_SIZE) {
                break;
            }
        }
        return rows;
    }

    /* ---------- 类型容错读取（文档存储可能返回 Number/String/Boolean） ---------- */

    /** 文档里的嵌套数组：只取其中的对象行，其它形态一律忽略。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private static String asStr(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long asLong(Object value, long fallback) {
        Long parsed = asLongOrNull(value);
        return parsed == null ? fallback : parsed;
    }

    private static Long asLongOrNull(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean asBool(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text.trim());
        }
        return fallback;
    }
}
