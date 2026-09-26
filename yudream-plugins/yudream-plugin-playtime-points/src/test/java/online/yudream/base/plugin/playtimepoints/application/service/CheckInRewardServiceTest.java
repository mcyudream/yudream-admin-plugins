package online.yudream.base.plugin.playtimepoints.application.service;

import online.yudream.base.plugin.playtimepoints.application.dto.CheckInScanResult;
import online.yudream.base.plugin.playtimepoints.application.port.ProjectProgressPort;
import online.yudream.base.plugin.playtimepoints.application.port.WalletPort;
import online.yudream.base.plugin.playtimepoints.domain.aggregate.CheckInReward;
import online.yudream.base.plugin.playtimepoints.domain.valobj.CheckInRewardCursor;
import online.yudream.base.plugin.playtimepoints.domain.valobj.PlaytimePointsSettings;
import online.yudream.base.plugin.playtimepoints.infrastructure.repository.FakePlaytimePointsRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打卡积分联动（project-progress 软依赖）用例：拉取、项目金额覆盖、幂等双保险、实时回调注册与
 * 降级、失败重试策略。
 *
 * <p>沿用 {@code FakePlaytimePointsRepository} 与 fake 端口的风格；provider 用
 * {@link FakeProjectProgress} 模拟（含「旧版方法返回空」与「未安装」两种降级形态）。</p>
 */
class CheckInRewardServiceTest {

    private static final String PROJECT = "proj-1";
    private static final String OTHER_PROJECT = "proj-2";
    private static final String USER = "10001";
    private static final String OTHER_USER = "10002";

    private final FakePlaytimePointsRepository repo = new FakePlaytimePointsRepository();
    private final FakeProjectProgress projectProgress = new FakeProjectProgress();
    private final FakeWallet wallet = new FakeWallet();
    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

    private CheckInRewardService service() {
        return new CheckInRewardService(repo, projectProgress, wallet, now::incrementAndGet);
    }

    /** 固定金额模式（默认，与升级前一致）。 */
    private void settings(boolean checkInEnabled, String globalPoints, boolean realtime,
                          Map<String, String> projectOverrides) {
        repo.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                checkInEnabled, globalPoints, realtime, projectOverrides));
    }

    /** 按时薪折算模式：固定金额字段仍保留（切回固定模式时使用），时薪独立配置。 */
    private void hourlySettings(boolean checkInEnabled, String hourlyPoints, boolean realtime,
                                Map<String, String> projectOverrides) {
        // 十二参构造（新增「非时长打卡积分」之前的调用方）：该字段回退默认 "0"，即非时长打卡不发，
        // 行为与 1.3.0 逐位一致。
        repo.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                checkInEnabled, "1", realtime, projectOverrides,
                PlaytimePointsSettings.CHECK_IN_MODE_HOURLY, hourlyPoints));
    }

    /** 按时薪折算模式 + 非时长打卡积分（图片/文件/定位等没有时长的打卡每次发放的积分）。 */
    private void hourlySettings(boolean checkInEnabled, String hourlyPoints, String checkInFixedPoints,
                                boolean realtime, Map<String, String> projectOverrides) {
        repo.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                checkInEnabled, "1", realtime, projectOverrides,
                PlaytimePointsSettings.CHECK_IN_MODE_HOURLY, hourlyPoints, checkInFixedPoints));
    }

    private static ProjectProgressPort.RewardRef reward(String id, String projectId, String userId, long acceptedAt) {
        return reward(id, projectId, userId, acceptedAt, 0L);
    }

    private static ProjectProgressPort.RewardRef reward(String id, String projectId, String userId, long acceptedAt,
                                                        long effectiveMillis) {
        return new ProjectProgressPort.RewardRef(id, "detail-1", "铺设石砖路", projectId, "古城改造", userId,
                "MINECRAFT_ONLINE", acceptedAt - 1_000L, acceptedAt, "9001", effectiveMillis);
    }

    /** 把资产精度调成指定值，用于验证「按货币精度取整」。 */
    private void assetScale(int scale) {
        wallet.assets.clear();
        wallet.assets.add(new WalletPort.AssetRef("POINT", "积分", "", scale, false, true));
    }

    private List<BigDecimal> creditedAmounts() {
        return wallet.credits.stream().map(FakeWallet.Credit::amount).toList();
    }

    /* ---------- 拉取 ---------- */

    /** 一条打卡发一次；游标推进；边界记录被重复拉到时不重复发放。 */
    @Test
    void pullCreditsEveryAcceptedCheckInOnceAndAdvancesCursor() {
        settings(true, "1", false, Map.of());
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L));
        projectProgress.rewards.add(reward("c002", PROJECT, USER, 1_500L));

        CheckInScanResult first = service().scan();

        assertEquals(2, first.credited(), "两条打卡各发一次");
        assertEquals(0, first.retried());
        assertEquals("OK", first.message());
        assertEquals(2, wallet.credits.size());
        assertEquals("playtime-points:checkin:c001", wallet.credits.getFirst().businessNo(),
                "业务单号为 playtime-points:checkin:<checkInId>");
        assertEquals(USER, wallet.credits.getFirst().userId(), "用户解析直接用 provider 给的平台用户 ID");
        assertEquals("POINT", wallet.credits.getFirst().assetCode());
        assertEquals(0, wallet.credits.getFirst().amount().compareTo(BigDecimal.ONE));
        assertEquals(2, repo.checkInRewards(null, null, 1, 10).total(), "每笔发放都落一条流水");
        assertEquals(CheckInReward.SOURCE_PULL, repo.findCheckInReward("c001").orElseThrow().source());
        assertEquals(1_500L, repo.findCheckInRewardCursor().orElseThrow().lastAcceptedAt(), "游标推进到本批最大 acceptedAt");

        CheckInScanResult second = service().scan();

        assertEquals(0, second.credited(), "含下界会重复返回边界记录，但幂等不再发放");
        assertEquals(1, second.scanned(), "第二轮只重复拉到边界那一条");
        assertEquals(2, wallet.credits.size());
        assertEquals(wallet.credits.size(), wallet.distinctBusinessNos(), "同一业务单号不会重复入账");
        assertEquals(2, repo.findCheckInRewardCursor().orElseThrow().deliveredCount());
    }

    /** 金额：项目覆盖优先，未配置的项目回退全局。 */
    @Test
    void projectOverrideWinsOverGlobalAndFallsBackWhenAbsent() {
        settings(true, "2", false, Map.of(PROJECT, "5"));
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L));
        projectProgress.rewards.add(reward("c002", OTHER_PROJECT, USER, 1_200L));

        assertEquals(2, service().scan().credited());

        assertEquals(List.of("5", "2"), wallet.credits.stream()
                .map(credit -> credit.amount().stripTrailingZeros().toPlainString()).toList());
        assertEquals(0, new BigDecimal(repo.findCheckInReward("c001").orElseThrow().points())
                .compareTo(new BigDecimal("5")), "流水按配置金额落库");
        assertEquals("5", repo.checkInRewardPoints("c001"));
        assertEquals("5", repo.findCheckInReward("c001").orElseThrow().credit());
    }

    /** 金额为 0：既不调用钱包也不落流水（零副作用），但游标照常越过以免阻塞后续打卡。 */
    @Test
    void zeroAmountProducesNoWalletCallAndNoLedgerRecord() {
        settings(true, "0", false, Map.of());
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L));

        CheckInScanResult result = service().scan();

        assertEquals(0, result.credited());
        assertEquals(1, result.scanned());
        assertTrue(wallet.credits.isEmpty(), "金额为 0 时不做任何钱包调用");
        assertEquals(0, repo.checkInRewards(null, null, 1, 10).total(), "也不落发放流水");
        assertEquals(1_000L, repo.findCheckInRewardCursor().orElseThrow().lastAcceptedAt(), "游标照常推进");
    }

    /* ---------- 按时薪折算 ---------- */

    /** 按时薪折算：非整数小时按毫秒精确折算，结果按**货币资产精度**四舍五入（HALF_UP）。 */
    @Test
    void hourlyModeConvertsEffectiveMillisAtAssetScale() {
        assetScale(2);
        hourlySettings(true, "10", false, Map.of());
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L, 44 * 60_000L));
        projectProgress.rewards.add(reward("c002", PROJECT, USER, 1_500L, 30 * 60_000L));

        CheckInScanResult result = service().scan();

        assertEquals(2, result.credited());
        assertEquals(0, result.retried());
        assertEquals(List.of("7.33", "5"), wallet.credits.stream()
                .map(credit -> credit.amount().stripTrailingZeros().toPlainString()).toList(),
                "44 分钟 × 时薪 10 = 7.333… → 2 位精度取整 7.33；30 分钟 × 10 = 5");
        CheckInReward first = repo.findCheckInReward("c001").orElseThrow();
        assertEquals(CheckInReward.MODE_HOURLY, first.mode());
        assertEquals(44 * 60_000L, first.effectiveMillis(), "流水记下本次依据的有效在线毫秒数");
        assertEquals("10", first.rate(), "rate 是本次生效的时薪");
        assertEquals("7.33", first.credit());
        assertEquals("", first.note(), "正常发放没有跳过原因");
        assertEquals("playtime-points:checkin:c001", first.businessNo());
    }

    /** 取整是 HALF_UP：0 位精度货币下 30 分钟 × 时薪 1 = 0.5 分，进位为 1 分。 */
    @Test
    void hourlyModeRoundsHalfUpAtZeroScaleAsset() {
        assetScale(0);
        hourlySettings(true, "1", false, Map.of());
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L, 30 * 60_000L));

        assertEquals(1, service().scan().credited());
        assertEquals("1", repo.findCheckInReward("c001").orElseThrow().credit());
    }

    /** 有效在线时长为 0（非 MC 打卡 / 证据缺失 / 旧数据）：不发分、无钱包操作，留原因后越过游标。 */
    @Test
    void hourlyModeSkipsZeroDurationWithoutWalletCallAndAdvancesCursor() {
        hourlySettings(true, "10", false, Map.of());
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L, 0L));

        CheckInScanResult result = service().scan();

        assertEquals(0, result.credited());
        assertEquals(1, result.scanned());
        assertEquals(0, result.retried());
        assertTrue(wallet.credits.isEmpty(), "有效时长为 0 时不做任何钱包调用");
        CheckInReward ledger = repo.findCheckInReward("c001").orElseThrow();
        assertEquals("0", ledger.credit());
        assertEquals("0", ledger.points());
        assertEquals(CheckInReward.MODE_HOURLY, ledger.mode());
        assertEquals(0L, ledger.effectiveMillis());
        assertEquals("10", ledger.rate());
        assertTrue(ledger.note().contains("没有有效在线时长"), ledger.note());
        assertEquals(1_000L, repo.findCheckInRewardCursor().orElseThrow().lastAcceptedAt(), "跳过视为处理完成，游标越过");

        // 下一轮含下界会重复拉到同一条：流水幂等，不重复记录也不重复尝试
        assertEquals(0, service().scan().credited());
        assertEquals(1, repo.checkInRewards(null, null, 1, 10).total());
    }

    /** 折算结果不足一个最小入账单位：同样不发分、无钱包操作，留原因后越过游标。 */
    @Test
    void hourlyModeSkipsWhenConvertedAmountRoundsToZero() {
        assetScale(0);
        hourlySettings(true, "0.5", false, Map.of());
        // 30 分钟 × 0.5 = 0.25 分，0 位精度下取整为 0
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L, 30 * 60_000L));

        CheckInScanResult result = service().scan();

        assertEquals(0, result.credited());
        assertTrue(wallet.credits.isEmpty(), "折算为 0 不产生 0 金额钱包操作");
        CheckInReward ledger = repo.findCheckInReward("c001").orElseThrow();
        assertEquals("0", ledger.credit());
        assertEquals("0.5", ledger.rate());
        assertTrue(ledger.note().contains("不足 1 个最小入账单位"), ledger.note());
        assertEquals(1_000L, repo.findCheckInRewardCursor().orElseThrow().lastAcceptedAt());
    }

    /** 按时薪模式下项目覆盖是「每小时积分」，未配置的项目回退全局时薪。 */
    @Test
    void hourlyProjectOverrideActsAsHourlyRate() {
        assetScale(2);
        hourlySettings(true, "10", false, Map.of(PROJECT, "20"));
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L, 60 * 60_000L));
        projectProgress.rewards.add(reward("c002", OTHER_PROJECT, USER, 1_500L, 90 * 60_000L));

        assertEquals(2, service().scan().credited());

        assertEquals(List.of("20", "15"), wallet.credits.stream()
                .map(credit -> credit.amount().stripTrailingZeros().toPlainString()).toList(),
                "项目覆盖每小时 20 × 1 小时 = 20；未覆盖项目回退全局时薪 10 × 1.5 小时 = 15");
        assertEquals("20", repo.findCheckInReward("c001").orElseThrow().rate());
        assertEquals("10", repo.findCheckInReward("c002").orElseThrow().rate());
    }

    /* ---------- 非时长打卡（图片/文件/定位）按时薪模式下的固定积分发放 ---------- */

    /**
     * HOURLY + 非 MC 打卡（有效时长 0）+ 非时长打卡积分 &gt; 0：按**全局**固定积分发放一次。
     *
     * <p>项目覆盖只作用于主口径（时薪），不覆盖这个回退金额：即使该项目配了每小时 20，这条打卡也只发 3。</p>
     */
    @Test
    void hourlyNonDurationCheckInCreditsGlobalFixedPoints() {
        assetScale(2);
        hourlySettings(true, "100", "3", false, Map.of(PROJECT, "20"));
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L, 0L));

        CheckInScanResult result = service().scan();

        assertEquals(1, result.credited());
        assertEquals(0, result.retried());
        assertEquals(1, wallet.credits.size());
        FakeWallet.Credit credit = wallet.credits.getFirst();
        assertEquals("playtime-points:checkin:c001", credit.businessNo(), "单号口径与其它打卡一致");
        assertEquals(0, credit.amount().compareTo(new BigDecimal("3")),
                "非时长打卡按全局固定积分发放；项目覆盖（每小时 20）不参与");
        assertTrue(credit.remark().contains("非时长打卡（图片/文件/定位）按固定积分发放 3 分"), credit.remark());

        CheckInReward ledger = repo.findCheckInReward("c001").orElseThrow();
        assertEquals(CheckInReward.MODE_HOURLY, ledger.mode(), "仍在按时薪模式下处理，用 effectiveMillis=0 区分");
        assertEquals(0L, ledger.effectiveMillis());
        assertEquals("3", ledger.rate(), "rate 是本次实际生效的费率（那笔全局固定积分）");
        assertEquals("3", ledger.points());
        assertEquals("3", ledger.credit());
        assertTrue(ledger.note().contains("非时长打卡（图片/文件/定位）按固定积分发放"), ledger.note());
        assertEquals(1_000L, repo.findCheckInRewardCursor().orElseThrow().lastAcceptedAt(), "发放完成，游标越过");
    }

    /** HOURLY + 非 MC 打卡 + 非时长打卡积分 = 0：跳过（不调钱包、落 0 分流水、游标越过）。 */
    @Test
    void hourlyNonDurationCheckInSkipsWhenFixedPointsZero() {
        hourlySettings(true, "10", "0", false, Map.of());
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L, 0L));

        CheckInScanResult result = service().scan();

        assertEquals(0, result.credited());
        assertEquals(1, result.scanned());
        assertEquals(0, result.retried());
        assertTrue(wallet.credits.isEmpty(), "非时长打卡积分为 0 时不做任何钱包调用");
        CheckInReward ledger = repo.findCheckInReward("c001").orElseThrow();
        assertEquals("0", ledger.credit());
        assertEquals("0", ledger.points());
        assertEquals(CheckInReward.MODE_HOURLY, ledger.mode());
        assertEquals(0L, ledger.effectiveMillis());
        assertEquals("10", ledger.rate(), "跳过记录的 rate 仍是主口径费率（时薪）");
        assertTrue(ledger.note().contains("没有有效在线时长"), ledger.note());
        assertTrue(ledger.note().contains("非时长打卡"), ledger.note());
        assertTrue(ledger.note().contains("本次不发放积分"), ledger.note());
        assertEquals(1_000L, repo.findCheckInRewardCursor().orElseThrow().lastAcceptedAt(), "跳过视为处理完成，游标越过");

        // 下一轮含下界会重复拉到同一条：流水幂等，不重复记录也不重复尝试
        assertEquals(0, service().scan().credited());
        assertEquals(1, repo.checkInRewards(null, null, 1, 10).total());
    }

    /**
     * 主口径费率非法（例如时薪被手工改成 0，管理端校验不允许保存）时仍按 1.3.0 的「零副作用」处理：
     * 既不调用钱包，也不落任何流水（连 0 分流水都没有），游标照常越过。这条判断先于非时长打卡回退。
     */
    @Test
    void nonPositiveHourlyRateKeepsLegacyZeroSideEffectBehaviour() {
        hourlySettings(true, "0", "3", false, Map.of());
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L, 0L));

        CheckInScanResult result = service().scan();

        assertEquals(0, result.credited());
        assertEquals(1, result.scanned());
        assertEquals(0, result.retried());
        assertTrue(wallet.credits.isEmpty(), "费率非法时不做任何钱包调用");
        assertTrue(repo.findCheckInReward("c001").isEmpty(), "费率非法时连 0 分流水都不落，与 1.3.0 一致");
        assertEquals(1_000L, repo.findCheckInRewardCursor().orElseThrow().lastAcceptedAt(), "游标照常越过");
    }

    /** HOURLY + MC 打卡（有效时长 &gt; 0）：仍按时薪折算，既不被固定积分替换也不叠加。 */
    @Test
    void hourlyDurationCheckInIgnoresFixedPoints() {
        assetScale(2);
        hourlySettings(true, "10", "3", false, Map.of());
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L, 44 * 60_000L));

        assertEquals(1, service().scan().credited());

        assertEquals("7.33", wallet.credits.getFirst().amount().stripTrailingZeros().toPlainString(),
                "44 分钟 × 时薪 10 = 7.33，与固定积分 3 无关");
        CheckInReward ledger = repo.findCheckInReward("c001").orElseThrow();
        assertEquals("10", ledger.rate(), "rate 仍是时薪");
        assertEquals("7.33", ledger.credit());
        assertEquals("", ledger.note(), "按时薪折算发放的正常流水没有说明");
    }

    /** FIXED：非时长打卡积分配了也不生效，所有打卡（含没有时长的）都按每次金额发放，项目覆盖照旧。 */
    @Test
    void fixedModeStillPaysPerCheckInAmountAndIgnoresFixedPoints() {
        repo.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, "2", false, Map.of(PROJECT, "5"),
                PlaytimePointsSettings.CHECK_IN_MODE_FIXED, "1", "3"));
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L, 0L));
        projectProgress.rewards.add(reward("c002", OTHER_PROJECT, USER, 1_500L, 44 * 60_000L));

        assertEquals(2, service().scan().credited());

        assertEquals(List.of("5", "2"), wallet.credits.stream()
                .map(item -> item.amount().stripTrailingZeros().toPlainString()).toList(),
                "固定模式：项目覆盖的每次金额 5，未覆盖项目回退全局 2；与时长、非时长打卡积分都无关");
        CheckInReward noDuration = repo.findCheckInReward("c001").orElseThrow();
        assertEquals(CheckInReward.MODE_FIXED, noDuration.mode());
        assertEquals("5", noDuration.rate());
        assertEquals("", noDuration.note(), "固定模式没有回退语义，note 仍为空");
    }

    /** 非时长打卡回退发放的幂等：实时先到、拉取后到只发一次；再拉一轮（含下界）也只发一次。 */
    @Test
    void nonDurationFallbackKeepsIdempotencyAcrossPullAndRealtime() {
        hourlySettings(true, "100", "3", true, Map.of());
        CheckInRewardService service = service();
        assertTrue(service.registerRealtimeListener());
        ProjectProgressPort.RewardRef ref = reward("c001", PROJECT, USER, 1_000L, 0L);
        projectProgress.rewards.add(ref);

        projectProgress.fire(ref);

        assertEquals(1, wallet.credits.size());
        assertEquals("playtime-points:checkin:c001", wallet.credits.getFirst().businessNo());
        assertEquals(0, wallet.credits.getFirst().amount().compareTo(new BigDecimal("3")));
        assertEquals(CheckInReward.SOURCE_REALTIME, repo.findCheckInReward("c001").orElseThrow().source());

        CheckInScanResult pulled = service.scan();

        assertEquals(0, pulled.credited(), "拉取再扫到同一条不再发放");
        assertEquals(1, pulled.scanned());
        assertEquals(1, wallet.credits.size());
        assertEquals(1, wallet.distinctBusinessNos());
        assertEquals(1, repo.checkInRewards(null, null, 1, 10).total(), "只落一条流水");

        assertEquals(0, service.scan().credited(), "重复拉取依然只发一次");
        assertEquals(1, wallet.credits.size());
    }

    /** 固定模式不受有效时长影响：证据带出时长也按每次金额发放，行为与升级前一致。 */
    @Test
    void fixedModeIgnoresEffectiveMillis() {
        settings(true, "2", false, Map.of());
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L, 44 * 60_000L));

        assertEquals(1, service().scan().credited());

        assertEquals(0, wallet.credits.getFirst().amount().compareTo(new BigDecimal("2")));
        CheckInReward ledger = repo.findCheckInReward("c001").orElseThrow();
        assertEquals(CheckInReward.MODE_FIXED, ledger.mode());
        assertEquals(44 * 60_000L, ledger.effectiveMillis(), "时长如实记录，但不参与固定模式计算");
        assertEquals("2", ledger.rate());
        assertEquals("", ledger.note());
    }

    /** 按时薪模式的幂等：实时先到、拉取后到只发一次，业务单号不变。 */
    @Test
    void hourlyModeKeepsIdempotencyAcrossPullAndRealtime() {
        assetScale(2);
        hourlySettings(true, "10", true, Map.of());
        CheckInRewardService service = service();
        assertTrue(service.registerRealtimeListener());
        ProjectProgressPort.RewardRef ref = reward("c001", PROJECT, USER, 1_000L, 44 * 60_000L);
        projectProgress.rewards.add(ref);

        projectProgress.fire(ref);

        assertEquals(1, wallet.credits.size());
        assertEquals("playtime-points:checkin:c001", wallet.credits.getFirst().businessNo());
        assertEquals("7.33", wallet.credits.getFirst().amount().stripTrailingZeros().toPlainString());

        CheckInScanResult pulled = service.scan();

        assertEquals(0, pulled.credited(), "拉取再扫到同一条不再发放");
        assertEquals(1, pulled.scanned());
        assertEquals(1, wallet.credits.size());
        assertEquals(1, wallet.distinctBusinessNos());
        assertEquals(1, repo.checkInRewards(null, null, 1, 10).total(), "只落一条流水");
        assertEquals(CheckInReward.SOURCE_REALTIME, repo.findCheckInReward("c001").orElseThrow().source());
    }

    /** 按时薪模式的钱包入账失败同样不推进游标，恢复后按同一单号补发一次。 */
    @Test
    void hourlyModeWalletFailureDefersWithoutAdvancingCursor() {
        hourlySettings(true, "10", false, Map.of());
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L, 60 * 60_000L));
        wallet.failOnCredit = true;

        assertEquals(1, service().scan().retried());
        assertTrue(wallet.credits.isEmpty());
        assertTrue(repo.findCheckInReward("c001").isEmpty(), "入账失败不落流水");
        assertEquals(0L, repo.findCheckInRewardCursor().orElseThrow().lastAcceptedAt());

        wallet.failOnCredit = false;
        assertEquals(1, service().scan().credited());

        assertEquals("playtime-points:checkin:c001", wallet.credits.getFirst().businessNo());
        assertEquals("10", repo.findCheckInReward("c001").orElseThrow().credit());
    }

    /** 按时薪跳过的 0 分流水出现在用户端最近记录里，但不计入发放笔数。 */
    @Test
    void hourlySkippedRowsShowInRecentButNotInCount() {
        hourlySettings(true, "10", false, Map.of());
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L, 60 * 60_000L));
        projectProgress.rewards.add(reward("c002", PROJECT, USER, 1_500L, 0L));
        assertEquals(1, service().scan().credited());

        Map<String, Object> summary = service().mySummary(USER, 10);

        assertEquals("10", summary.get("totalPoints"));
        assertEquals(1, summary.get("count"), "只有真正发出去的才算发放笔数");
        assertEquals(2, ((List<?>) summary.get("recent")).size(), "跳过的流水也展示，便于看清为什么没发");
    }

    /** 总开关关闭：连 provider 都不读（保持链路零副作用）。 */
    @Test
    void disabledFeatureReadsNothing() {
        settings(false, "1", false, Map.of());
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L));

        CheckInScanResult result = service().scan();

        assertEquals("打卡积分已停用", result.message());
        assertEquals(0, projectProgress.acceptedCheckInsCalls, "关闭时不读取 provider");
        assertTrue(wallet.credits.isEmpty());
        assertTrue(repo.findCheckInRewardCursor().isEmpty(), "不写游标");
    }

    /* ---------- 失败重试 ---------- */

    /** 未绑定用户（userId 为空）时该条不推进：下一轮从它开始重试，且它后面的记录不会被跳过。 */
    @Test
    void unboundUserDefersThatRecordWithoutSkippingIt() {
        settings(true, "1", false, Map.of());
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L));
        projectProgress.rewards.add(reward("c002", PROJECT, "", 1_500L));
        projectProgress.rewards.add(reward("c003", PROJECT, USER, 1_700L));

        CheckInScanResult result = service().scan();

        assertEquals(1, result.credited(), "失败记录之前的照常发放");
        assertEquals(1, result.retried());
        assertTrue(result.message().contains("c002"));
        assertEquals(1_000L, repo.findCheckInRewardCursor().orElseThrow().lastAcceptedAt(),
                "游标停在最后一条成功处理的位置");
        assertEquals(1, wallet.credits.size());
        assertTrue(repo.findCheckInReward("c003").isEmpty(), "失败记录之后的本轮不发，留待重试");

        // 用户绑定后重试：c002 与它后面的 c003 一起补发，失败记录没有被跳过
        projectProgress.rewards.set(1, reward("c002", PROJECT, USER, 1_500L));
        CheckInScanResult retry = service().scan();

        assertEquals(2, retry.credited());
        assertEquals(0, retry.retried());
        assertEquals(List.of("playtime-points:checkin:c001", "playtime-points:checkin:c002",
                "playtime-points:checkin:c003"), wallet.credits.stream().map(FakeWallet.Credit::businessNo).toList());
        assertEquals(1_700L, repo.findCheckInRewardCursor().orElseThrow().lastAcceptedAt());
    }

    /** 钱包入账失败时同样不推进该条；恢复后同一单号补发一次。 */
    @Test
    void walletFailureRetriesSameRecordWithSameBusinessNo() {
        settings(true, "1", false, Map.of());
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L));
        wallet.failOnCredit = true;

        assertEquals(1, service().scan().retried());
        assertTrue(wallet.credits.isEmpty());
        assertEquals(0L, repo.findCheckInRewardCursor().orElseThrow().lastAcceptedAt());

        wallet.failOnCredit = false;
        assertEquals(1, service().scan().credited());

        assertEquals(1, wallet.credits.size());
        assertEquals("playtime-points:checkin:c001", wallet.credits.getFirst().businessNo());
    }

    /** 钱包不可用与货币未启用都按「暂缓发放」处理，不推进游标。 */
    @Test
    void walletUnavailableOrAssetDisabledDefersWithoutAdvancing() {
        settings(true, "1", false, Map.of());
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L));
        wallet.walletAvailable = false;

        CheckInScanResult unavailable = service().scan();
        assertEquals(1, unavailable.retried());
        assertTrue(unavailable.message().contains("钱包"));
        assertEquals(0L, repo.findCheckInRewardCursor().orElseThrow().lastAcceptedAt());

        wallet.walletAvailable = true;
        wallet.assets.clear();
        CheckInScanResult noAsset = service().scan();
        assertEquals(1, noAsset.retried());
        assertTrue(noAsset.message().contains("POINT"));
        assertEquals(0L, repo.findCheckInRewardCursor().orElseThrow().lastAcceptedAt());

        wallet.assets.add(new WalletPort.AssetRef("POINT", "积分", "", 0, false, true));
        assertEquals(1, service().scan().credited());
    }

    /* ---------- 幂等：拉取 + 实时只发一次 ---------- */

    /** 实时先到、拉取后到：只发一次（流水判重）；反向同样只发一次。 */
    @Test
    void pullAndRealtimeDeliverOnlyOnce() {
        settings(true, "1", true, Map.of());
        CheckInRewardService service = service();
        assertTrue(service.registerRealtimeListener());
        assertEquals(1, projectProgress.registrations);

        // 实时先到
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L));
        projectProgress.fire(reward("c001", PROJECT, USER, 1_000L));
        assertEquals(1, wallet.credits.size());
        assertEquals(CheckInReward.SOURCE_REALTIME, repo.findCheckInReward("c001").orElseThrow().source());

        // 拉取再扫到同一条：跳过，不重复发放
        CheckInScanResult pulled = service.scan();
        assertEquals(0, pulled.credited());
        assertEquals(1, pulled.scanned());
        assertEquals(1, wallet.credits.size());
        assertEquals(1, repo.checkInRewards(null, null, 1, 10).total());

        // 反向：拉取先发，实时回调再到达
        projectProgress.rewards.add(reward("c002", PROJECT, USER, 1_200L));
        assertEquals(1, service.scan().credited());
        assertEquals(2, wallet.credits.size());
        projectProgress.fire(reward("c002", PROJECT, USER, 1_200L));
        assertEquals(2, wallet.credits.size(), "重复回调不重复发放");
        assertEquals(wallet.credits.size(), wallet.distinctBusinessNos(), "业务单号一一对应，没有重复入账");
        assertEquals(2, repo.checkInRewards(null, null, 1, 10).total());
    }

    /** 业务单号是第二重保险：即使流水因为异常没落库，钱包侧也不会重复入账。 */
    @Test
    void walletBusinessNoKeepsIdempotencyWhenLedgerSaveFails() {
        FakePlaytimePointsRepository flaky = new FakePlaytimePointsRepository() {
            private boolean failedOnce;

            @Override
            public void saveCheckInReward(CheckInReward reward) {
                if (!failedOnce) {
                    failedOnce = true;
                    throw new IllegalStateException("流水落库失败");
                }
                super.saveCheckInReward(reward);
            }
        };
        flaky.saveSettings(new PlaytimePointsSettings(true, "POINT", 60, true, Map.of(), Map.of(),
                true, "1", false, Map.of()));
        flaky.saveCheckInRewardCursor(CheckInRewardCursor.empty());
        CheckInRewardService service = new CheckInRewardService(flaky, projectProgress, wallet, now::incrementAndGet);
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L));

        // 第一次：钱包入账成功、流水落库失败 → 整条按失败处理，游标停在原地
        assertEquals(1, service.scan().retried());
        assertEquals(1, wallet.credits.size());
        assertEquals(0L, flaky.findCheckInRewardCursor().orElseThrow().lastAcceptedAt());

        // 第二次：钱包按同一业务单号幂等（不会新增入账），本插件补落流水后游标推进
        assertEquals(1, service.scan().credited());
        assertEquals(1, wallet.credits.size(), "同一业务单号只入账一次");
        assertEquals(1, wallet.distinctBusinessNos());
        assertEquals(1_000L, flaky.findCheckInRewardCursor().orElseThrow().lastAcceptedAt());
        assertTrue(flaky.findCheckInReward("c001").isPresent());
    }

    /* ---------- 实时监听注册 ---------- */

    /** 总开关关闭 / 实时开关关闭 / provider 不可用：都不注册监听。 */
    @Test
    void realtimeListenerIsNotRegisteredWhenDisabledOrProviderMissing() {
        settings(false, "1", true, Map.of());
        assertFalse(service().registerRealtimeListener(), "总开关关闭时不注册");
        assertEquals(0, projectProgress.registrations);

        settings(true, "1", false, Map.of());
        assertFalse(service().registerRealtimeListener(), "实时开关关闭时不注册");
        assertEquals(0, projectProgress.registrations);

        settings(true, "1", true, Map.of());
        projectProgress.available = false;
        assertFalse(service().registerRealtimeListener(), "provider 未安装时不注册");
        assertEquals(0, projectProgress.registrations);

        projectProgress.available = true;
        CheckInRewardService service = service();
        assertTrue(service.registerRealtimeListener());
        assertTrue(service.registerRealtimeListener(), "重复调用幂等");
        assertEquals(1, projectProgress.registrations);
        assertTrue(service.realtimeRegistered());
    }

    /** 注册抛错（旧版 provider 没有该扩展点）时降级为「未注册」，不抛给调用方。 */
    @Test
    void realtimeRegistrationFailureDegradesQuietly() {
        settings(true, "1", true, Map.of());
        projectProgress.registerThrows = true;

        CheckInRewardService service = service();

        assertFalse(service.registerRealtimeListener());
        assertFalse(service.realtimeRegistered());
        assertTrue(wallet.credits.isEmpty());
    }

    /** 注册后验收事件能触发发放；插件停用（close）后迟到的回调变成空操作。 */
    @Test
    void registeredListenerCreditsAcceptedCheckInAndStopsAfterClose() {
        settings(true, "1", true, Map.of());
        CheckInRewardService service = service();
        assertTrue(service.registerRealtimeListener());

        projectProgress.fire(reward("c001", PROJECT, USER, 1_000L));
        assertEquals(1, wallet.credits.size());
        assertEquals(CheckInReward.SOURCE_REALTIME, repo.findCheckInReward("c001").orElseThrow().source());

        service.close();
        projectProgress.fire(reward("c002", PROJECT, USER, 1_200L));
        assertEquals(1, wallet.credits.size(), "停用后不再发放");
    }

    /* ---------- 降级 ---------- */

    /** project-progress 未安装或旧版（方法返回空）时全链路不报错，查询也安全降级。 */
    @Test
    void providerMissingOrLegacyDegradesWithoutError() {
        settings(true, "1", false, Map.of());
        projectProgress.available = false;

        CheckInScanResult missing = service().scan();
        assertEquals(0, missing.credited());
        assertTrue(missing.message().contains("project-progress"));
        assertTrue(wallet.credits.isEmpty());
        assertTrue(service().projectOptions().isEmpty());
        Map<String, Object> summary = service().mySummary(USER, 10);
        assertEquals("0", summary.get("totalPoints"));
        assertEquals(0, summary.get("count"));
        assertEquals(Boolean.FALSE, summary.get("providerAvailable"));
        assertEquals(0, service().adminPage(null, null, 1, 10).total());

        // 旧版 provider：方法存在但返回空列表（default 实现）
        projectProgress.available = true;
        projectProgress.legacyEmpty = true;
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L));
        CheckInScanResult legacy = service().scan();
        assertEquals(0, legacy.credited());
        assertTrue(wallet.credits.isEmpty());
        assertEquals(0L, repo.findCheckInRewardCursor().orElseThrow().lastAcceptedAt());
    }

    /** 用户端汇总：只统计自己的发放，并给出最近记录。 */
    @Test
    void mySummaryOnlyCountsOwnRewards() {
        settings(true, "2", false, Map.of());
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L));
        projectProgress.rewards.add(reward("c002", PROJECT, OTHER_USER, 1_100L));
        projectProgress.rewards.add(reward("c003", PROJECT, USER, 1_200L));
        assertEquals(3, service().scan().credited());

        Map<String, Object> summary = service().mySummary(USER, 10);

        assertEquals("4", summary.get("totalPoints"));
        assertEquals(2, summary.get("count"));
        assertEquals(Boolean.TRUE, summary.get("enabled"));
        assertEquals(2, ((List<?>) summary.get("recent")).size());
        assertEquals(0, service().mySummary("99999", 10).get("count"));
    }

    /** 管理端流水可按项目 / 用户筛选，并按验收时间倒序。 */
    @Test
    void adminPageFiltersByProjectAndUser() {
        settings(true, "1", false, Map.of());
        projectProgress.rewards.add(reward("c001", PROJECT, USER, 1_000L));
        projectProgress.rewards.add(reward("c002", OTHER_PROJECT, USER, 1_100L));
        projectProgress.rewards.add(reward("c003", PROJECT, OTHER_USER, 1_200L));
        assertEquals(3, service().scan().credited());

        assertEquals(3, service().adminPage(null, null, 1, 10).total());
        assertEquals(2, service().adminPage(PROJECT, null, 1, 10).total());
        assertEquals(2, service().adminPage(null, USER, 1, 10).total());
        assertEquals("c003", service().adminPage(null, null, 1, 10).records().getFirst().checkInId(),
                "按验收通过时间倒序");
        assertEquals(1, service().adminPage(PROJECT, OTHER_USER, 1, 10).total(), "项目与用户筛选同时生效");
        assertEquals("c003", service().adminPage(PROJECT, OTHER_USER, 1, 10).records().getFirst().checkInId());
    }

    /* ---------- 测试替身 ---------- */

    /** provider 替身：可模拟未安装（available=false）、旧版（acceptedCheckIns 恒空）与注册失败。 */
    private static final class FakeProjectProgress implements ProjectProgressPort {

        final List<RewardRef> rewards = new ArrayList<>();
        final List<ProjectRef> projects = new ArrayList<>();
        final List<Consumer<RewardRef>> listeners = new ArrayList<>();
        boolean available = true;
        boolean legacyEmpty;
        boolean registerThrows;
        int registrations;
        int acceptedCheckInsCalls;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public List<RewardRef> acceptedCheckIns(long sinceAcceptedAt, int page, int size) {
            acceptedCheckInsCalls++;
            if (legacyEmpty) {
                return List.of();
            }
            List<RewardRef> matched = rewards.stream()
                    .filter(item -> item.acceptedAt() >= sinceAcceptedAt)
                    .sorted(Comparator.comparingLong(RewardRef::acceptedAt).thenComparing(RewardRef::checkInId))
                    .toList();
            int safeSize = Math.min(Math.max(size, 1), 200);
            int from = Math.min((Math.max(page, 1) - 1) * safeSize, matched.size());
            int to = Math.min(from + safeSize, matched.size());
            return matched.subList(from, to);
        }

        @Override
        public List<ProjectRef> projects() {
            return projects;
        }

        @Override
        public void registerAcceptedListener(Consumer<RewardRef> listener) {
            if (registerThrows) {
                throw new IllegalStateException("旧版 provider 没有该扩展点");
            }
            registrations++;
            listeners.add(listener);
        }

        void fire(RewardRef reward) {
            listeners.forEach(listener -> listener.accept(reward));
        }
    }

    /** 钱包替身：按业务单号幂等（与真实钱包一致），金额精度校验也照做。 */
    private static final class FakeWallet implements WalletPort {

        final List<AssetRef> assets = new ArrayList<>(List.of(new AssetRef("POINT", "积分", "", 0, false, true)));
        final List<Credit> credits = new ArrayList<>();
        boolean walletAvailable = true;
        boolean failOnCredit;

        /** 多带一个 remark：非时长打卡回退发放要在钱包备注里写明语义，因此断言需要看到它。 */
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
            AssetRef asset = findAsset(assetCode).orElseThrow(() -> new IllegalArgumentException("货币不存在"));
            if (!asset.enabled()) {
                throw new IllegalArgumentException("货币未启用");
            }
            if (amount.stripTrailingZeros().scale() > asset.scale()) {
                throw new IllegalArgumentException("金额精度不能超过 " + asset.scale() + " 位小数");
            }
            // 钱包按单号幂等：同一 businessNo 不会重复入账
            if (credits.stream().anyMatch(credit -> credit.businessNo().equals(businessNo))) {
                return;
            }
            credits.add(new Credit(userId, assetCode, amount, businessNo, remark));
        }

        int distinctBusinessNos() {
            return (int) credits.stream().map(Credit::businessNo).distinct().count();
        }
    }
}
