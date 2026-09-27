package online.yudream.base.plugin.playtimepoints.application.service;

import online.yudream.base.plugin.playtimepoints.application.assembler.CheckInRewardAssembler;
import online.yudream.base.plugin.playtimepoints.application.dto.CheckInRewardPage;
import online.yudream.base.plugin.playtimepoints.application.dto.CheckInRewardView;
import online.yudream.base.plugin.playtimepoints.application.dto.CheckInScanResult;
import online.yudream.base.plugin.playtimepoints.application.port.ProjectProgressPort;
import online.yudream.base.plugin.playtimepoints.application.port.WalletPort;
import online.yudream.base.plugin.playtimepoints.domain.aggregate.CheckInReward;
import online.yudream.base.plugin.playtimepoints.domain.repo.PlaytimePointsRepository;
import online.yudream.base.plugin.playtimepoints.domain.service.PointsCalculator;
import online.yudream.base.plugin.playtimepoints.domain.valobj.CheckInRewardCursor;
import online.yudream.base.plugin.playtimepoints.domain.valobj.PlaytimePointsSettings;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 打卡积分联动（project-progress 软依赖）：项目工作细节**验收通过**后，对该细节下的每条打卡记录各发一次积分。
 *
 * <h2>两条发放路径，共用同一条处理函数</h2>
 * <ul>
 *   <li><b>拉取（默认）</b>：{@link #scan()} 按 {@code acceptedAt} 含下界升序增量拉取，游标持久化在本插件自己的
 *       命名空间（settings 集合的 {@code checkInRewardCursor} 文档）；</li>
 *   <li><b>实时（可选开关）</b>：{@link #registerRealtimeListener()} 注册 project-progress 的
 *       {@code PluginProjectCheckInAcceptedListener}，回调进 {@link #onAccepted}，与拉取走同一条
 *       {@link #deliver}。</li>
 * </ul>
 *
 * <h2>幂等双保险</h2>
 * <ol>
 *   <li>钱包业务单号 {@code playtime-points:checkin:<checkInId>}：钱包按单号幂等，重复入账不会发生；</li>
 *   <li>本插件的发放流水 {@link CheckInReward}（id = checkInId）：发放前先查流水，存在即跳过。</li>
 * </ol>
 * <p>两条路径可能同时到达（同一 JVM 内的调度线程与验收请求线程），因此「查流水 → 入账 → 落流水」整段在
 * 同一把锁内串行执行；即便落流水失败，钱包侧的单号幂等仍然兜住重复发放。</p>
 *
 * <h2>失败重试策略（游标只推进到已成功处理的位置）</h2>
 * <p>本轮按顺序处理，遇到第一条失败的记录（未绑定用户、钱包不可用、货币未启用、入账异常）就停止，
 * 并把游标落在**最后一条已成功处理**的记录上，然后下一轮从该位置重试——失败的记录不会被跳过。
 * 已经发放过的边界记录会因为流水存在而被幂等跳过，不会重复发钱。</p>
 *
 * <h2>两种计算方式（设置里的 {@code checkInRewardMode}）与三种情形</h2>
 * <ul>
 *   <li><b>{@code FIXED}（默认）</b>：每次打卡发固定积分，与升级前逐位一致；费率为 0 时既不调用钱包
 *       也不落流水，直接算处理完成（保持链路零副作用）。打卡有没有时长都一样，行为完全不变。</li>
 *   <li><b>{@code HOURLY} 且打卡有有效在线时长</b>：{@code 积分 = 时薪(每小时积分) × 该打卡的有效在线毫秒
 *       ÷ 3_600_000}，按**货币资产精度** HALF_UP 取整；折算结果为 0 时**不发分**：不产生任何钱包操作，
 *       但会落一条带中文原因的 0 分流水后越过游标。</li>
 *   <li><b>{@code HOURLY} 且打卡没有有效在线时长</b>（图片/文件/定位等非 MC 打卡、证据缺失或旧数据，
 *       {@code effectiveMillis <= 0}）：按时薪折算必然是 0，此时改按**独立的全局**「非时长打卡每次积分」
 *       {@code checkInFixedPoints} 发放一笔（{@code > 0} 时）；该值为 0 表示不发，仍按现状落一条
 *       带中文原因的 0 分流水后越过游标，既不阻塞后续打卡，也便于在流水里排查「为什么没发」。</li>
 * </ul>
 * <p>两种方式都按打卡记录 id 幂等（流水 + 钱包业务单号双保险），拉取与实时回调同时到达也只发一次。</p>
 *
 * <p><b>项目覆盖的作用范围</b>：{@code checkInRewardProjectPoints} 只覆盖「主口径」——FIXED 下的每次金额、
 * HOURLY 下的每小时时薪；非时长打卡回退用的固定积分始终取全局值，不参与项目覆盖。</p>
 *
 * <p><b>旧设置文档兼容</b>：缺少 {@code checkInFixedPoints} 的文档读作 {@code "0"}，即非时长打卡不发，
 * 行为与 1.3.0 逐位一致。</p>
 *
 * <h2>降级</h2>
 * <p>总开关关闭、project-progress 未安装/未启用/旧版没有该能力时，{@link #scan()} 直接返回且不读任何数据，
 * 不影响既有时长结算；实时监听注册失败只记日志。</p>
 */
public class CheckInRewardService implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(CheckInRewardService.class.getName());

    /** provider 侧单页上限 200。 */
    private static final int PULL_PAGE_SIZE = 200;
    /** 单轮拉取的页数上限：200 × 50 = 10000 条，避免单轮扫太久。 */
    private static final int MAX_PULL_PAGES = 50;
    /** 失败原因最多上报 3 条，避免消息过长。 */
    private static final int MAX_PROBLEMS = 3;
    /** 钱包 businessNo 长度上限（wallet 侧 96）。 */
    private static final int MAX_BUSINESS_NO_LENGTH = 96;
    private static final String BUSINESS_NO_PREFIX = "playtime-points:checkin:";
    /**
     * 非时长打卡（图片/文件/定位等没有时长的打卡）按固定积分发放时写进流水 note 的说明。
     *
     * <p>这是「这类记录不是按时薪折算出来的」的唯一人读标记；前端据此（配合
     * {@code mode=HOURLY && effectiveMillis<=0 && credit>0}）显示「非时长打卡 · 固定 N 积分」。</p>
     */
    private static final String NON_DURATION_FIXED_NOTE = "非时长打卡（图片/文件/定位）按固定积分发放";

    private final PlaytimePointsRepository repository;
    private final ProjectProgressPort projectProgress;
    private final WalletPort wallet;
    private final LongSupplier clock;
    /**
     * 「查流水 → 入账 → 落流水」的串行锁：拉取与实时回调共用，避免同一 JVM 内并发重复发放。
     *
     * <p>实时回调发生在 provider 的验收用例内，因此它只 tryLock 很短的时间：抢不到就放弃本次发放
     * （拉取轮次会补发），绝不把验收请求卡在拉取扫描后面。</p>
     */
    private final ReentrantLock lock = new ReentrantLock();
    /** 实时回调最多等待锁的时间：超过就放弃，交给拉取补发。 */
    private static final long REALTIME_LOCK_WAIT_MILLIS = 2_000L;
    private final AtomicBoolean realtimeRegistered = new AtomicBoolean();
    private volatile boolean disposed;

    public CheckInRewardService(PlaytimePointsRepository repository, ProjectProgressPort projectProgress,
                                WalletPort wallet, LongSupplier clock) {
        this.repository = repository;
        this.projectProgress = projectProgress;
        this.wallet = wallet;
        this.clock = clock;
    }

    /* ---------- 实时监听注册 ---------- */

    /**
     * 实时开关打开且 provider 可用时注册验收通过监听；幂等，重复调用只注册一次。
     *
     * <p>返回是否处于「已注册」状态。总开关关闭、实时开关关闭、provider 不可用时返回 false 且不做任何注册
     * （不注册监听 = 零副作用）。</p>
     */
    public boolean registerRealtimeListener() {
        if (disposed) {
            return false;
        }
        PlaytimePointsSettings settings = settings();
        if (!settings.checkInRewardEnabled() || !settings.checkInRewardRealtime()) {
            return false;
        }
        if (!projectProgress.available()) {
            return false;
        }
        if (!realtimeRegistered.compareAndSet(false, true)) {
            return true;
        }
        try {
            projectProgress.registerAcceptedListener(this::onAccepted);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            realtimeRegistered.set(false);
            LOGGER.log(Level.WARNING, "[playtime-points] 注册打卡验收实时回调失败，将只在拉取轮次发放", failure);
            return false;
        }
    }

    /** 是否已经注册了实时回调（供管理端展示）。 */
    public boolean realtimeRegistered() {
        return realtimeRegistered.get();
    }

    /**
     * 实时回调入口：与拉取共用 {@link #deliver}，靠业务单号 + 发放流水双保险只发一次。
     *
     * <p>回调发生在 provider 的验收用例内，因此这里只做一次同步入账（通常是一次点查 + 一次钱包调用），
     * 不做批量扫描；失败只记日志，下一轮拉取会补发。</p>
     */
    public void onAccepted(ProjectProgressPort.RewardRef reward) {
        if (disposed || reward == null) {
            return;
        }
        PlaytimePointsSettings settings = settings();
        if (!settings.checkInRewardEnabled()) {
            return;
        }
        boolean acquired = false;
        try {
            acquired = lock.tryLock(REALTIME_LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            if (!acquired) {
                // 拉取正在跑：不阻塞验收请求，这一条交给下一轮拉取补发
                LOGGER.log(Level.FINE, "[playtime-points] 打卡验收实时发放跳过（拉取进行中，将由拉取补发）：打卡 "
                        + reward.checkInId());
                return;
            }
            deliver(reward, CheckInReward.SOURCE_REALTIME, settings);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.log(Level.WARNING, "[playtime-points] 打卡验收实时发放失败（拉取轮次会重试）：打卡 "
                    + reward.checkInId(), failure);
        } finally {
            if (acquired) {
                lock.unlock();
            }
        }
    }

    /* ---------- 拉取 ---------- */

    /**
     * 一轮增量拉取：按游标拉取验收通过的打卡并逐条幂等发放。
     *
     * <p>可由定时任务或管理端手动触发；整个方法串行（同一把锁），实时回调不会与其交叉。</p>
     */
    public CheckInScanResult scan() {
        if (disposed) {
            return CheckInScanResult.skipped("插件已停用");
        }
        PlaytimePointsSettings settings = settings();
        if (!settings.checkInRewardEnabled()) {
            // 关闭时零副作用：不读 provider、不读流水、不动游标。
            return CheckInScanResult.skipped("打卡积分已停用");
        }
        if (!projectProgress.available()) {
            return CheckInScanResult.skipped("project-progress 插件不可用，无法拉取验收通过的打卡");
        }
        lock.lock();
        try {
            return pull(settings);
        } finally {
            lock.unlock();
        }
    }

    private CheckInScanResult pull(PlaytimePointsSettings settings) {
        // 游标是「含下界」，下一轮会重复返回边界那一条，靠发放流水幂等跳过。
        CheckInRewardCursor stored = repository.findCheckInRewardCursor().orElseGet(CheckInRewardCursor::empty);
        long since = Math.max(stored.lastAcceptedAt(), 0L);
        long cursor = since;
        long delivered = stored.deliveredCount();
        int credited = 0;
        int scanned = 0;
        List<String> problems = new ArrayList<>();
        for (int page = 1; page <= MAX_PULL_PAGES; page++) {
            List<ProjectProgressPort.RewardRef> batch = projectProgress.acceptedCheckIns(since, page, PULL_PAGE_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            for (ProjectProgressPort.RewardRef reward : batch) {
                if (reward == null || reward.checkInId() == null || reward.checkInId().isBlank()) {
                    continue;
                }
                if (reward.acceptedAt() < since) {
                    // provider 契约保证按 acceptedAt 升序且含下界，这里只做防御性跳过。
                    continue;
                }
                scanned++;
                Decision decision = deliver(reward, CheckInReward.SOURCE_PULL, settings);
                if (decision.outcome() == Outcome.RETRY) {
                    // 游标停在上一条已成功处理的位置：失败记录下一轮会被重新拉到，不会被跳过。
                    saveCursor(cursor, delivered);
                    if (problems.size() < MAX_PROBLEMS) {
                        problems.add(label(reward) + "：" + decision.reason());
                    }
                    return new CheckInScanResult(credited, scanned, 1, String.join("；", problems));
                }
                if (decision.outcome() == Outcome.DELIVERED) {
                    credited++;
                    delivered++;
                }
                cursor = Math.max(cursor, reward.acceptedAt());
            }
            if (batch.size() < PULL_PAGE_SIZE) {
                break;
            }
        }
        saveCursor(cursor, delivered);
        return new CheckInScanResult(credited, scanned, 0, "OK");
    }

    private void saveCursor(long acceptedAt, long delivered) {
        CheckInRewardCursor cursor = new CheckInRewardCursor(Math.max(acceptedAt, 0L), Math.max(delivered, 0L),
                clock.getAsLong());
        try {
            repository.saveCheckInRewardCursor(cursor);
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "[playtime-points] 打卡积分游标落库失败，下一轮会重复扫描（发放仍由流水幂等）",
                    failure);
        }
    }

    /* ---------- 单条发放（两条路径共用） ---------- */

    private enum Outcome {
        /** 新发放成功。 */
        DELIVERED,
        /** 无需发放（已发放过，或金额为 0），可以直接处理下一条。 */
        SKIPPED,
        /** 暂时发不出去，留待下一轮重试；游标不越过这一条。 */
        RETRY
    }

    private record Decision(Outcome outcome, String reason) {

        static Decision delivered() {
            return new Decision(Outcome.DELIVERED, "");
        }

        static Decision skipped() {
            return new Decision(Outcome.SKIPPED, "");
        }

        static Decision retry(String reason) {
            return new Decision(Outcome.RETRY, reason);
        }
    }

    /**
     * 处理一条打卡：算费率 → 判重 → 折算 → 幂等入账 → 落流水。
     *
     * <p>三种情形（{@code checkInRewardMode} 与打卡证据里的有效在线时长共同决定）：</p>
     * <ol>
     *   <li><b>FIXED（默认，与升级前逐位一致）</b>：费率就是该项目的每次打卡金额，整笔发放，与时长无关；</li>
     *   <li><b>HOURLY 且 {@code effectiveMillis > 0}</b>：{@code 积分 = 时薪 × 有效在线毫秒 ÷ 3_600_000}，
     *       按货币资产精度 HALF_UP 取整；折算后不足一个最小入账单位时**不发分**，但会落一条带中文原因的
     *       0 分流水后越过游标；</li>
     *   <li><b>HOURLY 且 {@code effectiveMillis <= 0}</b>（图片/文件/定位等非 MC 打卡、证据缺失或旧数据）：
     *       按时薪折算恒为 0，改用**全局**「非时长打卡每次积分」{@code checkInFixedPoints} 每次发放一笔，
     *       该值不参与项目覆盖；配置为 0（含旧设置文档缺键）时不发分，与 1.3.0 一致地落 0 分流水后越过游标。</li>
     * </ol>
     *
     * <p>任何情况下，如果本次生效的主口径费率不大于 0（固定金额为 0、时薪为 0 或非法），一律按 1.3.0 的
     * 「零副作用」处理：不调用钱包、不落流水，直接越过游标——这条判断先于上面的非时长回退。</p>
     *
     * <p>调用方必须持有 {@link #lock}。</p>
     */
    private Decision deliver(ProjectProgressPort.RewardRef reward, String source, PlaytimePointsSettings settings) {
        String checkInId = reward.checkInId();
        // 幂等保险二：本插件自己的发放流水（id = checkInId）。拉取 + 实时、以及拉取边界重复都靠它去重。
        if (repository.findCheckInReward(checkInId).isPresent()) {
            return Decision.skipped();
        }
        boolean hourly = settings.checkInHourlyMode();
        BigDecimal rate = settings.checkInRateFor(reward.projectId());
        if (rate.signum() <= 0) {
            // 费率为 0（固定金额为 0，或时薪被手工改成非法值）：不发钱，也不落流水（零副作用），
            // 直接算处理完成（游标可以越过它）。这个判断刻意放在「非时长打卡回退」之前，
            // 与 1.3.0 逐位一致：主口径费率非法时连 0 分流水都不落。管理端校验不允许保存出这种配置。
            return Decision.skipped();
        }
        // 非时长打卡（图片/文件/定位等没有时长的打卡）：按时薪模式下没有可折算的时长，改走独立的固定积分回退。
        boolean nonDurationFallback = hourly && reward.effectiveMillis() <= 0;
        BigDecimal fixedPoints = nonDurationFallback ? settings.checkInFixedPointsValue() : BigDecimal.ZERO;
        if (nonDurationFallback && fixedPoints.signum() <= 0) {
            // 没配非时长打卡积分（0 = 不发）：不产生任何钱包操作，落一条 0 分流水记下原因后越过游标，
            // 避免每轮重复处理同一条。行为与 1.3.0 一致（文案在原来基础上补充了「固定积分为 0」）。
            return recordSkipped(reward, source, rate, "该打卡没有有效在线时长（0 分钟），"
                    + "且非时长打卡（图片/文件/定位）每次积分未设置（0），本次不发放积分");
        }
        String userId = reward.userId() == null ? "" : reward.userId().trim();
        if (userId.isEmpty()) {
            return Decision.retry("打卡人身份缺失，暂缓发放");
        }
        if (!wallet.walletAvailable()) {
            return Decision.retry("钱包插件不可用，暂缓发放");
        }
        String assetCode = settings.assetCode() == null ? "" : settings.assetCode().trim();
        if (assetCode.isEmpty()) {
            return Decision.retry("未配置钱包货币类型，暂缓发放");
        }
        WalletPort.AssetRef asset = wallet.findAsset(assetCode).filter(WalletPort.AssetRef::enabled).orElse(null);
        if (asset == null) {
            return Decision.retry("货币类型 " + assetCode + " 不存在或未启用，暂缓发放");
        }
        BigDecimal amount;
        String mode;
        String note;
        if (nonDurationFallback) {
            amount = fixedPoints;
            mode = CheckInReward.MODE_HOURLY;
            note = NON_DURATION_FIXED_NOTE;
        } else if (hourly) {
            // 按资产精度取整：钱包按资产精度入账，超出精度的金额会被永久拒绝并卡住游标。
            amount = PointsCalculator.hourlyPoints(reward.effectiveMillis(), rate, asset.scale());
            if (amount.signum() <= 0) {
                return recordSkipped(reward, source, rate, "有效在线 " + minutesLabel(reward.effectiveMillis())
                        + " × 时薪 " + PointsCalculator.plain(rate) + " 折算后不足 1 个最小入账单位（货币精度 "
                        + Math.max(asset.scale(), 0) + " 位），本次不发放积分");
            }
            mode = CheckInReward.MODE_HOURLY;
            note = "";
        } else {
            amount = rate;
            mode = CheckInReward.MODE_FIXED;
            note = "";
        }
        // 流水里的费率是**本次实际生效的费率**：非时长打卡回退时就是那笔固定积分（时薪并未参与计算），
        // 其余情形是 checkInRateFor 给出的主口径费率。这样 credit / points / rate 三者对这类记录自洽可读。
        BigDecimal ledgerRate = nonDurationFallback ? fixedPoints : rate;
        // 幂等保险一：钱包业务单号。同一 checkInId 永远得到同一个单号，钱包侧不会重复入账。
        String businessNo = businessNo(checkInId);
        try {
            wallet.credit(userId, assetCode, amount, businessNo,
                    remark(reward, amount, mode, ledgerRate, nonDurationFallback));
        } catch (RuntimeException failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return Decision.retry("钱包入账失败：" + message);
        }
        try {
            repository.saveCheckInReward(new CheckInReward(checkInId, reward.detailId(), reward.detailTitle(),
                    reward.projectId(), reward.projectName(), userId, PointsCalculator.plain(amount),
                    PointsCalculator.plain(amount), businessNo, source, reward.acceptedAt(), clock.getAsLong(),
                    mode, reward.effectiveMillis(), PointsCalculator.plain(ledgerRate), note));
        } catch (RuntimeException failure) {
            // 钱已经出去了但流水没落：按失败处理让下一轮重来，重复入账由钱包业务单号兜住。
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return Decision.retry("发放流水落库失败：" + message);
        }
        return Decision.delivered();
    }

    /**
     * 落一条「折算不出来所以不发」的 0 分流水：不调用钱包（不产生 0 金额操作），游标可以越过这一条。
     *
     * <p>两种原因共用：按时薪折算不足一个最小入账单位；或非时长打卡且非时长打卡积分配置为 0。
     * 流水里的 {@code mode} 一律记 {@link CheckInReward#MODE_HOURLY}、{@code rate} 记主口径费率
     * （按时薪模式即每小时积分），与 1.3.0 的记录口径一致。</p>
     *
     * <p>流水本身就是幂等标记：下一轮再拉到同一条时直接跳过，不会重复记录，也不会重复尝试发放。
     * 落库失败按 {@link Outcome#RETRY} 处理，游标不越过，下一轮重试。</p>
     */
    private Decision recordSkipped(ProjectProgressPort.RewardRef reward, String source, BigDecimal rate, String note) {
        // userId 与正常发放同一口径（去空白）：用户端「我的积分」按 userId 收窄，凭这一列才能看到自己的跳过记录。
        String userId = reward.userId() == null ? "" : reward.userId().trim();
        try {
            repository.saveCheckInReward(new CheckInReward(reward.checkInId(), reward.detailId(), reward.detailTitle(),
                    reward.projectId(), reward.projectName(), userId, "0", "0", "", source,
                    reward.acceptedAt(), clock.getAsLong(), CheckInReward.MODE_HOURLY, reward.effectiveMillis(),
                    PointsCalculator.plain(rate), note));
        } catch (RuntimeException failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return Decision.retry("发放流水落库失败：" + message);
        }
        return Decision.skipped();
    }

    /** 钱包幂等单号：{@code playtime-points:checkin:<checkInId>}；超长时退化为确定性摘要，仍然一一对应。 */
    private String businessNo(String checkInId) {
        String businessNo = BUSINESS_NO_PREFIX + checkInId;
        if (businessNo.length() <= MAX_BUSINESS_NO_LENGTH) {
            return businessNo;
        }
        return BUSINESS_NO_PREFIX + digest(checkInId);
    }

    private static String digest(String seed) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(seed.getBytes(StandardCharsets.UTF_8)), 0, 24);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(seed.hashCode());
        }
    }

    /**
     * 钱包入账备注：按时薪模式写出折算过程（便于在钱包流水里直接对账），非时长打卡写出「按固定积分发放」，
     * 固定模式与升级前一致。
     */
    private static String remark(ProjectProgressPort.RewardRef reward, BigDecimal amount, String mode,
                                 BigDecimal rate, boolean nonDurationFallback) {
        String project = reward.projectName() == null || reward.projectName().isBlank()
                ? reward.projectId() : reward.projectName();
        String detail = reward.detailTitle() == null || reward.detailTitle().isBlank()
                ? reward.detailId() : reward.detailTitle();
        if (nonDurationFallback) {
            return "项目打卡积分：" + project + " · " + detail + " · 打卡 " + reward.checkInId()
                    + " · 非时长打卡（图片/文件/定位）按固定积分发放 " + PointsCalculator.plain(amount) + " 分";
        }
        if (CheckInReward.MODE_HOURLY.equals(mode)) {
            return "项目打卡积分：" + project + " · " + detail + " · 打卡 " + reward.checkInId()
                    + " · 有效在线 " + minutesLabel(reward.effectiveMillis())
                    + " × 时薪 " + PointsCalculator.plain(rate) + " = " + PointsCalculator.plain(amount) + " 分";
        }
        return "项目打卡积分：" + project + " · " + detail + " · 打卡 " + reward.checkInId()
                + " · " + PointsCalculator.plain(amount) + " 分";
    }

    /** 毫秒 → 「X 分钟」；不足 1 分钟但大于 0 时显示「不足 1 分钟」，0 显示「0 分钟」。 */
    private static String minutesLabel(long millis) {
        long safe = Math.max(0L, millis);
        long minutes = safe / 60_000L;
        if (minutes > 0) {
            return minutes + " 分钟";
        }
        return safe > 0 ? "不足 1 分钟" : "0 分钟";
    }

    private static String label(ProjectProgressPort.RewardRef reward) {
        String project = reward.projectName() == null || reward.projectName().isBlank()
                ? reward.projectId() : reward.projectName();
        return "打卡 " + reward.checkInId() + "（" + project + "）";
    }

    /* ---------- 查询与设置联动 ---------- */

    public boolean enabled() {
        return settings().checkInRewardEnabled();
    }

    /** provider 是否可用（管理端据此提示「未安装 project-progress」）。 */
    public boolean available() {
        return projectProgress.available();
    }

    /** 项目下拉选项（来自 provider 的 projects()）；provider 不可用时为空列表。 */
    public List<Map<String, Object>> projectOptions() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProjectProgressPort.ProjectRef project : projectProgress.projects()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", project.id());
            row.put("name", project.name());
            row.put("enabled", project.enabled());
            rows.add(row);
        }
        return rows;
    }

    /** 管理端流水分页。 */
    public CheckInRewardPage adminPage(String projectId, String userId, int page, int size) {
        PlaytimePointsRepository.CheckInRewardPage result =
                repository.checkInRewards(trimToNull(projectId), trimToNull(userId), page, size);
        return new CheckInRewardPage(result.records().stream().map(CheckInRewardAssembler::toView).toList(),
                result.total());
    }

    /**
     * 用户端汇总：打卡积分合计、笔数与最近记录（供 {@code /me/summary} 合并展示）。
     *
     * <p>归属只来自登录主体传入的 userId（管理端不能借此读他人数据）。
     * {@code count} 是**实际发放笔数**：按时薪折算后跳过（0 分）的流水不计入，但会出现在最近记录里，
     * 用户能看到「为什么这条没发」。</p>
     */
    public Map<String, Object> mySummary(String userId, int recentLimit) {
        PlaytimePointsSettings settings = settings();
        List<CheckInReward> all = userId == null || userId.isBlank()
                ? List.of() : repository.allCheckInRewards(userId);
        BigDecimal total = BigDecimal.ZERO;
        int credited = 0;
        for (CheckInReward reward : all) {
            BigDecimal credit = PointsCalculator.parseCarry(reward.credit());
            total = total.add(credit);
            if (credit.signum() > 0) {
                credited++;
            }
        }
        int limit = Math.max(recentLimit, 0);
        List<CheckInRewardView> recent = all.stream()
                .limit(limit)
                .map(CheckInRewardAssembler::toView)
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enabled", settings.checkInRewardEnabled());
        payload.put("realtime", settings.checkInRewardEnabled() && settings.checkInRewardRealtime());
        payload.put("providerAvailable", projectProgress.available());
        payload.put("totalPoints", PointsCalculator.plain(total));
        payload.put("count", credited);
        payload.put("recent", recent);
        return payload;
    }

    private PlaytimePointsSettings settings() {
        return repository.findSettings().orElseGet(PlaytimePointsSettings::defaults);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 插件停用时调用：置位后迟到的实时回调与拉取都变成空操作。 */
    @Override
    public void close() {
        disposed = true;
    }

    /** 供测试与诊断：当前游标。 */
    public Optional<CheckInRewardCursor> cursor() {
        return repository.findCheckInRewardCursor();
    }
}
