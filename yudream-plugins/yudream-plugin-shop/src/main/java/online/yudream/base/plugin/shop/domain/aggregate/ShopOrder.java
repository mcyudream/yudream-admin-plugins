package online.yudream.base.plugin.shop.domain.aggregate;

import online.yudream.base.plugin.shop.domain.enumerate.ShopOrderStatus;

import java.math.BigDecimal;

/**
 * 订单聚合。支付成功即创建（PAID），随后进入发货流程；
 * 商品字段为下单时快照，商品后续被编辑或删除不影响订单可读性。
 * deliveryVoucher 为卖家发货凭证（发货时提交）；买家核验后写入 verifiedAt 并完成订单。
 */
public record ShopOrder(String id, String productId, String productTitle, String productImage,
                        String productType, String buyerId, String sellerId, String assetCode,
                        BigDecimal price, int quantity, BigDecimal totalAmount, ShopOrderStatus status,
                        String walletTransactionId, String refundTransactionId, String deliveryMessage,
                        String deliveryContent, String deliveryVoucher, Long verifiedAt,
                        long createdAt, long paidAt, Long deliveredAt) {

    public static ShopOrder paid(String id, ShopProduct product, String buyerId, int quantity,
                                 BigDecimal totalAmount, String walletTransactionId) {
        long now = System.currentTimeMillis();
        return new ShopOrder(id, product.id(), product.title(), product.coverImage(), product.type(),
                buyerId, product.ownerId(), product.assetCode(), product.price(), quantity, totalAmount,
                ShopOrderStatus.PAID, walletTransactionId, null, "支付成功，等待发货", null, null, null,
                now, now, null);
    }

    public ShopOrder markDelivering(String message) {
        return copy(ShopOrderStatus.DELIVERING, refundTransactionId,
                message == null || message.isBlank() ? "发货处理中" : message.trim(), deliveryContent,
                deliveryVoucher, verifiedAt, deliveredAt);
    }

    public ShopOrder markDelivered(String message, String content) {
        return copy(ShopOrderStatus.DELIVERED, refundTransactionId,
                message == null || message.isBlank() ? "发货成功" : message.trim(), trimToNull(content),
                deliveryVoucher, verifiedAt, System.currentTimeMillis());
    }

    public ShopOrder markDeliveryFailed(String message) {
        return copy(ShopOrderStatus.DELIVERY_FAILED, refundTransactionId,
                message == null || message.isBlank() ? "发货失败" : message.trim(), deliveryContent,
                deliveryVoucher, verifiedAt, deliveredAt);
    }

    public ShopOrder markRefunded(String refundTxId, String message) {
        return copy(ShopOrderStatus.REFUNDED, trimToNull(refundTxId) == null ? refundTransactionId : refundTxId.trim(),
                message == null || message.isBlank() ? "已退款" : message.trim(), deliveryContent,
                deliveryVoucher, verifiedAt, deliveredAt);
    }

    /** 卖家提交发货凭证：订单进入待买家核验状态。 */
    public ShopOrder markVoucherSubmitted(String voucher) {
        return copy(ShopOrderStatus.DELIVERING, refundTransactionId, "卖家已发货，请买家核验发货凭证",
                deliveryContent, trimToNull(voucher), verifiedAt, deliveredAt);
    }

    /** 买家核验发货凭证：订单完成，之后不可再退款。 */
    public ShopOrder verifyDelivery() {
        long now = System.currentTimeMillis();
        return copy(ShopOrderStatus.DELIVERED, refundTransactionId, "买家已核验发货凭证，交易完成",
                deliveryContent, deliveryVoucher, now, now);
    }

    public boolean delivering() {
        return status == ShopOrderStatus.PAID || status == ShopOrderStatus.DELIVERING;
    }

    public boolean refundable() {
        return status != ShopOrderStatus.DELIVERED && status != ShopOrderStatus.REFUNDED
                && refundTransactionId == null;
    }

    private ShopOrder copy(ShopOrderStatus nextStatus, String nextRefundTxId, String nextMessage,
                           String nextContent, String nextVoucher, Long nextVerifiedAt, Long nextDeliveredAt) {
        return new ShopOrder(id, productId, productTitle, productImage, productType, buyerId, sellerId,
                assetCode, price, quantity, totalAmount, nextStatus, walletTransactionId, nextRefundTxId,
                nextMessage, nextContent, nextVoucher, nextVerifiedAt, createdAt, paidAt, nextDeliveredAt);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
