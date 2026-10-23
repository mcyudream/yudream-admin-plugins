package online.yudream.base.plugin.ymcl.application.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家下载令牌（YAP 下载分发扩展）。
 *
 * <p>启动器经 {@code POST /v1/download/token}（域会话）换取短效下载令牌，
 * 下载时以 {@code ?key=<token>} 携带给 YDA 适配的镜像网关；网关持网关共享
 * 密钥回调 {@code POST /v1/download/token/verify} 换取玩家身份——会话令牌
 * 永不出站，镜像侧只见到短效、可撤销的下载令牌。
 *
 * <p>令牌形态 {@code dlt1.<userId>.<expMillis>.<hmac>}（base64url，无填充），
 * HMAC-SHA256 签名密钥为站内 {@code tokenSecret}（见
 * {@link YmclDownloadSourceService}）。校验走签名与有效期，无状态、无落库。
 */
public class YmclDownloadTokenService {

    public static final String TOKEN_PREFIX = "dlt1";
    /** 默认 6 小时：覆盖一次大型整合包更新窗口，轮换密钥即全量失效。 */
    public static final long DEFAULT_TTL_MILLIS = 6 * 60 * 60 * 1000L;
    /** 提前 10 分钟视为过期，避免网关校验通过后令牌在传输途中失效。 */
    private static final long EXPIRY_SKEW_MILLIS = 10 * 60 * 1000L;

    private final YmclDownloadSourceService sources;

    public YmclDownloadTokenService(YmclDownloadSourceService sources) {
        this.sources = sources;
    }

    /** 为玩家签发下载令牌。 */
    public Map<String, Object> issue(Long userId) {
        long expiresAt = System.currentTimeMillis() + DEFAULT_TTL_MILLIS;
        String token = mint(userId, expiresAt);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("token", token);
        payload.put("expires_at", expiresAt);
        payload.put("token_type", "download");
        return payload;
    }

    /**
     * 校验下载令牌并给出玩家身份。返回载荷（{@code active:true} +
     * {@code user_id}/{@code expires_at}）；无效时返回 {@code active:false}。
     */
    public Map<String, Object> verify(String token) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Long userId = userIdOf(token);
        if (userId == null) {
            payload.put("active", false);
            return payload;
        }
        payload.put("active", true);
        payload.put("user_id", String.valueOf(userId));
        payload.put("expires_at", expiresOf(token));
        return payload;
    }

    /** 令牌对应的玩家 id；无效/过期/签名不符返回 null。 */
    public Long userIdOf(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX + ".")) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        try {
            long userId = Long.parseLong(unpad(parts[1]));
            long expiresAt = Long.parseLong(unpad(parts[2]));
            if (expiresAt - EXPIRY_SKEW_MILLIS <= System.currentTimeMillis()) {
                return null;
            }
            byte[] expected = hmac(parts[1] + "." + parts[2]);
            byte[] provided = Base64.getUrlDecoder().decode(parts[3]);
            if (!java.security.MessageDigest.isEqual(expected, provided)) {
                return null;
            }
            return userId;
        } catch (IllegalArgumentException | IllegalStateException error) {
            return null;
        }
    }

    private long expiresOf(String token) {
        String[] parts = token.split("\\.");
        try {
            return Long.parseLong(unpad(parts[2]));
        } catch (RuntimeException error) {
            return 0L;
        }
    }

    /** 包内可见以便测试构造过期/篡改令牌。 */
    String mint(Long userId, long expiresAt) {
        String userPart = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(userId).getBytes(StandardCharsets.UTF_8));
        String expPart = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(expiresAt).getBytes(StandardCharsets.UTF_8));
        byte[] signature = hmac(userPart + "." + expPart);
        return TOKEN_PREFIX + "." + userPart + "." + expPart + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    sources.tokenSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("Failed to sign download token", error);
        }
    }

    private static String unpad(String base64Url) {
        byte[] raw = Base64.getUrlDecoder().decode(pad(base64Url));
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static String pad(String base64Url) {
        int remainder = base64Url.length() % 4;
        return remainder == 0 ? base64Url : base64Url + "=".repeat(4 - remainder);
    }
}
