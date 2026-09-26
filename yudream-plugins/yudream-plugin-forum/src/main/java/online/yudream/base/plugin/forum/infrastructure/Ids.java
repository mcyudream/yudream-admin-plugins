package online.yudream.base.plugin.forum.infrastructure;

import java.security.SecureRandom;

public final class Ids {
    private static final SecureRandom RANDOM = new SecureRandom();
    private Ids() {}
    public static String newId() {
        return Long.toString(System.currentTimeMillis(), 36) + "-" + Long.toString(RANDOM.nextLong() & Long.MAX_VALUE, 36);
    }
}
