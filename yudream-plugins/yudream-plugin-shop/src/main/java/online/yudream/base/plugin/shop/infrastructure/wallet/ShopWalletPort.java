package online.yudream.base.plugin.shop.infrastructure.wallet;

import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.wallet.api.PluginWalletAsset;
import online.yudream.base.plugin.wallet.api.PluginWalletService;
import online.yudream.base.plugin.wallet.api.PluginWalletTransaction;
import online.yudream.base.plugin.wallet.api.PluginWalletTransferRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 钱包软依赖端口：钱包 API 类型的所有引用都隔离在本类中。
 * 钱包插件未安装/未启用时 available() 为 false，商店其余功能（浏览、上架）不受影响，
 * 仅购买链路显式报错降级。方法每次调用现取服务，不跨 provider disable/reload 缓存。
 */
public class ShopWalletPort {

    public record WalletAsset(String code, String name, String symbol, int scale) {
    }

    public record WalletPayment(String transactionId) {
    }

    private static final String WALLET_PLUGIN_CODE = "yudream-wallet";
    private static final String WALLET_SERVICE_CLASS = "online.yudream.base.plugin.wallet.api.PluginWalletService";

    private final PluginContext context;

    private ShopWalletPort(PluginContext context) {
        this.context = context;
    }

    public static ShopWalletPort create(PluginContext context) {
        return new ShopWalletPort(context);
    }

    public boolean available() {
        try {
            Class.forName(WALLET_SERVICE_CLASS, false, getClass().getClassLoader());
            return context.dependencyAvailable(WALLET_PLUGIN_CODE) && service().isPresent();
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    /** 钱包中已启用的资产（上架可选货币）；钱包不可用时返回空表。 */
    public List<WalletAsset> enabledAssets() {
        if (!available()) {
            return List.of();
        }
        try {
            return service().map(wallet -> wallet.assets().stream()
                            .filter(PluginWalletAsset::enabled)
                            .map(asset -> new WalletAsset(asset.code(), asset.name(), asset.symbol(), asset.scale()))
                            .toList())
                    .orElse(List.of());
        } catch (RuntimeException | LinkageError ignored) {
            return List.of();
        }
    }

    public boolean assetEnabled(String assetCode) {
        if (assetCode == null || assetCode.isBlank() || !available()) {
            return false;
        }
        try {
            return service()
                    .flatMap(wallet -> wallet.findAsset(assetCode.trim()))
                    .map(PluginWalletAsset::enabled)
                    .orElse(false);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** 买家付款给卖家；钱包不可用抛 IllegalStateException，余额不足等业务异常由钱包原样抛出。 */
    public WalletPayment transfer(String fromUserId, String toUserId, String assetCode, BigDecimal amount,
                                  String businessNo, String remark) {
        PluginWalletService wallet = service()
                .orElseThrow(() -> new IllegalStateException("钱包插件未启用，暂时无法完成支付"));
        PluginWalletTransaction transaction = wallet.transfer(new PluginWalletTransferRequest(
                fromUserId, toUserId, assetCode, amount, businessNo, remark));
        return new WalletPayment(transaction.id());
    }

    /** 查询用户某货币余额；钱包不可用或查询失败返回 empty。 */
    public Optional<BigDecimal> balance(String userId, String assetCode) {
        if (!available()) {
            return Optional.empty();
        }
        try {
            return service().map(wallet -> wallet.balance(userId, assetCode).balance());
        } catch (RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    private Optional<PluginWalletService> service() {
        try {
            return context.service(WALLET_PLUGIN_CODE, PluginWalletService.class);
        } catch (RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }
}
