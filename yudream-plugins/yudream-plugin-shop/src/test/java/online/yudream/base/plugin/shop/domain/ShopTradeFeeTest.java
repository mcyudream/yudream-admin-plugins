package online.yudream.base.plugin.shop.domain;

import online.yudream.base.plugin.shop.domain.enumerate.ShopTradeFeePayee;
import online.yudream.base.plugin.shop.domain.valobj.ShopSettings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家市场交易手续费的口径：成交额 × 费率% 按计价货币小数位四舍五入（HALF_UP）、
 * 取最低手续费、不得超过成交额；关闭 / 费率为 0 / 算出的手续费为 0 时完全不收费。
 */
class ShopTradeFeeTest {

    private static ShopSettings feeSettings(boolean enabled, String rate, String minFee,
                                            ShopTradeFeePayee payee, String payeeUserId) {
        return new ShopSettings(true, null, BigDecimal.ZERO, List.of(), "官方", null,
                enabled, new BigDecimal(rate), new BigDecimal(minFee), payee, payeeUserId).normalized();
    }

    private static ShopSettings enabled(String rate, String minFee) {
        return feeSettings(true, rate, minFee, ShopTradeFeePayee.BURN, null);
    }

    /** 金额断言按数值比较（不比较 BigDecimal 的 scale）。 */
    private static void assertAmount(String expected, BigDecimal actual) {
        assertAmount(expected, actual, null);
    }

    private static void assertAmount(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                (message == null ? "" : message + "：") + "期望 " + expected + "，实际 " + actual);
    }

    // ------------------------------------------------------------------ 计费

    @Test
    void feeIsRatePercentOfTotalRoundedToTheCurrencyScale() {
        ShopSettings settings = enabled("2.5", "0");

        assertAmount("0.25", settings.tradeFee(new BigDecimal("10"), 2));
        assertAmount("25", settings.tradeFee(new BigDecimal("1000"), 2));
        assertAmount("3", settings.tradeFee(new BigDecimal("100"), 0));
    }

    @Test
    void feeRoundsHalfUpOnTheCurrencyScale() {
        // 1 × 0.5% = 0.005，两位小数按 HALF_UP 进到 0.01
        assertAmount("0.01", enabled("0.5", "0").tradeFee(BigDecimal.ONE, 2));
        // 1 × 0.4% = 0.004，四舍五入后为 0：不收费，卖家实收即成交额
        assertAmount("0", enabled("0.4", "0").tradeFee(BigDecimal.ONE, 2));
        assertAmount("1", enabled("0.4", "0").sellerAmount(BigDecimal.ONE, 2));
        // 100 × 0.5% = 0.5，整数货币下按 HALF_UP 进到 1
        assertAmount("1", enabled("0.5", "0").tradeFee(new BigDecimal("100"), 0));
    }

    @Test
    void minimumFeeWinsWhenItIsHigherThanTheRateFee() {
        ShopSettings settings = enabled("1", "5");

        assertAmount("5", settings.tradeFee(new BigDecimal("10"), 2));
        assertAmount("5", settings.tradeFee(new BigDecimal("100"), 2));
        assertAmount("10", settings.tradeFee(new BigDecimal("1000"), 2));
    }

    @Test
    void minimumIsRoundedToTheCurrencyScaleToo() {
        assertAmount("0.01", enabled("0.1", "0.005").tradeFee(new BigDecimal("10"), 2));
        assertAmount("0", enabled("0.1", "0.005").tradeFee(new BigDecimal("10"), 0));
    }

    @Test
    void feeNeverExceedsTheTotalAmount() {
        ShopSettings settings = enabled("80", "0");

        assertAmount("80", settings.tradeFee(new BigDecimal("100"), 2));
        assertAmount("20", settings.sellerAmount(new BigDecimal("100"), 2));

        ShopSettings full = enabled("100", "0");
        assertAmount("3", full.tradeFee(new BigDecimal("3"), 2));
        assertEquals(0, full.sellerAmount(new BigDecimal("3"), 2).signum(), "等于成交额时卖家实收 0");

        ShopSettings withFloor = enabled("10", "500");
        assertAmount("30", withFloor.tradeFee(new BigDecimal("30"), 2), "最低手续费也要被成交额封顶");
        assertEquals(0, withFloor.sellerAmount(new BigDecimal("30"), 2).signum());
    }

    @Test
    void disabledOrZeroRateChargesNothing() {
        assertFalse(enabled("0", "0").chargesTradeFee());
        assertAmount("0", enabled("0", "0").tradeFee(new BigDecimal("100"), 2));
        assertAmount("100", enabled("0", "0").sellerAmount(new BigDecimal("100"), 2));

        ShopSettings off = feeSettings(false, "5", "0", ShopTradeFeePayee.BURN, null);
        assertFalse(off.chargesTradeFee());
        assertAmount("0", off.tradeFee(new BigDecimal("100"), 2));

        // 费率为 0 时完全退化成改动前的单次全额转账：最低手续费不再单独生效。
        ShopSettings zeroRate = enabled("0", "10");
        assertFalse(zeroRate.chargesTradeFee());
        assertAmount("0", zeroRate.tradeFee(new BigDecimal("100"), 2));

        assertAmount("0", enabled("5", "0").tradeFee(BigDecimal.ZERO, 2));
        assertAmount("0", enabled("5", "0").tradeFee(null, 2));
    }

    // ------------------------------------------------------------------ 设置规范化

    @Test
    void defaultsAndLegacySettingsKeepTheFeeOff() {
        ShopSettings defaults = ShopSettings.defaults();

        assertFalse(defaults.tradeFeeEnabled());
        assertAmount("0", defaults.tradeFeeRate());
        assertAmount("0", defaults.tradeFeeMinAmount());
        assertEquals(ShopTradeFeePayee.BURN, defaults.tradeFeePayee());
        assertNull(defaults.tradeFeePayeeUserId());
        assertFalse(defaults.chargesTradeFee(), "升级后默认不收费，购买链路与改动前一致");

        ShopSettings legacy = new ShopSettings(true, null, BigDecimal.ZERO, List.of(), "官方", null).normalized();
        assertFalse(legacy.chargesTradeFee());
    }

    @Test
    void rateAndMinimumAreValidatedWithChineseMessages() {
        assertEquals("交易手续费费率必须是 0 到 100 之间的百分比", assertThrows(IllegalArgumentException.class,
                () -> enabled("101", "0")).getMessage());
        assertEquals("交易手续费费率必须是 0 到 100 之间的百分比", assertThrows(IllegalArgumentException.class,
                () -> enabled("-1", "0")).getMessage());
        assertEquals("最低手续费不能为负数", assertThrows(IllegalArgumentException.class,
                () -> enabled("1", "-0.01")).getMessage());
    }

    @Test
    void platformPayeeRequiresAUserId() {
        assertEquals("手续费收款方式选择「平台用户」时，必须填写平台用户 ID",
                assertThrows(IllegalArgumentException.class,
                        () -> feeSettings(true, "1", "0", ShopTradeFeePayee.PLATFORM, "   ")).getMessage());

        ShopSettings settings = feeSettings(true, "1", "0", ShopTradeFeePayee.PLATFORM, " 1001 ");
        assertEquals(ShopTradeFeePayee.PLATFORM, settings.tradeFeePayee());
        assertEquals("1001", settings.tradeFeePayeeUserId());
        assertTrue(settings.chargesTradeFee());
    }

    @Test
    void unknownPayeeFallsBackToBurn() {
        assertEquals(ShopTradeFeePayee.BURN, ShopTradeFeePayee.from(null));
        assertEquals(ShopTradeFeePayee.BURN, ShopTradeFeePayee.from("  "));
        assertEquals(ShopTradeFeePayee.BURN, ShopTradeFeePayee.from("WEIRD"));
        assertEquals(ShopTradeFeePayee.PLATFORM, ShopTradeFeePayee.from("platform"));
    }
}
