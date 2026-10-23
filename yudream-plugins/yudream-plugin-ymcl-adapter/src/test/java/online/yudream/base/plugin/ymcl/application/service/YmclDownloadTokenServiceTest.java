package online.yudream.base.plugin.ymcl.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下载令牌签发/校验契约：签名防篡改、有效期、与网关共享密钥相互独立。
 */
class YmclDownloadTokenServiceTest {

    @Test
    void issuedTokenVerifiesToSamePlayer() {
        YmclDownloadSourceService sources = new YmclDownloadSourceService(new MemoryDocumentStore());
        YmclDownloadTokenService tokens = new YmclDownloadTokenService(sources);

        Map<String, Object> issued = tokens.issue(42L);
        String token = String.valueOf(issued.get("token"));
        assertTrue(token.startsWith(YmclDownloadTokenService.TOKEN_PREFIX + "."));
        assertTrue((long) issued.get("expires_at") > System.currentTimeMillis());

        Long userId = tokens.userIdOf(token);
        assertEquals(42L, userId);

        Map<String, Object> verified = tokens.verify(token);
        assertEquals(true, verified.get("active"));
        assertEquals("42", verified.get("user_id"));
    }

    @Test
    void tamperedPayloadIsRejected() {
        YmclDownloadSourceService sources = new YmclDownloadSourceService(new MemoryDocumentStore());
        YmclDownloadTokenService tokens = new YmclDownloadTokenService(sources);

        String token = String.valueOf(tokens.issue(42L).get("token"));
        String[] parts = token.split("\\.");
        // 篡改 user 段（base64url of "43"），签名不再匹配。
        String forgedUser = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("43".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedUser + "." + parts[2] + "." + parts[3];
        assertNull(tokens.userIdOf(forged));
        assertEquals(false, tokens.verify(forged).get("active"));
        // 原令牌仍有效。
        assertEquals(42L, tokens.userIdOf(token));
    }

    @Test
    void expiredTokenIsRejected() {
        YmclDownloadSourceService sources = new YmclDownloadSourceService(new MemoryDocumentStore());
        YmclDownloadTokenService tokens = new YmclDownloadTokenService(sources);
        String expired = tokens.mint(7L, System.currentTimeMillis() - 1_000);
        assertNull(tokens.userIdOf(expired));
    }

    @Test
    void foreignAndGarbageTokensAreRejected() {
        YmclDownloadSourceService sources = new YmclDownloadSourceService(new MemoryDocumentStore());
        YmclDownloadTokenService tokens = new YmclDownloadTokenService(sources);
        // 其它密钥签发的令牌不通过。
        YmclDownloadTokenService foreign = new YmclDownloadTokenService(
                new YmclDownloadSourceService(new MemoryDocumentStore()));
        String foreignToken = String.valueOf(foreign.issue(1L).get("token"));
        assertNull(tokens.userIdOf(foreignToken));
        assertNull(tokens.userIdOf(null));
        assertNull(tokens.userIdOf("garbage"));
        assertNull(tokens.userIdOf("dlt1.only.two"));
    }

    @Test
    void secretRotationInvalidatesOutstandingTokens() {
        MemoryDocumentStore store = new MemoryDocumentStore();
        YmclDownloadSourceService sources = new YmclDownloadSourceService(store);
        YmclDownloadTokenService tokens = new YmclDownloadTokenService(sources);
        String token = String.valueOf(tokens.issue(9L).get("token"));
        assertEquals(9L, tokens.userIdOf(token));

        String newGatewaySecret = sources.rotateSecrets();
        assertNotNull(newGatewaySecret);
        assertNotNull(sources.gatewaySecret());
        assertTrue(sources.gatewaySecretMatches(newGatewaySecret));
        assertNull(tokens.userIdOf(token), "轮换后旧下载令牌必须失效");
    }

    @Test
    void gatewaySecretIsStableAndIndependentOfTokenSecret() {
        YmclDownloadSourceService sources = new YmclDownloadSourceService(new MemoryDocumentStore());
        String gatewaySecret = sources.gatewaySecret();
        assertEquals(gatewaySecret, sources.gatewaySecret());
        assertTrue(sources.gatewaySecretMatches(gatewaySecret));
        assertFalse(sources.gatewaySecretMatches(gatewaySecret + "x"));
        assertFalse(sources.gatewaySecretMatches(null));
        assertNotEquals(gatewaySecret, sources.tokenSecret());
    }

    /** 内存版文档存根：语义对齐宿主 MongoPluginDocumentStore（返回带 id 键）。 */
    static class MemoryDocumentStore implements PluginDocumentStore {
        private final Map<String, Map<String, Map<String, Object>>> collections = new ConcurrentHashMap<>();

        private Map<String, Map<String, Object>> collection(String name) {
            return collections.computeIfAbsent(name, key -> new ConcurrentHashMap<>());
        }

        @Override
        public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            Map<String, Object> stored = new LinkedHashMap<>(document);
            stored.put("id", id);
            collection(collection).put(id, stored);
            return stored;
        }

        @Override
        public Optional<Map<String, Object>> findById(String collection, String id) {
            return Optional.ofNullable(collection(collection).get(id));
        }

        @Override
        public List<Map<String, Object>> findAll(String collection, int page, int size) {
            List<Map<String, Object>> all = new ArrayList<>(collection(collection).values());
            all.sort(java.util.Comparator.comparing(doc -> String.valueOf(doc.get("id"))));
            int from = Math.max(0, (page - 1) * size);
            if (from >= all.size()) {
                return List.of();
            }
            return new ArrayList<>(all.subList(from, Math.min(all.size(), from + size)));
        }

        @Override
        public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
            List<Map<String, Object>> matches = new ArrayList<>();
            for (Map<String, Object> document : collection(collection).values()) {
                if (value.equals(document.get(field))) {
                    matches.add(document);
                }
            }
            matches.sort(java.util.Comparator.comparing(doc -> String.valueOf(doc.get("id"))));
            int from = Math.max(0, (page - 1) * size);
            if (from >= matches.size()) {
                return List.of();
            }
            return new ArrayList<>(matches.subList(from, Math.min(matches.size(), from + size)));
        }

        @Override
        public long count(String collection) {
            return collection(collection).size();
        }

        @Override
        public void delete(String collection, String id) {
            collection(collection).remove(id);
        }
    }
}
