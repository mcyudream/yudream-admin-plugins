package online.yudream.base.plugin.playtimepoints.infrastructure.repository;

import online.yudream.base.plugin.playtimepoints.domain.aggregate.CheckInReward;
import online.yudream.base.plugin.playtimepoints.domain.aggregate.PointsSettlement;
import online.yudream.base.plugin.playtimepoints.domain.aggregate.SettlementState;
import online.yudream.base.plugin.playtimepoints.domain.valobj.CheckInRewardCursor;
import online.yudream.base.plugin.playtimepoints.domain.valobj.PlaytimePointsSettings;
import online.yudream.base.plugin.playtimepoints.domain.valobj.SubSettlement;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子服维度的落库与读回，以及改造前文档（没有 subServers 键）的兼容读取。
 *
 * <p>旧文档必须读成「没有子服维度」，行为与升级前一致；新文档写入的明细必须原样读回。
 */
class PlaytimePointsDocumentRepositoryTest {

    private static final String SETTLEMENTS = "settlements";
    private static final String STATES = "settlement-states";
    private static final String SETTINGS = "settings";

    private final FakeDocumentStore documents = new FakeDocumentStore();
    private final PlaytimePointsDocumentRepository repository = new PlaytimePointsDocumentRepository(documents);

    @Test
    void settlementSubServerDetailsRoundTrip() {
        repository.saveSettlement(new PointsSettlement("1:srv-1:player-a", "srv-1", "主服", "player-a", "Steve",
                "10001", null, 2_000L, 60 * 60_000L, 0L, 60 * 60_000L, "1", "1.5000", "1", "0.5000",
                "POINT", "pp:abc", 5L,
                List.of(new SubSettlement("fabric", 30 * 60_000L, 0L, 30 * 60_000L, "2", "1.0000", true),
                        new SubSettlement("paper", 30 * 60_000L, 5 * 60_000L, 25 * 60_000L, "1", "0.4167", false))));

        PointsSettlement read = repository.settlements(null, null, null, 1, 10).records().getFirst();

        assertEquals(2, read.subServers().size());
        SubSettlement fabric = read.subServers().getFirst();
        assertEquals("fabric", fabric.subServer());
        assertEquals(30 * 60_000L, fabric.onlineMillis());
        assertEquals(0L, fabric.afkMillis());
        assertEquals(30 * 60_000L, fabric.effectiveMillis());
        assertEquals("2", fabric.weight());
        assertEquals("1.0000", fabric.points());
        assertTrue(fabric.enabled());
        SubSettlement paper = read.subServers().get(1);
        assertEquals("paper", paper.subServer());
        assertEquals(25 * 60_000L, paper.effectiveMillis());
        assertFalse(paper.enabled());
    }

    @Test
    void legacySettlementDocumentWithoutSubServersReadsBackAsEmpty() {
        documents.save(SETTLEMENTS, "1:srv-1:player-a", legacySettlementDocument());

        PointsSettlement read = repository.settlements(null, null, null, 1, 10).records().getFirst();

        assertTrue(read.subServers().isEmpty());
        assertEquals("POINT", read.assetCode());
        assertEquals(60 * 60_000L, read.effectiveMillis());
    }

    @Test
    void legacySettlementStateWithoutSubServersReadsBackAsEmpty() {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("serverId", "srv-1");
        legacy.put("playerId", "player-a");
        legacy.put("playerName", "Steve");
        legacy.put("settledOnlineMillis", 60 * 60_000L);
        legacy.put("settledAfkMillis", 0L);
        legacy.put("settledQuitAt", 1_000L);
        legacy.put("carryPoints", "0.5000");
        legacy.put("updatedAt", 6L);
        documents.save(STATES, "srv-1:player-a", legacy);

        SettlementState read = repository.findState("srv-1:player-a").orElseThrow();

        assertTrue(read.subServers().isEmpty());
        assertEquals(60 * 60_000L, read.settledOnlineMillis());
        assertEquals(1_000L, read.settledQuitAt());
    }

    @Test
    void subServerBaselinesRoundTripAndLegacyStateIsIgnored() {
        Map<String, online.yudream.base.plugin.playtimepoints.domain.valobj.SubServerBaseline> baselines =
                new LinkedHashMap<>();
        baselines.put("fabric", new online.yudream.base.plugin.playtimepoints.domain.valobj.SubServerBaseline(
                "fabric", 90 * 60_000L, 5 * 60_000L));
        baselines.put("paper", new online.yudream.base.plugin.playtimepoints.domain.valobj.SubServerBaseline(
                "paper", 30 * 60_000L, 0L));
        repository.saveState(new SettlementState("srv-1:player-a", "srv-1", "player-a", "Steve",
                120 * 60_000L, 5 * 60_000L, 2_000L, "0.2500", 7L, baselines));

        SettlementState read = repository.findState("srv-1:player-a").orElseThrow();

        assertEquals(2, read.subServers().size());
        assertEquals(90 * 60_000L, read.subServers().get("fabric").onlineMillis());
        assertEquals(5 * 60_000L, read.subServers().get("fabric").afkMillis());
        assertEquals(30 * 60_000L, read.subServers().get("paper").onlineMillis());
    }

    @Test
    void settingsSubServerRulesRoundTrip() {
        repository.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true,
                Map.of("srv-1", new PlaytimePointsSettings.ServerRule("2", true)),
                Map.of("srv-1::fabric", new PlaytimePointsSettings.ServerRule("0.5", false))));

        PlaytimePointsSettings read = repository.findSettings().orElseThrow();

        assertEquals(1, read.subServers().size());
        assertEquals(0, read.weightFor("srv-1", "fabric").compareTo(new BigDecimal("0.5")));
        assertFalse(read.enabledFor("srv-1", "fabric"));
        // 服务器规则原样保留
        assertEquals(0, read.weightFor("srv-1", "paper").compareTo(new BigDecimal("2")));
    }

    @Test
    void legacySettingsDocumentWithoutSubServersFallsBackToServerRule() {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("enabled", true);
        legacy.put("assetCode", "POINT");
        legacy.put("minutesPerPoint", 60L);
        legacy.put("subtractAfk", true);
        legacy.put("servers", Map.of("srv-1", Map.of("weight", "2", "enabled", true)));
        documents.save(SETTINGS, "settings", legacy);

        PlaytimePointsSettings read = repository.findSettings().orElseThrow();

        assertTrue(read.subServers().isEmpty());
        // 没有子服规则时退化成服务器规则，行为与改造前一致
        assertEquals(0, read.weightFor("srv-1", "fabric").compareTo(new BigDecimal("2")));
        assertTrue(read.enabledFor("srv-1", "fabric"));
        assertEquals(0, read.weightFor("srv-1").compareTo(new BigDecimal("2")));
    }

    /* ---------- 打卡积分联动 ---------- */

    /** 打卡积分设置落库读回；旧设置文档缺键时读成「关闭 + 默认金额 1 + 无项目覆盖」。 */
    @Test
    void checkInRewardSettingsRoundTripAndLegacyDocumentsStayDisabled() {
        repository.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, "2.5", true, Map.of("proj-1", "5")));

        PlaytimePointsSettings read = repository.findSettings().orElseThrow();

        assertTrue(read.checkInRewardEnabled());
        assertEquals("2.5", read.checkInRewardPoints());
        assertTrue(read.checkInRewardRealtime());
        assertEquals(Map.of("proj-1", "5"), read.checkInRewardProjectPoints());
        assertEquals(0, read.checkInRewardPointsFor("proj-1").compareTo(new BigDecimal("5")));
        assertEquals(0, read.checkInRewardPointsFor("proj-2").compareTo(new BigDecimal("2.5")));

        // 改造之前的设置文档：没有这四个键，读出来必须是关闭状态、默认金额 1
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("enabled", true);
        legacy.put("assetCode", "POINT");
        legacy.put("minutesPerPoint", 60L);
        legacy.put("subtractAfk", true);
        legacy.put("servers", Map.of());
        documents.save(SETTINGS, "settings", legacy);

        PlaytimePointsSettings legacyRead = repository.findSettings().orElseThrow();

        assertFalse(legacyRead.checkInRewardEnabled());
        assertFalse(legacyRead.checkInRewardRealtime());
        assertEquals("1", legacyRead.checkInRewardPoints());
        assertTrue(legacyRead.checkInRewardProjectPoints().isEmpty());
        assertEquals(0, legacyRead.checkInRewardPointsFor("proj-1").compareTo(BigDecimal.ONE));
    }

    /** 计算方式与时薪落库读回；旧设置文档缺键时读成 FIXED + 1（行为与升级前一致）。 */
    @Test
    void checkInRewardModeRoundTripAndLegacyDocumentsStayFixed() {
        repository.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, "2", false, Map.of("proj-1", "20"),
                PlaytimePointsSettings.CHECK_IN_MODE_HOURLY, "10"));

        PlaytimePointsSettings read = repository.findSettings().orElseThrow();

        assertEquals(PlaytimePointsSettings.CHECK_IN_MODE_HOURLY, read.checkInRewardMode());
        assertTrue(read.checkInHourlyMode());
        assertEquals("10", read.checkInHourlyPoints());
        assertEquals(0, read.checkInHourlyPointsFor("proj-1").compareTo(new BigDecimal("20")));
        assertEquals(0, read.checkInRateFor("proj-2").compareTo(new BigDecimal("10")));

        // 改造之前的设置文档：连 checkInRewardMode / checkInHourlyPoints 两个键都没有
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("enabled", true);
        legacy.put("assetCode", "POINT");
        legacy.put("minutesPerPoint", 60L);
        legacy.put("subtractAfk", true);
        legacy.put("servers", Map.of());
        legacy.put("checkInRewardEnabled", true);
        legacy.put("checkInRewardPoints", "2.5");
        documents.save(SETTINGS, "settings", legacy);

        PlaytimePointsSettings legacyRead = repository.findSettings().orElseThrow();

        assertEquals(PlaytimePointsSettings.CHECK_IN_MODE_FIXED, legacyRead.checkInRewardMode(),
                "旧文档读出来必须是固定金额模式");
        assertFalse(legacyRead.checkInHourlyMode());
        assertEquals("1", legacyRead.checkInHourlyPoints());
        assertEquals(0, legacyRead.checkInRateFor("proj-1").compareTo(new BigDecimal("2.5")),
                "固定模式下费率就是既有金额，行为与 1.2.0 逐位一致");
    }

    /**
     * 非时长打卡积分落库读回；旧设置文档缺键时读成 "0"（这类打卡不发），行为与 1.3.0 一致。
     */
    @Test
    void checkInFixedPointsRoundTripAndLegacyDocumentsDefaultToZero() {
        repository.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, "1", false, Map.of(), PlaytimePointsSettings.CHECK_IN_MODE_HOURLY, "10", "3"));

        PlaytimePointsSettings read = repository.findSettings().orElseThrow();

        assertEquals("3", read.checkInFixedPoints());
        assertEquals(0, read.checkInFixedPointsValue().compareTo(new BigDecimal("3")));

        // 1.3.0 的设置文档：有 mode / hourlyPoints，但没有 checkInFixedPoints
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("enabled", true);
        legacy.put("assetCode", "POINT");
        legacy.put("minutesPerPoint", 60L);
        legacy.put("subtractAfk", true);
        legacy.put("servers", Map.of());
        legacy.put("checkInRewardEnabled", true);
        legacy.put("checkInRewardPoints", "1");
        legacy.put("checkInRewardMode", PlaytimePointsSettings.CHECK_IN_MODE_HOURLY);
        legacy.put("checkInHourlyPoints", "10");
        documents.save(SETTINGS, "settings", legacy);

        PlaytimePointsSettings legacyRead = repository.findSettings().orElseThrow();

        assertEquals("0", legacyRead.checkInFixedPoints(), "旧文档缺键读成 0：非时长打卡不发分");
        assertEquals(0, legacyRead.checkInFixedPointsValue().compareTo(BigDecimal.ZERO));
        assertEquals(0, legacyRead.checkInRateFor("proj-1").compareTo(new BigDecimal("10")),
                "既有字段（时薪）不受新字段影响");
    }

    /** 折算审计字段落库读回；旧流水缺键时读成「固定金额 + 时长 0 + 无说明」。 */
    @Test
    void checkInRewardAuditFieldsRoundTripAndLegacyLedgerStaysFixed() {
        repository.saveCheckInReward(new CheckInReward("c001", "detail-1", "铺设石砖路", "proj-1", "古城改造",
                "10001", "7.33", "7.33", "playtime-points:checkin:c001", CheckInReward.SOURCE_PULL, 1_500L, 8L,
                CheckInReward.MODE_HOURLY, 44 * 60_000L, "10", ""));
        repository.saveCheckInReward(new CheckInReward("c002", "detail-1", "铺设石砖路", "proj-1", "古城改造",
                "10001", "0", "0", "", CheckInReward.SOURCE_PULL, 1_600L, 9L,
                CheckInReward.MODE_HOURLY, 0L, "10", "该打卡没有有效在线时长（0 分钟），按时薪折算为 0，本次不发放积分"));

        CheckInReward paid = repository.findCheckInReward("c001").orElseThrow();
        assertEquals(CheckInReward.MODE_HOURLY, paid.mode());
        assertEquals(44 * 60_000L, paid.effectiveMillis());
        assertEquals("10", paid.rate());
        assertEquals("7.33", paid.credit());
        assertEquals("", paid.note());

        CheckInReward skipped = repository.findCheckInReward("c002").orElseThrow();
        assertEquals("0", skipped.credit());
        assertEquals(0L, skipped.effectiveMillis());
        assertTrue(skipped.note().contains("没有有效在线时长"));

        // 旧流水文档（1.2.0 写入）没有 mode/effectiveMillis/rate/note 四个键
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("detailId", "detail-1");
        legacy.put("projectId", "proj-1");
        legacy.put("userId", "10001");
        legacy.put("points", "2");
        legacy.put("credit", "2");
        legacy.put("businessNo", "playtime-points:checkin:c003");
        legacy.put("source", CheckInReward.SOURCE_REALTIME);
        legacy.put("acceptedAt", 1_700L);
        legacy.put("createdAt", 10L);
        documents.save("check-in-rewards", "c003", legacy);

        CheckInReward legacyRead = repository.findCheckInReward("c003").orElseThrow();
        assertEquals(CheckInReward.MODE_FIXED, legacyRead.mode(), "旧流水按固定金额展示");
        assertEquals(0L, legacyRead.effectiveMillis());
        assertEquals("2", legacyRead.rate(), "旧流水没有 rate 时用当时的金额兜底");
        assertEquals("", legacyRead.note());
    }

    /** 发放游标与发放流水往返一致；游标是独立文档，不会被设置覆盖。 */
    @Test
    void checkInRewardCursorAndLedgerRoundTrip() {        repository.saveCheckInRewardCursor(new CheckInRewardCursor(1_500L, 3L, 7L));
        repository.saveCheckInReward(new CheckInReward("c001", "detail-1", "铺设石砖路", "proj-1", "古城改造",
                "10001", "2", "2", "playtime-points:checkin:c001", CheckInReward.SOURCE_REALTIME, 1_500L, 8L));
        repository.saveCheckInReward(new CheckInReward("c002", "detail-2", "搭建牌楼", "proj-1", "古城改造",
                "10002", "2", "2", "playtime-points:checkin:c002", CheckInReward.SOURCE_PULL, 1_600L, 9L));

        CheckInRewardCursor cursor = repository.findCheckInRewardCursor().orElseThrow();
        assertEquals(1_500L, cursor.lastAcceptedAt());
        assertEquals(3L, cursor.deliveredCount());

        CheckInReward read = repository.findCheckInReward("c001").orElseThrow();
        assertEquals("proj-1", read.projectId());
        assertEquals("10001", read.userId());
        assertEquals("2", read.credit());
        assertEquals(CheckInReward.SOURCE_REALTIME, read.source());
        assertEquals(1_500L, read.acceptedAt());
        assertEquals(2, repository.checkInRewards(null, null, 1, 10).total());
        assertEquals("c002", repository.checkInRewards(null, null, 1, 10).records().getFirst().checkInId(),
                "按验收通过时间倒序");
        assertEquals(1, repository.checkInRewards("proj-1", "10001", 1, 10).total(), "项目与用户筛选同时生效");
        assertEquals(1, repository.checkInRewards(null, "10002", 1, 10).total());
        assertEquals(0, repository.checkInRewards("proj-2", null, 1, 10).total());
        assertEquals(1, repository.allCheckInRewards("10001").size());
        assertTrue(repository.allCheckInRewards("10003").isEmpty());
    }

    private Map<String, Object> legacySettlementDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("serverId", "srv-1");
        document.put("serverName", "主服");
        document.put("playerId", "player-a");
        document.put("playerName", "Steve");
        document.put("userId", "10001");
        document.put("windowEnd", 2_000L);
        document.put("onlineMillis", 80 * 60_000L);
        document.put("afkMillis", 20 * 60_000L);
        document.put("effectiveMillis", 60 * 60_000L);
        document.put("weight", "1");
        document.put("points", "1.0000");
        document.put("credited", "1");
        document.put("carryAfter", "0.0000");
        document.put("assetCode", "POINT");
        document.put("businessNo", "pp:abc");
        document.put("createdAt", 5L);
        return document;
    }

    /** 内存版文档存储：语义与宿主一致（save 回写 id，findByField 按字段等值过滤）。 */
    private static final class FakeDocumentStore implements PluginDocumentStore {

        private final Map<String, Map<String, Map<String, Object>>> collections = new LinkedHashMap<>();

        @Override
        public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            Map<String, Object> stored = new LinkedHashMap<>(document);
            stored.put("id", id);
            collections.computeIfAbsent(collection, key -> new LinkedHashMap<>()).put(id, stored);
            return stored;
        }

        @Override
        public Optional<Map<String, Object>> findById(String collection, String id) {
            return Optional.ofNullable(collections.getOrDefault(collection, Map.of()).get(id));
        }

        @Override
        public List<Map<String, Object>> findAll(String collection, int page, int size) {
            return slice(new ArrayList<>(collections.getOrDefault(collection, Map.of()).values()), page, size);
        }

        @Override
        public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
            List<Map<String, Object>> matched = new ArrayList<>();
            for (Map<String, Object> document : collections.getOrDefault(collection, Map.of()).values()) {
                if (value.equals(document.get(field))) {
                    matched.add(document);
                }
            }
            return slice(matched, page, size);
        }

        @Override
        public long count(String collection) {
            return collections.getOrDefault(collection, Map.of()).size();
        }

        @Override
        public void delete(String collection, String id) {
            Map<String, Map<String, Object>> rows = collections.get(collection);
            if (rows != null) {
                rows.remove(id);
            }
        }

        private static List<Map<String, Object>> slice(List<Map<String, Object>> rows, int page, int size) {
            int safeSize = Math.max(size, 1);
            int from = Math.min(Math.max(page - 1, 0) * safeSize, rows.size());
            int to = Math.min(from + safeSize, rows.size());
            return rows.subList(from, to);
        }
    }
}
