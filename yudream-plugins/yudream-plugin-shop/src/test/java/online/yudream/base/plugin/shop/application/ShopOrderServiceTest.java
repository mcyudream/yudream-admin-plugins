package online.yudream.base.plugin.shop.application;

import online.yudream.base.plugin.shop.application.cmd.ShopPurchaseCmd;
import online.yudream.base.plugin.shop.application.service.ShopOrderService;
import online.yudream.base.plugin.shop.application.service.ShopProductTypeRegistry;
import online.yudream.base.plugin.shop.domain.aggregate.ShopOrder;
import online.yudream.base.plugin.shop.domain.aggregate.ShopProduct;
import online.yudream.base.plugin.shop.domain.aggregate.ShopVariant;
import online.yudream.base.plugin.shop.domain.enumerate.ShopOrderStatus;
import online.yudream.base.plugin.shop.domain.enumerate.ShopSettlement;
import online.yudream.base.plugin.shop.infrastructure.repository.ShopOrderRepository;
import online.yudream.base.plugin.shop.infrastructure.repository.ShopProductRepository;
import online.yudream.base.plugin.shop.infrastructure.wallet.ShopWalletPort;
import online.yudream.base.plugin.shop.support.FakeDocumentStore;
import online.yudream.base.plugin.shop.support.FakePluginContext;
import online.yudream.base.plugin.shop.support.FakeWalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单用例的消耗式结算（POINTS_REDEEM）主流程。
 *
 * <p>真实仓储（内存文档存储）+ 真实钱包端口（假钱包服务经 PluginContext 发现）：
 * 覆盖「扣买家积分且不给任何人」「每人限兑且取消后还回额度」「发货前取消原路退款、回滚库存并记退款流水」
 * 「退款失败不动订单与库存」「发货凭证必须来自平台上传」，以及 SELLER 路径未被改坏。
 */
class ShopOrderServiceTest {

    private static final String SELLER = "2001";
    private static final String BUYER = "1001";
    private static final String OTHER_USER = "1002";

    private FakeDocumentStore documents;
    private FakeWalletService wallet;
    private ShopProductRepository products;
    private ShopOrderRepository orders;
    private ShopOrderService service;

    @BeforeEach
    void setUp() {
        documents = new FakeDocumentStore();
        wallet = new FakeWalletService()
                .setBalance(BUYER, "POINT", "1000")
                .setBalance(BUYER, "CNY", "50")
                .setBalance(OTHER_USER, "POINT", "1000")
                .setBalance(SELLER, "POINT", "0")
                .setBalance(SELLER, "CNY", "0");
        products = new ShopProductRepository(documents);
        orders = new ShopOrderRepository(documents);
        ShopWalletPort walletPort = ShopWalletPort.create(FakePluginContext.withWallet(wallet));
        ShopProductTypeRegistry typeRegistry = new ShopProductTypeRegistry(FakePluginContext.withWallet(wallet));
        service = new ShopOrderService(orders, products, typeRegistry, walletPort);
    }

    private ShopProduct product(String type, String assetCode, String price, int stock, int perUserLimit) {
        return products.save(ShopProduct.create(SELLER, "称号兑换券", "", "", List.of(), assetCode,
                new BigDecimal(price), stock, perUserLimit, type, Map.of("deliveryNote", "兑换后联系管理员")));
    }

    private ShopProduct pointsProduct(String price, int stock, int perUserLimit) {
        return product(ShopProductTypeRegistry.POINTS_REDEEM_TYPE, "POINT", price, stock, perUserLimit);
    }

    private ShopOrder purchase(ShopProduct item, int quantity) {
        return service.purchase(BUYER, new ShopPurchaseCmd(item.id(), null, quantity));
    }

    // ------------------------------------------------------------------ 商品型号

    /** 有型号的商品：按下单型号计价扣款、只扣该型号库存，订单快照记录型号，取消后退回该型号。 */
    @Test
    void purchaseUsesSelectedVariantPriceAndStock() {
        ShopProduct item = products.save(ShopProduct.create(SELLER, "冰箱贴", "", "", List.of(), "POINT",
                new BigDecimal("1000"), -1, 0, List.of(
                        ShopVariant.create("西瓜", new BigDecimal("300"), 3, null),
                        ShopVariant.create("钻石", new BigDecimal("400"), 2, null)),
                ShopProductTypeRegistry.POINTS_REDEEM_TYPE, Map.of()));
        String diamond = item.variants().get(1).id();

        ShopOrder order = service.purchase(BUYER, new ShopPurchaseCmd(item.id(), diamond, 2));

        assertEquals(new BigDecimal("400"), order.price(), "订单单价取所选型号价");
        assertEquals(new BigDecimal("800"), order.totalAmount());
        assertEquals("钻石", order.variantName());
        assertEquals(diamond, order.variantId());
        assertEquals(new BigDecimal("200"), wallet.balanceOf(BUYER, "POINT"), "按型号价扣款");

        ShopProduct afterBuy = products.findById(item.id()).orElseThrow();
        assertEquals(0, afterBuy.variant(diamond).orElseThrow().stock(), "只扣所选型号");
        assertEquals(3, afterBuy.variant(item.variants().get(0).id()).orElseThrow().stock(), "其它型号不受影响");
        assertEquals(3, afterBuy.stock(), "商品库存为型号合计");

        ShopOrder cancelled = service.cancelOrder(BUYER, order.id(), "不想要了");
        assertEquals(ShopOrderStatus.CANCELLED, cancelled.status());
        assertEquals(2, products.findById(item.id()).orElseThrow().variant(diamond).orElseThrow().stock(),
                "取消后型号库存回滚");
        assertEquals(new BigDecimal("1000"), wallet.balanceOf(BUYER, "POINT"));
    }

    @Test
    void purchaseRejectsMissingOrUnexpectedVariant() {
        ShopProduct variantItem = products.save(ShopProduct.create(SELLER, "冰箱贴", "", "", List.of(), "POINT",
                new BigDecimal("1000"), -1, 0, List.of(ShopVariant.create("西瓜", new BigDecimal("1000"), 1, null)),
                ShopProductTypeRegistry.POINTS_REDEEM_TYPE, Map.of()));

        assertEquals("请选择商品型号", assertThrows(IllegalArgumentException.class,
                () -> service.purchase(BUYER, new ShopPurchaseCmd(variantItem.id(), null, 1))).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> service.purchase(BUYER, new ShopPurchaseCmd(variantItem.id(), "not-exist", 1)));

        ShopProduct plainItem = pointsProduct("100", 5, 0);
        assertEquals("该商品没有型号可选", assertThrows(IllegalArgumentException.class,
                () -> service.purchase(BUYER, new ShopPurchaseCmd(plainItem.id(), "any", 1))).getMessage());
    }

    // ------------------------------------------------------------------ 消耗式结算

    @Test
    void pointsRedeemBurnsBuyerPointsAndNobodyReceivesThem() {
        ShopProduct item = pointsProduct("100", 5, 0);

        ShopOrder order = purchase(item, 2);

        assertEquals(ShopOrderStatus.DELIVERING, order.status());
        assertEquals(ShopSettlement.BURN, order.settlement());
        assertEquals("等待发放：积分已扣除，请上架者提交发货凭证", order.deliveryMessage());
        assertEquals(new BigDecimal("800"), wallet.balanceOf(BUYER, "POINT"));
        assertEquals(0, wallet.balanceOf(SELLER, "POINT").signum());
        assertTrue(wallet.transferBusinessNos().isEmpty());
        // 业务单号仍必须是 shop:<订单号>，保证幂等与对账。
        assertEquals(List.of("shop:" + order.id()), wallet.debitBusinessNos());
        assertEquals(3, products.findById(item.id()).orElseThrow().stock());
    }

    @Test
    void pointsRedeemPurchaseIsPersistedWithSettlementAndProofDefaults() {
        ShopOrder order = purchase(pointsProduct("100", 5, 0), 1);

        ShopOrder reloaded = orders.findById(order.id()).orElseThrow();
        assertEquals(ShopSettlement.BURN, reloaded.settlement());
        assertEquals(List.of(), reloaded.deliveryProofs());
        assertTrue(reloaded.cancellableByBuyer());
        assertEquals("POINT", reloaded.assetCode());
    }

    @Test
    void burnedPointsAreRefundedAndStockRolledBackWhenTheWalletFailsMidPurchase() {
        ShopProduct item = pointsProduct("100", 5, 0);
        wallet.failDebits();

        assertThrows(IllegalStateException.class, () -> purchase(item, 1));

        assertEquals(new BigDecimal("1000"), wallet.balanceOf(BUYER, "POINT"));
        assertEquals(5, products.findById(item.id()).orElseThrow().stock());
        assertTrue(orders.findAll().isEmpty());
    }

    @Test
    void genericProductsStillTransferToTheSeller() {
        ShopProduct item = product(ShopProductTypeRegistry.GENERIC_TYPE, "CNY", "10", 5, 0);

        ShopOrder order = purchase(item, 2);

        assertEquals(ShopSettlement.SELLER, order.settlement());
        assertEquals(ShopOrderStatus.DELIVERED, order.status());
        assertEquals(new BigDecimal("30"), wallet.balanceOf(BUYER, "CNY"));
        assertEquals(new BigDecimal("20"), wallet.balanceOf(SELLER, "CNY"));
        assertEquals(List.of("shop:" + order.id()), wallet.transferBusinessNos());
        assertTrue(wallet.debitBusinessNos().isEmpty());
    }

    // ------------------------------------------------------------------ 每人限兑

    @Test
    void perUserLimitIsEnforcedAndFreedByCancellation() {
        ShopProduct item = pointsProduct("100", 5, 2);

        ShopOrder first = purchase(item, 1);
        assertEquals("每人限兑 2 件，你已兑换 1 件", assertThrows(IllegalArgumentException.class,
                () -> purchase(item, 2)).getMessage());

        // 恰好到限仍然允许。
        ShopOrder second = purchase(item, 1);
        assertEquals(new BigDecimal("800"), wallet.balanceOf(BUYER, "POINT"));
        assertEquals(3, products.findById(item.id()).orElseThrow().stock());
        assertEquals("每人限兑 2 件，你已兑换 2 件", assertThrows(IllegalArgumentException.class,
                () -> purchase(item, 1)).getMessage());

        // 取消后额度、库存与积分一起还回。
        service.cancelOrder(BUYER, second.id(), "不想要了");
        assertEquals(new BigDecimal("900"), wallet.balanceOf(BUYER, "POINT"));
        assertEquals(4, products.findById(item.id()).orElseThrow().stock());
        assertEquals("每人限兑 2 件，你已兑换 1 件", assertThrows(IllegalArgumentException.class,
                () -> purchase(item, 2)).getMessage());

        ShopOrder third = purchase(item, 1);
        assertEquals(ShopOrderStatus.DELIVERING, third.status());
        assertEquals(new BigDecimal("800"), wallet.balanceOf(BUYER, "POINT"));
        assertEquals(3, products.findById(item.id()).orElseThrow().stock());
        assertEquals("每人限兑 2 件，你已兑换 2 件", assertThrows(IllegalArgumentException.class,
                () -> purchase(item, 1)).getMessage());
        assertEquals(ShopOrderStatus.DELIVERING, orders.findById(first.id()).orElseThrow().status());
    }

    @Test
    void perUserLimitCountsOnlyThisBuyer() {
        ShopProduct item = pointsProduct("100", 5, 1);

        purchase(item, 1);

        assertEquals(ShopOrderStatus.DELIVERING,
                service.purchase(OTHER_USER, new ShopPurchaseCmd(item.id(), null, 1)).status());
        assertEquals("每人限兑 1 件，你已兑换 1 件", assertThrows(IllegalArgumentException.class,
                () -> purchase(item, 1)).getMessage());
    }

    @Test
    void unlimitedProductsSkipTheLimitEvenAcrossManyOrders() {
        ShopProduct item = pointsProduct("10", -1, 0);

        for (int index = 0; index < 5; index++) {
            assertEquals(ShopOrderStatus.DELIVERING, purchase(item, 2).status());
        }

        assertEquals(ShopProduct.UNLIMITED_STOCK, products.findById(item.id()).orElseThrow().stock());
        assertEquals(new BigDecimal("900"), wallet.balanceOf(BUYER, "POINT"));
    }

    // ------------------------------------------------------------------ 取消与退款

    @Test
    void cancelRefundsThroughTheWalletAndKeepsTheRefundTrace() {
        ShopProduct item = pointsProduct("100", 5, 0);
        ShopOrder order = purchase(item, 2);

        ShopOrder cancelled = service.cancelOrder(BUYER, order.id(), "买错了");

        assertEquals(ShopOrderStatus.CANCELLED, cancelled.status());
        assertEquals("买错了", cancelled.deliveryMessage());
        assertNotNull(cancelled.refundTransactionId());
        assertEquals(List.of("shop:refund:" + order.id()), wallet.creditBusinessNos());
        assertEquals(new BigDecimal("1000"), wallet.balanceOf(BUYER, "POINT"));
        assertEquals(5, products.findById(item.id()).orElseThrow().stock());
        assertEquals(0L, products.findById(item.id()).orElseThrow().soldCount());

        ShopOrder reloaded = orders.findById(order.id()).orElseThrow();
        assertEquals(ShopOrderStatus.CANCELLED, reloaded.status());
        assertEquals(cancelled.refundTransactionId(), reloaded.refundTransactionId());
        assertFalse(reloaded.cancellableByBuyer());
    }

    @Test
    void cancelKeepsOrderAndStockUntouchedWhenTheRefundFails() {
        ShopProduct item = pointsProduct("100", 5, 0);
        ShopOrder order = purchase(item, 1);
        wallet.failCredits();

        assertEquals("取消失败：退款未成功，请稍后重试或联系管理员",
                assertThrows(IllegalStateException.class,
                        () -> service.cancelOrder(BUYER, order.id(), "买错了")).getMessage());

        ShopOrder reloaded = orders.findById(order.id()).orElseThrow();
        assertEquals(ShopOrderStatus.DELIVERING, reloaded.status());
        assertNull(reloaded.refundTransactionId());
        assertEquals(4, products.findById(item.id()).orElseThrow().stock());
        assertEquals(new BigDecimal("900"), wallet.balanceOf(BUYER, "POINT"));
    }

    @Test
    void onlyTheBuyerCanCancelAndOnlyBeforeDelivery() {
        ShopOrder order = purchase(pointsProduct("100", 5, 0), 1);

        assertEquals("订单不存在", assertThrows(IllegalArgumentException.class,
                () -> service.cancelOrder(OTHER_USER, order.id(), "顺手取消")).getMessage());
        assertEquals("订单不存在", assertThrows(IllegalArgumentException.class,
                () -> service.cancelOrder(SELLER, order.id(), "卖家不能替买家取消")).getMessage());

        service.submitDelivery(SELLER, order.id(), "已发放", List.of("/api/files/7/content"));
        assertEquals("当前状态的订单不能取消：发货中", assertThrows(IllegalArgumentException.class,
                () -> service.cancelOrder(BUYER, order.id(), "来晚了")).getMessage());
        assertEquals(new BigDecimal("900"), wallet.balanceOf(BUYER, "POINT"));
    }

    @Test
    void pointsRedeemDeliveryIsVerifiedByTheBuyerAndThenCannotBeCancelled() {
        ShopProduct item = pointsProduct("100", 5, 0);
        ShopOrder order = purchase(item, 1);

        ShopOrder delivering = service.submitDelivery(SELLER, order.id(), "已发放", List.of());
        assertEquals(ShopOrderStatus.DELIVERING, delivering.status());
        assertEquals("已发放", delivering.deliveryVoucher());
        assertFalse(delivering.cancellableByBuyer());

        assertEquals("当前状态的订单不能取消：发货中", assertThrows(IllegalArgumentException.class,
                () -> service.cancelOrder(BUYER, order.id(), "反悔")).getMessage());

        ShopOrder verified = service.verifyDelivery(BUYER, order.id());
        assertEquals(ShopOrderStatus.DELIVERED, verified.status());
        assertEquals("当前状态的订单不能取消：已发货", assertThrows(IllegalArgumentException.class,
                () -> service.cancelOrder(BUYER, order.id(), "反悔")).getMessage());
        assertTrue(wallet.creditBusinessNos().isEmpty());
    }

    // ------------------------------------------------------------------ 发货凭证

    @Test
    void submitDeliveryNeedsAVoucherOrProofs() {
        ShopOrder order = purchase(pointsProduct("100", 5, 0), 1);

        assertEquals("发货凭证不能为空", assertThrows(IllegalArgumentException.class,
                () -> service.submitDelivery(SELLER, order.id(), "   ", List.of())).getMessage());
        assertEquals("发货凭证不能为空", assertThrows(IllegalArgumentException.class,
                () -> service.submitDelivery(SELLER, order.id(), null, null)).getMessage());
    }

    @Test
    void submitDeliveryOnlyAcceptsPlatformUploadedImages() {
        ShopOrder order = purchase(pointsProduct("100", 5, 0), 1);

        assertEquals("发货凭证图片必须是通过平台上传的文件", assertThrows(IllegalArgumentException.class,
                () -> service.submitDelivery(SELLER, order.id(), null,
                        List.of("https://evil.example/proof.png"))).getMessage());
        assertEquals("发货凭证图片必须是通过平台上传的文件", assertThrows(IllegalArgumentException.class,
                () -> service.submitDelivery(SELLER, order.id(), "已发放",
                        List.of("/api/files/1/content", "javascript:alert(1)"))).getMessage());
        assertEquals(ShopOrderStatus.DELIVERING, orders.findById(order.id()).orElseThrow().status());
        assertFalse(orders.findById(order.id()).orElseThrow().hasDeliveryProof());
    }

    @Test
    void submitDeliveryKeepsAtMostSixProofsAndAcceptsImagesOnly() {
        ShopOrder order = purchase(pointsProduct("100", 5, 0), 1);
        List<String> proofs = IntStream.rangeClosed(1, 8)
                .mapToObj(index -> "/api/files/" + index + "/content")
                .toList();

        ShopOrder delivering = service.submitDelivery(SELLER, order.id(), "  ", proofs);

        assertEquals(ShopOrder.MAX_DELIVERY_PROOFS, delivering.deliveryProofs().size());
        assertNull(delivering.deliveryVoucher());
        assertEquals(List.of("/api/files/1/content"), delivering.deliveryProofs().stream().limit(1).toList());
        ShopOrder reloaded = orders.findById(order.id()).orElseThrow();
        assertEquals(6, reloaded.deliveryProofs().size());
        assertEquals("卖家已发货，请买家核验发货凭证", reloaded.deliveryMessage());
    }

    @Test
    void submitDeliveryRejectsOtherSellersAndTerminalOrders() {
        ShopOrder order = purchase(pointsProduct("100", 5, 0), 1);

        assertEquals("订单不存在", assertThrows(IllegalArgumentException.class,
                () -> service.submitDelivery(OTHER_USER, order.id(), "已发放", List.of())).getMessage());
        assertEquals("订单不存在", assertThrows(IllegalArgumentException.class,
                () -> service.submitDelivery(BUYER, order.id(), "已发放", List.of())).getMessage());

        ShopOrder delivered = service.submitDelivery(SELLER, order.id(), "已发放", List.of());
        service.verifyDelivery(BUYER, delivered.id());
        assertEquals("当前状态的订单不能提交发货凭证：已发货", assertThrows(IllegalArgumentException.class,
                () -> service.submitDelivery(SELLER, order.id(), "再发一次", List.of())).getMessage());
    }

    @Test
    void verifyDeliveryRejectsOrdersWithoutAnyProof() {
        ShopOrder order = purchase(pointsProduct("100", 5, 0), 1);

        assertEquals(ShopOrderStatus.DELIVERING, order.status());
        assertEquals("该订单还没有发货凭证，暂时无法核验", assertThrows(IllegalArgumentException.class,
                () -> service.verifyDelivery(BUYER, order.id())).getMessage());
    }

    @Test
    void submitVoucherStaysAsAThinDelegateWithoutProofs() {
        ShopOrder order = purchase(pointsProduct("100", 5, 0), 1);

        ShopOrder delivering = service.submitVoucher(SELLER, order.id(), " 卡密 123 ");

        assertEquals("卡密 123", delivering.deliveryVoucher());
        assertEquals(List.of(), delivering.deliveryProofs());
    }

    /**
     * 迁移自积分商城的存量订单归属平台（sellerId=system），没有自然人卖家；
     * 这类订单只能由管理员代发货，卖家侧接口仍按归属拒绝。
     */
    @Test
    void adminCanDeliverPlatformOwnedMigratedOrder() {
        String orderId = "red-pending";
        ShopProduct platformProduct = products.save(ShopProduct.create(ShopProduct.PLATFORM_OWNER, "限量徽章", "", "",
                List.of("/api/files/cover.png"), "POINT", new BigDecimal("120"), -1, 3, "POINTS_REDEEM", Map.of()));
        orders.save(ShopOrder.paid(orderId, platformProduct, BUYER, 1, new BigDecimal("120"),
                "mall-redeem-" + orderId, ShopSettlement.BURN));

        assertEquals("发货凭证图片必须是通过平台上传的文件", assertThrows(IllegalArgumentException.class,
                () -> service.adminSubmitDelivery(orderId, "已寄出", List.of("https://evil.example/x.png"))).getMessage());

        ShopOrder delivered = service.adminSubmitDelivery(orderId, null, List.of("/api/files/proof.png"));

        assertEquals(ShopOrderStatus.DELIVERING, delivered.status());
        assertEquals(List.of("/api/files/proof.png"), delivered.deliveryProofs());
        assertTrue(delivered.hasDeliveryProof());
        assertEquals("订单不存在", assertThrows(IllegalArgumentException.class,
                () -> service.submitDelivery(SELLER, orderId, "卖家视角发货", List.of())).getMessage());
    }
}
