package online.yudream.base.plugin.playtimepoints.application.service;

import online.yudream.base.plugin.playtimepoints.application.dto.ScanResult;
import online.yudream.base.plugin.playtimepoints.application.dto.SettlementPage;
import online.yudream.base.plugin.playtimepoints.application.dto.SettlementView;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /* ---------- 打卡积分联动的设置校验 ---------- */

    /** 打卡积分金额必须是大于 0 的十进制数字，且精度不能超过货币类型允许的位数（中文报错）。 */
    @Test
    void saveSettingsValidatesCheckInRewardPoints() {
        wallet.assets.clear();
        wallet.assets.add(new WalletPort.AssetRef("POINT", "积分", "", 0, false, true));

        PlaytimePointsSettings saved = service().saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true,
                Map.of(), Map.of(), true, "2", true, Map.of("proj-1", "3")));
        assertTrue(saved.checkInRewardEnabled());
        assertEquals("2", saved.checkInRewardPoints());
        assertEquals("3", saved.checkInRewardProjectPoints().get("proj-1"));

        for (String illegal : List.of("abc", "0", "-1", "1.5")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> service().saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(),
                            Map.of(), true, illegal, false, Map.of())), "非法金额必须被拒绝：" + illegal);
            assertTrue(failure.getMessage().contains("打卡积分金额"), failure.getMessage());
        }

        IllegalArgumentException projectFailure = assertThrows(IllegalArgumentException.class,
                () -> service().saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                        true, "1", false, Map.of("proj-1", "abc"))));
        assertTrue(projectFailure.getMessage().contains("proj-1"), projectFailure.getMessage());

        // 货币精度放宽到 2 位后，两位小数合法
        wallet.assets.clear();
        wallet.assets.add(new WalletPort.AssetRef("POINT", "积分", "", 2, false, true));
        assertEquals("1.50", service().saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(),
                Map.of(), true, "1.50", false, Map.of())).checkInRewardPoints());
    }

    /** 时薪校验：> 0、不超过 1000000、最多 4 位小数（中文报错）；时薪是费率，不受货币精度限制。 */
    @Test
    void saveSettingsValidatesCheckInHourlyPoints() {
        wallet.assets.clear();
        wallet.assets.add(new WalletPort.AssetRef("POINT", "积分", "", 2, false, true));

        PlaytimePointsSettings saved = service().saveSettings(hourlySettings("10.5", Map.of()));
        assertEquals(PlaytimePointsSettings.CHECK_IN_MODE_HOURLY, saved.checkInRewardMode());
        assertEquals("10.5", saved.checkInHourlyPoints());

        for (String illegal : List.of("abc", "0", "-1", "1.00001")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> service().saveSettings(hourlySettings(illegal, Map.of())),
                    "非法时薪必须被拒绝：" + illegal);
            assertTrue(failure.getMessage().contains("时薪"), failure.getMessage());
        }

        IllegalArgumentException tooLarge = assertThrows(IllegalArgumentException.class,
                () -> service().saveSettings(hourlySettings("1000001", Map.of())));
        assertTrue(tooLarge.getMessage().contains("时薪"), tooLarge.getMessage());
        assertTrue(tooLarge.getMessage().contains("1000000"), tooLarge.getMessage());

        // 时薪 0.125 只有 3 位小数：合法，即使货币精度是 2 位也放行（精度由折算结果承担）
        assertEquals("0.125", service().saveSettings(hourlySettings("0.125", Map.of())).checkInHourlyPoints());
    }

    /** 按当前货币精度折算后恒为 0 的时薪会被拒绝，并提示最小可用时薪。 */
    @Test
    void saveSettingsRejectsHourlyRateThatAlwaysRoundsToZero() {
        wallet.assets.clear();
        wallet.assets.add(new WalletPort.AssetRef("POINT", "积分", "", 0, false, true));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service().saveSettings(hourlySettings("0.1", Map.of())));

        assertTrue(failure.getMessage().contains("时薪"), failure.getMessage());
        assertTrue(failure.getMessage().contains("折算后为 0"), failure.getMessage());
        assertTrue(failure.getMessage().contains("0.5"), "提示最小可用时薪：" + failure.getMessage());

        // 0.5 恰好能折算成 1 分（HALF_UP），必须放行
        assertEquals("0.5", service().saveSettings(hourlySettings("0.5", Map.of())).checkInHourlyPoints());
    }

    /** 按时薪模式下项目覆盖按「每小时积分」校验，报错文案指出是时薪。 */
    @Test
    void saveSettingsValidatesHourlyProjectOverrides() {
        wallet.assets.clear();
        wallet.assets.add(new WalletPort.AssetRef("POINT", "积分", "", 2, false, true));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service().saveSettings(hourlySettings("10", Map.of("proj-1", "0"))));

        assertTrue(failure.getMessage().contains("proj-1"), failure.getMessage());
        assertTrue(failure.getMessage().contains("时薪"), failure.getMessage());
        // 固定模式下同一个项目覆盖按「每次金额」校验，文案不含时薪
        IllegalArgumentException fixedFailure = assertThrows(IllegalArgumentException.class,
                () -> service().saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                        true, "1", false, Map.of("proj-1", "abc"))));
        assertTrue(fixedFailure.getMessage().contains("金额"), fixedFailure.getMessage());
    }

    /**
     * 非时长打卡积分校验：**0 是合法值**（表示这类打卡不发）；大于 0 时不超过 1000000、最多 4 位小数，
     * 且不超过当前货币精度（它是最终入账金额，不经过折算）——全部中文报错。
     */
    @Test
    void saveSettingsValidatesCheckInFixedPoints() {
        wallet.assets.clear();
        wallet.assets.add(new WalletPort.AssetRef("POINT", "积分", "", 2, false, true));

        // 0 合法；正常值与两位小数放行
        assertEquals("0", service().saveSettings(hourlySettingsWithFixed("10", "0")).checkInFixedPoints());
        assertEquals("3", service().saveSettings(hourlySettingsWithFixed("10", "3")).checkInFixedPoints());
        assertEquals("1.50", service().saveSettings(hourlySettingsWithFixed("10", "1.50")).checkInFixedPoints());

        for (String illegal : List.of("abc", "-1", "1.00001")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> service().saveSettings(hourlySettingsWithFixed("10", illegal)),
                    "非法非时长打卡积分必须被拒绝：" + illegal);
            assertTrue(failure.getMessage().contains("非时长打卡"), failure.getMessage());
        }

        IllegalArgumentException tooLarge = assertThrows(IllegalArgumentException.class,
                () -> service().saveSettings(hourlySettingsWithFixed("10", "1000001")));
        assertTrue(tooLarge.getMessage().contains("非时长打卡"), tooLarge.getMessage());
        assertTrue(tooLarge.getMessage().contains("1000000"), tooLarge.getMessage());

        IllegalArgumentException tooPrecise = assertThrows(IllegalArgumentException.class,
                () -> service().saveSettings(hourlySettingsWithFixed("10", "1.23456")));
        assertTrue(tooPrecise.getMessage().contains("非时长打卡"), tooPrecise.getMessage());
        assertTrue(tooPrecise.getMessage().contains("4 位小数"), tooPrecise.getMessage());

        // 货币精度 0 位时两位小数被拦下（精度提示带上货币代码）
        wallet.assets.clear();
        wallet.assets.add(new WalletPort.AssetRef("POINT", "积分", "", 0, false, true));
        IllegalArgumentException assetPrecision = assertThrows(IllegalArgumentException.class,
                () -> service().saveSettings(hourlySettingsWithFixed("10", "1.5")));
        assertTrue(assetPrecision.getMessage().contains("非时长打卡"), assetPrecision.getMessage());
        assertTrue(assetPrecision.getMessage().contains("0 位小数"), assetPrecision.getMessage());
        // 精度 0 位下整数依然合法
        assertEquals("2", service().saveSettings(hourlySettingsWithFixed("10", "2")).checkInFixedPoints());
    }

    /** 构造按时薪折算的设置（时薪 + 项目覆盖）。 */
    private static PlaytimePointsSettings hourlySettings(String hourlyPoints, Map<String, String> projectOverrides) {
        return new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, "1", false, projectOverrides, PlaytimePointsSettings.CHECK_IN_MODE_HOURLY, hourlyPoints);
    }

    /** 构造按时薪折算 + 非时长打卡积分的设置。 */
    private static PlaytimePointsSettings hourlySettingsWithFixed(String hourlyPoints, String checkInFixedPoints) {
        return new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, "1", false, Map.of(), PlaytimePointsSettings.CHECK_IN_MODE_HOURLY, hourlyPoints,
                checkInFixedPoints);
    }

    /** 打开打卡积分联动不改变原有在线时长结算行为（同一仓储下流水与业务单号照旧）。 */
    @Test
    void settlementIsUnaffectedByCheckInRewardSettings() {        repo.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, "5", true, Map.of("proj-1", "9")));
        skin.owners.put(PLAYER, OWNER);
        minecraft.activities.add(activity(false, 0, 0, 1_000L));
        service().scan();
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 60, 0, 2_000L));

        ScanResult result = service().scan();

        assertEquals(1, result.credited());
        assertEquals(1, wallet.credits.size());
        assertTrue(wallet.credits.getFirst().businessNo().startsWith("pp:"),
                "时长结算的单号前缀不变，与打卡积分单号互不干扰");
        assertEquals(0, wallet.credits.getFirst().amount().compareTo(BigDecimal.ONE));
    }

    /* ---------- 群组服：子服维度 ---------- */

    private static MinecraftPort.SubActivityRef sub(String subServer, long onlineMinutes, long afkMinutes) {
        return new MinecraftPort.SubActivityRef(subServer, false, onlineMinutes * 60_000L, afkMinutes * 60_000L);
    }

    private static Map<String, PlaytimePointsSettings.ServerRule> subRules(String... subServerAndWeight) {
        Map<String, PlaytimePointsSettings.ServerRule> rules = new LinkedHashMap<>();
        for (int index = 0; index + 1 < subServerAndWeight.length; index += 2) {
            rules.put(PlaytimePointsSettings.subServerKey(SERVER, subServerAndWeight[index]),
                    new PlaytimePointsSettings.ServerRule(subServerAndWeight[index + 1], true));
        }
        return rules;
    }

    private SettlementView onlySettlement() {
        SettlementPage page = service().adminSettlements(null, null, 1, 10);
        assertEquals(1, page.total());
        return page.records().getFirst();
    }

    @Test
    void groupedSubServersSumWeightedPoints() {
        repo.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(),
                subRules("fabric", "2", "paper", "1")));
        skin.owners.put(PLAYER, OWNER);
        // 基线：两个子服都还没有时长
        minecraft.activities.add(activity(false, 0, 0, 1_000L));
        minecraft.subActivities.add(sub("fabric", 0, 0));
        minecraft.subActivities.add(sub("paper", 0, 0));
        service().scan();
        // 本次会话：fabric 30 分钟（权重 2）+ paper 30 分钟（权重 1）
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 60, 0, 2_000L));
        minecraft.subActivities.clear();
        minecraft.subActivities.add(sub("fabric", 30, 0));
        minecraft.subActivities.add(sub("paper", 30, 0));
        ScanResult result = service().scan();
        // (30×2 + 30×1) ÷ 60 = 1.5 → 入账 1 分，零头 0.5
        assertEquals(1, result.credited());
        assertEquals(1, result.sessions());
        assertEquals(1, wallet.credits.size());
        assertEquals(0, wallet.credits.getFirst().amount().compareTo(BigDecimal.ONE));
        assertEquals(0, new BigDecimal(repo.state(SERVER + ":" + PLAYER).carryPoints())
                .compareTo(new BigDecimal("0.5")));

        SettlementView view = onlySettlement();
        // 顶层是各子服的合计，与 mc-server 的整服累计一致
        assertEquals(60 * 60_000L, view.onlineMillis());
        assertEquals(60L, view.effectiveMinutes());
        assertEquals(0, new BigDecimal(view.points()).compareTo(new BigDecimal("1.5")));
        // 两个子服各一条明细
        assertEquals(2, view.subServers().size());
        assertEquals("fabric", view.subServers().getFirst().subServer());
        assertEquals("2", view.subServers().getFirst().weight());
        assertEquals(30L, view.subServers().getFirst().effectiveMinutes());
        assertEquals(0, new BigDecimal(view.subServers().getFirst().points()).compareTo(BigDecimal.ONE));
        assertTrue(view.subServers().getFirst().enabled());
        assertEquals("paper", view.subServers().get(1).subServer());
        assertEquals(0, new BigDecimal(view.subServers().get(1).points()).compareTo(new BigDecimal("0.5")));
    }

    @Test
    void subServerRuleOverridesServerRuleForThatSubServerOnly() {
        // 服务器权重 1，但 fabric 子服权重 2：只有 fabric 上的时长被加权
        repo.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true,
                Map.of(SERVER, new PlaytimePointsSettings.ServerRule("1", true)),
                subRules("fabric", "2")));
        skin.owners.put(PLAYER, OWNER);
        minecraft.activities.add(activity(false, 0, 0, 1_000L));
        minecraft.subActivities.add(sub("fabric", 0, 0));
        minecraft.subActivities.add(sub("paper", 0, 0));
        service().scan();
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 60, 0, 2_000L));
        minecraft.subActivities.clear();
        minecraft.subActivities.add(sub("fabric", 30, 0));
        minecraft.subActivities.add(sub("paper", 30, 0));
        service().scan();
        // (30×2 + 30×1) ÷ 60 = 1.5，与服务器权重无关
        assertEquals(0, new BigDecimal(onlySettlement().points()).compareTo(new BigDecimal("1.5")));
    }

    @Test
    void zeroWeightSubServerStopsCreditingButKeepsAdvancingBaseline() {
        repo.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(),
                subRules("fabric", "0")));
        skin.owners.put(PLAYER, OWNER);
        minecraft.activities.add(activity(false, 0, 0, 1_000L));
        minecraft.subActivities.add(sub("fabric", 0, 0));
        service().scan();
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 120, 0, 2_000L));
        minecraft.subActivities.clear();
        minecraft.subActivities.add(sub("fabric", 120, 0));
        ScanResult result = service().scan();
        // 权重 0 = 停算：仍然写流水（便于审计）但积分与入账都是 0
        assertEquals(0, result.credited());
        assertEquals(1, result.sessions());
        assertTrue(wallet.credits.isEmpty());
        SettlementView view = onlySettlement();
        assertEquals(0, new BigDecimal(view.points()).compareTo(BigDecimal.ZERO));
        assertEquals("0", view.subServers().getFirst().weight());
        assertEquals(0, new BigDecimal(view.subServers().getFirst().points()).compareTo(BigDecimal.ZERO));
        // 基线照常推进：恢复权重后不会补发停算期间的时长
        assertEquals(120 * 60_000L, repo.state(SERVER + ":" + PLAYER).subServers().get("fabric").onlineMillis());
    }

    @Test
    void disabledSubServerIsExcludedFromPointsButStillRecorded() {
        Map<String, PlaytimePointsSettings.ServerRule> rules = new LinkedHashMap<>();
        rules.put(PlaytimePointsSettings.subServerKey(SERVER, "fabric"),
                new PlaytimePointsSettings.ServerRule("2", true));
        rules.put(PlaytimePointsSettings.subServerKey(SERVER, "paper"),
                new PlaytimePointsSettings.ServerRule("1", false));
        repo.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), rules));
        skin.owners.put(PLAYER, OWNER);
        minecraft.activities.add(activity(false, 0, 0, 1_000L));
        minecraft.subActivities.add(sub("fabric", 0, 0));
        minecraft.subActivities.add(sub("paper", 0, 0));
        service().scan();
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 60, 0, 2_000L));
        minecraft.subActivities.clear();
        minecraft.subActivities.add(sub("fabric", 30, 0));
        minecraft.subActivities.add(sub("paper", 30, 0));
        service().scan();
        // 只有 fabric 参与计分：30×2 ÷ 60 = 1 分
        assertEquals(1, wallet.credits.size());
        SettlementView view = onlySettlement();
        assertEquals(0, new BigDecimal(view.points()).compareTo(BigDecimal.ONE));
        assertEquals(2, view.subServers().size());
        assertEquals("paper", view.subServers().get(1).subServer());
        assertFalse(view.subServers().get(1).enabled());
        assertEquals(0, new BigDecimal(view.subServers().get(1).points()).compareTo(BigDecimal.ZERO));
        // 未参与结算的子服时长仍然如实记录在顶层
        assertEquals(60L, view.effectiveMinutes());
    }

    @Test
    void defaultOnlyBucketKeepsTheFlatSettlementResult() {
        repo.saveSettings(PlaytimePointsSettings.defaults());
        skin.owners.put(PLAYER, OWNER);
        minecraft.activities.add(activity(false, 60, 0, 1_000L));
        minecraft.subActivities.add(sub("default", 60, 0));
        service().scan();
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 120, 10, 2_000L));
        minecraft.subActivities.clear();
        minecraft.subActivities.add(sub("default", 120, 10));
        ScanResult result = service().scan();
        // 有效 50 分钟 ÷ 60 = 0.8333，不足 1 分只记零头：与改造前的整服口径完全一致
        assertEquals(0, result.credited());
        assertEquals(1, result.sessions());
        SettlementView view = onlySettlement();
        assertEquals(0, new BigDecimal(view.points()).compareTo(new BigDecimal("0.8333")));
        assertEquals("1", view.weight());
        assertTrue(view.subServers().isEmpty());
        assertTrue(repo.state(SERVER + ":" + PLAYER).subServers().isEmpty());
    }

    @Test
    void firstGroupedScanAfterUpgradeRebaselinesWithoutDoubleCrediting() {
        repo.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(),
                subRules("fabric", "1")));
        skin.owners.put(PLAYER, OWNER);
        // 改造前：整服累计 60 分钟已经结算过（只有 default 桶 = 旧口径）
        minecraft.activities.add(activity(false, 60, 0, 1_000L));
        minecraft.subActivities.add(sub("default", 60, 0));
        service().scan();
        assertEquals(60 * 60_000L, repo.state(SERVER + ":" + PLAYER).settledOnlineMillis());
        // 子服明细出现：整服 180 分钟里包含改造前已结算的 60 分钟
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 180, 0, 2_000L));
        minecraft.subActivities.clear();
        minecraft.subActivities.add(sub("default", 60, 0));
        minecraft.subActivities.add(sub("fabric", 120, 0));
        ScanResult migration = service().scan();
        assertEquals(0, migration.sessions());
        assertTrue(wallet.credits.isEmpty());
        // 只重建子服基线，不结算
        assertEquals(120 * 60_000L,
                repo.state(SERVER + ":" + PLAYER).subServers().get("fabric").onlineMillis());
        // 下一轮：fabric 前进 60 分钟 → 1 分
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 240, 0, 3_000L));
        minecraft.subActivities.clear();
        minecraft.subActivities.add(sub("default", 60, 0));
        minecraft.subActivities.add(sub("fabric", 180, 0));
        ScanResult settled = service().scan();
        assertEquals(1, settled.sessions());
        assertEquals(1, wallet.credits.size());
        assertEquals(0, wallet.credits.getFirst().amount().compareTo(BigDecimal.ONE));
    }

    @Test
    void subServerTimelineSurvivesUnboundPlayerRetry() {        repo.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(),
                subRules("fabric", "1")));
        minecraft.activities.add(activity(false, 0, 0, 1_000L));
        minecraft.subActivities.add(sub("fabric", 0, 0));
        service().scan();
        // 未绑定：暂缓结算且子服基线不推进
        minecraft.activities.clear();
        minecraft.activities.add(activity(false, 60, 0, 2_000L));
        minecraft.subActivities.clear();
        minecraft.subActivities.add(sub("fabric", 60, 0));
        ScanResult deferred = service().scan();
        assertEquals(0, deferred.sessions());
        assertEquals(0L, repo.state(SERVER + ":" + PLAYER).subServers().get("fabric").onlineMillis());
        // 绑定后自动补发同一段时长
        skin.owners.put(PLAYER, OWNER);
        assertEquals(1, service().scan().credited());
    }

    @Test
    void saveSettingsRejectsIllegalSubServerWeight() {
        wallet.assets.add(new WalletPort.AssetRef("POINT", "积分", "", 0, false, true));
        Map<String, PlaytimePointsSettings.ServerRule> rules = new LinkedHashMap<>();
        rules.put(PlaytimePointsSettings.subServerKey(SERVER, "fabric"),
                new PlaytimePointsSettings.ServerRule("1000", true));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service().saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), rules)));
        assertTrue(failure.getMessage().contains("fabric"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void optionsExposeServerSubServerTopology() {
        minecraft.subServers.add(new MinecraftPort.SubServerRef("fabric", true, 3, true));
        Map<String, Object> payload = service().options();
        List<Map<String, Object>> servers = (List<Map<String, Object>>) payload.get("servers");
        assertEquals(1, servers.size());
        List<Map<String, Object>> subServers = (List<Map<String, Object>>) servers.getFirst().get("subServers");
        assertEquals(1, subServers.size());
        assertEquals("fabric", subServers.getFirst().get("name"));
        assertEquals(Boolean.TRUE, subServers.getFirst().get("defaultServer"));
        assertEquals(3, subServers.getFirst().get("online"));
        // 提供方不支持子服时返回空数组，前端据此不显示子服区块
        minecraft.subServers.clear();
        List<Map<String, Object>> empty = (List<Map<String, Object>>) ((List<Map<String, Object>>)
                service().options().get("servers")).getFirst().get("subServers");
        assertTrue(empty.isEmpty());
    }

    /* ---------- 测试替身 ---------- */

    private static class FakeMinecraft implements MinecraftPort {
        final List<ActivityRef> activities = new ArrayList<>();
        /** 最近一次读取返回的子服明细；留空表示提供方不支持子服（整服口径）。 */
        final List<SubActivityRef> subActivities = new ArrayList<>();
        final List<SubServerRef> subServers = new ArrayList<>();

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

        @Override
        public List<SubServerRef> subServers(String serverId) {
            return subServers;
        }

        @Override
        public List<SubActivityRef> subServerActivities(String serverId, String playerId) {
            return subActivities;
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
