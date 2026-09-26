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
 * <p>可靠性设计：
 * <ul>
 *   <li>首次见到某玩家只建基线不补发历史，避免安装插件后一次性发放存量时长；</li>
 *   <li>玩家未绑定网站账号、钱包不可用或入账失败时不推进基线，下轮自动重试补发；</li>
 *   <li>零头（不足 1 分的小数）持久化在基线上，凑满 1 分才入账，避免每次退出抹掉小数；</li>
 *   <li>businessNo 由 服务器+玩家+退出时间 派生，钱包侧幂等，重复结算不会重复入账。</li>
 * </ul>
 */
public class PlaytimePointsAppService {

    private static final int ACTIVITY_PAGE_SIZE = 100;
    private static final int MAX_ACTIVITY_PAGES = 50;
    private static final int MAX_PROBLEMS = 3;

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
        if (wallet.walletAvailable()) {
            boolean assetExists = wallet.findAsset(settings.assetCode()).filter(WalletPort.AssetRef::enabled).isPresent();
            if (!assetExists) {
                throw new IllegalArgumentException("钱包中不存在已启用的货币类型：" + settings.assetCode());
            }
        }
        for (Map.Entry<String, PlaytimePointsSettings.ServerRule> entry : settings.servers().entrySet()) {
            BigDecimal weight;
            try {
                weight = new BigDecimal(entry.getValue().weight() == null ? "" : entry.getValue().weight().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("服务器「" + entry.getKey() + "」的权重不是合法数字");
            }
            if (weight.signum() < 0 || weight.compareTo(new BigDecimal("999")) > 0 || weight.scale() > 2) {
                throw new IllegalArgumentException("服务器「" + entry.getKey() + "」的权重必须在 0-999 之间且最多两位小数");
            }
        }
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

    private Outcome settleOne(MinecraftPort.ServerRef server, MinecraftPort.ActivityRef activity,
                              PlaytimePointsSettings settings) {
        long now = clock.getAsLong();
        String stateId = server.id() + ":" + activity.playerId();
        SettlementState state = repository.findState(stateId).orElse(null);
        if (state == null) {
            // 首次见到该玩家：以当前累计为基线，不补发安装前的历史时长
            repository.saveState(new SettlementState(stateId, server.id(), activity.playerId(), activity.playerName(),
                    activity.totalOnlineMillis(), activity.totalAfkMillis(), activity.lastQuitAt(),
                    PointsCalculator.parseCarry(null).toPlainString(), now));
            return Outcome.SILENT;
        }
        long settledQuitAt = state.settledQuitAt() == null ? 0L : state.settledQuitAt();
        Long quitAt = activity.lastQuitAt();
        if (quitAt == null || quitAt <= settledQuitAt) {
            return Outcome.SILENT;
        }
        long deltaOnline = Math.max(0L, activity.totalOnlineMillis() - state.settledOnlineMillis());
        long deltaAfk = Math.max(0L, activity.totalAfkMillis() - state.settledAfkMillis());
        long effective = settings.subtractAfk() ? Math.max(0L, deltaOnline - deltaAfk) : deltaOnline;
        if (effective <= 0) {
            advanceState(state, activity, activity.totalOnlineMillis(), activity.totalAfkMillis(), quitAt,
                    state.carryPoints(), now);
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
        BigDecimal weight = settings.weightFor(server.id());
        BigDecimal points = PointsCalculator.points(effective, weight, settings.minutesPerPoint());
        BigDecimal[] split = PointsCalculator.splitCredit(PointsCalculator.parseCarry(state.carryPoints()), points);
        BigDecimal credit = split[0];
        BigDecimal newCarry = split[1];
        String businessNo = null;
        if (credit.signum() > 0) {
            businessNo = businessNo(server.id(), activity.playerId(), quitAt);
            String remark = "在线时长积分：" + server.name() + " · " + activity.playerName()
                    + " · 有效 " + effective / 60_000 + " 分钟";
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
                deltaOnline, deltaAfk, effective,
                PointsCalculator.plain(weight), PointsCalculator.plain(points), PointsCalculator.plain(credit),
                PointsCalculator.plain(newCarry), assetCode, businessNo, now);
        repository.saveSettlement(settlement);
        advanceState(state, activity, activity.totalOnlineMillis(), activity.totalAfkMillis(), quitAt,
                newCarry.toPlainString(), now);
        return new Outcome(true, credit.signum() > 0, null);
    }

    private void advanceState(SettlementState state, MinecraftPort.ActivityRef activity,
                              long settledOnlineMillis, long settledAfkMillis, Long settledQuitAt,
                              String carryPoints, long now) {
        repository.saveState(new SettlementState(state.id(), state.serverId(), state.playerId(),
                activity.playerName(), settledOnlineMillis, settledAfkMillis, settledQuitAt, carryPoints, now));
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

    /** 我的积分汇总：累计积分、有效时长、按服务器分组与钱包余额。 */
    public Map<String, Object> mySummary(String userId) {
        PlaytimePointsSettings settings = settings();
        List<PointsSettlement> all = repository.settlements(null, userId, null, 1, Integer.MAX_VALUE).records();
        BigDecimal totalPoints = BigDecimal.ZERO;
        long totalEffectiveMillis = 0;
        Map<String, int[]> serverSessions = new LinkedHashMap<>();
        Map<String, long[]> serverMillis = new LinkedHashMap<>();
        Map<String, BigDecimal> serverPoints = new LinkedHashMap<>();
        Map<String, String> serverNames = new LinkedHashMap<>();
        for (PointsSettlement settlement : all) {
            totalPoints = totalPoints.add(PointsCalculator.parseCarry(settlement.credited()));
            totalEffectiveMillis += Math.max(0L, settlement.effectiveMillis());
            serverSessions.computeIfAbsent(settlement.serverId(), key -> new int[1])[0]++;
            serverMillis.computeIfAbsent(settlement.serverId(), key -> new long[1])[0] += Math.max(0L, settlement.effectiveMillis());
            serverPoints.merge(settlement.serverId(), PointsCalculator.parseCarry(settlement.points()), BigDecimal::add);
            serverNames.put(settlement.serverId(), settlement.serverName());
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
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("assetCode", settings.assetCode());
        summary.put("totalPoints", PointsCalculator.plain(totalPoints));
        summary.put("totalEffectiveMinutes", totalEffectiveMillis / 60_000);
        summary.put("sessions", all.size());
        summary.put("byServer", byServer);
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

    /** 管理端设置页数据源：依赖可用性、服务器列表（合并当前规则）与钱包货币选项。 */
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
