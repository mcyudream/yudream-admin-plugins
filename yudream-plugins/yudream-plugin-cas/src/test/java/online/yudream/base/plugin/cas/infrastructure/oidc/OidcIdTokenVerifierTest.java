package online.yudream.base.plugin.cas.infrastructure.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OidcIdTokenVerifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    @Test
    void verifiesRs256Token() throws Exception {
        KeyPair pair = rsa();
        JsonNode jwks = jwks((RSAPublicKey) pair.getPublic(), "k1");
        String token = jwt(pair, "k1", "https://auth.taru.edu.cn/authserver/oidc/", "site-client", "u-1", Instant.now().getEpochSecond() + 600);
        JsonNode payload = OidcIdTokenVerifier.verify(token, jwks, "https://auth.taru.edu.cn/authserver/oidc/", "site-client");
        assertEquals("u-1", payload.path("sub").asText());
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        KeyPair pair = rsa();
        JsonNode jwks = jwks((RSAPublicKey) pair.getPublic(), "k1");
        String token = jwt(pair, "k1", "https://auth.taru.edu.cn/authserver/oidc/", "other-client", "u-1", Instant.now().getEpochSecond() + 600);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> OidcIdTokenVerifier.verify(token, jwks, "https://auth.taru.edu.cn/authserver/oidc/", "site-client"));
        assertTrue(error.getMessage().contains("audience"));
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        KeyPair pair = rsa();
        JsonNode jwks = jwks((RSAPublicKey) pair.getPublic(), "k1");
        String token = jwt(pair, "k1", "https://auth.taru.edu.cn/authserver/oidc/", "site-client", "u-1", Instant.now().getEpochSecond() - 120);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> OidcIdTokenVerifier.verify(token, jwks, "https://auth.taru.edu.cn/authserver/oidc/", "site-client"));
        assertTrue(error.getMessage().contains("过期"));
    }

    @Test
    void rejectsTamperedPayload() throws Exception {
        KeyPair pair = rsa();
        JsonNode jwks = jwks((RSAPublicKey) pair.getPublic(), "k1");
        String token = jwt(pair, "k1", "https://auth.taru.edu.cn/authserver/oidc/", "site-client", "u-1", Instant.now().getEpochSecond() + 600);
        String[] parts = token.split("\\.");
        ObjectNode forged = MAPPER.createObjectNode();
        forged.put("sub", "attacker");
        forged.put("iss", "https://auth.taru.edu.cn/authserver/oidc/");
        forged.put("aud", "site-client");
        forged.put("exp", Instant.now().getEpochSecond() + 600);
        String forgedPayload = URL_ENCODER.encodeToString(MAPPER.writeValueAsBytes(forged));
        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];
        assertThrows(IllegalStateException.class,
                () -> OidcIdTokenVerifier.verify(tampered, jwks, "https://auth.taru.edu.cn/authserver/oidc/", "site-client"));
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static JsonNode jwks(RSAPublicKey publicKey, String kid) {
        ObjectNode key = MAPPER.createObjectNode();
        key.put("kty", "RSA");
        key.put("kid", kid);
        key.put("alg", "RS256");
        key.put("use", "sig");
        key.put("n", URL_ENCODER.encodeToString(toUnsigned(publicKey.getModulus())));
        key.put("e", URL_ENCODER.encodeToString(toUnsigned(publicKey.getPublicExponent())));
        ObjectNode jwks = MAPPER.createObjectNode();
        jwks.putArray("keys").add(key);
        return jwks;
    }

    private static String jwt(KeyPair pair, String kid, String issuer, String audience, String sub, long exp) throws Exception {
        ObjectNode header = MAPPER.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", kid);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("sub", sub);
        payload.put("iss", issuer);
        payload.put("aud", audience);
        payload.put("exp", exp);
        payload.put("iat", Instant.now().getEpochSecond());
        String encodedHeader = URL_ENCODER.encodeToString(MAPPER.writeValueAsBytes(header));
        String encodedPayload = URL_ENCODER.encodeToString(MAPPER.writeValueAsBytes(payload));
        String signingInput = encodedHeader + "." + encodedPayload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(pair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        String encodedSignature = URL_ENCODER.encodeToString(signature.sign());
        return signingInput + "." + encodedSignature;
    }

    private static byte[] toUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }
}
