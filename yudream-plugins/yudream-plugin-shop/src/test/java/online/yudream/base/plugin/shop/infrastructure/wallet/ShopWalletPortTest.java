package online.yudream.base.plugin.shop.infrastructure.wallet;

import online.yudream.base.plugin.shop.support.FakePluginContext;
import online.yudream.base.plugin.shop.support.FakeWalletService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钱包端口的消耗式扣减与原路退款。
 *
 * <p>这里锁定两条对账命脉：业务单号是幂等键（重试不会二次扣减/二次退款），
 * 以及软依赖降级时抛出的错误必须是明确的 IllegalStateException 而不是空指针。
 */
class ShopWalletPortTest {

    private static final String USER = "1001";
    private static final String SELLER = "2001";

    private final FakeWalletService wallet = new FakeWalletService()
            .setBalance(USER, "POINT", "100")
            .setBalance(USER, "CNY", "50");

    private ShopWalletPort port() {
        return ShopWalletPort.create(FakePluginContext.withWallet(wallet));
    }

    @Test
    void burnDebitsTheBuyerOnly() {
        ShopWalletPort port = port();

        ShopWalletPort.WalletPayment payment = port.burn(USER, "POINT", new BigDecimal("30"), "shop:order-1",
                "兑换商品：称号兑换券");

        assertTrue(port.available());
        assertEquals(new BigDecimal("70"), wallet.balanceOf(USER, "POINT"));
        assertEquals(0, wallet.balanceOf(SELLER, "POINT").signum());
        assertTrue(wallet.transferBusinessNos().isEmpty());
        assertTrue(payment.transactionId().contains("shop:order-1"));
    }

    @Test
    void burnIsIdempotentByBusinessNo() {
        ShopWalletPort port = port();

        ShopWalletPort.WalletPayment first = port.burn(USER, "POINT", new BigDecimal("30"), "shop:order-1", "兑换");
        ShopWalletPort.WalletPayment second = port.burn(USER, "POINT", new BigDecimal("30"), "shop:order-1", "兑换");

        assertEquals(first.transactionId(), second.transactionId());
        assertEquals(new BigDecimal("70"), wallet.balanceOf(USER, "POINT"));
        assertEquals(1, wallet.debitBusinessNos().size());
    }

    @Test
    void refundCreditsTheBuyerBackAndIsIdempotent() {
        ShopWalletPort port = port();
        port.burn(USER, "POINT", new BigDecimal("30"), "shop:order-1", "兑换");

        port.refund(USER, "POINT", new BigDecimal("30"), "shop:refund:order-1", "订单退款");
        port.refund(USER, "POINT", new BigDecimal("30"), "shop:refund:order-1", "订单退款");

        assertEquals(new BigDecimal("100"), wallet.balanceOf(USER, "POINT"));
        assertEquals(1, wallet.creditBusinessNos().size());
    }

    @Test
    void transferStillMovesMoneyFromBuyerToSeller() {
        ShopWalletPort port = port();

        port.transfer(USER, SELLER, "CNY", new BigDecimal("20"), "shop:order-2", "购买商品");

        assertEquals(new BigDecimal("30"), wallet.balanceOf(USER, "CNY"));
        assertEquals(new BigDecimal("20"), wallet.balanceOf(SELLER, "CNY"));
    }

    @Test
    void unavailableWalletFailsLoudlyInsteadOfSilently() {
        ShopWalletPort port = ShopWalletPort.create(FakePluginContext.withoutWallet());

        assertFalse(port.available());
        assertEquals("钱包插件未启用，暂时无法完成扣减", assertThrows(IllegalStateException.class,
                () -> port.burn(USER, "POINT", BigDecimal.ONE, "shop:order-3", "兑换")).getMessage());
        assertEquals("钱包插件未启用，暂时无法完成退款", assertThrows(IllegalStateException.class,
                () -> port.refund(USER, "POINT", BigDecimal.ONE, "shop:refund:order-3", "退款")).getMessage());
        assertEquals("钱包插件未启用，暂时无法完成支付", assertThrows(IllegalStateException.class,
                () -> port.transfer(USER, SELLER, "POINT", BigDecimal.ONE, "shop:order-4", "购买")).getMessage());
    }

    @Test
    void businessFailuresFromTheWalletArePropagatedUnchanged() {
        ShopWalletPort port = port();

        assertEquals("余额不足", assertThrows(IllegalArgumentException.class,
                () -> port.burn(USER, "POINT", new BigDecimal("999"), "shop:order-5", "兑换")).getMessage());
    }
}
