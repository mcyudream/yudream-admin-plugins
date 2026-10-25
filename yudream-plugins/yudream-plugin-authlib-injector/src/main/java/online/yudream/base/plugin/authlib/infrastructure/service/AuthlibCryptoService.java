package online.yudream.base.plugin.authlib.infrastructure.service;

import online.yudream.base.plugin.authlib.domain.valobj.AuthlibKeyPair;
import online.yudream.base.plugin.authlib.infrastructure.repository.AuthlibRepository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.X509EncodedKeySpec;
import java.security.PublicKey;
import java.security.Signature;
import java.security.GeneralSecurityException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class AuthlibCryptoService {

    private final AuthlibRepository repository;

    public AuthlibCryptoService(AuthlibRepository repository) {
        this.repository = repository;
    }

    public AuthlibKeyPair keyPair() {
        return repository.keyPair().orElseGet(this::generateAndSave);
    }

    /**
     * 导入外部密钥对（联盟共享材质签名密钥）：校验 PEM 可解析、为 RSA 且公私钥匹配后，
     * 以 base64 DER 形态落库。导入后材质签名与元数据 signaturePublickey 均使用该密钥，
     * 联盟内各站点共享同一对即可让跨站客户端互相验证材质签名。
     */
    public void importKeyPair(String publicKeyPem, String privateKeyPem) {
        PublicKey publicPart;
        PrivateKey privatePart;
        try {
            String publicBody = stripPem(publicKeyPem, "PUBLIC KEY");
            publicPart = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder().decode(publicBody)));
            String privateBody = stripPem(privateKeyPem, "PRIVATE KEY");
            privatePart = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(privateBody)));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalArgumentException("密钥解析失败（需 PEM 格式的 RSA 公钥与 PKCS#8 私钥）：" + e.getMessage(), e);
        }
        byte[] probe = "yggdrasil-union-keypair-probe".getBytes(StandardCharsets.UTF_8);
        String signature;
        try {
            Signature signer = Signature.getInstance("SHA1withRSA");
            signer.initSign(privatePart);
            signer.update(probe);
            signature = Base64.getEncoder().encodeToString(signer.sign());
            java.security.Signature verifier = Signature.getInstance("SHA1withRSA");
            verifier.initVerify(publicPart);
            verifier.update(probe);
            if (!verifier.verify(Base64.getDecoder().decode(signature))) {
                throw new IllegalArgumentException("公钥与私钥不匹配");
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("密钥对自验失败：" + e.getMessage(), e);
        }
        repository.saveKeyPair(new AuthlibKeyPair(
                Base64.getEncoder().encodeToString(publicPart.getEncoded()),
                Base64.getEncoder().encodeToString(privatePart.getEncoded())));
    }

    private static String stripPem(String pem, String label) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("PEM 为空");
        }
        String body = pem.replaceAll("-----BEGIN " + label + "-----", "")
                .replaceAll("-----END " + label + "-----", "")
                .replaceAll("\s", "");
        if (body.isEmpty()) {
            throw new IllegalArgumentException("PEM 内容为空");
        }
        return body;
    }

    public String publicKeyPem() {
        String base64 = keyPair().publicKey();
        StringBuilder builder = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        for (int index = 0; index < base64.length(); index += 76) {
            builder.append(base64, index, Math.min(index + 76, base64.length())).append('\n');
        }
        return builder.append("-----END PUBLIC KEY-----\n").toString();
    }

    public String sign(String value) {
        try {
            byte[] privateBytes = Base64.getDecoder().decode(keyPair().privateKey());
            PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(privateKey);
            signature.update(value.getBytes());
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("材质属性签名失败：" + e.getMessage(), e);
        }
    }

    /**
     * 1.19+ 聊天签名证书签发：现生成玩家 RSA-2048 密钥对，用站点签名密钥
     * （即元数据 signaturePublickey 对应密钥）签发公钥，玩家私钥不落库。
     * PEM 标签沿用 authlib-injector ProfileKeyFilter 的写法（PKCS8 内容配
     * "RSA PRIVATE KEY" 标签），与原版客户端 PemCodec 的解析约定一致。
     * 签名格式对齐 Mojang：v1（publicKeySignature，客户端原样放进聊天会话、
     * 服务端用 SHA1withRSA 校验）= SHA1withRSA(玩家 UUID 高 8B ‖ UUID 低 8B ‖
     * 到期 epoch 毫秒 8B 大端 ‖ 公钥 DER)；v2 = SHA256withRSA（到期 epoch 秒
     * 8B 大端 ‖ 0x00000000 ‖ 公钥 DER，供 1.19.1–1.20.4 客户端本地校验）。
     */
    public Map<String, Object> playerCertificateBody(String profileId) {
        try {
            UUID uuid = parseProfileUuid(profileId);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair playerKey = generator.generateKeyPair();
            byte[] publicDer = playerKey.getPublic().getEncoded();
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Instant expiresAt = now.plus(Duration.ofHours(24));
            Instant refreshedAfter = now.plus(Duration.ofHours(18));
            ByteBuffer v1Payload = ByteBuffer.allocate(16 + 8 + publicDer.length)
                    .putLong(uuid.getMostSignificantBits())
                    .putLong(uuid.getLeastSignificantBits())
                    .putLong(expiresAt.toEpochMilli())
                    .put(publicDer);
            ByteBuffer v2Payload = ByteBuffer.allocate(8 + 4 + publicDer.length)
                    .putLong(expiresAt.getEpochSecond())
                    .putInt(0)
                    .put(publicDer);
            Base64.Encoder mime = Base64.getMimeEncoder(76, "\n".getBytes(StandardCharsets.UTF_8));
            Map<String, Object> keyPair = new LinkedHashMap<>();
            keyPair.put("privateKey", pem("RSA PRIVATE KEY", mime.encodeToString(playerKey.getPrivate().getEncoded())));
            keyPair.put("publicKey", pem("RSA PUBLIC KEY", mime.encodeToString(publicDer)));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("keyPair", keyPair);
            body.put("publicKeySignature", Base64.getEncoder().encodeToString(signWith("SHA1withRSA", v1Payload.array())));
            body.put("publicKeySignatureV2", Base64.getEncoder().encodeToString(signWith("SHA256withRSA", v2Payload.array())));
            body.put("expiresAt", DateTimeFormatter.ISO_INSTANT.format(expiresAt));
            body.put("refreshedAfter", DateTimeFormatter.ISO_INSTANT.format(refreshedAfter));
            return body;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("玩家聊天签名证书签发失败：" + e.getMessage(), e);
        }
    }

    private UUID parseProfileUuid(String profileId) {
        String hex = profileId == null ? "" : profileId.replace("-", "");
        if (hex.length() != 32 || !hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            throw new IllegalStateException("角色 UUID 非法，无法签发聊天签名证书：" + profileId);
        }
        return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32));
    }

    private byte[] signWith(String algorithm, byte[] data) throws GeneralSecurityException {
        byte[] privateBytes = Base64.getDecoder().decode(keyPair().privateKey());
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
        Signature signature = Signature.getInstance(algorithm);
        signature.initSign(privateKey);
        signature.update(data);
        return signature.sign();
    }

    private String pem(String label, String base64) {
        return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
    }

    /** 公钥指纹（SHA-256 hex，前端展示/跨站核对用）。 */
    public String publicKeyFingerprint() {
        try {
            byte[] der = Base64.getDecoder().decode(keyPair().publicKey());
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 丢弃现有（含导入的）密钥对，重新自生成并落库。 */
    public AuthlibKeyPair regenerateAndSave() {
        return generateAndSave();
    }

    private AuthlibKeyPair generateAndSave() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(4096);
            KeyPair keyPair = generator.generateKeyPair();
            return repository.saveKeyPair(new AuthlibKeyPair(
                    Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                    Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Authlib RSA 密钥生成失败：" + e.getMessage(), e);
        }
    }
}
