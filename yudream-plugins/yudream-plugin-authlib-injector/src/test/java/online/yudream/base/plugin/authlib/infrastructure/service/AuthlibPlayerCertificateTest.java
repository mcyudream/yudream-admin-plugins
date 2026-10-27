package online.yudream.base.plugin.authlib.infrastructure.service;

import online.yudream.base.plugin.authlib.domain.valobj.AuthlibKeyPair;
import online.yudream.base.plugin.authlib.infrastructure.repository.AuthlibRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthlibPlayerCertificateTest {

    private static final UUID PLAYER_ID = UUID.fromString("00001234-5678-90ab-cdef-0123456789ab");
    private static KeyPair issuer;
    private static AuthlibCryptoService crypto;

    @BeforeAll
    static void setUpIssuer() throws Exception {
        issuer = newRsaKeyPair();
        AuthlibKeyPair stored = new AuthlibKeyPair(
                Base64.getEncoder().encodeToString(issuer.getPublic().getEncoded()),
                Base64.getEncoder().encodeToString(issuer.getPrivate().getEncoded()));
        crypto = new AuthlibCryptoService(new AuthlibRepository(null) {
            @Override
            public Optional<AuthlibKeyPair> keyPair() {
                return Optional.of(stored);
            }
        });
    }

    @Test
    void modernV2SignsUuidExpiryMillisAndPublicDerWithSha1() throws Exception {
        // Modern authlib's publicKeySignature() accessor is mapped to JSON publicKeySignatureV2.
        for (String id : new String[]{PLAYER_ID.toString(), PLAYER_ID.toString().replace("-", "")}) {
            Map<String, Object> body = crypto.playerCertificateBody(id);
            byte[] payload = modernPayload(PLAYER_ID, expiry(body), publicDer(body));

            assertTrue(verify(issuer.getPublic(), payload, signature(body, "publicKeySignatureV2")));
        }
    }

    @Test
    void legacyV1SignsTextExpiryFollowedByMojangPem() throws Exception {
        Map<String, Object> body = crypto.playerCertificateBody(PLAYER_ID.toString());
        String base64 = Base64.getEncoder().encodeToString(publicDer(body));
        StringBuilder pem = new StringBuilder("-----BEGIN RSA PUBLIC KEY-----\n");
        for (int offset = 0; offset < base64.length(); offset += 76) {
            pem.append(base64, offset, Math.min(offset + 76, base64.length())).append('\n');
        }
        pem.append("-----END RSA PUBLIC KEY-----\n");
        byte[] payload = (Long.toString(expiry(body).toEpochMilli()) + pem).getBytes(StandardCharsets.UTF_8);

        assertTrue(verify(issuer.getPublic(), payload, signature(body, "publicKeySignature")));
        assertFalse(verify(issuer.getPublic(), modernPayload(PLAYER_ID, expiry(body), publicDer(body)),
                signature(body, "publicKeySignature")));
    }

    @Test
    void modernV2RejectsChangedUuidExpiryPublicKeyAndIssuer() throws Exception {
        Map<String, Object> body = crypto.playerCertificateBody(PLAYER_ID.toString());
        byte[] signature = signature(body, "publicKeySignatureV2");
        byte[] publicDer = publicDer(body);
        Instant expiresAt = expiry(body);
        KeyPair stranger = newRsaKeyPair();

        assertFalse(verify(issuer.getPublic(), modernPayload(UUID.randomUUID(), expiresAt, publicDer), signature));
        assertFalse(verify(issuer.getPublic(), modernPayload(PLAYER_ID, expiresAt.plusMillis(1), publicDer), signature));
        assertFalse(verify(issuer.getPublic(), modernPayload(PLAYER_ID, expiresAt, stranger.getPublic().getEncoded()), signature));
        assertFalse(verify(stranger.getPublic(), modernPayload(PLAYER_ID, expiresAt, publicDer), signature));
        signature[0] ^= 1;
        assertFalse(verify(issuer.getPublic(), modernPayload(PLAYER_ID, expiresAt, publicDer), signature));
    }

    @Test
    void responseContainsMatchingPlayerKeysAndRefreshWindow() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        Map<String, Object> body = crypto.playerCertificateBody(PLAYER_ID.toString());
        Instant after = Instant.now();
        assertEquals(Set.of("keyPair", "publicKeySignature", "publicKeySignatureV2", "expiresAt", "refreshedAfter"),
                body.keySet());
        Map<?, ?> keys = (Map<?, ?>) body.get("keyPair");
        assertEquals(Set.of("privateKey", "publicKey"), keys.keySet());
        KeyFactory factory = KeyFactory.getInstance("RSA");
        PublicKey playerPublicKey = factory.generatePublic(new X509EncodedKeySpec(publicDer(body)));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(factory.generatePrivate(new PKCS8EncodedKeySpec(
                decodePem((String) keys.get("privateKey"), "RSA PRIVATE KEY"))));
        byte[] chat = "a signed chat message".getBytes(StandardCharsets.UTF_8);
        signer.update(chat);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(playerPublicKey);
        verifier.update(chat);
        assertTrue(verifier.verify(signer.sign()));
        Instant issuedAt = expiry(body).minus(Duration.ofHours(24));
        assertFalse(issuedAt.isBefore(before));
        assertFalse(issuedAt.isAfter(after));
        assertEquals(Duration.ofHours(6), Duration.between(
                Instant.parse((String) body.get("refreshedAfter")), expiry(body)));
    }

    @Test
    void invalidProfileIdsAreRejectedBeforeIssuance() {
        for (String id : new String[]{null, "", "1234", "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"}) {
            assertThrows(IllegalStateException.class, () -> crypto.playerCertificateBody(id));
        }
    }

    private static Instant expiry(Map<String, Object> body) {
        return Instant.parse((String) body.get("expiresAt"));
    }

    private static byte[] publicDer(Map<String, Object> body) {
        return decodePem((String) ((Map<?, ?>) body.get("keyPair")).get("publicKey"), "RSA PUBLIC KEY");
    }

    private static byte[] decodePem(String value, String label) {
        return Base64.getMimeDecoder().decode(value.replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", ""));
    }

    private static byte[] signature(Map<String, Object> body, String field) {
        return Base64.getDecoder().decode((String) body.get(field));
    }

    private static byte[] modernPayload(UUID uuid, Instant expiresAt, byte[] publicDer) {
        return ByteBuffer.allocate(24 + publicDer.length).order(ByteOrder.BIG_ENDIAN)
                .putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits())
                .putLong(expiresAt.toEpochMilli()).put(publicDer).array();
    }

    private static boolean verify(PublicKey key, byte[] payload, byte[] signature) throws Exception {
        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(key);
        verifier.update(payload);
        return verifier.verify(signature);
    }

    private static KeyPair newRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
