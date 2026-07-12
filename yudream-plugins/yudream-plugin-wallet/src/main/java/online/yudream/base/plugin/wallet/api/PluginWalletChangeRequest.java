package online.yudream.base.plugin.wallet.api;

import java.math.BigDecimal;

public record PluginWalletChangeRequest(String userId, String assetCode, BigDecimal amount, String businessNo, String remark) {
}
