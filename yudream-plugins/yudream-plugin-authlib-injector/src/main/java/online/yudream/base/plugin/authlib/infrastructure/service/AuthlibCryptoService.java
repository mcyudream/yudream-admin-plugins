package online.yudream.base.plugin.authlib.infrastructure.service;

import online.yudream.base.plugin.authlib.domain.valobj.AuthlibKeyPair;
import online.yudream.base.plugin.authlib.infrastructure.repository.AuthlibRepository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
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

public class AuthlibCryptoService {

    private final AuthlibRepository repository;

    public AuthlibCryptoService(AuthlibRepository repository) {
        this.repository = repository;
    }

    public AuthlibKeyPair keyPair() {
        return repository.keyPair().orElseGet(this::generateAndSave);
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
     * 签名格式对齐 Mojang：v1 = SHA1withRSA(公钥 DER)；v2 = SHA256withRSA
     * （到期 epoch 秒 8B 大端 ‖ 0x00000000 ‖ 公钥 DER）。
     */
    public Map<String, Object> playerCertificateBody() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair playerKey = generator.generateKeyPair();
            byte[] publicDer = playerKey.getPublic().getEncoded();
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Instant expiresAt = now.plus(Duration.ofHours(24));
            Instant refreshedAfter = now.plus(Duration.ofHours(18));
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
            body.put("publicKeySignature", Base64.getEncoder().encodeToString(signWith("SHA1withRSA", publicDer)));
            body.put("publicKeySignatureV2", Base64.getEncoder().encodeToString(signWith("SHA256withRSA", v2Payload.array())));
            body.put("expiresAt", DateTimeFormatter.ISO_INSTANT.format(expiresAt));
            body.put("refreshedAfter", DateTimeFormatter.ISO_INSTANT.format(refreshedAfter));
            return body;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("玩家聊天签名证书签发失败：" + e.getMessage(), e);
        }
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
