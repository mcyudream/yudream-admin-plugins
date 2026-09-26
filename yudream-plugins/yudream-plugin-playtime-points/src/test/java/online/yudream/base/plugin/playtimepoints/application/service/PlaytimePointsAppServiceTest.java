package online.yudream.base.plugin.playtimepoints.application.service;

import online.yudream.base.plugin.playtimepoints.application.dto.ScanResult;
import online.yudream.base.plugin.playtimepoints.application.dto.SettlementPage;
import online.yudream.base.plugin.playtimepoints.application.port.MinecraftPort;
import online.yudream.base.plugin.playtimepoints.application.port.SkinPort;
import online.yudream.base.plugin.playtimepoints.application.port.WalletPort;
import online.yudream.base.plugin.playtimepoints.domain.aggregate.SettlementState;
import online.yudream.base.plugin.playtimepoints.domain.repo.PlaytimePointsRepository;
import online.yudream.base.plugin.playtimepoints.domain.service.PointsCalculator;
import online.yudream.base.plugin.playtimepoints.domain.valobj.PlaytimePointsSettings;
import online.yudream.base.plugin.playtimepoints.infrastructure.repository.FakePlaytimePointsRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaytimePointsAppServiceTest {

    private static final String SERVER = "srv-1";
    private static final String SERVER_NAME = "主服";
    private static final String PLAYER = "player-a";
    private static final String PLAYER_NAME = "Steve";
    private static final String OWNER = "10001";

    private final FakePlaytimePointsRepository repo = new FakePlaytimePointsRepository();
    private final FakeMinecraft minecraft = new FakeMinecraft();
    private final FakeWallet wallet = new FakeWallet();
    private final FakeSkin skin = new FakeSkin();
    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

    private PlaytimePointsAppService service() {
        return new PlaytimePointsAppService(repo, minecraft, wallet, skin, now::incrementAndGet);
    }

    private MinecraftPort.ActivityRef activity(boolean online, long onlineMinutes, long afkMinutes, Long lastQuitAt) {
        return new MinecraftPort.ActivityRef(SERVER, PLAYER, PLAYER_NAME, online,
                onlineMinutes * 60_000L, afkMinutes * 60_000L, lastQuitAt);
    }

    @Test
    void firstSightOnlyBaselinesAndNeverBackfillsHistory() {
        minecraft.activities.add(activity(false, 600, 60, 1_700_000_000_000L - 60_000L));
        ScanResult result = service().scan();
        assertEquals(0, result.credited());
        assertTrue(wallet.credits.isEmpty());
        SettlementState state = repo.state(SERVER + ":" + PLAYER);
        assertNotNull(state);
        assertEquals(600 * 60_000L, state.settledOnlineMillis());
        assertEquals(60 * 60_000L, state.settledAfkMillis());
    }

    @Test
    void quitAfterBaselineRecordsFractionalCarryWithoutCredit() {
        skin.owners.put(PLAYER, OWNER);
        // 基线：累计 60 分钟在线
        minecraft.activities.add(activity(false, 60, 0, 1_000L));
        service().scan();
        // 本次会话：累计 120 分钟在线、10 分钟挂机，退出时间前进
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 120, 10, 2_000L));
        ScanResult result = service().scan();
        // 有效 50 分钟 ÷ 60 = 0.8333，不足 1 分只记零头
        assertEquals(0, result.credited());
        assertEquals(1, result.sessions());
        assertEquals(1, repo.settlements(null, null, null, 1, 10).total());
        SettlementState state = repo.state(SERVER + ":" + PLAYER);
        assertEquals(0, new BigDecimal("0.8333").compareTo(new BigDecimal(state.carryPoints())));
        assertTrue(wallet.credits.isEmpty());
    }

    @Test
    void carryAccumulatesUntilWholePointThenCreditsOnce() {
        repo.saveSettings(PlaytimePointsSettings.defaults());
        skin.owners.put(PLAYER, OWNER);
        minecraft.activities.add(activity(false, 0, 0, 1_000L));
        service().scan();
        // 第一段：30 分钟有效 → 0.5 分零头
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 30, 0, 2_000L));
        service().scan();
        // 第二段：再 30 分钟有效 → 累计 1 分，整分入账
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 60, 0, 3_000L));
        ScanResult result = service().scan();
        assertEquals(1, result.credited());
        assertEquals(1, wallet.credits.size());
        FakeWallet.Credit credit = wallet.credits.getFirst();
        assertEquals(OWNER, credit.userId());
        assertEquals("POINT", credit.assetCode());
        assertEquals(0, credit.amount().compareTo(BigDecimal.ONE));
        assertTrue(credit.businessNo().startsWith("pp:"));
        assertEquals("0", PointsCalculator.plain(PointsCalculator.parseCarry(
                repo.state(SERVER + ":" + PLAYER).carryPoints())));
    }

    @Test
    void afkTimeIsSubtractedFromOnlineDelta() {
        repo.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of()));
        skin.owners.put(PLAYER, OWNER);
        minecraft.activities.add(activity(false, 100, 20, 1_000L));
        service().scan();
        // 本段在线 60 分钟、挂机 60 分钟 → 有效 0，只推进基线不产生流水
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 160, 80, 2_000L));
        ScanResult result = service().scan();
        assertEquals(0, result.sessions());
        SettlementState state = repo.state(SERVER + ":" + PLAYER);
        assertEquals(160 * 60_000L, state.settledOnlineMillis());
        assertEquals(0, repo.settlements(null, null, null, 1, 10).total());
    }

    @Test
    void subtractAfkCanBeDisabled() {
        repo.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, false, Map.of()));
        skin.owners.put(PLAYER, OWNER);
        minecraft.activities.add(activity(false, 60, 60, 1_000L));
        service().scan();
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 120, 60, 2_000L));
        ScanResult result = service().scan();
        // 挂机不扣：60 分钟 → 1 分整
        assertEquals(1, result.credited());
        assertEquals(0, wallet.credits.getFirst().amount().compareTo(BigDecimal.ONE));
    }

    @Test
    void serverWeightMultipliesPoints() {
        Map<String, PlaytimePointsSettings.ServerRule> rules = new LinkedHashMap<>();
        rules.put(SERVER, new PlaytimePointsSettings.ServerRule("2", true));
        repo.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, rules));
        skin.owners.put(PLAYER, OWNER);
        minecraft.activities.add(activity(false, 0, 0, 1_000L));
        service().scan();
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 60, 0, 2_000L));
        service().scan();
        assertEquals(0, wallet.credits.getFirst().amount().compareTo(new BigDecimal("2")));
    }

    @Test
    void unboundPlayerDefersSettlementWithoutAdvancingBaseline() {
        minecraft.activities.add(activity(false, 60, 0, 1_000L));
        service().scan();
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 120, 0, 2_000L));
        ScanResult result = service().scan();
        assertEquals(0, result.credited());
        assertTrue(result.message().contains("未绑定"));
        // 基线未推进，钱包无入账
        assertEquals(60 * 60_000L, repo.state(SERVER + ":" + PLAYER).settledOnlineMillis());
        assertTrue(wallet.credits.isEmpty());
        // 绑定后同一份累计数据自动补发
        skin.owners.put(PLAYER, OWNER);
        ScanResult retry = service().scan();
        assertEquals(1, retry.sessions());
        assertEquals(1, wallet.credits.size());
    }

    @Test
    void walletUnavailableDefersSettlement() {
        skin.owners.put(PLAYER, OWNER);
        wallet.walletAvailable = false;
        minecraft.activities.add(activity(false, 60, 0, 1_000L));
        service().scan();
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 120, 0, 2_000L));
        ScanResult result = service().scan();
        assertTrue(result.message().contains("钱包"));
        assertEquals(60 * 60_000L, repo.state(SERVER + ":" + PLAYER).settledOnlineMillis());
        wallet.walletAvailable = true;
        assertEquals(1, service().scan().credited());
    }

    @Test
    void disabledServerAndOnlinePlayersAreSkipped() {
        Map<String, PlaytimePointsSettings.ServerRule> rules = new LinkedHashMap<>();
        rules.put(SERVER, new PlaytimePointsSettings.ServerRule("1", false));
        repo.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, rules));
        minecraft.activities.add(activity(true, 120, 0, null));
        assertEquals(0, service().scan().sessions());
        assertNull(repo.state(SERVER + ":" + PLAYER));
    }

    @Test
    void masterSwitchStopsScanning() {
        repo.saveSettings(new PlaytimePointsSettings(false, "POINT", 60, true, Map.of()));
        minecraft.activities.add(activity(false, 120, 0, 2_000L));
        ScanResult result = service().scan();
        assertEquals(0, result.sessions());
        assertEquals("结算已停用", result.message());
    }

    @Test
    void sameQuitAtIsSettledOnlyOnce() {
        skin.owners.put(PLAYER, OWNER);
        minecraft.activities.add(activity(false, 0, 0, 1_000L));
        service().scan();
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 120, 0, 2_000L));
        assertEquals(1, service().scan().credited());
        // 数据未变化再扫一轮：lastQuitAt 未前进，不重复入账
        assertEquals(0, service().scan().credited());
        assertEquals(1, wallet.credits.size());
    }

    @Test
    void walletCreditFailureKeepsBaselineForRetry() {
        skin.owners.put(PLAYER, OWNER);
        wallet.failOnCredit = true;
        minecraft.activities.add(activity(false, 0, 0, 1_000L));
        service().scan();
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 120, 0, 2_000L));
        ScanResult result = service().scan();
        assertTrue(result.message().contains("入账失败"));
        // 基线仍停留在首扫时的 0 分钟，未推进
        assertEquals(0L, repo.state(SERVER + ":" + PLAYER).settledOnlineMillis());
        wallet.failOnCredit = false;
        assertEquals(1, service().scan().credited());
    }

    @Test
    void totalsResetOnMcServerSideNeverProducesNegativePoints() {
        skin.owners.put(PLAYER, OWNER);
        minecraft.activities.add(activity(false, 300, 0, 1_000L));
        service().scan();
        // mc-server 侧累计值被重置变小但退出时间前进：差值钳制为 0，静默推进基线
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 10, 0, 2_000L));
        ScanResult result = service().scan();
        assertEquals(0, result.sessions());
        assertEquals(10 * 60_000L, repo.state(SERVER + ":" + PLAYER).settledOnlineMillis());
        assertTrue(wallet.credits.isEmpty());
    }

    @Test
    void saveSettingsNormalizesAndValidates() {
        wallet.assets.add(new WalletPort.AssetRef("POINT", "积分", "", 0, false, true));
        PlaytimePointsSettings saved = service().saveSettings(
                new PlaytimePointsSettings(true, "point", 30, true, Map.of()));
        assertEquals("POINT", saved.assetCode());
        assertEquals(30, saved.minutesPerPoint());
        // 钱包可用时，不存在的货币类型必须被拒绝
        IllegalArgumentException failure = null;
        try {
            service().saveSettings(new PlaytimePointsSettings(true, "GOLD", 60, true, Map.of()));
        } catch (IllegalArgumentException e) {
            failure = e;
        }
        assertNotNull(failure);
        assertTrue(failure.getMessage().contains("GOLD"));
    }

    @Test
    void mySettlementsOnlyReturnOwnRecords() {
        repo.saveSettings(PlaytimePointsSettings.defaults());
        skin.owners.put(PLAYER, OWNER);
        minecraft.activities.add(activity(false, 0, 0, 1_000L));
        service().scan();
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 120, 0, 2_000L));
        service().scan();
        SettlementPage mine = service().mySettlements(OWNER, 1, 10);
        assertEquals(1, mine.total());
        assertEquals(OWNER, mine.records().getFirst().userId());
        assertEquals(0, service().mySettlements("99999", 1, 10).total());
    }

    /* ---------- 测试替身 ---------- */

    private static class FakeMinecraft implements MinecraftPort {
        final List<ActivityRef> activities = new ArrayList<>();

        @Override
        public boolean minecraftAvailable() {
            return true;
        }

        @Override
        public List<ServerRef> servers() {
            return List.of(new ServerRef(SERVER, SERVER_NAME));
        }

        @Override
        public List<ActivityRef> playerActivities(String serverId, int page, int size) {
            if (page > 1) {
                return List.of();
            }
            return activities;
        }
    }

    private static class FakeWallet implements WalletPort {
        final List<AssetRef> assets = new ArrayList<>(List.of(
                new AssetRef("POINT", "积分", "", 0, false, true)));
        final List<Credit> credits = new ArrayList<>();
        boolean walletAvailable = true;
        boolean failOnCredit = false;

        record Credit(String userId, String assetCode, BigDecimal amount, String businessNo, String remark) {
        }

        @Override
        public boolean walletAvailable() {
            return walletAvailable;
        }

        @Override
        public List<AssetRef> assets() {
            return assets;
        }

        @Override
        public Optional<AssetRef> findAsset(String assetCode) {
            return assets.stream().filter(asset -> asset.code().equals(assetCode)).findFirst();
        }

        @Override
        public Optional<String> balance(String userId, String assetCode) {
            return Optional.empty();
        }

        @Override
        public void credit(String userId, String assetCode, BigDecimal amount, String businessNo, String remark) {
            if (!walletAvailable) {
                throw new IllegalStateException("钱包插件不可用");
            }
            if (failOnCredit) {
                throw new IllegalStateException("模拟入账失败");
            }
            credits.add(new Credit(userId, assetCode, amount, businessNo, remark));
        }
    }

    private static class FakeSkin implements SkinPort {
        final Map<String, String> owners = new LinkedHashMap<>();

        @Override
        public Optional<String> resolveOwnerId(String playerId, String playerName) {
            return Optional.ofNullable(owners.get(playerId));
        }
    }
}
