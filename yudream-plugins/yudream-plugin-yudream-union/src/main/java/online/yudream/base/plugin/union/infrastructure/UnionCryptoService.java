package online.yudream.base.plugin.union.infrastructure;

import online.yudream.base.plugin.union.infrastructure.repository.UnionRepository;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.secret.PluginSecretStore;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 联合签名密钥（RSA-4096，SHA256withRSA）：与 Yggdrasil 材质签名密钥完全独立。
 * 联合私钥只允许进入插件 SecretStore；宿主未提供安全存储时 fail-closed，禁止降级写入文档库。
 */
public class UnionCryptoService {

    public static final String SECRET_KEY = "union-signing-key";
    private static final String SETTING_KEYPAIR = "union-keypair";

    private final UnionRepository repository;
    private final PluginSecretStore secrets;

    public UnionCryptoService(PluginContext context, UnionRepository repository) {
        this.repository = repository;
        PluginSecretStore store;
        try {
            store = context.secrets();
        } catch (RuntimeException e) {
            store = null;
        }
        this.secrets = store;
    }

    public record UnionKeyPair(String publicKeyBase64, String privateKeyBase64, boolean privateKeyInSecretStore) {
    }

    public synchronized UnionKeyPair keyPair() {
        Optional<Map<String, Object>> stored = repository.setting(SETTING_KEYPAIR);
        if (stored.isPresent()) {
            Map<String, Object> document = stored.get();
            String storage = String.valueOf(document.get("privateKeyStored"));
            if (!"secret-store".equals(storage)) {
                throw new IllegalStateException("联合认证拒绝使用普通文档库中的私钥；请迁移到 SecretStore 后重新生成密钥");
            }
            String privateKey = readPrivateKey(storage);
            return new UnionKeyPair(String.valueOf(document.get("publicKey")), privateKey, true);
        }
        return generateAndSave();
    }

    public String publicKeyPem() {
        return pem("PUBLIC KEY", keyPair().publicKeyBase64());
    }

    /** issuerKeyId：公钥 DER SHA-256 前 16 字节 hex（联合身份命名空间）。 */
    public String issuerKeyId() {
        return issuerKeyId(publicKeyPem());
    }

    /** 任一联合 PEM 公钥对应的 issuerKeyId（受信断言的 issuer claim 校验用）。 */
    public static String issuerKeyId(String publicKeyPem) {
        try {
            byte[] der = Base64.getMimeDecoder().decode(pemBody(publicKeyPem));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < 16; index++) {
                builder.append(String.format("%02x", digest[index]));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("联合 issuerKeyId 计算失败：" + e.getMessage(), e);
        }
    }

    public String sign(byte[] data) {
        try {
            PrivateKey privateKey = privateKey();
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(data);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("联合断言签名失败：" + e.getMessage(), e);
        }
    }

    /** 验签：pem 支持主/备两钥；任一命中即通过。 */
    public boolean verify(String publicKeyPem, byte[] data, String signatureBase64) {
        if (publicKeyPem == null || publicKeyPem.isBlank() || signatureBase64 == null || signatureBase64.isBlank()) {
            return false;
        }
        try {
            byte[] der = Base64.getMimeDecoder().decode(pemBody(publicKeyPem));
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(data);
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            return false;
        }
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String value) {
        StringBuilder builder = new StringBuilder();
        for (byte item : sha256(value.getBytes(StandardCharsets.UTF_8))) {
            builder.append(String.format("%02x", item));
        }
        return builder.toString();
    }

    public static String pem(String label, String base64) {
        StringBuilder builder = new StringBuilder("-----BEGIN " + label + "-----\n");
        for (int index = 0; index < base64.length(); index += 76) {
            builder.append(base64, index, Math.min(index + 76, base64.length())).append('\n');
        }
        return builder.append("-----END ").append(label).append("-----\n").toString();
    }

    public static String pemBody(String pem) {
        return pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
    }

    private UnionKeyPair generateAndSave() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(4096);
            KeyPair keyPair = generator.generateKeyPair();
            String publicBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            String privateBase64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
            if (secrets == null) {
                throw new IllegalStateException("联合认证需要宿主 PluginSecretStore，拒绝将签名私钥写入文档库");
            }
            try {
                secrets.put(SECRET_KEY, privateBase64.getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw new IllegalStateException("联合认证无法写入 SecretStore：" + e.getMessage(), e);
            }
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("publicKey", publicBase64);
            document.put("privateKeyStored", "secret-store");
            repository.saveSetting(SETTING_KEYPAIR, document);
            return new UnionKeyPair(publicBase64, privateBase64, true);
        } catch (Exception e) {
            throw new IllegalStateException("联合签名密钥生成失败：" + e.getMessage(), e);
        }
    }

    private String readPrivateKey(String stored) {
        if (!"secret-store".equals(stored) || secrets == null) {
            throw new IllegalStateException("联合签名私钥未安全存放在 SecretStore");
        }
        return secrets.get(SECRET_KEY).map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .orElseThrow(() -> new IllegalStateException("联合签名私钥在 SecretStore 中缺失"));
    }

    private PrivateKey privateKey() throws Exception {
        byte[] der = Base64.getDecoder().decode(keyPair().privateKeyBase64());
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String lower(Locale locale, String value) {
        return value == null ? "" : value.toLowerCase(locale);
    }
}
