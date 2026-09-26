package online.yudream.base.plugin.playtimepoints.domain.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PointsCalculatorTest {

    @Test
    void effectiveMinutesConvertsMillisAndClampsNegative() {
        assertEquals(0, PointsCalculator.effectiveMinutes(-100).compareTo(BigDecimal.ZERO));
        assertEquals(new BigDecimal("1.5000"), PointsCalculator.effectiveMinutes(90_000));
        assertEquals(new BigDecimal("90.0000"), PointsCalculator.effectiveMinutes(90 * 60_000L));
    }

    @Test
    void pointsFollowsWeightAndMinutesPerPoint() {
        // 90 分钟 × 权重 1.5 ÷ 60 分钟/分 = 2.25
        assertEquals(new BigDecimal("2.2500"),
                PointsCalculator.points(90 * 60_000L, new BigDecimal("1.5"), 60));
        // 30 分钟 × 权重 1 ÷ 30 分钟/分 = 1
        assertEquals(new BigDecimal("1.0000"),
                PointsCalculator.points(30 * 60_000L, BigDecimal.ONE, 30));
    }

    @Test
    void pointsIsZeroForInvalidConfig() {
        assertEquals(0, PointsCalculator.points(60_000, BigDecimal.ZERO, 60).compareTo(BigDecimal.ZERO));
        assertEquals(0, PointsCalculator.points(60_000, new BigDecimal("-1"), 60).compareTo(BigDecimal.ZERO));
        assertEquals(0, PointsCalculator.points(60_000, BigDecimal.ONE, 0).compareTo(BigDecimal.ZERO));
    }

    @Test
    void splitCreditFloorsIntegerAndKeepsCarry() {
        BigDecimal[] split = PointsCalculator.splitCredit(new BigDecimal("0.2500"), new BigDecimal("0.9000"));
        assertEquals(0, split[0].compareTo(BigDecimal.ONE));
        assertEquals(0, split[1].compareTo(new BigDecimal("0.15")));
    }

    @Test
    void splitCreditKeepsZeroCreditWhenBelowOne() {
        BigDecimal[] split = PointsCalculator.splitCredit(BigDecimal.ZERO, new BigDecimal("0.5000"));
        assertEquals(0, split[0].compareTo(BigDecimal.ZERO));
        assertEquals(0, split[1].compareTo(new BigDecimal("0.5")));
    }

    @Test
    void parseCarryToleratesBlankAndInvalid() {
        assertEquals(0, PointsCalculator.parseCarry(null).compareTo(BigDecimal.ZERO));
        assertEquals(0, PointsCalculator.parseCarry(" ").compareTo(BigDecimal.ZERO));
        assertEquals(0, PointsCalculator.parseCarry("abc").compareTo(BigDecimal.ZERO));
        assertEquals(0, PointsCalculator.parseCarry("0.12345").compareTo(new BigDecimal("0.1234")));
    }

    @Test
    void plainStripsTrailingZeros() {
        assertEquals("2.25", PointsCalculator.plain(new BigDecimal("2.2500")));
        assertEquals("1", PointsCalculator.plain(new BigDecimal("1.0000")));
        assertEquals("0", PointsCalculator.plain(new BigDecimal("0.0000")));
    }
}
