package online.yudream.base.plugin.wallet.api;

import java.util.List;
import java.util.Optional;

public interface PluginWalletService {
    String MONEY_ASSET_CODE = "CNY";
    List<PluginWalletAsset> assets();
    Optional<PluginWalletAsset> findAsset(String assetCode);
    PluginWalletAsset ensureAsset(PluginWalletAsset asset);
    List<PluginWalletBalance> balances(String userId);
    default List<PluginWalletBalance> listBalances(String assetCode, int page, int size) {
        throw new UnsupportedOperationException("Current wallet plugin does not support balance queries.");
    }
    PluginWalletBalance balance(String userId, String assetCode);
    PluginWalletTransaction credit(PluginWalletChangeRequest request);
    PluginWalletTransaction debit(PluginWalletChangeRequest request);
    PluginWalletTransaction transfer(PluginWalletTransferRequest request);
    default List<PluginWalletTransaction> transactions(PluginWalletTransactionQuery query) {
        throw new UnsupportedOperationException("Current wallet plugin does not support transaction queries.");
    }
    Optional<PluginWalletTransaction> findTransactionByBusinessNo(String businessNo);
}
