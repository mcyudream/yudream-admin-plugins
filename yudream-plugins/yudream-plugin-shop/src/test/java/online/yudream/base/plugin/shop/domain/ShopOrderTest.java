package online.yudream.base.plugin.shop.domain;

import online.yudream.base.plugin.shop.domain.aggregate.ShopOrder;
import online.yudream.base.plugin.shop.domain.aggregate.ShopProduct;
import online.yudream.base.plugin.shop.domain.enumerate.ShopOrderStatus;
import online.yudream.base.plugin.shop.domain.enumerate.ShopSettlement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单聚合的发货凭证与取消/核验流转。
 *
 * <p>覆盖多图凭证（去空、上限 6）、空凭证拒绝、凭证图片单独成证，以及
 * 「买家只在发货前能取消」这一积分兑换的关键约束。
 */
class ShopOrderTest {

    private static final String BUYER = "1001";

    private ShopProduct product;

    @BeforeEach
    void setUp() {
        product = ShopProduct.create("2001", "称号兑换券", "", "", List.of(), "POINT", new BigDecimal("100"), 5,
                0, "POINTS_REDEEM", Map.of());
    }

    private ShopOrder paidOrder() {
        return ShopOrder.paid("order-1", product, BUYER, 1, new BigDecimal("100"), "tx-1",
                ShopSettlement.BURN);
    }

    @Test
    void paidOrderDefaultsToSellerSettlementAndIsCancellable() {
        ShopOrder order = ShopOrder.paid("order-1", product, BUYER, 1, new BigDecimal("100"), "tx-1");

        assertEquals(ShopSettlement.SELLER, order.settlement());
        assertEquals(ShopOrderStatus.PAID, order.status());
        assertEquals("POINT", order.assetCode());
        assertTrue(order.deliveryProofs().isEmpty());
        assertFalse(order.hasDeliveryProof());
        assertTrue(order.cancellableByBuyer());
        assertTrue(order.delivering());
    }

    @Test
    void deliverySubmissionNeedsTextOrImages() {
        ShopOrder order = paidOrder();

        assertEquals("发货凭证不能为空", assertThrows(IllegalArgumentException.class,
                () -> order.markDeliverySubmitted("   ", List.of())).getMessage());
        assertEquals("发货凭证不能为空", assertThrows(IllegalArgumentException.class,
                () -> order.markDeliverySubmitted(null, null)).getMessage());
        assertEquals("发货凭证不能为空", assertThrows(IllegalArgumentException.class,
                () -> order.markDeliverySubmitted(null, List.of(" ", ""))).getMessage());
    }

    @Test
    void voucherLongerThanTheLimitIsRejected() {
        assertEquals("发货凭证不能超过 500 个字符", assertThrows(IllegalArgumentException.class,
                () -> paidOrder().markDeliverySubmitted("x".repeat(501), List.of())).getMessage());
        assertEquals("发货凭证图片地址过长", assertThrows(IllegalArgumentException.class,
                () -> paidOrder().markDeliverySubmitted("已发放", List.of("/api/files/" + "x".repeat(500))))
                .getMessage());
    }

    @Test
    void imagesAloneAreAValidProofAndCloseBuyerCancellation() {
        ShopOrder delivering = paidOrder().markDeliverySubmitted(null, List.of("/api/files/1/content"));

        assertEquals(ShopOrderStatus.DELIVERING, delivering.status());
        assertNull(delivering.deliveryVoucher());
        assertEquals(List.of("/api/files/1/content"), delivering.deliveryProofs());
        assertTrue(delivering.hasDeliveryProof());
        assertFalse(delivering.cancellableByBuyer());
        assertEquals("卖家已发货，请买家核验发货凭证", delivering.deliveryMessage());
    }

    @Test
    void proofsDropBlankValuesAndKeepAtMostSix() {
        List<String> proofs = new ArrayList<>(List.of(" ", "", "   "));
        for (int index = 1; index <= 8; index++) {
            proofs.add(" /api/files/" + index + "/content ");
        }

        ShopOrder order = paidOrder().markDeliverySubmitted(" 已发放 ", proofs);

        assertEquals(ShopOrder.MAX_DELIVERY_PROOFS, order.deliveryProofs().size());
        assertEquals("/api/files/1/content", order.deliveryProofs().get(0));
        assertEquals("/api/files/6/content", order.deliveryProofs().get(5));
        assertEquals("已发放", order.deliveryVoucher());
    }

    @Test
    void buyerCanCancelOnlyBeforeDelivery() {
        ShopOrder order = paidOrder();

        ShopOrder cancelled = order.cancel("买错了");
        assertEquals(ShopOrderStatus.CANCELLED, cancelled.status());
        assertEquals("买错了", cancelled.deliveryMessage());
        assertFalse(cancelled.cancellableByBuyer());
        assertFalse(cancelled.refundable());

        assertFalse(order.markDeliverySubmitted("已发放", List.of()).cancellableByBuyer());
        assertFalse(order.markDelivered("已发货", null).cancellableByBuyer());
        assertFalse(order.markRefunded("tx-2", "已退款").cancellableByBuyer());
    }

    @Test
    void cancelFallsBackToADefaultReason() {
        assertEquals("买家取消订单", paidOrder().cancel("   ").deliveryMessage());
        assertEquals("买家取消订单", paidOrder().cancel(null).deliveryMessage());
    }

    @Test
    void cancelIsRejectedInTerminalStates() {
        assertEquals("当前状态的订单不能取消：已发货", assertThrows(IllegalArgumentException.class,
                () -> paidOrder().markDelivered("已发货", null).cancel("不想要了")).getMessage());
        assertEquals("当前状态的订单不能取消：已退款", assertThrows(IllegalArgumentException.class,
                () -> paidOrder().markRefunded("tx-2", "已退款").cancel("不想要了")).getMessage());
        assertEquals("当前状态的订单不能取消：已取消", assertThrows(IllegalArgumentException.class,
                () -> paidOrder().cancel("第一次").cancel("第二次")).getMessage());
        assertEquals("当前状态的订单不能取消：发货失败", assertThrows(IllegalArgumentException.class,
                () -> paidOrder().markDeliveryFailed("发货失败").cancel("不想要了")).getMessage());
    }

    @Test
    void verifyDeliveryCompletesTheOrderAndDropsRefundability() {
        ShopOrder verified = paidOrder().markDeliverySubmitted("卡密 123", List.of()).verifyDelivery();

        assertEquals(ShopOrderStatus.DELIVERED, verified.status());
        assertEquals("卡密 123", verified.deliveryVoucher());
        assertNotNull(verified.verifiedAt());
        assertNotNull(verified.deliveredAt());
        assertFalse(verified.refundable());
        assertFalse(verified.cancellableByBuyer());
    }

    @Test
    void markVoucherSubmittedKeepsExistingProofs() {
        ShopOrder withImage = paidOrder().markDeliverySubmitted(null, List.of("/api/files/2/content"));

        ShopOrder updated = withImage.markVoucherSubmitted("补充：卡密 456");

        assertEquals("补充：卡密 456", updated.deliveryVoucher());
        assertEquals(List.of("/api/files/2/content"), updated.deliveryProofs());
    }
}
