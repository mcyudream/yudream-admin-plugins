package online.yudream.base.plugin.shop.infrastructure.wallet;

import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.wallet.api.PluginWalletAsset;
import online.yudream.base.plugin.wallet.api.PluginWalletChangeRequest;
import online.yudream.base.plugin.wallet.api.PluginWalletService;
import online.yudream.base.plugin.wallet.api.PluginWalletTransaction;
import online.yudream.base.plugin.wallet.api.PluginWalletTransferRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

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

    /**
     * 资产小数位（交易手续费按它四舍五入）；钱包不可用或货币不存在返回 empty，由调用方决定回退口径。
     */
    public OptionalInt assetScale(String assetCode) {
        if (assetCode == null || assetCode.isBlank() || !available()) {
            return OptionalInt.empty();
        }
        try {
            return service()
                    .flatMap(wallet -> wallet.findAsset(assetCode.trim()))
                    .map(asset -> OptionalInt.of(Math.max(asset.scale(), 0)))
                    .orElse(OptionalInt.empty());
        } catch (RuntimeException | LinkageError ignored) {
            return OptionalInt.empty();
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

    /**
     * 消耗式结算：从买家账户扣减资产、不产生收款方（积分兑换类商品）。
     *
     * <p>走钱包 {@code debit(PluginWalletChangeRequest)}；钱包按 businessNo 幂等，同一单号重复调用只会扣一次
     * 并返回首笔流水。钱包不可用抛 IllegalStateException，余额不足等业务异常由钱包原样抛出。
     */
    public WalletPayment burn(String userId, String assetCode, BigDecimal amount, String businessNo, String remark) {
        PluginWalletService wallet = service()
                .orElseThrow(() -> new IllegalStateException("钱包插件未启用，暂时无法完成扣减"));
        PluginWalletTransaction transaction = wallet.debit(new PluginWalletChangeRequest(
                userId, assetCode, amount, businessNo, remark));
        return new WalletPayment(transaction.id());
    }

    /**
     * 原路退款：把钱加回买家账户（走钱包 {@code credit(PluginWalletChangeRequest)}）。
     *
     * <p>消耗式结算的订单没有卖家可退，只能退回买家；businessNo 同样是幂等键，重复退款不会重复入账。
     */
    public WalletPayment refund(String userId, String assetCode, BigDecimal amount, String businessNo, String remark) {
        PluginWalletService wallet = service()
                .orElseThrow(() -> new IllegalStateException("钱包插件未启用，暂时无法完成退款"));
        PluginWalletTransaction transaction = wallet.credit(new PluginWalletChangeRequest(
                userId, assetCode, amount, businessNo, remark));
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
