package online.yudream.base.plugin.playtimepoints.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 积分计算：积分 = 权重 × 有效分钟 ÷ 每积分所需分钟数。
 * 中间值保留 4 位小数，零头（carry）跨会话累计，凑满 1 分才向钱包入账整数分。
 *
 * <p>群组服下同一段会话按子服拆分后逐子服加权求和，再一次性除以每积分分钟数；只有一个分项时
 * 与 {@link #points(long, BigDecimal, long)} 完全等价，因此整服口径的行为没有变化。
 */
public final class PointsCalculator {

    public static final int SCALE = 4;
    private static final BigDecimal MILLIS_PER_MINUTE = BigDecimal.valueOf(60_000);
    private static final BigDecimal MILLIS_PER_HOUR = BigDecimal.valueOf(3_600_000L);

    private PointsCalculator() {
    }

    /** 一个子服本次会话的有效时长与权重。 */
    public record WeightedMinutes(long effectiveMillis, BigDecimal weight) {
    }

    /**
     * 按时薪折算本条打卡的积分：{@code 每小时积分 × 有效在线毫秒 ÷ 3_600_000}，
     * 并按**目标货币资产精度**四舍五入（HALF_UP）。
     *
     * <p>取整必须按资产精度做：钱包按资产精度入账，0 位小数货币下 7.33 分会被永久拒绝，
     * 而永久失败会阻塞打卡积分游标。因此这里直接给出可以入账的金额，不足一个最小单位时返回 0。</p>
     *
     * @param effectiveMillis 该打卡窗口内的有效在线毫秒数；{@code <= 0} 返回 0（不发分）
     * @param hourlyPoints    每小时积分；{@code null} 或非正返回 0
     * @param scale           目标货币资产精度（负值按 0 处理）
     */
    public static BigDecimal hourlyPoints(long effectiveMillis, BigDecimal hourlyPoints, int scale) {
        int targetScale = Math.max(scale, 0);
        if (effectiveMillis <= 0 || hourlyPoints == null || hourlyPoints.signum() <= 0) {
            return BigDecimal.ZERO.setScale(targetScale, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(effectiveMillis)
                .multiply(hourlyPoints)
                .divide(MILLIS_PER_HOUR, targetScale, RoundingMode.HALF_UP);
    }

    /** 有效分钟数（向下保护为非负）。 */
    public static BigDecimal effectiveMinutes(long effectiveMillis) {
        BigDecimal minutes = BigDecimal.valueOf(Math.max(0L, effectiveMillis))
                .divide(MILLIS_PER_MINUTE, SCALE, RoundingMode.HALF_UP);
        return minutes.signum() < 0 ? BigDecimal.ZERO : minutes;
    }

    /** 本次会话应得积分（含小数零头）；权重非正或配置非法时为 0。 */
    public static BigDecimal points(long effectiveMillis, BigDecimal weight, long minutesPerPoint) {
        if (minutesPerPoint <= 0 || weight == null || weight.signum() <= 0) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        return effectiveMinutes(effectiveMillis)
                .multiply(weight)
                .divide(BigDecimal.valueOf(minutesPerPoint), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 按子服加权求和：Σ(该子服有效分钟 × 该子服权重) ÷ 每积分分钟数。
     *
     * <p>权重非正或未参与结算的子服由调用方提前剔除；这里再兜一层，权重为 0 的子服贡献 0 分。
     * 没有任何有效分项、或每积分分钟数非法时返回 0。
     */
    public static BigDecimal weightedPoints(List<WeightedMinutes> parts, long minutesPerPoint) {
        if (minutesPerPoint <= 0 || parts == null || parts.isEmpty()) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        BigDecimal weighted = BigDecimal.ZERO;
        for (WeightedMinutes part : parts) {
            if (part == null || part.weight() == null || part.weight().signum() <= 0) {
                continue;
            }
            weighted = weighted.add(effectiveMinutes(part.effectiveMillis()).multiply(part.weight()));
        }
        if (weighted.signum() <= 0) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        return weighted.divide(BigDecimal.valueOf(minutesPerPoint), SCALE, RoundingMode.HALF_UP);
    }

    /** 累计零头 + 本次积分 → [应入账整数分, 新零头]。 */
    public static BigDecimal[] splitCredit(BigDecimal carry, BigDecimal points) {
        BigDecimal total = carry.add(points).setScale(SCALE, RoundingMode.DOWN);
        BigDecimal credit = total.setScale(0, RoundingMode.FLOOR);
        return new BigDecimal[]{credit, total.subtract(credit)};
    }

    /** 读取持久化的零头，空/非法按 0 处理。 */
    public static BigDecimal parseCarry(String carry) {
        if (carry == null || carry.isBlank()) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        try {
            return new BigDecimal(carry.trim()).setScale(SCALE, RoundingMode.DOWN);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
    }

    /** 展示用十进制字符串：去掉尾随 0。 */
    public static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
