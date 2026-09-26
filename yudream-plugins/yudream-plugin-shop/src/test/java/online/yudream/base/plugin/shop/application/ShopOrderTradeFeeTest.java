package online.yudream.base.plugin.shop.application;

import online.yudream.base.plugin.shop.api.ShopDeliveryContext;
import online.yudream.base.plugin.shop.api.ShopDeliveryResult;
import online.yudream.base.plugin.shop.api.ShopProductTypeHandler;
import online.yudream.base.plugin.shop.application.cmd.ShopPurchaseCmd;
import online.yudream.base.plugin.shop.application.service.ShopOrderService;
import online.yudream.base.plugin.shop.application.service.ShopProductTypeRegistry;
import online.yudream.base.plugin.shop.application.service.ShopSettingsService;
import online.yudream.base.plugin.shop.domain.aggregate.ShopOrder;
import online.yudream.base.plugin.shop.domain.aggregate.ShopProduct;
import online.yudream.base.plugin.shop.domain.enumerate.ShopOrderStatus;
import online.yudream.base.plugin.shop.domain.enumerate.ShopTradeFeePayee;
import online.yudream.base.plugin.shop.domain.valobj.ShopSettings;
import online.yudream.base.plugin.shop.infrastructure.repository.ShopOrderRepository;
import online.yudream.base.plugin.shop.infrastructure.repository.ShopProductRepository;
import online.yudream.base.plugin.shop.infrastructure.repository.ShopSettingsRepository;
import online.yudream.base.plugin.shop.infrastructure.wallet.ShopWalletPort;
import online.yudream.base.plugin.shop.support.FakeDocumentStore;
import online.yudream.base.plugin.shop.support.FakePluginContext;
import online.yudream.base.plugin.shop.support.FakeWalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家市场交易手续费的两腿支付编排、失败对冲与两腿退款。
 *
 * <p>真实仓储（内存文档存储）+ 真实钱包端口（假钱包服务经 PluginContext 发现），
 * 覆盖：SELLER 才收费、BURN 销毁与 PLATFORM 平台用户两种去向、单号口径
 * （{@code shop:<id>} / {@code shop:fee:<id>} / {@code shop:refund:<id>} / {@code shop:fee-refund:<id>}）、
 * 一腿失败时按固定对冲单号把买家账户还原、退款（取消 / 管理员 / 发货失败）拿回完整成交额且幂等，
 * 以及旧订单文档（无手续费字段）读出与退款行为保持不变。
 */
class ShopOrderTradeFeeTest {

    private static final String SELLER = "2001";
    private static final String BUYER = "1001";
    private static final String PLATFORM_USER = "9001";
    /** 发货后停在发货中的玩家侧类型：订单可被买家取消，用于退款路径。 */
    private static final String PENDING_TYPE = "TEST_PENDING";
    /** 发货必然失败的玩家侧类型：用于发货失败自动退款。 */
    private static final String FAIL_TYPE = "TEST_FAIL";

    private FakeDocumentStore documents;
    private FakeWalletService wallet;
    private ShopProductRepository products;
    private ShopOrderRepository orders;
    private ShopSettingsService settingsService;
    private ShopOrderService service;

    @BeforeEach
    void setUp() {
        documents = new FakeDocumentStore();
        wallet = new FakeWalletService()
                .setBalance(BUYER, "CNY", "1000")
                .setBalance(SELLER, "CNY", "0")
                .setBalance(PLATFORM_USER, "CNY", "0");
        products = new ShopProductRepository(documents);
        orders = new ShopOrderRepository(documents);
        ShopWalletPort walletPort = ShopWalletPort.create(FakePluginContext.withWallet(wallet));
        ShopProductTypeRegistry typeRegistry = new ShopProductTypeRegistry(
                FakePluginContext.withWallet(wallet, List.of(pendingHandler(), failingHandler())));
        settingsService = new ShopSettingsService(new ShopSettingsRepository(documents), walletPort);
        service = new ShopOrderService(orders, products, typeRegistry, walletPort, settingsService);
    }

    // ------------------------------------------------------------------ 夹具

    private static ShopProductTypeHandler pendingHandler() {
        return new ShopProductTypeHandler() {
            @Override
            public String type() {
                return PENDING_TYPE;
            }

            @Override
            public String displayName() {
                return "待卖家发货";
            }

            @Override
            public Map<String, Object> normalizeConfig(Map<String, Object> config) {
                return Map.of();
            }

            @Override
            public ShopDeliveryResult deliver(ShopDeliveryContext context) {
                return ShopDeliveryResult.pending("等待卖家发货");
            }
        };
    }

    private static ShopProductTypeHandler failingHandler() {
        return new ShopProductTypeHandler() {
            @Override
            public String type() {
                return FAIL_TYPE;
            }

            @Override
            public String displayName() {
                return "发货必失败";
            }

            @Override
            public Map<String, Object> normalizeConfig(Map<String, Object> config) {
                return Map.of();
            }

            @Override
            public ShopDeliveryResult deliver(ShopDeliveryContext context) {
                return ShopDeliveryResult.failed("自动发货失败");
            }
        };
    }

    private void feeEnabled(String rate, String minFee, ShopTradeFeePayee payee, String payeeUserId) {
        settingsService.save(new ShopSettings(true, null, BigDecimal.ZERO, List.of(), "官方", null,
                true, new BigDecimal(rate), new BigDecimal(minFee), payee, payeeUserId));
    }

    private void feeDisabled() {
        settingsService.save(ShopSettings.defaults());
    }

    private ShopProduct product(String type, String price) {
        return products.save(ShopProduct.create(SELLER, "手办", "", "", List.of(), "CNY",
                new BigDecimal(price), 5, 0, type, Map.of()));
    }

    private ShopOrder purchase(ShopProduct item) {
        return service.purchase(BUYER, new ShopPurchaseCmd(item.id(), null, 1));
    }

    /** 金额断言按数值比较（不比较 BigDecimal 的 scale）。 */
    private void assertAmount(String expected, BigDecimal actual) {
        assertAmount(expected, actual, null);
    }

    private void assertAmount(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                (message == null ? "" : message + "：") + "期望 " + expected + "，实际 " + actual);
    }

    private void assertBalance(String expected, String userId) {
        assertBalance(expected, userId, null);
    }

    private void assertBalance(String expected, String userId, String message) {
        BigDecimal actual = wallet.balanceOf(userId, "CNY");
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                (message == null ? "" : message + "：") + "账户 " + userId + " 余额应为 " + expected + "，实际 " + actual);
    }

    private static List<String> movementNos(FakeWalletService wallet, String type) {
        return wallet.movements().stream().filter(item -> type.equals(item.type()))
                .map(FakeWalletService.Movement::businessNo).toList();
    }

    // ------------------------------------------------------------------ 适用范围与降级

    @Test
    void officialRedemptionOrdersNeverPayATradeFee() {
        feeEnabled("10", "0", ShopTradeFeePayee.BURN, null);
        ShopProduct item = product(ShopProductTypeRegistry.POINTS_REDEEM_TYPE, "100");

        ShopOrder order = purchase(item);

        assertAmount("0", order.feeAmount());
        assertAmount("100", order.sellerAmount());
        assertBalance("900", BUYER);
        assertBalance("0", SELLER);
        assertEquals(List.of("shop:" + order.id()), wallet.debitBusinessNos(), "消耗类订单只有一条扣减腿");
        assertTrue(wallet.transferBusinessNos().isEmpty());
    }

    @Test
    void disabledFeeKeepsTheSingleTransferChain() {
        feeDisabled();
        ShopProduct item = product(ShopProductTypeRegistry.GENERIC_TYPE, "10");

        ShopOrder order = purchase(item);

        assertAmount("0", order.feeAmount());
        assertAmount("10", order.sellerAmount());
        assertEquals(List.of("shop:" + order.id()), wallet.transferBusinessNos());
        assertEquals(1, wallet.movements().size(), "不产生手续费时不额外发钱包操作");
        assertBalance("990", BUYER);
        assertBalance("10", SELLER);
    }

    // ------------------------------------------------------------------ 两腿支付

    @Test
    void burnedFeeLeavesTheBuyerPayingTheTotalAndTheSellerWithTheNet() {
        feeEnabled("10", "0", ShopTradeFeePayee.BURN, null);
        ShopProduct item = product(ShopProductTypeRegistry.GENERIC_TYPE, "100");

        ShopOrder order = purchase(item);

        assertAmount("100", order.totalAmount());
        assertAmount("10", order.feeAmount());
        assertAmount("90", order.sellerAmount());
        assertEquals(ShopTradeFeePayee.BURN, order.feePayee());
        assertBalance("900", BUYER);
        assertBalance("90", SELLER);
        assertEquals(List.of("shop:" + order.id()), wallet.transferBusinessNos());
        assertEquals(List.of("shop:fee:" + order.id()), wallet.debitBusinessNos(), "销毁式手续费从买家扣减");

        ShopOrder reloaded = orders.findById(order.id()).orElseThrow();
        assertAmount("10", reloaded.feeAmount());
        assertAmount("90", reloaded.sellerAmount());
        assertEquals(ShopTradeFeePayee.BURN, reloaded.feePayee());
    }

    @Test
    void platformPayeeFeeIsTransferredToTheConfiguredUser() {
        feeEnabled("10", "0", ShopTradeFeePayee.PLATFORM, PLATFORM_USER);
        ShopProduct item = product(ShopProductTypeRegistry.GENERIC_TYPE, "100");

        ShopOrder order = purchase(item);

        assertAmount("10", order.feeAmount());
        assertAmount("90", order.sellerAmount());
        assertEquals(ShopTradeFeePayee.PLATFORM, order.feePayee());
        assertEquals(PLATFORM_USER, order.feePayeeUserId());
        assertBalance("900", BUYER);
        assertBalance("90", SELLER);
        assertBalance("10", PLATFORM_USER);
        assertEquals(List.of("shop:" + order.id(), "shop:fee:" + order.id()), wallet.transferBusinessNos());
        assertTrue(wallet.debitBusinessNos().isEmpty());
    }

    @Test
    void minimumFeeIsChargedOnTopOfTheRate() {
        feeEnabled("1", "5", ShopTradeFeePayee.BURN, null);
        ShopProduct item = product(ShopProductTypeRegistry.GENERIC_TYPE, "100");

        ShopOrder order = purchase(item);

        assertAmount("5", order.feeAmount());
        assertAmount("95", order.sellerAmount());
        assertBalance("900", BUYER, "买家总支出恒为成交额");
        assertBalance("95", SELLER);
    }

    @Test
    void aFeeEqualToTheTotalSkipsTheZeroAmountSellerLeg() {
        feeEnabled("100", "0", ShopTradeFeePayee.BURN, null);
        ShopProduct item = product(ShopProductTypeRegistry.GENERIC_TYPE, "30");

        ShopOrder order = purchase(item);

        assertAmount("30", order.feeAmount());
        assertAmount("0", order.sellerAmount());
        assertEquals(List.of("shop:fee:" + order.id()), wallet.debitBusinessNos());
        assertTrue(wallet.transferBusinessNos().isEmpty(), "卖家实收 0 时不再发 0 金额转账");
        assertEquals(1, wallet.movements().size());
        assertBalance("970", BUYER);
        assertBalance("0", SELLER);
    }

    // ------------------------------------------------------------------ 支付失败与对冲

    @Test
    void failedFeeLegRevertsTheSellerLegAndRollsBackTheStock() {
        feeEnabled("10", "0", ShopTradeFeePayee.BURN, null);
        ShopProduct item = product(ShopProductTypeRegistry.GENERIC_TYPE, "100");
        wallet.failBusinessNoStartingWith("shop:fee:");

        assertThrows(IllegalStateException.class, () -> purchase(item));

        assertBalance("1000", BUYER);
        assertBalance("0", SELLER);
        assertEquals(5, products.findById(item.id()).orElseThrow().stock(), "支付失败必须回滚库存");
        assertTrue(orders.findAll().isEmpty(), "订单不能落成已支付");

        List<FakeWalletService.Movement> reverts = wallet.movements().stream()
                .filter(movement -> movement.businessNo().startsWith("shop:net-revert:"))
                .toList();
        assertEquals(1, reverts.size(), "已成功的卖家腿必须按固定对冲单号转回买家");
        assertEquals(SELLER, reverts.get(0).fromUserId());
        assertEquals(BUYER, reverts.get(0).toUserId());
        assertAmount("90", reverts.get(0).amount());
    }

    @Test
    void failedSellerLegNeedsNoRevertAndLeavesTheBuyerUntouched() {
        feeEnabled("10", "0", ShopTradeFeePayee.BURN, null);
        ShopProduct item = product(ShopProductTypeRegistry.GENERIC_TYPE, "100");
        wallet.failTransfers();

        assertThrows(IllegalStateException.class, () -> purchase(item));

        assertBalance("1000", BUYER);
        assertBalance("0", SELLER);
        assertEquals(5, products.findById(item.id()).orElseThrow().stock());
        assertTrue(orders.findAll().isEmpty());
        assertTrue(wallet.movements().isEmpty());
    }

    @Test
    void aFailedRevertIsSurfacedToAdministrators() {
        feeEnabled("10", "0", ShopTradeFeePayee.BURN, null);
        ShopProduct item = product(ShopProductTypeRegistry.GENERIC_TYPE, "100");
        wallet.failBusinessNoStartingWith("shop:fee:").failBusinessNoStartingWith("shop:net-revert:");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> purchase(item));

        assertTrue(failure.getMessage().contains("自动冲正未完成"), failure.getMessage());
        assertTrue(failure.getMessage().contains("卖家货款腿冲正失败"), failure.getMessage());
        assertEquals(5, products.findById(item.id()).orElseThrow().stock(), "库存仍要回滚");
        assertTrue(orders.findAll().isEmpty(), "订单不落成已支付");
        // 冲正失败时买家已转给卖家的钱无法自动收回，只能靠异常提示管理员人工处理
        // （手续费腿没成功，所以买家此刻只少了卖家实收部分）。
        assertBalance("910", BUYER);
        assertBalance("90", SELLER);
    }

    // ------------------------------------------------------------------ 退款：两腿原路收回

    @Test
    void buyerCancellationReturnsTheFullTotalAcrossBothLegs() {
        feeEnabled("10", "0", ShopTradeFeePayee.BURN, null);
        ShopProduct item = product(PENDING_TYPE, "100");
        ShopOrder order = purchase(item);
        assertBalance("900", BUYER);

        ShopOrder cancelled = service.cancelOrder(BUYER, order.id(), "买错了");

        assertEquals(ShopOrderStatus.CANCELLED, cancelled.status());
        assertBalance("1000", BUYER, "退款后买家必须拿回完整成交额");
        assertBalance("0", SELLER);
        assertBalance("0", PLATFORM_USER);
        assertEquals(List.of("shop:fee-refund:" + order.id()), wallet.creditBusinessNos());
        assertEquals(List.of("shop:" + order.id(), "shop:refund:" + order.id()), wallet.transferBusinessNos());
        assertEquals(5, products.findById(item.id()).orElseThrow().stock());
    }

    @Test
    void cancellingWithPlatformPayeeTakesTheFeeBackFromThePlatformUser() {
        feeEnabled("10", "0", ShopTradeFeePayee.PLATFORM, PLATFORM_USER);
        ShopProduct item = product(PENDING_TYPE, "100");
        ShopOrder order = purchase(item);
        assertBalance("10", PLATFORM_USER);

        service.cancelOrder(BUYER, order.id(), "买错了");

        assertBalance("1000", BUYER);
        assertBalance("0", SELLER);
        assertBalance("0", PLATFORM_USER, "手续费必须从平台用户原路转回买家");
        assertTrue(wallet.movements().stream().anyMatch(movement ->
                ("shop:fee-refund:" + order.id()).equals(movement.businessNo())
                        && PLATFORM_USER.equals(movement.fromUserId())
                        && BUYER.equals(movement.toUserId())));
    }

    @Test
    void partialRefundFailureIsRetriedWithIdempotentLegs() {
        feeEnabled("10", "0", ShopTradeFeePayee.BURN, null);
        ShopProduct item = product(PENDING_TYPE, "100");
        ShopOrder order = purchase(item);
        wallet.failBusinessNoStartingWith("shop:refund:");

        assertEquals("取消失败：退款未成功，请稍后重试或联系管理员",
                assertThrows(IllegalStateException.class,
                        () -> service.cancelOrder(BUYER, order.id(), "买错了")).getMessage());

        // 手续费腿已成功（买家拿回 10），卖家腿未完成：订单仍是发货中，可重试。
        assertBalance("910", BUYER);
        assertEquals(ShopOrderStatus.DELIVERING, orders.findById(order.id()).orElseThrow().status());

        wallet.recover();
        service.cancelOrder(BUYER, order.id(), "买错了");

        assertBalance("1000", BUYER, "重试后拿回完整成交额（不重复退）");
        assertBalance("0", SELLER);
        assertEquals(List.of("shop:fee-refund:" + order.id()), wallet.creditBusinessNos(),
                "手续费腿按固定单号幂等，重试不重复入账");
        assertEquals(1, movementNos(wallet, "TRANSFER").stream()
                .filter(no -> no.startsWith("shop:refund:")).count(), "卖家腿按固定单号只成功一次");
    }

    @Test
    void adminRefundReturnsBothLegsForPlatformPayeeOrders() {
        feeEnabled("10", "0", ShopTradeFeePayee.PLATFORM, PLATFORM_USER);
        ShopProduct item = product(PENDING_TYPE, "100");
        ShopOrder order = purchase(item);
        assertBalance("900", BUYER);
        assertBalance("10", PLATFORM_USER);

        ShopOrder refunded = service.adminRefund(order.id());

        assertEquals(ShopOrderStatus.REFUNDED, refunded.status());
        assertBalance("1000", BUYER);
        assertBalance("0", SELLER);
        assertBalance("0", PLATFORM_USER);
    }

    @Test
    void deliveryFailureAutoRefundReturnsTheFullTotal() {
        feeEnabled("10", "0", ShopTradeFeePayee.BURN, null);
        ShopProduct item = product(FAIL_TYPE, "100");

        ShopOrder order = purchase(item);

        assertEquals(ShopOrderStatus.REFUNDED, order.status());
        assertAmount("10", order.feeAmount());
        assertAmount("90", order.sellerAmount());
        assertBalance("1000", BUYER, "发货失败自动退款也要退回完整成交额");
        assertBalance("0", SELLER);
        assertEquals(List.of("shop:fee-refund:" + order.id()), wallet.creditBusinessNos());
        assertEquals(List.of("shop:" + order.id(), "shop:refund:" + order.id()), wallet.transferBusinessNos());
    }

    // ------------------------------------------------------------------ 旧数据兼容

    @Test
    void legacyOrdersWithoutFeeFieldsReadAsZeroFeeAndRefundAsBefore() {
        ShopProduct item = product(PENDING_TYPE, "100");
        wallet.setBalance(BUYER, "CNY", "0").setBalance(SELLER, "CNY", "100");
        documents.save("orders", "legacy-1", legacyOrderDocument(item.id()));

        ShopOrder legacy = orders.findById("legacy-1").orElseThrow();

        assertEquals(ShopOrderStatus.DELIVERING, legacy.status());
        assertAmount("0", legacy.feeAmount());
        assertAmount("100", legacy.sellerAmount());
        assertAmount("100", legacy.totalAmount());
        assertEquals(ShopTradeFeePayee.BURN, legacy.feePayee());

        ShopOrder cancelled = service.cancelOrder(BUYER, "legacy-1", "旧订单取消");

        assertEquals(ShopOrderStatus.CANCELLED, cancelled.status());
        assertBalance("100", BUYER, "旧订单仍是单腿原路退款");
        assertBalance("0", SELLER);
        assertTrue(wallet.creditBusinessNos().isEmpty());
        assertEquals(List.of("shop:refund:legacy-1"), wallet.transferBusinessNos());
    }

    /** 升级前的订单文档：没有 feeAmount / sellerAmount / feePayee 字段。 */
    private Map<String, Object> legacyOrderDocument(String productId) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", "legacy-1");
        document.put("productId", productId);
        document.put("productTitle", "手办");
        document.put("productType", PENDING_TYPE);
        document.put("settlement", "SELLER");
        document.put("buyerId", BUYER);
        document.put("sellerId", SELLER);
        document.put("assetCode", "CNY");
        document.put("price", "100");
        document.put("quantity", 1);
        document.put("totalAmount", "100");
        document.put("status", "DELIVERING");
        document.put("walletTransactionId", "tx-legacy");
        document.put("deliveryProofs", List.of());
        document.put("createdAt", 1L);
        document.put("paidAt", 1L);
        return document;
    }
}
