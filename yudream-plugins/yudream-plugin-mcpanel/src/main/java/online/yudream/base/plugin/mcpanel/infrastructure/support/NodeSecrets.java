package online.yudream.base.plugin.mcpanel.infrastructure.support;

import online.yudream.base.plugin.spi.system.secret.PluginSecretStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 节点凭据与令牌：
 * - nodeSecret 存宿主 SecretStore（enclave），管理端永不回显；
 * - enroll token 明文只在签发响应出现一次，落库仅存 SHA-256 摘要。
 */
public final class NodeSecrets {

    private static final String SECRET_KEY_PREFIX = "nodes/";
    private static final String SECRET_KEY_SUFFIX = "/secret";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PluginSecretStore store;

    public NodeSecrets(PluginSecretStore store) {
        this.store = store;
    }

    public String ensureSecret(String nodeId) {
        return secret(nodeId).orElseGet(() -> {
            String secret = newToken();
            store.put(key(nodeId), secret.getBytes(StandardCharsets.UTF_8));
            return secret;
        });
    }

    public java.util.Optional<String> secret(String nodeId) {
        return store.get(key(nodeId)).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    public boolean hasSecret(String nodeId) {
        return store.get(key(nodeId)).isPresent();
    }

    public void delete(String nodeId) {
        store.delete(key(nodeId));
    }

    /** 通用键值 secret（设置页密文：CF token 等），键需自带命名空间前缀。 */
    public void putRaw(String key, String value) {
        store.put(key, value.getBytes(StandardCharsets.UTF_8));
    }

    public java.util.Optional<String> raw(String key) {
        return store.get(key).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    public void deleteRaw(String key) {
        store.delete(key);
    }

    public static String key(String nodeId) {
        return SECRET_KEY_PREFIX + nodeId + SECRET_KEY_SUFFIX;
    }

    /** 32 字节随机 → base64url（43 字符，无填充）。用于 nodeSecret 与 enroll token。 */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256（UTF-8 文本）。 */
    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256（原始字节：上传校验和等必须对原始 bytes 求摘要，不得先转 base64 字符串）。 */
    public static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }
}
