package online.yudream.base.plugin.shop.api;

import java.math.BigDecimal;

/**
 * 订单跨插件视图。
 */
public record PluginShopOrder(String id, String productId, String productTitle, String productType,
                              String buyerId, String sellerId, String assetCode, BigDecimal price,
                              int quantity, BigDecimal totalAmount, String status,
                              String walletTransactionId, String deliveryMessage, String deliveryContent,
                              long createdAt, Long deliveredAt) {
}
