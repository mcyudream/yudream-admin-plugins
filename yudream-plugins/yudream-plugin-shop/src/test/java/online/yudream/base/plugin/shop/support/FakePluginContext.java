package online.yudream.base.plugin.shop.support;

import online.yudream.base.plugin.shop.api.ShopProductTypeHandler;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.wallet.api.PluginWalletService;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

/**
 * 测试用插件上下文：只实现钱包软依赖发现与商品类型扩展点所需的最小行为
 * （{@code dependencyAvailable} / {@code service} / {@code extensions}），其余 SPI 方法调用即报错，
 * 避免为测试造一个庞大的上下文实现。
 */
public final class FakePluginContext {

    private static final String WALLET_PLUGIN_CODE = "yudream-wallet";

    private FakePluginContext() {
    }

    /** 钱包依赖已启用且可取得服务（无扩展商品类型）。 */
    public static PluginContext withWallet(PluginWalletService wallet) {
        return create(wallet, true, List.of());
    }

    /** 钱包依赖已启用，并注册给定的商品类型扩展点（用于制造发货失败等场景）。 */
    public static PluginContext withWallet(PluginWalletService wallet, List<ShopProductTypeHandler> handlers) {
        return create(wallet, true, handlers);
    }

    /** 钱包插件未启用（软依赖降级路径）。 */
    public static PluginContext withoutWallet() {
        return create(null, false, List.of());
    }

    private static PluginContext create(PluginWalletService wallet, boolean dependencyAvailable,
                                        List<ShopProductTypeHandler> handlers) {
        return (PluginContext) Proxy.newProxyInstance(FakePluginContext.class.getClassLoader(),
                new Class<?>[]{PluginContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "dependencyAvailable" ->
                            dependencyAvailable && WALLET_PLUGIN_CODE.equals(args[0]);
                    case "service" -> WALLET_PLUGIN_CODE.equals(args[0]) && wallet != null
                            ? Optional.of(wallet) : Optional.empty();
                    case "extensions" -> args[0] == ShopProductTypeHandler.class ? handlers : List.of();
                    case "pluginCode" -> "shop-test";
                    case "toString" -> "FakePluginContext";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("测试上下文未实现：" + method.getName());
                });
    }
}
