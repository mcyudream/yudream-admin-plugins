package online.yudream.base.plugin.playtimepoints.domain.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

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

    @Test
    void weightedPointsSumsSubServerContributions() {
        // (30 分钟 × 权重 2 + 30 分钟 × 权重 1) ÷ 60 分钟/分 = 1.5
        List<PointsCalculator.WeightedMinutes> parts = List.of(
                new PointsCalculator.WeightedMinutes(30 * 60_000L, new BigDecimal("2")),
                new PointsCalculator.WeightedMinutes(30 * 60_000L, BigDecimal.ONE));
        assertEquals(new BigDecimal("1.5000"), PointsCalculator.weightedPoints(parts, 60));
    }

    @Test
    void weightedPointsIgnoresZeroAndInvalidWeights() {
        List<PointsCalculator.WeightedMinutes> stopped = List.of(
                new PointsCalculator.WeightedMinutes(60 * 60_000L, BigDecimal.ZERO),
                new PointsCalculator.WeightedMinutes(60 * 60_000L, new BigDecimal("-1")),
                new PointsCalculator.WeightedMinutes(60 * 60_000L, null));
        assertEquals(0, PointsCalculator.weightedPoints(stopped, 60).compareTo(BigDecimal.ZERO));
        assertEquals(0, PointsCalculator.weightedPoints(List.of(), 60).compareTo(BigDecimal.ZERO));
        assertEquals(0, PointsCalculator.weightedPoints(null, 60).compareTo(BigDecimal.ZERO));
        // 每积分分钟数非法时与 points 一样返回 0
        assertEquals(0, PointsCalculator.weightedPoints(
                List.of(new PointsCalculator.WeightedMinutes(60_000L, BigDecimal.ONE)), 0)
                .compareTo(BigDecimal.ZERO));
    }

    /** 只有一个分项时必须与改造前的整服公式逐位一致（旧数据等价性的底线）。 */
    @Test
    void weightedPointsMatchesSingleBucketPoints() {
        for (long millis : new long[]{0L, 1L, 59_999L, 90_000L, 30 * 60_000L, 50 * 60_000L, 121 * 60_000L}) {
            for (String weight : new String[]{"1", "1.5", "0.01", "2.25", "999"}) {
                for (long minutesPerPoint : new long[]{30L, 60L, 7L}) {
                    assertEquals(
                            PointsCalculator.points(millis, new BigDecimal(weight), minutesPerPoint),
                            PointsCalculator.weightedPoints(
                                    List.of(new PointsCalculator.WeightedMinutes(millis, new BigDecimal(weight))),
                                    minutesPerPoint),
                            "毫秒 " + millis + " 权重 " + weight + " 每积分分钟 " + minutesPerPoint);
                }
            }
        }
    }

    /* ---------- 按时薪折算 ---------- */

    /** 按时薪折算：积分 = 时薪 × 有效毫秒 ÷ 3_600_000，按目标精度 HALF_UP 取整。 */
    @Test
    void hourlyPointsConvertsMillisAtTargetScale() {
        // 44 分钟 × 时薪 10 = 7.3333… → 2 位精度 7.33
        assertEquals(0, PointsCalculator.hourlyPoints(44 * 60_000L, new BigDecimal("10"), 2)
                .compareTo(new BigDecimal("7.33")));
        assertEquals(2, PointsCalculator.hourlyPoints(44 * 60_000L, new BigDecimal("10"), 2).scale());
        // 0 位精度：0.5 分按 HALF_UP 进位为 1
        assertEquals(0, PointsCalculator.hourlyPoints(30 * 60_000L, BigDecimal.ONE, 0)
                .compareTo(BigDecimal.ONE));
        // 0.25 分在 0 位精度下取整为 0（调用方据此判断「不发分」）
        assertEquals(0, PointsCalculator.hourlyPoints(30 * 60_000L, new BigDecimal("0.5"), 0)
                .compareTo(BigDecimal.ZERO));
        // 整整一小时 × 时薪 12.5 = 12.5，4 位精度
        assertEquals(0, PointsCalculator.hourlyPoints(3_600_000L, new BigDecimal("12.5"), 4)
                .compareTo(new BigDecimal("12.5000")));
    }

    /** 没有有效时长、时薪非法或精度为负：一律返回目标精度的 0，绝不抛错。 */
    @Test
    void hourlyPointsIsZeroForMissingDurationOrInvalidRate() {
        assertEquals(0, PointsCalculator.hourlyPoints(0L, new BigDecimal("10"), 2).compareTo(BigDecimal.ZERO));
        assertEquals(0, PointsCalculator.hourlyPoints(-1L, new BigDecimal("10"), 2).compareTo(BigDecimal.ZERO));
        assertEquals(0, PointsCalculator.hourlyPoints(3_600_000L, BigDecimal.ZERO, 2).compareTo(BigDecimal.ZERO));
        assertEquals(0, PointsCalculator.hourlyPoints(3_600_000L, new BigDecimal("-1"), 2).compareTo(BigDecimal.ZERO));
        assertEquals(0, PointsCalculator.hourlyPoints(3_600_000L, null, 2).compareTo(BigDecimal.ZERO));
        // 负精度按 0 处理，不抛 ArithmeticException
        assertEquals(0, PointsCalculator.hourlyPoints(30 * 60_000L, BigDecimal.ONE, -3).compareTo(BigDecimal.ONE));
    }

    /** 固定模式的行为不受影响：新增方法不改变既有 points/weightedPoints 的结果。 */
    @Test
    void hourlyPointsDoesNotAffectExistingFormulas() {
        assertEquals(new BigDecimal("2.2500"),
                PointsCalculator.points(90 * 60_000L, new BigDecimal("1.5"), 60));
        assertEquals(0, PointsCalculator.hourlyPoints(3_600_000L, new BigDecimal("2"), 0)
                .compareTo(new BigDecimal("2")));
    }
}
