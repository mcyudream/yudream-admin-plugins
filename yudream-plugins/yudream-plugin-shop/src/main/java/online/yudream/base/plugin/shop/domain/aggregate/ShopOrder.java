package online.yudream.base.plugin.shop.domain.aggregate;

import online.yudream.base.plugin.shop.domain.enumerate.ShopOrderStatus;
import online.yudream.base.plugin.shop.domain.enumerate.ShopSettlement;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单聚合。支付成功即创建（PAID），随后进入发货流程；
 * 商品字段为下单时快照，商品后续被编辑或删除不影响订单可读性。
 *
 * <p>settlement 决定钱怎么走：{@link ShopSettlement#SELLER} 由钱包把货款从买家转给卖家（默认，
 * 历史文档缺该字段时按此处理）；{@link ShopSettlement#BURN} 只从买家账户扣减、不产生收款方，
 * 用于积分兑换类商品。两种方式的退款都原路退回买家。
 *
 * <p>发货凭证分两部分：deliveryVoucher 为文本说明（卡密、链接、联系方式等），deliveryProofs 为
 * 最多 {@link #MAX_DELIVERY_PROOFS} 张凭证图片（积分兑换发货时管理员上传的实拍/快递单等）。
 * 买家核验后写入 verifiedAt 并完成订单；买家也可在发货前取消（CANCELLED），由应用层退款并回滚库存。
 */
public record ShopOrder(String id, String productId, String productTitle, String productImage,
                        String productType, ShopSettlement settlement, String buyerId, String sellerId,
                        String assetCode, BigDecimal price, int quantity, BigDecimal totalAmount,
                        String variantId, String variantName,
                        ShopOrderStatus status, String walletTransactionId, String refundTransactionId,
                        String deliveryMessage, String deliveryContent, String deliveryVoucher,
                        List<String> deliveryProofs, Long verifiedAt, long createdAt, long paidAt,
                        Long deliveredAt) {

    /** 单笔订单可提交的凭证图片上限。 */
    public static final int MAX_DELIVERY_PROOFS = 6;
    /** 文本发货凭证长度上限。 */
    public static final int MAX_VOUCHER_LENGTH = 500;
    /** 单张凭证图片地址长度上限。 */
    public static final int MAX_PROOF_LENGTH = 500;

    public ShopOrder {
        settlement = settlement == null ? ShopSettlement.SELLER : settlement;
        deliveryProofs = normalizeProofs(deliveryProofs);
    }

    public static ShopOrder paid(String id, ShopProduct product, String buyerId, int quantity,
                                 BigDecimal totalAmount, String walletTransactionId) {
        return paid(id, product, null, buyerId, quantity, totalAmount, walletTransactionId, ShopSettlement.SELLER);
    }

    public static ShopOrder paid(String id, ShopProduct product, String buyerId, int quantity,
                                 BigDecimal totalAmount, String walletTransactionId, ShopSettlement settlement) {
        return paid(id, product, null, buyerId, quantity, totalAmount, walletTransactionId, settlement);
    }

    /**
     * 下单快照。variant 为选中的商品型号（可为 null）；型号价即该订单单价，
     * 因此 price 取型号价而不是商品的展示价（商品价在有型号时是最低价）。
     */
    public static ShopOrder paid(String id, ShopProduct product, ShopVariant variant, String buyerId, int quantity,
                                 BigDecimal totalAmount, String walletTransactionId, ShopSettlement settlement) {
        long now = System.currentTimeMillis();
        BigDecimal unitPrice = variant == null ? product.price() : variant.price();
        return new ShopOrder(id, product.id(), product.title(), product.coverImage(), product.type(),
                settlement, buyerId, product.ownerId(), product.assetCode(), unitPrice, quantity, totalAmount,
                variant == null ? null : variant.id(), variant == null ? null : variant.name(),
                ShopOrderStatus.PAID, walletTransactionId, null, "支付成功，等待发货", null, null, List.of(), null,
                now, now, null);
    }

    public ShopOrder markDelivering(String message) {
        return copy(ShopOrderStatus.DELIVERING, refundTransactionId,
                message == null || message.isBlank() ? "发货处理中" : message.trim(), deliveryContent,
                deliveryVoucher, deliveryProofs, verifiedAt, deliveredAt);
    }

    public ShopOrder markDelivered(String message, String content) {
        return copy(ShopOrderStatus.DELIVERED, refundTransactionId,
                message == null || message.isBlank() ? "发货成功" : message.trim(), trimToNull(content),
                deliveryVoucher, deliveryProofs, verifiedAt, System.currentTimeMillis());
    }

    public ShopOrder markDeliveryFailed(String message) {
        return copy(ShopOrderStatus.DELIVERY_FAILED, refundTransactionId,
                message == null || message.isBlank() ? "发货失败" : message.trim(), deliveryContent,
                deliveryVoucher, deliveryProofs, verifiedAt, deliveredAt);
    }

    public ShopOrder markRefunded(String refundTxId, String message) {
        return copy(ShopOrderStatus.REFUNDED, trimToNull(refundTxId) == null ? refundTransactionId : refundTxId.trim(),
                message == null || message.isBlank() ? "已退款" : message.trim(), deliveryContent,
                deliveryVoucher, deliveryProofs, verifiedAt, deliveredAt);
    }

    /** 卖家提交发货凭证（仅文本）：订单进入待买家核验状态。 */
    public ShopOrder markVoucherSubmitted(String voucher) {
        return markDeliverySubmitted(voucher, deliveryProofs);
    }

    /** 卖家/管理员提交发货凭证（文本 + 最多 6 张图片）：订单进入待买家核验状态。 */
    public ShopOrder markDeliverySubmitted(String voucher, List<String> proofs) {
        List<String> safeProofs = normalizeProofs(proofs);
        String safeVoucher = normalizeVoucher(voucher);
        if (safeVoucher == null && safeProofs.isEmpty()) {
            throw new IllegalArgumentException("发货凭证不能为空");
        }
        return copy(ShopOrderStatus.DELIVERING, refundTransactionId, "卖家已发货，请买家核验发货凭证",
                deliveryContent, safeVoucher, safeProofs, verifiedAt, deliveredAt);
    }

    /** 买家核验发货凭证：订单完成，之后不可再退款。 */
    public ShopOrder verifyDelivery() {
        long now = System.currentTimeMillis();
        return copy(ShopOrderStatus.DELIVERED, refundTransactionId, "买家已核验发货凭证，交易完成",
                deliveryContent, deliveryVoucher, deliveryProofs, now, now);
    }

    /**
     * 买家在发货前取消订单。已发货、发货失败、已退款、已取消的订单不能取消；
     * 发货失败订单的退款由管理员 adminRefund 处理（其退款流水与状态由管理员操作决定）。
     * 调用方负责按 {@code shop:refund:<id>} 退款并回滚库存。
     */
    public ShopOrder cancel(String reason) {
        if (status == ShopOrderStatus.DELIVERED || status == ShopOrderStatus.DELIVERY_FAILED
                || status == ShopOrderStatus.REFUNDED || status == ShopOrderStatus.CANCELLED) {
            throw new IllegalArgumentException("当前状态的订单不能取消：" + status.label());
        }
        return copy(ShopOrderStatus.CANCELLED, refundTransactionId,
                trimToNull(reason) == null ? "买家取消订单" : reason.trim(), deliveryContent,
                deliveryVoucher, deliveryProofs, verifiedAt, deliveredAt);
    }

    public boolean delivering() {
        return status == ShopOrderStatus.PAID || status == ShopOrderStatus.DELIVERING;
    }

    public boolean refundable() {
        return status != ShopOrderStatus.DELIVERED && status != ShopOrderStatus.REFUNDED
                && status != ShopOrderStatus.CANCELLED && refundTransactionId == null;
    }

    /** 是否已有买家可核验的发货凭证（文本或图片任一）。 */
    public boolean hasDeliveryProof() {
        return (deliveryVoucher != null && !deliveryVoucher.isBlank()) || !deliveryProofs.isEmpty();
    }

    /** 买家能否自行取消：尚未发货（没有凭证）且未进入终态。 */
    public boolean cancellableByBuyer() {
        return delivering() && !hasDeliveryProof();
    }

    private ShopOrder copy(ShopOrderStatus nextStatus, String nextRefundTxId, String nextMessage,
                           String nextContent, String nextVoucher, List<String> nextProofs, Long nextVerifiedAt,
                           Long nextDeliveredAt) {
        return new ShopOrder(id, productId, productTitle, productImage, productType, settlement, buyerId, sellerId,
                assetCode, price, quantity, totalAmount, variantId, variantName, nextStatus, walletTransactionId,
                nextRefundTxId, nextMessage, nextContent, nextVoucher, nextProofs, nextVerifiedAt, createdAt, paidAt,
                nextDeliveredAt);
    }

    private static String normalizeVoucher(String value) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        if (text.length() > MAX_VOUCHER_LENGTH) {
            throw new IllegalArgumentException("发货凭证不能超过 " + MAX_VOUCHER_LENGTH + " 个字符");
        }
        return text;
    }

    private static List<String> normalizeProofs(List<String> proofs) {
        if (proofs == null || proofs.isEmpty()) {
            return List.of();
        }
        List<String> normalized = proofs.stream()
                .filter(proof -> proof != null && !proof.isBlank())
                .map(String::trim)
                .peek(proof -> {
                    if (proof.length() > MAX_PROOF_LENGTH) {
                        throw new IllegalArgumentException("发货凭证图片地址过长");
                    }
                })
                .limit(MAX_DELIVERY_PROOFS)
                .toList();
        return normalized;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
