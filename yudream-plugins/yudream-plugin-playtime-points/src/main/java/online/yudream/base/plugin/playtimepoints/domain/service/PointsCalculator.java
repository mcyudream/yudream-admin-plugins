package online.yudream.base.plugin.playtimepoints.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 积分计算：积分 = 权重 × 有效分钟 ÷ 每积分所需分钟数。
 * 中间值保留 4 位小数，零头（carry）跨会话累计，凑满 1 分才向钱包入账整数分。
 */
public final class PointsCalculator {

    public static final int SCALE = 4;
    private static final BigDecimal MILLIS_PER_MINUTE = BigDecimal.valueOf(60_000);

    private PointsCalculator() {
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
