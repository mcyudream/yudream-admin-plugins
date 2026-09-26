package online.yudream.base.plugin.shop.interfaces.res;

import java.math.BigDecimal;

/**
 * 订单视图。deliveryContent 仅买家本人与管理员可见，卖家视图不含；
 * deliveryVoucher（发货凭证）买卖双方与管理员均可见。
 */
public record ShopOrderRes(String id, String productId, String productTitle, String productImage,
                           String productType, String productTypeDisplayName, ShopUserRes buyer,
                           ShopUserRes seller, String assetCode, BigDecimal price, int quantity,
                           BigDecimal totalAmount, String status, String statusText, String walletTransactionId,
                           String refundTransactionId, String deliveryMessage, String deliveryContent,
                           String deliveryVoucher, boolean voucherVerified,
                           long createdAt, long paidAt, Long deliveredAt) {
}
