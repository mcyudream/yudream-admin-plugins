package online.yudream.base.plugin.cas.infrastructure.oidc;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;

/**
 * 纯 JDK 校验 OIDC id_token（RS256）。不引入 nimbus/jose 依赖。
 */
public final class OidcIdTokenVerifier {

    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private OidcIdTokenVerifier() {
    }

    public static JsonNode verify(String idToken, JsonNode jwks, String issuer, String clientId) {
        if (idToken == null || idToken.isBlank()) {
            throw new IllegalStateException("token 响应未返回 id_token");
        }
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) {
            throw new IllegalStateException("id_token 不是三段 JWT");
        }
        JsonNode header = parseJson(parts[0]);
        JsonNode payload = parseJson(parts[1]);
        String alg = text(header, "alg");
        if (!"RS256".equals(alg)) {
            throw new IllegalStateException("不支持的 id_token 签名算法：" + alg);
        }
        RSAPublicKey publicKey = publicKey(jwks, text(header, "kid"));
        verifySignature(parts[0] + "." + parts[1], parts[2], publicKey);
        verifyClaims(payload, issuer, clientId);
        return payload;
    }

    static RSAPublicKey publicKey(JsonNode jwks, String kid) {
        if (jwks == null || !jwks.has("keys") || !jwks.get("keys").isArray() || jwks.get("keys").isEmpty()) {
            throw new IllegalStateException("JWKS 未返回 RSA 公钥");
        }
        JsonNode selected = null;
        for (JsonNode key : jwks.get("keys")) {
            if (!"RSA".equals(text(key, "kty"))) {
                continue;
            }
            if (kid == null || kid.isBlank() || kid.equals(text(key, "kid"))) {
                selected = key;
                break;
            }
        }
        if (selected == null) {
            selected = jwks.get("keys").get(0);
        }
        try {
            byte[] modulus = URL_DECODER.decode(text(selected, "n"));
            byte[] exponent = URL_DECODER.decode(text(selected, "e"));
            RSAPublicKeySpec spec = new RSAPublicKeySpec(new BigInteger(1, modulus), new BigInteger(1, exponent));
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalStateException("解析 JWKS RSA 公钥失败：" + e.getMessage(), e);
        }
    }

    static void verifySignature(String signingInput, String signaturePart, RSAPublicKey publicKey) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(URL_DECODER.decode(pad(signaturePart)))) {
                throw new IllegalStateException("id_token 签名校验失败");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("id_token 签名校验失败：" + e.getMessage(), e);
        }
    }

    static void verifyClaims(JsonNode payload, String issuer, String clientId) {
        String iss = text(payload, "iss");
        if (issuer != null && !issuer.isBlank() && !normalizeIssuer(issuer).equals(normalizeIssuer(iss))) {
            throw new IllegalStateException("id_token issuer 不匹配");
        }
        if (clientId != null && !clientId.isBlank() && !audienceContains(payload.get("aud"), clientId)) {
            throw new IllegalStateException("id_token audience 不匹配");
        }
        long now = Instant.now().getEpochSecond();
        long exp = payload.path("exp").asLong(0);
        if (exp > 0 && now - 30 > exp) {
            throw new IllegalStateException("id_token 已过期");
        }
        long nbf = payload.path("nbf").asLong(0);
        if (nbf > 0 && now + 30 < nbf) {
            throw new IllegalStateException("id_token 尚未生效");
        }
    }

    static String text(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) {
            return "";
        }
        return node.path(field).asText("").trim();
    }

    private static boolean audienceContains(JsonNode aud, String clientId) {
        if (aud == null || aud.isMissingNode() || aud.isNull()) {
            return false;
        }
        if (aud.isArray()) {
            Iterator<JsonNode> it = aud.elements();
            while (it.hasNext()) {
                if (clientId.equals(it.next().asText())) {
                    return true;
                }
            }
            return false;
        }
        return clientId.equals(aud.asText());
    }

    private static String normalizeIssuer(String issuer) {
        if (issuer == null) {
            return "";
        }
        return issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    }

    private static JsonNode parseJson(String part) {
        byte[] decoded = URL_DECODER.decode(pad(part));
        return online.yudream.base.plugin.cas.infrastructure.support.JsonSupport.tree(new String(decoded, StandardCharsets.UTF_8));
    }

    private static String pad(String value) {
        int remainder = value.length() % 4;
        if (remainder == 0) {
            return value;
        }
        return value + "====".substring(remainder);
    }

}
