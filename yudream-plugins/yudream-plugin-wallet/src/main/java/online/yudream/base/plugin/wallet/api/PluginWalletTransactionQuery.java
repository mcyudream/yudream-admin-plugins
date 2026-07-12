package online.yudream.base.plugin.wallet.api;

public record PluginWalletTransactionQuery(String assetCode, String type, String source, String userId, Long startAt,
                                           Long endAt, int page, int size) {
}
