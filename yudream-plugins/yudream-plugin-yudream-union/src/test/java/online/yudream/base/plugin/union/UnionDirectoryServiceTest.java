package online.yudream.base.plugin.union;

import online.yudream.base.plugin.union.application.service.UnionDirectoryService;
import online.yudream.base.plugin.union.application.service.UnionDiscoveryService;
import online.yudream.base.plugin.union.domain.UnionBusinessException;
import online.yudream.base.plugin.union.domain.aggregate.UnionPeer;
import online.yudream.base.plugin.union.infrastructure.UnionCryptoService;
import online.yudream.base.plugin.union.infrastructure.support.JsonSupport;
import online.yudream.base.plugin.union.infrastructure.repository.UnionRepository;
import online.yudream.base.plugin.union.support.UnionTestSupport;
import online.yudream.base.plugin.union.support.UnionTestSupport.FakePeerHttpClient;
import online.yudream.base.plugin.union.support.UnionTestSupport.InMemoryDocumentStore;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 信任注册：一次性令牌 CAS、TOFU 固定、API 根路径与自检。 */
class UnionDirectoryServiceTest {

    private static final String PEER_ORIGIN = "https://sitmc.peer.test";
    private static final String API_ROOT = PEER_ORIGIN + UnionDiscoveryService.UNION_API_PREFIX;
    private static final String META_URL = API_ROOT + "/peer/meta";

    private record Deps(UnionRepository repository, UnionCryptoService crypto,
                        UnionDiscoveryService discovery, UnionDirectoryService directory,
                        FakePeerHttpClient http) {

        static Deps create() {
            return create(Map.of());
        }

        static Deps create(Map<String, String> frameworkSettings) {
            InMemoryDocumentStore store = new InMemoryDocumentStore();
            UnionRepository repository = new UnionRepository(store);
            UnionCryptoService crypto = new UnionCryptoService(
                    UnionTestSupport.context(new UnionTestSupport.TestConfig() {
                        @Override
                        public String setting(String key) {
                            return frameworkSettings.get(key);
                        }
                    }, store), repository);
            UnionDiscoveryService discovery = new UnionDiscoveryService(
                    UnionTestSupport.context(new UnionTestSupport.TestConfig() {
                        @Override
                        public String setting(String key) {
                            return frameworkSettings.get(key);
                        }
                    }, store), repository, crypto);
            FakePeerHttpClient http = new FakePeerHttpClient();
            return new Deps(repository, crypto, discovery,
                    new UnionDirectoryService(repository, crypto, http, discovery), http);
        }
    }

    @Test
    void enrollConsumesTokenOnceAndStoresApiRootNotMetaUrl() {
        Deps deps = Deps.create();
        Map<String, Object> token = deps.directory.issueEnrollToken("admin", 1_000L);
        deps.http.responses.put(META_URL, peerMeta("sitmc", PEER_ORIGIN, API_ROOT, newKeyPair()));

        Map<String, Object> result = deps.directory.enroll(PEER_ORIGIN,
                String.valueOf(token.get("enrollToken")), "友站互联", "admin", 2_000L);

        assertEquals("trusted", result.get("status"));
        assertEquals(API_ROOT, deps.repository.findPeer("sitmc").orElseThrow().apiRoot());
        assertTrue(deps.repository.findPeer("sitmc").isPresent());
        assertFalse(((String) result.get("peerSecret")).isBlank());

        assertThrows(UnionBusinessException.class,
                () -> deps.directory.enroll(PEER_ORIGIN, String.valueOf(token.get("enrollToken")), null, "admin", 3_000L));
    }

    @Test
    void unreachablePeerDoesNotConsumeEnrollmentToken() {
        Deps deps = Deps.create();
        Map<String, Object> token = deps.directory.issueEnrollToken("admin", 1_000L);

        assertThrows(UnionBusinessException.class,
                () -> deps.directory.enroll(PEER_ORIGIN, String.valueOf(token.get("enrollToken")), null, "admin", 2_000L));
        assertFalse(deps.repository.findEnrollToken(UnionCryptoService.sha256Hex(String.valueOf(token.get("enrollToken"))))
                .orElseThrow().consumed());
    }

    @Test
    void expiredAndUnknownTokensShareCodeButDifferInMessage() {
        Deps deps = Deps.create();
        Map<String, Object> token = deps.directory.issueEnrollToken("admin", 1_000L);
        UnionBusinessException expired = assertThrows(UnionBusinessException.class, () -> deps.directory
                .enroll(PEER_ORIGIN, String.valueOf(token.get("enrollToken")), null, "admin", 1_000L + 6 * 60_000L));
        UnionBusinessException unknown = assertThrows(UnionBusinessException.class, () -> deps.directory
                .enroll(PEER_ORIGIN, "token-issued-by-another-site", null, "admin", 1_001L));

        // 协议 code 保持一致（对端机器面依赖），文案区分过期与查无，便于管理员排查跨站令牌混用。
        assertEquals(expired.code(), unknown.code());
        assertNotEquals(expired.getMessage(), unknown.getMessage());
        assertTrue(expired.getMessage().contains("过期"));
        assertTrue(unknown.getMessage().contains("本站签发"));
    }

    @Test
    void rejectsPeerWhoseDeclaredOriginDiffersFromRegistrationOrigin() {
        Deps deps = Deps.create();
        Map<String, Object> token = deps.directory.issueEnrollToken("admin", 1_000L);
        deps.http.responses.put(META_URL, peerMeta("sitmc", "https://evil.invalid", API_ROOT, newKeyPair()));

        assertThrows(UnionBusinessException.class,
                () -> deps.directory.enroll(PEER_ORIGIN, String.valueOf(token.get("enrollToken")), null, "admin", 2_000L));
    }

    @Test
    void blockedPeerIsNotTrusted() {
        Deps deps = Deps.create();
        Map<String, Object> token = deps.directory.issueEnrollToken("admin", 1_000L);
        deps.http.responses.put(META_URL, peerMeta("sitmc", PEER_ORIGIN, API_ROOT, newKeyPair()));
        deps.directory.enroll(PEER_ORIGIN, String.valueOf(token.get("enrollToken")), null, "admin", 2_000L);

        deps.directory.setPeerStatus("sitmc", "blocked", "admin");

        assertFalse(deps.repository.findPeer("sitmc").orElseThrow().trusted());
    }

    @Test
    void selfTestDetectsKeyMismatch() {
        Deps deps = Deps.create();
        Map<String, Object> token = deps.directory.issueEnrollToken("admin", 1_000L);
        KeyPair initial = newKeyPair();
        deps.http.responses.put(META_URL, peerMeta("sitmc", PEER_ORIGIN, API_ROOT, initial));
        deps.directory.enroll(PEER_ORIGIN, String.valueOf(token.get("enrollToken")), null, "admin", 2_000L);

        Map<String, Object> ok = deps.directory.selfTest("sitmc", 3_000L);
        assertTrue((Boolean) ok.get("ok"));

        deps.http.responses.put(META_URL, peerMeta("sitmc", PEER_ORIGIN, API_ROOT, newKeyPair()));
        Map<String, Object> failed = deps.directory.selfTest("sitmc", 4_000L);
        assertFalse((Boolean) failed.get("ok"));
    }

    @Test
    void enrollAllowsPeerWithoutJoinAssertionsFeature() {
        Deps deps = Deps.create();
        Map<String, Object> token = deps.directory.issueEnrollToken("admin", 1_000L);
        // 与 authlib 解耦：对端不声明 join-assertions（旧版/魔改 authlib 或不跑 authlib）也可注册
        deps.http.responses.put(META_URL, peerMeta("sitmc", PEER_ORIGIN, API_ROOT, newKeyPair())
                .replace("\"join-assertions\",", ""));

        Map<String, Object> result = deps.directory.enroll(PEER_ORIGIN,
                String.valueOf(token.get("enrollToken")), null, "admin", 2_000L);

        assertTrue(deps.repository.findPeer("sitmc").isPresent());
        assertFalse(((String) result.get("peerSecret")).isBlank());
    }

    @Test
    void selfEnrollProxiesLocalOriginAndTokenToPeerMachineFace() {
        Deps deps = Deps.create(Map.of("app.web-url", "https://yda.test"));
        deps.discovery.saveSite("yda", "余梦", "");
        String machineEnrollUrl = PEER_ORIGIN + UnionDiscoveryService.UNION_API_PREFIX + "/peer/enroll";
        deps.http.postResponses.put(machineEnrollUrl,
                JsonSupport.write(Map.of("status", "trusted", "peerSecret", "sec-123",
                        "peer", Map.of("id", "sitmc", "siteName", "SIT MC"))));

        Map<String, Object> result = deps.directory.selfEnroll(
                PEER_ORIGIN, "invite-token-1", "友站互联", "admin", 2_000L);

        assertEquals("sitmc", result.get("peerSiteCode"));
        assertEquals("sec-123", result.get("peerSecret"));
        String posted = deps.http.postedBodies.get(machineEnrollUrl);
        assertTrue(posted.contains("\"origin\":\"https://yda.test\""));
        assertTrue(posted.contains("\"enrollToken\":\"invite-token-1\""));
    }

    @Test
    void selfEnrollTranslatesPeerBadTokenError() {
        Deps deps = Deps.create(Map.of("app.web-url", "https://yda.test"));
        deps.discovery.saveSite("yda", "余梦", "");
        deps.http.postResponses.put(PEER_ORIGIN + UnionDiscoveryService.UNION_API_PREFIX + "/peer/enroll",
                "401:" + JsonSupport.write(Map.of("error", "auth.badToken", "errorMessage", "注册令牌无效")));

        UnionBusinessException thrown = assertThrows(UnionBusinessException.class,
                () -> deps.directory.selfEnroll(PEER_ORIGIN, "wrong-token", null, "admin", 2_000L));

        assertEquals("auth.badToken", thrown.code());
        assertTrue(thrown.getMessage().contains("注册令牌无效"));
    }

    @Test
    void selfEnrollRequiresLocalOriginAndSiteCode() {
        Deps deps = Deps.create();

        UnionBusinessException thrown = assertThrows(UnionBusinessException.class,
                () -> deps.directory.selfEnroll(PEER_ORIGIN, "token", null, "admin", 2_000L));

        assertTrue(thrown.getMessage().contains("origin"));
    }

    private static String peerMeta(String siteCode, String origin, String apiRoot, KeyPair peerKey) {
        return peerMeta(siteCode, origin, apiRoot, peerKey, null);
    }

    private static String peerMeta(String siteCode, String origin, String apiRoot, KeyPair peerKey, String namePrefix) {
        Map<String, Object> site = new java.util.LinkedHashMap<>();
        site.put("code", siteCode);
        site.put("name", "SIT MC");
        site.put("origin", origin);
        if (namePrefix != null) {
            site.put("namePrefix", namePrefix);
        }
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("unionVersion", "1");
        meta.put("site", site);
        meta.put("endpoints", Map.of("apiRoot", apiRoot));
        meta.put("textureBaseUrl", origin + "/textures");
        meta.put("signaturePublickey", UnionCryptoService.pem("PUBLIC KEY",
                Base64.getEncoder().encodeToString(peerKey.getPublic().getEncoded())));
        meta.put("backupSignaturePublickey", null);
        meta.put("features", java.util.List.of("join-assertions", "ban-ledger"));
        meta.put("bans", Map.of("cursor", 0));
        meta.put("enrollEnabled", true);
        return online.yudream.base.plugin.union.infrastructure.support.JsonSupport.write(meta);
    }

    @Test
    void enrollStoresPeerNamePrefix() {
        Deps deps = Deps.create();
        Map<String, Object> token = deps.directory.issueEnrollToken("admin", 1_000L);
        deps.http.responses.put(META_URL, peerMeta("sitmc", PEER_ORIGIN, API_ROOT, newKeyPair(), "bs"));

        deps.directory.enroll(PEER_ORIGIN, String.valueOf(token.get("enrollToken")), null, "admin", 2_000L);

        assertEquals("bs", deps.repository.findPeer("sitmc").orElseThrow().namePrefix());
    }

    @Test
    void enrollRejectsDuplicatePeerNamePrefix() {
        Deps deps = Deps.create();
        deps.repository.savePeer(new UnionPeer("other", "OTHER", "https://other.peer.test",
                "https://other.peer.test/api/plugins/yudream-union", "https://other.peer.test/t",
                UnionCryptoService.pem("PUBLIC KEY",
                        Base64.getEncoder().encodeToString(newKeyPair().getPublic().getEncoded())),
                null, "f".repeat(64), null, UnionPeer.STATUS_TRUSTED, "admin", 1L, "", "bs", 0L, null));
        Map<String, Object> token = deps.directory.issueEnrollToken("admin", 1_000L);
        deps.http.responses.put(META_URL, peerMeta("sitmc", PEER_ORIGIN, API_ROOT, newKeyPair(), "bs"));

        UnionBusinessException thrown = assertThrows(UnionBusinessException.class,
                () -> deps.directory.enroll(PEER_ORIGIN, String.valueOf(token.get("enrollToken")), null, "admin", 2_000L));

        assertTrue(thrown.getMessage().contains("前缀"));
    }

    @Test
    void enrollRejectsIllegalPeerNamePrefix() {
        Deps deps = Deps.create();
        Map<String, Object> token = deps.directory.issueEnrollToken("admin", 1_000L);
        deps.http.responses.put(META_URL, peerMeta("sitmc", PEER_ORIGIN, API_ROOT, newKeyPair(), "BAD!"));

        UnionBusinessException thrown = assertThrows(UnionBusinessException.class,
                () -> deps.directory.enroll(PEER_ORIGIN, String.valueOf(token.get("enrollToken")), null, "admin", 2_000L));

        assertTrue(thrown.getMessage().contains("namePrefix"));
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
