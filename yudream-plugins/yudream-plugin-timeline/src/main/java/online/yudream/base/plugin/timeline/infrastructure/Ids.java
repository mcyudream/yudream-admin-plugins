package online.yudream.base.plugin.timeline.infrastructure;

import java.security.SecureRandom;

/**
 * ID 生成器：倒置毫秒时间戳（13 位补零）+ 8 位 hex 后缀。
 * 宿主文档存储按 _id 字典序升序返回，倒置后字典序即「最新在前」；
 * 同一毫秒内的连续生成靠后缀递减保持次序（每毫秒随机起点，预留 4096 次递减空间）。
 */
public final class Ids {
    private static final long MAX_MILLIS = 9_999_999_999_999L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static long lastInverted = -1;
    private static int lastSuffix;

    private Ids() {
    }

    public static synchronized String newId() {
        long inverted = MAX_MILLIS - System.currentTimeMillis();
        if (inverted != lastInverted) {
            lastInverted = inverted;
            lastSuffix = 4096 + RANDOM.nextInt(Integer.MAX_VALUE - 4096);
        }
        else {
            lastSuffix--;
        }
        return String.format("%013d-%08x", inverted, lastSuffix);
    }
}
