package online.yudream.base.plugin.playtimepoints.infrastructure.repository;

import online.yudream.base.plugin.playtimepoints.domain.aggregate.PointsSettlement;
import online.yudream.base.plugin.playtimepoints.domain.aggregate.SettlementState;
import online.yudream.base.plugin.playtimepoints.domain.repo.PlaytimePointsRepository;
import online.yudream.base.plugin.playtimepoints.domain.valobj.PlaytimePointsSettings;
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
        return new PlaytimePointsSettings(
                asBool(doc.get("enabled"), true),
                asStr(doc.get("assetCode")),
                asLong(doc.get("minutesPerPoint"), 60),
                asBool(doc.get("subtractAfk"), true),
                servers);
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
        documents.save(STATES, state.id(), doc);
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
                asLong(doc.get("updatedAt"), 0));
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
        documents.save(SETTLEMENTS, settlement.id(), doc);
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
                asLong(doc.get("createdAt"), 0));
    }

    /* ---------- 类型容错读取（文档存储可能返回 Number/String/Boolean） ---------- */

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
