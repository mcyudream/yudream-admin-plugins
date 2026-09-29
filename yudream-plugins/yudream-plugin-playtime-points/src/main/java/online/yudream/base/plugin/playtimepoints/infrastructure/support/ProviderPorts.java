package online.yudream.base.plugin.playtimepoints.infrastructure.support;

import online.yudream.base.plugin.minecraft.api.PluginMinecraftService;
import online.yudream.base.plugin.playtimepoints.application.port.MinecraftPort;
import online.yudream.base.plugin.playtimepoints.application.port.ProjectProgressPort;
import online.yudream.base.plugin.playtimepoints.application.port.SkinPort;
import online.yudream.base.plugin.playtimepoints.application.port.WalletPort;
import online.yudream.base.plugin.projectprogress.api.PluginProjectCheckInAcceptedListener;
import online.yudream.base.plugin.projectprogress.api.PluginProjectCheckInReward;
import online.yudream.base.plugin.projectprogress.api.PluginProjectProgressService;
import online.yudream.base.plugin.projectprogress.api.PluginProjectSummary;
import online.yudream.base.plugin.skin.api.PluginSkinService;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.wallet.api.PluginWalletChangeRequest;
import online.yudream.base.plugin.wallet.api.PluginWalletService;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 软依赖端口实现：对 minecraft-server / yudream-wallet / yudream-skin / project-progress 四个 provider API 类型
 * 的引用全部集中在本类。先经 dependencyAvailable 做无类检查，通过后才解析 API 类字面量，
 * 避免 provider 缺失/禁用时抛 NoClassDefFoundError；API 对象每次现取，不跨 disable/reload 缓存。
 */
public final class ProviderPorts implements MinecraftPort, WalletPort, SkinPort, ProjectProgressPort {

    public static final String MINECRAFT_PLUGIN = "minecraft-server";
    public static final String WALLET_PLUGIN = "yudream-wallet";
    public static final String SKIN_PLUGIN = "yudream-skin";
    public static final String PROJECT_PROGRESS_PLUGIN = "project-progress";

    private final PluginContext context;

    public ProviderPorts(PluginContext context) {
        this.context = context;
    }

    /* ---------- minecraft-server ---------- */

    private Optional<PluginMinecraftService> minecraft() {
        if (context == null || !context.dependencyAvailable(MINECRAFT_PLUGIN)) {
            return Optional.empty();
        }
        try {
            return context.service(MINECRAFT_PLUGIN, PluginMinecraftService.class);
        } catch (LinkageError e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean minecraftAvailable() {
        return minecraft().isPresent();
    }

    @Override
    public List<ServerRef> servers() {
        return minecraft()
                .map(service -> safe(() -> service.minecraftServers(false)).orElse(List.of()).stream()
                        .map(server -> new ServerRef(server.id(), server.name()))
                        .toList())
                .orElse(List.of());
    }

    @Override
    public List<ActivityRef> playerActivities(String serverId, int page, int size) {
        return minecraft()
                .map(service -> safe(() -> service.minecraftPlayerActivities(serverId, page, size)).orElse(List.of()).stream()
                        .map(activity -> new ActivityRef(activity.serverId(), activity.playerId(), activity.playerName(),
                                activity.online(), activity.totalOnlineMillis(), activity.totalAfkMillis(), activity.lastQuitAt()))
                        .toList())
                .orElse(List.of());
    }

    /**
     * 子服拓扑。SPI 里这两个读取方法都有 default 降级实现：旧版本提供方没有覆盖它们时返回空列表，
     * 消费方按「没有子服维度」处理，因此这里不需要额外的版本判断，也不缓存 API 对象。
     */
    @Override
    public List<SubServerRef> subServers(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return List.of();
        }
        return minecraft()
                .map(service -> safe(() -> service.minecraftSubServers(serverId)).orElse(List.of()).stream()
                        .map(sub -> new SubServerRef(sub.name(), sub.defaultServer(), sub.online(), sub.sensor()))
                        .toList())
                .orElse(List.of());
    }

    @Override
    public List<SubActivityRef> subServerActivities(String serverId, String playerId) {
        if (serverId == null || serverId.isBlank() || playerId == null || playerId.isBlank()) {
            return List.of();
        }
        return minecraft()
                .map(service -> safe(() -> service.minecraftSubServerActivities(serverId, playerId)).orElse(List.of()).stream()
                        .map(sub -> new SubActivityRef(sub.subServer(), sub.online(),
                                sub.totalOnlineMillis(), sub.totalAfkMillis()))
                        .toList())
                .orElse(List.of());
    }

    /* ---------- yudream-wallet ---------- */

    private Optional<PluginWalletService> wallet() {
        if (context == null || !context.dependencyAvailable(WALLET_PLUGIN)) {
            return Optional.empty();
        }
        try {
            return context.service(WALLET_PLUGIN, PluginWalletService.class);
        } catch (LinkageError e) {
            return Optional.empty();
        }
    }

    // MinecraftPort 与 WalletPort 是同端口类的两个视角，可用性方法分别命名为 minecraftAvailable / walletAvailable

    @Override
    public boolean walletAvailable() {
        return wallet().isPresent();
    }

    @Override
    public List<AssetRef> assets() {
        return wallet()
                .map(service -> safe(service::assets).orElse(List.of()).stream()
                        .map(asset -> new AssetRef(asset.code(), asset.name(), asset.symbol(), asset.scale(),
                                asset.money(), asset.enabled()))
                        .toList())
                .orElse(List.of());
    }

    @Override
    public Optional<AssetRef> findAsset(String assetCode) {
        return wallet().flatMap(service -> safe(() -> service.findAsset(assetCode)))
                .flatMap(asset -> asset.map(value -> new AssetRef(value.code(), value.name(), value.symbol(),
                        value.scale(), value.money(), value.enabled())));
    }

    @Override
    public Optional<String> balance(String userId, String assetCode) {
        Optional<PluginWalletService> service = wallet();
        if (service.isEmpty()) {
            return Optional.empty();
        }
        return safe(() -> service.get().balance(userId, assetCode)).flatMap(balance -> {
            try {
                return Optional.of(balance.balance().toPlainString());
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        });
    }

    @Override
    public void credit(String userId, String assetCode, java.math.BigDecimal amount, String businessNo, String remark) {
        PluginWalletService service = wallet().orElseThrow(() -> new IllegalStateException("钱包插件不可用"));
        service.credit(new PluginWalletChangeRequest(userId, assetCode, amount, businessNo, remark));
    }

    /* ---------- yudream-skin ---------- */

    private Optional<PluginSkinService> skin() {
        if (context == null || !context.dependencyAvailable(SKIN_PLUGIN)) {
            return Optional.empty();
        }
        try {
            return context.service(SKIN_PLUGIN, PluginSkinService.class);
        } catch (LinkageError e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> resolveOwnerId(String playerId, String playerName) {
        PluginSkinService service = skin().orElse(null);
        if (service == null) {
            return Optional.empty();
        }
        Optional<String> owner = Optional.empty();
        if (playerId != null && !playerId.isBlank()) {
            owner = ownerOf(service, () -> service.findProfileByUuid(playerId));
            if (owner.isEmpty() && playerId.trim().matches("[0-9a-fA-F]{32}")) {
                owner = ownerOf(service, () -> service.findProfileByUuid(dashed(playerId.trim())));
            }
        }
        if (owner.isEmpty() && playerName != null && !playerName.isBlank()) {
            owner = ownerOf(service, () -> service.findProfileByName(playerName.trim()));
        }
        return owner;
    }

    private Optional<String> ownerOf(PluginSkinService service, Supplier<Optional<online.yudream.base.plugin.skin.api.PluginSkinProfile>> lookup) {
        return safe(lookup).flatMap(profile -> {
            String ownerId = profile.map(online.yudream.base.plugin.skin.api.PluginSkinProfile::ownerId).orElse(null);
            return ownerId == null || ownerId.isBlank() ? Optional.empty() : Optional.of(ownerId);
        });
    }

    /** 32 位无横线 uuid → 8-4-4-4-12 标准格式。 */
    private static String dashed(String uuid) {
        String lower = uuid.toLowerCase(Locale.ROOT);
        return lower.substring(0, 8) + "-" + lower.substring(8, 12) + "-" + lower.substring(12, 16)
                + "-" + lower.substring(16, 20) + "-" + lower.substring(20);
    }

    private static <T> Optional<T> safe(Supplier<T> call) {
        try {
            return Optional.ofNullable(call.get());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /* ---------- project-progress ---------- */

    /**
     * provider API 对象：dependencyAvailable 通过后才解析 API 类字面量；旧版 provider 缺类时按不可用降级。
     *
     * <p>每次现取、不缓存：provider 的 disable/reload 会让旧对象失效。</p>
     */
    private Optional<PluginProjectProgressService> projectProgress() {
        if (context == null || !context.dependencyAvailable(PROJECT_PROGRESS_PLUGIN)) {
            return Optional.empty();
        }
        try {
            return context.service(PROJECT_PROGRESS_PLUGIN, PluginProjectProgressService.class);
        } catch (LinkageError e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean available() {
        return projectProgress().isPresent();
    }

    @Override
    public List<RewardRef> acceptedCheckIns(long sinceAcceptedAt, int page, int size) {
        Optional<PluginProjectProgressService> service = projectProgress();
        if (service.isEmpty()) {
            return List.of();
        }
        // 旧版 provider 的两个方法都有 default 空实现，混跑时拿到的是空列表而不是 AbstractMethodError。
        // 映射也放在同一个保护里：provider 1.5.0 的 PluginProjectCheckInReward 没有 effectiveMillis()，
        // 读取新字段会抛 NoSuchMethodError（LinkageError），必须按「本次拉不到」降级，
        // 否则会一路冒到调度线程上把整轮任务打断。provider 内部异常同样按降级处理。
        return safeApi(() -> service.get().acceptedCheckIns(sinceAcceptedAt, page, size).stream()
                .map(ProviderPorts::toRewardRef)
                .toList(), List.of());
    }

    @Override
    public List<ProjectRef> projects() {
        Optional<PluginProjectProgressService> service = projectProgress();
        if (service.isEmpty()) {
            return List.of();
        }
        List<PluginProjectSummary> rows = safeApi(service.get()::projects, List.of());
        return rows.stream().map(row -> new ProjectRef(row.id(), row.name(), row.enabled())).toList();
    }

    @Override
    public void registerAcceptedListener(Consumer<RewardRef> listener) {
        if (listener == null) {
            return;
        }
        // provider 未安装/未启用/API 不可解析：静默跳过（softdepend 降级），不抛错。
        if (projectProgress().isEmpty()) {
            return;
        }
        try {
            registerProjectProgressListener(listener);
        } catch (RuntimeException | LinkageError ignored) {
            // 旧版 provider 没有该扩展点，或注册失败：按「没有实时回调」降级，拉取仍然可用。
        }
    }

    /**
     * 单独一个方法承载对 provider 回调类型的类字面量引用：只有确认 provider 可用之后才会被调用，
     * 因此类解析失败（provider 旧版缺该类型）不会污染 {@link #registerAcceptedListener} 的正常路径。
     */
    private void registerProjectProgressListener(Consumer<RewardRef> listener) {
        if (context == null) {
            return;
        }
        context.registerExtension(PluginProjectCheckInAcceptedListener.class,
                (PluginProjectCheckInReward reward) -> {
                    if (reward == null) {
                        return;
                    }
                    try {
                        listener.accept(toRewardRef(reward));
                    } catch (RuntimeException | LinkageError ignored) {
                        // 消费方自己的失败不能影响 provider 的验收用例（provider 侧也会再兜一层）
                    }
                });
    }

    private static RewardRef toRewardRef(PluginProjectCheckInReward reward) {
        return new RewardRef(reward.checkInId(), reward.detailId(), reward.detailTitle(), reward.projectId(),
                reward.projectName(), reward.userId(), reward.checkInType(), reward.checkInAt(),
                reward.acceptedAt(), reward.acceptedBy(), reward.effectiveMillis());
    }

    /** 调用 provider API：运行时异常与类解析错误都按「本次调用失败」降级为 fallback。 */
    private static <T> T safeApi(Supplier<T> call, T fallback) {
        try {
            T value = call.get();
            return value == null ? fallback : value;
        } catch (RuntimeException | LinkageError e) {
            return fallback;
        }
    }
}
