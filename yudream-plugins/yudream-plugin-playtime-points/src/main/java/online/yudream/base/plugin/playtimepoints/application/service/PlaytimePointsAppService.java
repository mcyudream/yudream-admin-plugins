package online.yudream.base.plugin.playtimepoints.application.service;

import online.yudream.base.plugin.playtimepoints.application.dto.ScanResult;
import online.yudream.base.plugin.playtimepoints.application.dto.SettlementPage;
import online.yudream.base.plugin.playtimepoints.application.dto.SettlementView;
import online.yudream.base.plugin.playtimepoints.application.assembler.PointsSettlementAssembler;
import online.yudream.base.plugin.playtimepoints.application.port.MinecraftPort;
import online.yudream.base.plugin.playtimepoints.application.port.SkinPort;
import online.yudream.base.plugin.playtimepoints.application.port.WalletPort;
import online.yudream.base.plugin.playtimepoints.domain.aggregate.PointsSettlement;
import online.yudream.base.plugin.playtimepoints.domain.aggregate.SettlementState;
import online.yudream.base.plugin.playtimepoints.domain.repo.PlaytimePointsRepository;
import online.yudream.base.plugin.playtimepoints.domain.service.PointsCalculator;
import online.yudream.base.plugin.playtimepoints.domain.valobj.PlaytimePointsSettings;
import online.yudream.base.plugin.playtimepoints.domain.valobj.SubServerBaseline;
import online.yudream.base.plugin.playtimepoints.domain.valobj.SubSettlement;

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
import java.util.function.LongSupplier;

/**
 * 在线时长积分用例编排：定时扫描 mc-server 玩家活动，对「退出且 lastQuitAt 前进」的玩家
 * 用累计值差值结算本次有效时长（扣除挂机），按服务器权重换算积分并入账钱包。
 *
 * <p>群组服（Velocity 代理 + 多台子服）下同一段会话的时长分散在多个子服上，mc-server 会把它们
 * 汇总，同时通过 {@code minecraftSubServerActivities} 暴露子服明细。此时结算改为
 * Σ(子服有效分钟 × 子服权重) ÷ 每积分分钟数，基线也按子服分别记录；没有子服明细（单机服、
 * 提供方旧版本或只有 {@code default} 桶）时完全走改造前的整服口径。
 *
 * <p>可靠性设计：
 * <ul>
 *   <li>首次见到某玩家只建基线不补发历史，避免安装插件后一次性发放存量时长；</li>
 *   <li>玩家未绑定网站账号、钱包不可用或入账失败时不推进基线，下轮自动重试补发；</li>
 *   <li>零头（不足 1 分的小数）持久化在基线上，凑满 1 分才入账，避免每次退出抹掉小数；</li>
 *   <li>businessNo 由 服务器+玩家+退出时间 派生，钱包侧幂等，重复结算不会重复入账；</li>
 *   <li>升级后第一次看到子服明细时只重建子服基线、不结算，避免把改造前已按整服结算过的时长再发一次。</li>
 * </ul>
 */
public class PlaytimePointsAppService {

    private static final int ACTIVITY_PAGE_SIZE = 100;
    private static final int MAX_ACTIVITY_PAGES = 50;
    private static final int MAX_PROBLEMS = 3;

    /** 没有子服维度时 mc-server 使用的桶名，与 provider 侧常量一致。 */
    private static final String DEFAULT_SUB_SERVER = "default";

    private final PlaytimePointsRepository repository;
    private final MinecraftPort minecraft;
    private final WalletPort wallet;
    private final SkinPort skin;
    private final LongSupplier clock;
    private volatile long lastScanAt;
    private volatile String lastScanMessage = "";

    public PlaytimePointsAppService(PlaytimePointsRepository repository, MinecraftPort minecraft,
                                    WalletPort wallet, SkinPort skin, LongSupplier clock) {
        this.repository = repository;
        this.minecraft = minecraft;
        this.wallet = wallet;
        this.skin = skin;
        this.clock = clock;
    }

    /* ---------- 设置 ---------- */

    public PlaytimePointsSettings settings() {
        return repository.findSettings().orElse(PlaytimePointsSettings.defaults());
    }

    public PlaytimePointsSettings saveSettings(PlaytimePointsSettings next) {
        PlaytimePointsSettings normalized = next.normalized();
        validate(normalized);
        repository.saveSettings(normalized);
        return normalized;
    }

    private void validate(PlaytimePointsSettings settings) {
        if (settings.assetCode() == null || !settings.assetCode().matches("[A-Z0-9_]{2,32}")) {
            throw new IllegalArgumentException("货币代码必须是 2-32 位大写字母、数字或下划线");
        }
        if (settings.minutesPerPoint() < 1 || settings.minutesPerPoint() > 1_000_000) {
            throw new IllegalArgumentException("每积分所需有效时长必须在 1-1000000 分钟之间");
        }
        WalletPort.AssetRef asset = null;
        if (wallet.walletAvailable()) {
            asset = wallet.findAsset(settings.assetCode()).filter(WalletPort.AssetRef::enabled).orElse(null);
            if (asset == null) {
                throw new IllegalArgumentException("钱包中不存在已启用的货币类型：" + settings.assetCode());
            }
        }
        for (Map.Entry<String, PlaytimePointsSettings.ServerRule> entry : settings.servers().entrySet()) {
            validateWeight("服务器「" + entry.getKey() + "」", entry.getValue());
        }
        for (Map.Entry<String, PlaytimePointsSettings.ServerRule> entry : settings.subServers().entrySet()) {
            validateWeight(subRuleLabel(entry.getKey()), entry.getValue());
        }
        validateCheckInRewardPoints(settings, asset);
    }

    /**
     * 打卡积分金额/时薪校验：全局金额、全局时薪与每个项目覆盖都必须是大于 0 的十进制数字，
     * 且精度不超过货币类型允许的位数。
     *
     * <p>精度校验很关键：钱包按资产精度入账，配置成 0 位小数货币却填 1.5 会被钱包永久拒绝，
     * 而发放失败会一直重试并阻塞游标，因此在保存时就拦住。</p>
     *
     * <p>按时薪折算还多一条约束：按当前货币资产精度折算后不能恒为 0。货币精度是 0 位时，
     * 时薪低于 0.5 会让「一小时」都折算成 0 分，这类配置虽然不会卡游标（折算为 0 的打卡按跳过处理），
     * 但实际效果是永远不发积分，属于明显的配置错误，保存时就拦下来。</p>
     *
     * <p>{@code checkInRewardProjectPoints} 的语义随计算方式变化，因此项目覆盖按当前模式的口径校验：
     * 固定模式按「每次金额」，按时薪模式按「每小时积分」。</p>
     *
     * <p>{@code checkInFixedPoints}（非时长打卡每次积分）是按时薪模式下「没有时长的打卡」的回退项，
     * 与其他金额不同：<b>0 是合法值</b>，表示这类打卡不发积分。</p>
     */
    private void validateCheckInRewardPoints(PlaytimePointsSettings settings, WalletPort.AssetRef asset) {
        boolean hourly = settings.checkInHourlyMode();
        validateCheckInPoints("打卡积分金额", settings.checkInRewardPoints(), asset);
        validateCheckInHourlyPoints("打卡积分时薪", settings.checkInHourlyPoints(), asset);
        validateCheckInFixedPoints("非时长打卡积分", settings.checkInFixedPoints(), asset);
        for (Map.Entry<String, String> entry : settings.checkInRewardProjectPoints().entrySet()) {
            String project = "项目「" + entry.getKey() + "」的打卡积分";
            if (hourly) {
                validateCheckInHourlyPoints(project + "时薪", entry.getValue(), asset);
            } else {
                validateCheckInPoints(project + "金额", entry.getValue(), asset);
            }
        }
    }

    private void validateCheckInPoints(String label, String value, WalletPort.AssetRef asset) {
        BigDecimal points = PlaytimePointsSettings.parsePoints(value);
        if (points == null) {
            throw new IllegalArgumentException(label + "必须是合法的十进制数字");
        }
        if (points.signum() <= 0) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
        if (points.compareTo(new BigDecimal("1000000")) > 0) {
            throw new IllegalArgumentException(label + "不能超过 1000000");
        }
        int scale = points.stripTrailingZeros().scale();
        if (scale > PointsCalculator.SCALE) {
            throw new IllegalArgumentException(label + "最多支持 " + PointsCalculator.SCALE + " 位小数");
        }
        if (asset != null && scale > asset.scale()) {
            throw new IllegalArgumentException(label + "最多支持 " + asset.scale() + " 位小数（当前货币类型 "
                    + asset.code() + "）");
        }
    }

    /**
     * 时薪校验：与金额同样是「大于 0、不超过 1000000、最多 4 位小数」，但**不要求**时薪小数位不超过
     * 货币精度——时薪是费率而不是最终金额，精度由折算结果承担；多出的一条是折算后不能恒为 0。
     */
    private void validateCheckInHourlyPoints(String label, String value, WalletPort.AssetRef asset) {
        BigDecimal rate = PlaytimePointsSettings.parsePoints(value);
        if (rate == null) {
            throw new IllegalArgumentException(label + "必须是合法的十进制数字");
        }
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
        if (rate.compareTo(new BigDecimal("1000000")) > 0) {
            throw new IllegalArgumentException(label + "不能超过 1000000");
        }
        if (rate.stripTrailingZeros().scale() > PointsCalculator.SCALE) {
            throw new IllegalArgumentException(label + "最多支持 " + PointsCalculator.SCALE + " 位小数");
        }
        if (asset == null) {
            return;
        }
        int assetScale = Math.max(asset.scale(), 0);
        // 整整一小时都折算不出一个最小单位 → 任何时长的打卡都会是 0 分，属于配置错误。
        if (PointsCalculator.hourlyPoints(3_600_000L, rate, assetScale).signum() <= 0) {
            throw new IllegalArgumentException(label + "按当前货币类型 " + asset.code() + " 的精度（" + assetScale
                    + " 位小数）折算后为 0，请提高到至少 "
                    + BigDecimal.valueOf(5, assetScale + 1).toPlainString());
        }
    }

    /**
     * 非时长打卡积分校验（按时薪模式下「没有时长的打卡」的回退项）。
     *
     * <p>与其他打卡金额的关键区别：<b>0 是合法值</b>，表示这类打卡不发积分，因此不套用「必须大于 0」。
     * 大于 0 时仍要求不超过 1000000、最多 {@link PointsCalculator#SCALE} 位小数，并且小数位不超过当前
     * 货币精度——它是最终入账金额（不经过折算），配置成 0 位小数货币却填 1.5 会被钱包永久拒绝。</p>
     */
    private void validateCheckInFixedPoints(String label, String value, WalletPort.AssetRef asset) {
        BigDecimal points = PlaytimePointsSettings.parsePoints(value);
        if (points == null) {
            throw new IllegalArgumentException(label + "必须是合法的十进制数字（填 0 表示这类打卡不发放积分）");
        }
        if (points.signum() < 0) {
            throw new IllegalArgumentException(label + "不能是负数（填 0 表示这类打卡不发放积分）");
        }
        if (points.signum() == 0) {
            // 0 是合法值：非时长打卡不发积分。
            return;
        }
        if (points.compareTo(new BigDecimal("1000000")) > 0) {
            throw new IllegalArgumentException(label + "不能超过 1000000");
        }
        int scale = points.stripTrailingZeros().scale();
        if (scale > PointsCalculator.SCALE) {
            throw new IllegalArgumentException(label + "最多支持 " + PointsCalculator.SCALE + " 位小数");
        }
        if (asset != null && scale > asset.scale()) {
            throw new IllegalArgumentException(label + "最多支持 " + asset.scale() + " 位小数（当前货币类型 "
                    + asset.code() + "）");
        }
    }

    private void validateWeight(String label, PlaytimePointsSettings.ServerRule rule) {
        BigDecimal weight;
        try {
            weight = new BigDecimal(rule.weight() == null ? "" : rule.weight().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "的权重不是合法数字");
        }
        if (weight.signum() < 0 || weight.compareTo(new BigDecimal("999")) > 0 || weight.scale() > 2) {
            throw new IllegalArgumentException(label + "的权重必须在 0-999 之间且最多两位小数");
        }
    }

    private static String subRuleLabel(String key) {
        int at = key == null ? -1 : key.indexOf(PlaytimePointsSettings.SUB_SERVER_SEPARATOR);
        if (at < 0) {
            return "子服规则「" + key + "」";
        }
        return "服务器「" + key.substring(0, at) + "」的子服「"
                + key.substring(at + PlaytimePointsSettings.SUB_SERVER_SEPARATOR.length()) + "」";
    }

    /* ---------- 扫描结算 ---------- */

    /** 单调度器线程与手动触发都从这里进，synchronized 防止并发重复结算。 */
    public synchronized ScanResult scan() {
        PlaytimePointsSettings settings = settings();
        if (!settings.enabled()) {
            return finish(0, 0, "结算已停用");
        }
        if (!minecraft.minecraftAvailable()) {
            return finish(0, 0, "minecraft-server 插件不可用，无法读取在线时长");
        }
        int credited = 0;
        int sessions = 0;
        List<String> problems = new ArrayList<>();
        for (MinecraftPort.ServerRef server : minecraft.servers()) {
            if (!settings.enabledFor(server.id())) {
                continue;
            }
            for (int page = 1; page <= MAX_ACTIVITY_PAGES; page++) {
                List<MinecraftPort.ActivityRef> activities = minecraft.playerActivities(server.id(), page, ACTIVITY_PAGE_SIZE);
                for (MinecraftPort.ActivityRef activity : activities) {
                    if (activity.online()) {
                        continue;
                    }
                    Outcome outcome = settleOne(server, activity, settings);
                    sessions += outcome.recorded() ? 1 : 0;
                    credited += outcome.credited() ? 1 : 0;
                    if (outcome.problem() != null && problems.size() < MAX_PROBLEMS) {
                        problems.add(server.name() + " · " + activity.playerName() + "：" + outcome.problem());
                    }
                }
                if (activities.size() < ACTIVITY_PAGE_SIZE) {
                    break;
                }
            }
        }
        String message = String.join("；", problems);
        if (problems.size() >= MAX_PROBLEMS) {
            message += "……";
        }
        return finish(credited, sessions, message.isEmpty() ? "OK" : message);
    }

    private record Outcome(boolean recorded, boolean credited, String problem) {
        static final Outcome SILENT = new Outcome(false, false, null);
    }

    /**
     * 本次会话的结算依据。
     *
     * @param details          子服明细；整服口径为空列表
     * @param baselines        结算后应写回的子服基线；整服口径为空表（同时清掉过期的子服基线）
     */
    private record Session(List<SubSettlement> details, long onlineMillis, long afkMillis, long effectiveMillis,
                           BigDecimal points, Map<String, SubServerBaseline> baselines) {
    }

    private Outcome settleOne(MinecraftPort.ServerRef server, MinecraftPort.ActivityRef activity,
                              PlaytimePointsSettings settings) {
        long now = clock.getAsLong();
        String stateId = server.id() + ":" + activity.playerId();
        // 子服明细只在玩家已退出（scan 已过滤在线玩家）时读取；提供方不支持或版本过旧时返回空列表。
        List<MinecraftPort.SubActivityRef> subActivities = subServerBuckets(
                minecraft.subServerActivities(server.id(), activity.playerId()));
        SettlementState state = repository.findState(stateId).orElse(null);
        if (state == null) {
            // 首次见到该玩家：以当前累计为基线，不补发安装前的历史时长
            repository.saveState(new SettlementState(stateId, server.id(), activity.playerId(), activity.playerName(),
                    activity.totalOnlineMillis(), activity.totalAfkMillis(), activity.lastQuitAt(),
                    PointsCalculator.parseCarry(null).toPlainString(), now, baselinesOf(subActivities)));
            return Outcome.SILENT;
        }
        long settledQuitAt = state.settledQuitAt() == null ? 0L : state.settledQuitAt();
        Long quitAt = activity.lastQuitAt();
        if (quitAt == null || quitAt <= settledQuitAt) {
            return Outcome.SILENT;
        }
        boolean grouped = !subActivities.isEmpty();
        if (grouped && state.subServers().isEmpty()) {
            // 升级迁移：基线里还没有子服维度，而 mc-server 已经开始上报子服。用当前各子服累计值重建
            // 基线并推进整服基线，本轮不结算——改造前的时长已经按整服口径结算过，不能再发一次。
            advanceState(state, activity, activity.totalOnlineMillis(), activity.totalAfkMillis(), quitAt,
                    state.carryPoints(), now, baselinesOf(subActivities));
            return Outcome.SILENT;
        }
        Session session = grouped
                ? groupedSession(server, state, subActivities, settings)
                : flatSession(server, state, activity, settings);
        if (session.effectiveMillis() <= 0) {
            advanceState(state, activity, activity.totalOnlineMillis(), activity.totalAfkMillis(), quitAt,
                    state.carryPoints(), now, session.baselines());
            return Outcome.SILENT;
        }
        Optional<String> ownerId = skin.resolveOwnerId(activity.playerId(), activity.playerName());
        if (ownerId.isEmpty()) {
            return new Outcome(false, false, "玩家未绑定网站用户，暂缓结算（绑定后自动补发）");
        }
        if (!wallet.walletAvailable()) {
            return new Outcome(false, false, "钱包插件不可用，暂缓结算");
        }
        String assetCode = settings.assetCode();
        if (wallet.findAsset(assetCode).filter(WalletPort.AssetRef::enabled).isEmpty()) {
            return new Outcome(false, false, "货币类型 " + assetCode + " 不存在或未启用，暂缓结算");
        }
        BigDecimal points = session.points();
        BigDecimal[] split = PointsCalculator.splitCredit(PointsCalculator.parseCarry(state.carryPoints()), points);
        BigDecimal credit = split[0];
        BigDecimal newCarry = split[1];
        String businessNo = null;
        if (credit.signum() > 0) {
            businessNo = businessNo(server.id(), activity.playerId(), quitAt);
            String remark = "在线时长积分：" + server.name() + " · " + activity.playerName()
                    + " · 有效 " + session.effectiveMillis() / 60_000 + " 分钟"
                    + (grouped ? " · " + subActivities.size() + " 个子服" : "");
            try {
                wallet.credit(ownerId.get(), assetCode, credit, businessNo, remark);
            } catch (RuntimeException e) {
                return new Outcome(false, false,
                        "钱包入账失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        PointsSettlement settlement = new PointsSettlement(
                settlementId(server.id(), activity.playerId(), quitAt), server.id(), server.name(),
                activity.playerId(), activity.playerName(), ownerId.get(), state.settledQuitAt(), quitAt,
                session.onlineMillis(), session.afkMillis(), session.effectiveMillis(),
                PointsCalculator.plain(settings.weightFor(server.id())), PointsCalculator.plain(points),
                PointsCalculator.plain(credit), PointsCalculator.plain(newCarry), assetCode, businessNo, now,
                session.details());
        repository.saveSettlement(settlement);
        advanceState(state, activity, activity.totalOnlineMillis(), activity.totalAfkMillis(), quitAt,
                newCarry.toPlainString(), now, session.baselines());
        return new Outcome(true, credit.signum() > 0, null);
    }

    /** 整服口径：与改造前逐字一致，不产生子服明细，并清掉可能残留的子服基线。 */
    private Session flatSession(MinecraftPort.ServerRef server, SettlementState state,
                                MinecraftPort.ActivityRef activity, PlaytimePointsSettings settings) {
        long deltaOnline = Math.max(0L, activity.totalOnlineMillis() - state.settledOnlineMillis());
        long deltaAfk = Math.max(0L, activity.totalAfkMillis() - state.settledAfkMillis());
        long effective = settings.subtractAfk() ? Math.max(0L, deltaOnline - deltaAfk) : deltaOnline;
        BigDecimal points = PointsCalculator.points(effective, settings.weightFor(server.id()),
                settings.minutesPerPoint());
        return new Session(List.of(), deltaOnline, deltaAfk, effective, points, Map.of());
    }

    /** 群组服口径：逐子服求差值并按子服权重加权求和，明细随流水一起落库。 */
    private Session groupedSession(MinecraftPort.ServerRef server, SettlementState state,
                                   List<MinecraftPort.SubActivityRef> subActivities,
                                   PlaytimePointsSettings settings) {
        long onlineTotal = 0L;
        long afkTotal = 0L;
        long effectiveTotal = 0L;
        List<PointsCalculator.WeightedMinutes> parts = new ArrayList<>();
        List<SubSettlement> details = new ArrayList<>();
        Map<String, SubServerBaseline> next = new LinkedHashMap<>(state.subServers());
        for (MinecraftPort.SubActivityRef sub : subActivities) {
            String name = sub.subServer().trim();
            SubServerBaseline baseline = state.subServers().get(name);
            // 首次出现的子服：此前从未结算过它，累计值即为本次应结；基线缺失不会造成重复发放。
            long deltaOnline = Math.max(0L, sub.totalOnlineMillis() - (baseline == null ? 0L : baseline.onlineMillis()));
            long deltaAfk = Math.max(0L, sub.totalAfkMillis() - (baseline == null ? 0L : baseline.afkMillis()));
            long effective = settings.subtractAfk() ? Math.max(0L, deltaOnline - deltaAfk) : deltaOnline;
            BigDecimal weight = settings.weightFor(server.id(), name);
            boolean enabled = settings.enabledFor(server.id(), name);
            if (enabled) {
                parts.add(new PointsCalculator.WeightedMinutes(effective, weight));
            }
            BigDecimal subPoints = enabled
                    ? PointsCalculator.points(effective, weight, settings.minutesPerPoint())
                    : BigDecimal.ZERO.setScale(PointsCalculator.SCALE);
            details.add(new SubSettlement(name, deltaOnline, deltaAfk, effective,
                    PointsCalculator.plain(weight), PointsCalculator.plain(subPoints), enabled));
            // 顶层仍是各子服的合计（与 mc-server 的整服累计一致），不参与结算的子服时长也如实记录。
            onlineTotal += deltaOnline;
            afkTotal += deltaAfk;
            effectiveTotal += effective;
            next.put(name, new SubServerBaseline(name, sub.totalOnlineMillis(), sub.totalAfkMillis()));
        }
        return new Session(details, onlineTotal, afkTotal, effectiveTotal,
                PointsCalculator.weightedPoints(parts, settings.minutesPerPoint()), next);
    }

    /** 只保留真正的子服桶：{@code default} 表示没有子服维度，走整服口径。 */
    private static List<MinecraftPort.SubActivityRef> subServerBuckets(List<MinecraftPort.SubActivityRef> subActivities) {
        if (subActivities == null || subActivities.isEmpty()) {
            return List.of();
        }
        List<MinecraftPort.SubActivityRef> result = new ArrayList<>();
        for (MinecraftPort.SubActivityRef sub : subActivities) {
            if (sub == null || sub.subServer() == null) {
                continue;
            }
            String name = sub.subServer().trim();
            if (!name.isEmpty() && !DEFAULT_SUB_SERVER.equals(name)) {
                result.add(sub);
            }
        }
        return result;
    }

    private static Map<String, SubServerBaseline> baselinesOf(List<MinecraftPort.SubActivityRef> subActivities) {
        Map<String, SubServerBaseline> baselines = new LinkedHashMap<>();
        for (MinecraftPort.SubActivityRef sub : subServerBuckets(subActivities)) {
            baselines.put(sub.subServer().trim(),
                    new SubServerBaseline(sub.subServer().trim(), sub.totalOnlineMillis(), sub.totalAfkMillis()));
        }
        return baselines;
    }

    private void advanceState(SettlementState state, MinecraftPort.ActivityRef activity,
                              long settledOnlineMillis, long settledAfkMillis, Long settledQuitAt,
                              String carryPoints, long now, Map<String, SubServerBaseline> subServers) {
        repository.saveState(new SettlementState(state.id(), state.serverId(), state.playerId(),
                activity.playerName(), settledOnlineMillis, settledAfkMillis, settledQuitAt, carryPoints, now,
                subServers));
    }

    /** 结算流水 ID：毫秒时间戳零填充前缀，保证文档存储按 ID 字典序分页时即时间序。 */
    private String settlementId(String serverId, String playerId, long windowEnd) {
        return String.format("%013d", windowEnd) + ":" + serverId + ":" + playerId;
    }

    /** 钱包幂等号：同一 服务器+玩家+退出时间 只会入账一次；截断 32 位十六进制以适配钱包长度限制。 */
    private String businessNo(String serverId, String playerId, long quitAt) {
        String seed = "playtime-points|" + serverId + "|" + playerId + "|" + quitAt;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "pp:" + HexFormat.of().formatHex(digest.digest(seed.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            return "pp:" + Integer.toHexString(seed.hashCode());
        }
    }

    private ScanResult finish(int credited, int sessions, String message) {
        lastScanAt = clock.getAsLong();
        lastScanMessage = message;
        return new ScanResult(credited, sessions, message);
    }

    /* ---------- 查询 ---------- */

    public SettlementPage adminSettlements(String serverId, String keyword, int page, int size) {
        PlaytimePointsRepository.SettlementPage result = repository.settlements(trimToNull(serverId), null,
                trimToNull(keyword), page, size);
        return new SettlementPage(result.records().stream().map(PointsSettlementAssembler::toView).toList(), result.total());
    }

    public SettlementPage mySettlements(String userId, int page, int size) {
        PlaytimePointsRepository.SettlementPage result = repository.settlements(null, userId, null, page, size);
        return new SettlementPage(result.records().stream().map(PointsSettlementAssembler::toView).toList(), result.total());
    }

    /** 我的积分汇总：累计积分、有效时长、按服务器/子服分组与钱包余额。 */
    public Map<String, Object> mySummary(String userId) {
        PlaytimePointsSettings settings = settings();
        List<PointsSettlement> all = repository.settlements(null, userId, null, 1, Integer.MAX_VALUE).records();
        BigDecimal totalPoints = BigDecimal.ZERO;
        long totalEffectiveMillis = 0;
        Map<String, int[]> serverSessions = new LinkedHashMap<>();
        Map<String, long[]> serverMillis = new LinkedHashMap<>();
        Map<String, BigDecimal> serverPoints = new LinkedHashMap<>();
        Map<String, String> serverNames = new LinkedHashMap<>();
        Map<String, int[]> subSessions = new LinkedHashMap<>();
        Map<String, long[]> subMillis = new LinkedHashMap<>();
        Map<String, BigDecimal> subPoints = new LinkedHashMap<>();
        Map<String, SubLabel> subLabels = new LinkedHashMap<>();
        for (PointsSettlement settlement : all) {
            totalPoints = totalPoints.add(PointsCalculator.parseCarry(settlement.credited()));
            totalEffectiveMillis += Math.max(0L, settlement.effectiveMillis());
            serverSessions.computeIfAbsent(settlement.serverId(), key -> new int[1])[0]++;
            serverMillis.computeIfAbsent(settlement.serverId(), key -> new long[1])[0] += Math.max(0L, settlement.effectiveMillis());
            serverPoints.merge(settlement.serverId(), PointsCalculator.parseCarry(settlement.points()), BigDecimal::add);
            serverNames.put(settlement.serverId(), settlement.serverName());
            for (SubSettlement sub : settlement.subServers()) {
                if (!sub.enabled()) {
                    continue;
                }
                String key = settlement.serverId() + PlaytimePointsSettings.SUB_SERVER_SEPARATOR + sub.subServer();
                subSessions.computeIfAbsent(key, item -> new int[1])[0]++;
                subMillis.computeIfAbsent(key, item -> new long[1])[0] += Math.max(0L, sub.effectiveMillis());
                subPoints.merge(key, PointsCalculator.parseCarry(sub.points()), BigDecimal::add);
                subLabels.put(key, new SubLabel(settlement.serverId(), settlement.serverName(), sub.subServer()));
            }
        }
        List<Map<String, Object>> byServer = new ArrayList<>();
        for (String serverId : serverSessions.keySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serverId", serverId);
            row.put("serverName", serverNames.get(serverId));
            row.put("points", PointsCalculator.plain(serverPoints.get(serverId)));
            row.put("effectiveMinutes", serverMillis.get(serverId)[0] / 60_000);
            row.put("sessions", serverSessions.get(serverId)[0]);
            byServer.add(row);
        }
        List<Map<String, Object>> bySubServer = new ArrayList<>();
        for (String key : subSessions.keySet()) {
            SubLabel label = subLabels.get(key);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serverId", label.serverId());
            row.put("serverName", label.serverName());
            row.put("subServer", label.subServer());
            row.put("points", PointsCalculator.plain(subPoints.get(key)));
            row.put("effectiveMinutes", subMillis.get(key)[0] / 60_000);
            row.put("sessions", subSessions.get(key)[0]);
            bySubServer.add(row);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("assetCode", settings.assetCode());
        summary.put("totalPoints", PointsCalculator.plain(totalPoints));
        summary.put("totalEffectiveMinutes", totalEffectiveMillis / 60_000);
        summary.put("sessions", all.size());
        summary.put("byServer", byServer);
        summary.put("bySubServer", bySubServer);
        boolean walletReady = wallet.walletAvailable();
        summary.put("walletAvailable", walletReady);
        summary.put("assetName", null);
        summary.put("assetSymbol", "");
        summary.put("balance", null);
        if (walletReady) {
            Optional<WalletPort.AssetRef> asset = wallet.findAsset(settings.assetCode());
            asset.ifPresent(ref -> {
                summary.put("assetName", ref.name());
                summary.put("assetSymbol", ref.symbol());
            });
            wallet.balance(userId, settings.assetCode()).ifPresent(value -> summary.put("balance", value));
        }
        return summary;
    }

    private record SubLabel(String serverId, String serverName, String subServer) {
    }

    /**
     * 管理端设置页数据源：依赖可用性、服务器列表（合并当前规则与该服的子服拓扑）与钱包货币选项。
     *
     * <p>子服拓扑（名称/是否默认入口/在线数）供前端配置子服规则；提供方不支持子服或该服不是群组服时
     * 返回空数组，前端不显示子服区块。
     */
    public Map<String, Object> options() {
        PlaytimePointsSettings settings = settings();
        boolean mcReady = minecraft.minecraftAvailable();
        boolean walletReady = wallet.walletAvailable();
        List<Map<String, Object>> servers = new ArrayList<>();
        if (mcReady) {
            for (MinecraftPort.ServerRef server : minecraft.servers()) {
                PlaytimePointsSettings.ServerRule rule = settings.ruleFor(server.id());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", server.id());
                row.put("name", server.name());
                row.put("configured", rule != null);
                row.put("weight", rule == null ? null : rule.weight());
                row.put("enabled", settings.enabledFor(server.id()));
                List<Map<String, Object>> subServers = new ArrayList<>();
                for (MinecraftPort.SubServerRef sub : minecraft.subServers(server.id())) {
                    Map<String, Object> subRow = new LinkedHashMap<>();
                    subRow.put("name", sub.name());
                    subRow.put("defaultServer", sub.defaultServer());
                    subRow.put("online", sub.online());
                    subRow.put("sensor", sub.sensor());
                    subServers.add(subRow);
                }
                row.put("subServers", subServers);
                servers.add(row);
            }
        }
        List<Map<String, Object>> assets = new ArrayList<>();
        if (walletReady) {
            for (WalletPort.AssetRef asset : wallet.assets()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("code", asset.code());
                row.put("name", asset.name());
                row.put("symbol", asset.symbol());
                row.put("scale", asset.scale());
                row.put("money", asset.money());
                row.put("enabled", asset.enabled());
                assets.add(row);
            }
        }
        Map<String, Object> dependencies = new LinkedHashMap<>();
        dependencies.put("minecraftServer", mcReady);
        dependencies.put("wallet", walletReady);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("settings", settings);
        payload.put("dependencies", dependencies);
        payload.put("servers", servers);
        payload.put("assets", assets);
        return payload;
    }

    public Map<String, Object> lastScan() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lastScanAt", lastScanAt == 0 ? null : lastScanAt);
        payload.put("message", lastScanMessage);
        return payload;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
