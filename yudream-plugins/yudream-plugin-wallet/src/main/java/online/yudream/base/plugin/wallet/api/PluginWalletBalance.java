package online.yudream.base.plugin.wallet.api;

import java.math.BigDecimal;

public record PluginWalletBalance(String userId, String assetCode, BigDecimal balance, long updatedAt) {
}
