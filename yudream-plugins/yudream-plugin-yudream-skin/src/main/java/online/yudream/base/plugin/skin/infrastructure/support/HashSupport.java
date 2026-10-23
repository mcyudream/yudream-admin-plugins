package online.yudream.base.plugin.skin.infrastructure.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

public class HashSupport {

    /** 离线派生 UUIDv5 的固定命名空间（skin 内自持）。 */
    private static final UUID OFFLINE_NAMESPACE =
            UUID.nameUUIDFromBytes("online.yudream.skin:offline:v2".getBytes(StandardCharsets.UTF_8));

    private HashSupport() {
    }

    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest("SHA-256", bytes));
    }

    /** 旧版按名派生（v3）；仅保留给既有数据兼容，新角色不再使用（同名跨站/跨用户撞 UUID）。 */
    public static String playerUuid(String name) {
        UUID uuid = UUID.nameUUIDFromBytes(("YuDreamSkin:" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
        return uuid.toString().replace("-", "");
    }

    /**
     * 新版离线角色 UUID（UUIDv5，RFC 4122 命名空间派生）：
     * 输入 = [联合身份命名空间前缀（可空） ‖ 宿主用户主键 ‖ 不可变联合角色 ID]，
     * **不含角色名**——同一身份改名不变；新建角色的随机 identityId 永不复用，因此
     * 不同角色（即使删后重建同名）也不共享 UUID。
     */
    public static String offlineUuid(String unionIssuerKeyId, String ownerId, String offlineIdentityId) {
        String input = (unionIssuerKeyId == null ? "" : unionIssuerKeyId.trim()) + ":" + ownerId + ":" + offlineIdentityId;
        byte[] hash = sha1(concat(uuidBytes(OFFLINE_NAMESPACE), input.getBytes(StandardCharsets.UTF_8)));
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);   // version 5
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);   // RFC 4122 variant
        UUID uuid = new UUID(longAt(hash, 0), longAt(hash, 8));
        return uuid.toString().replace("-", "");
    }

    private static byte[] uuidBytes(UUID namespace) {
        return new byte[]{
                (byte) (namespace.getMostSignificantBits() >>> 56), (byte) (namespace.getMostSignificantBits() >>> 48),
                (byte) (namespace.getMostSignificantBits() >>> 40), (byte) (namespace.getMostSignificantBits() >>> 32),
                (byte) (namespace.getMostSignificantBits() >>> 24), (byte) (namespace.getMostSignificantBits() >>> 16),
                (byte) (namespace.getMostSignificantBits() >>> 8), (byte) namespace.getMostSignificantBits(),
                (byte) (namespace.getLeastSignificantBits() >>> 56), (byte) (namespace.getLeastSignificantBits() >>> 48),
                (byte) (namespace.getLeastSignificantBits() >>> 40), (byte) (namespace.getLeastSignificantBits() >>> 32),
                (byte) (namespace.getLeastSignificantBits() >>> 24), (byte) (namespace.getLeastSignificantBits() >>> 16),
                (byte) (namespace.getLeastSignificantBits() >>> 8), (byte) namespace.getLeastSignificantBits()
        };
    }

    private static long longAt(byte[] bytes, int offset) {
        long value = 0;
        for (int index = 0; index < 8; index++) {
            value = (value << 8) | (bytes[offset + index] & 0xffL);
        }
        return value;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] merged = new byte[first.length + second.length];
        System.arraycopy(first, 0, merged, 0, first.length);
        System.arraycopy(second, 0, merged, first.length, second.length);
        return merged;
    }

    private static byte[] sha1(byte[] bytes) {
        return digest("SHA-1", bytes);
    }

    private static byte[] digest(String algorithm, byte[] bytes) {
        try {
            return MessageDigest.getInstance(algorithm).digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " 不可用", e);
        }
    }
}
