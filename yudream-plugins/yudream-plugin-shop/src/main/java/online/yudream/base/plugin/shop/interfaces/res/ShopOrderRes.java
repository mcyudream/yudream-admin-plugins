package online.yudream.base.plugin.shop.interfaces.res;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单视图。deliveryContent 仅买家本人与管理员可见，卖家视图不含；
 * deliveryVoucher（文本发货凭证）与 deliveryProofs（凭证图片，最多 6 张）买卖双方与管理员均可见。
 *
 * <p>settlement 为结算方式 code（SELLER 转给卖家 / BURN 消耗，见订单快照）；
 * cancellable 表示买家当前能否自行取消（尚未发货且未进入终态）。
 *
 * <p>variantId/variantName 为下单时选中的商品型号快照（商品无型号时为 null）；
 * sellerLabel 为没有卖家账号的消耗式订单的归属展示名，null 表示不显示归属行。
 *
 * <p>feeAmount 为玩家市场订单被收取的交易手续费、sellerAmount 为卖家实收（均为十进制字符串）；
 * 没有手续费的历史订单与消耗类订单为 "0" 与成交额，前端据此不显示手续费行。
 */
public record ShopOrderRes(String id, String productId, String productTitle, String productImage,
                           String productType, String productTypeDisplayName, String settlement,
                           ShopUserRes buyer, ShopUserRes seller, String sellerLabel, String assetCode,
                           BigDecimal price, String variantId, String variantName,
                           int quantity, BigDecimal totalAmount, String feeAmount, String sellerAmount,
                           String status, String statusText,
                           String walletTransactionId, String refundTransactionId, String deliveryMessage,
                           String deliveryContent, String deliveryVoucher, List<String> deliveryProofs,
                           boolean cancellable, boolean voucherVerified,
                           long createdAt, long paidAt, Long deliveredAt) {
}
