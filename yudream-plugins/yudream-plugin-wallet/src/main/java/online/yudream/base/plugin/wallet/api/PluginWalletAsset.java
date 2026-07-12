package online.yudream.base.plugin.wallet.api;

import java.math.BigDecimal;

public record PluginWalletAsset(String code, String name, String symbol, int scale, boolean money, boolean enabled,
                                boolean transferEnabled, BigDecimal minTransferAmount) {
}
