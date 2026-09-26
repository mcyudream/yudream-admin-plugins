package online.yudream.base.plugin.mcpanel.infrastructure.dns;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 云解析驱动的公共工具：相对名换算、签名用摘要、UTC 时间格式。 */
public final class DnsNames {

    private DnsNames() {
    }

    /**
     * 完整记录名 → 区域内的相对名（阿里云 RR / 腾讯云 SubDomain）。
     * 记录名必须落在区域根域名之内，否则说明「游戏域后缀」与「根域名」配置不一致，直接报错。
     */
    public static String relative(String name, String root) {
        String normalizedRoot = normalize(root);
        String normalizedName = normalize(name);
        if (normalizedName.equals(normalizedRoot)) {
            return "@";
        }
        String suffix = "." + normalizedRoot;
        if (!normalizedName.endsWith(suffix)) {
            throw new DnsCallException("记录名 " + name + " 不在根域名 " + root + " 之内，请核对面板设置");
        }
        return normalizedName.substring(0, normalizedName.length() - suffix.length());
    }

    /** 名称是否落在根域名之内（含根域名自身）——设置保存时校验「后缀 vs 根域名」用。 */
    public static boolean covers(String root, String name) {
        String normalizedRoot = normalize(root);
        String normalizedName = normalize(name);
        return !normalizedRoot.isEmpty() && !normalizedName.isEmpty()
                && (normalizedName.equals(normalizedRoot) || normalizedName.endsWith("." + normalizedRoot));
    }

    public static String normalize(String value) {
        String trimmed = value == null ? "" : value.trim().toLowerCase();
        while (trimmed.startsWith(".")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static String hostOf(String apiBase, String fallbackHost) {
        String base = apiBase == null ? "" : apiBase.trim();
        if (base.isEmpty()) {
            return fallbackHost;
        }
        String withoutScheme = base.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        int slash = withoutScheme.indexOf('/');
        String host = slash >= 0 ? withoutScheme.substring(0, slash) : withoutScheme;
        return host.isBlank() ? fallbackHost : host;
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new DnsCallException("摘要计算失败：" + error.getMessage(), error);
        }
    }

    static byte[] hmac(String algorithm, byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key, algorithm));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new DnsCallException("签名计算失败：" + error.getMessage(), error);
        }
    }

    static String utcDate(long epochSeconds) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochSecond(epochSeconds));
    }

    static String utcTimestamp() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

    /** 错误信息截断：云商返回体可能很长，只保留可读片段。 */
    static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String text = body.trim().replaceAll("\\s+", " ");
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }
}
