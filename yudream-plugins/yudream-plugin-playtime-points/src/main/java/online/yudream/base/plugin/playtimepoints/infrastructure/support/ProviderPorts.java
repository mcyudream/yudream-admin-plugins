package online.yudream.base.plugin.playtimepoints.infrastructure.support;

import online.yudream.base.plugin.minecraft.api.PluginMinecraftService;
import online.yudream.base.plugin.playtimepoints.application.port.MinecraftPort;
import online.yudream.base.plugin.playtimepoints.application.port.SkinPort;
import online.yudream.base.plugin.playtimepoints.application.port.WalletPort;
import online.yudream.base.plugin.skin.api.PluginSkinService;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.wallet.api.PluginWalletChangeRequest;
import online.yudream.base.plugin.wallet.api.PluginWalletService;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 软依赖端口实现：对 minecraft-server / yudream-wallet / yudream-skin 三个 provider API 类型
 * 的引用全部集中在本类。先经 dependencyAvailable 做无类检查，通过后才解析 API 类字面量，
 * 避免 provider 缺失/禁用时抛 NoClassDefFoundError；API 对象每次现取，不跨 disable/reload 缓存。
 */
public final class ProviderPorts implements MinecraftPort, WalletPort, SkinPort {

    public static final String MINECRAFT_PLUGIN = "minecraft-server";
    public static final String WALLET_PLUGIN = "yudream-wallet";
    public static final String SKIN_PLUGIN = "yudream-skin";

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
}
