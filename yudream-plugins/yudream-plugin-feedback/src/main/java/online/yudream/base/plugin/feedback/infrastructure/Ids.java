package online.yudream.base.plugin.feedback.infrastructure;

import java.security.SecureRandom;
import java.util.UUID;

/** 插件内 ID 生成：短小、可读、可排序。 */
public final class Ids {

    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String next() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static String token(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }
}
