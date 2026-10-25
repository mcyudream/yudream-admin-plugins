package online.yudream.base.plugin.union;

import online.yudream.base.plugin.union.api.UnionFederationService;
import online.yudream.base.plugin.union.api.UnionJoinEvidenceProvider;
import online.yudream.base.plugin.union.application.service.FederationJoinService;
import online.yudream.base.plugin.union.application.service.UnionBanService;
import online.yudream.base.plugin.union.application.service.UnionDiscoveryService;
import online.yudream.base.plugin.union.domain.aggregate.UnionPeer;
import online.yudream.base.plugin.union.infrastructure.UnionCryptoService;
import online.yudream.base.plugin.union.infrastructure.repository.UnionRepository;
import online.yudream.base.plugin.union.infrastructure.support.JsonSupport;
import online.yudream.base.plugin.union.support.UnionTestSupport;
import online.yudream.base.plugin.union.support.UnionTestSupport.FakePeerHttpClient;
import online.yudream.base.plugin.union.support.UnionTestSupport.InMemoryDocumentStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 联合 join 断言矩阵：B 侧签发自查、A 侧验签/受众/唯一命中/封禁/防重放。 */
class FederationJoinServiceTest {

    private static final String SERVER_ID = "-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1";
    private static final String PROFILE_ID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final String TEXTURE_VALUE = textureValue("https://sitmc.peer.test/api/plugins/yudream-skin/textures/abc123");
    private static final String LOCAL_SITE = "yda";

    /** 每用例独立装配：一个共享 context/config，扩展与设置作用于同一文档库。 */
    private static final class Fixture {
        final InMemoryDocumentStore store = new InMemoryDocumentStore();
        final Map<String, UnionJoinEvidenceProvider.JoinEvidence> evidence = new ConcurrentHashMap<>();
        final FakePeerHttpClient http = new FakePeerHttpClient();
        final UnionRepository repository = new UnionRepository(store);
        final UnionCryptoService crypto = new UnionCryptoService(context(), repository);
        final UnionBanService bans = new UnionBanService(repository, http, crypto);
        final UnionDiscoveryService discovery = new UnionDiscoveryService(context(), repository, crypto);
        final FederationJoinService federation = new FederationJoinService(context(), repository, crypto,
                http, bans, discovery, Executors.newFixedThreadPool(2));

        private online.yudream.base.plugin.spi.core.PluginContext context() {
            return UnionTestSupport.context(new UnionTestSupport.TestConfig() {
                @Override
                public List<Object> extensionsFor(Class<?> extensionPoint) {
                    if (extensionPoint != UnionJoinEvidenceProvider.class) {
                        return List.of();
                    }
                    return List.of((UnionJoinEvidenceProvider) (username, serverId, since) ->
                            evidence.values().stream()
                                    .filter(item -> item.profileName().equalsIgnoreCase(username))
                                    .findFirst());
                }
            }, store);
        }

        void givenLocalJoin(String profileName) {
            evidence.put(PROFILE_ID, new UnionJoinEvidenceProvider.JoinEvidence(PROFILE_ID, profileName, TEXTURE_VALUE));
        }

        void givenLocalSite() {
            discovery.saveSite(LOCAL_SITE, "YD 测试站", "");
        }

        UnionPeer givenPeer(String siteCode, KeyPair peerKey, String aud, long expiresAt, String jti) {
            return givenPeer(siteCode, peerKey, aud, expiresAt, jti, "Notch", "");
        }

        UnionPeer givenPeer(String siteCode, KeyPair peerKey, String aud, long expiresAt, String jti,
                            String profileName, String namePrefix) {
            String peerOrigin = "https://" + siteCode + ".peer.test";
            Map<String, Object> assertion = new LinkedHashMap<>();
            assertion.put("profileId", PROFILE_ID);
            assertion.put("profileName", profileName);
            assertion.put("serverId", SERVER_ID);
            assertion.put("aud", aud);
            assertion.put("issuerKeyId", UnionCryptoService.issuerKeyId(pem(peerKey)));
            assertion.put("jti", normalizedJti(jti));
            assertion.put("issuedAt", expiresAt - 59_000L);
            assertion.put("expiresAt", expiresAt);
            assertion.put("textures", textureValue(peerOrigin + UnionDiscoveryService.SKIN_TEXTURE_LOCATION, profileName));
            String canonical = FederationJoinService.canonicalAssertion(assertion);
            String signature;
            try {
                Signature signer = Signature.getInstance("SHA256withRSA");
                signer.initSign(peerKey.getPrivate());
                signer.update(canonical.getBytes(StandardCharsets.UTF_8));
                signature = Base64.getEncoder().encodeToString(signer.sign());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("assertion", assertion);
            body.put("signature", signature);
            http.responses.put(queryUrl(peerOrigin, profileName), JsonSupport.write(body));
            UnionPeer peer = new UnionPeer(siteCode, siteCode, peerOrigin,
                    peerOrigin + UnionDiscoveryService.UNION_API_PREFIX,
                    peerOrigin + UnionDiscoveryService.SKIN_TEXTURE_LOCATION,
                    pem(peerKey), null, "f".repeat(64), null, UnionPeer.STATUS_TRUSTED,
                    "admin", 1L, "", namePrefix, 0L);
            repository.savePeer(peer);
            return peer;
        }
    }

    @Test
    void answerJoinQuerySignsVerifiableAssertion() {
        Fixture fixture = new Fixture();
        fixture.givenLocalJoin("Notch");

        Optional<Map<String, Object>> body = fixture.federation
                .answerJoinQuery("Notch", SERVER_ID, LOCAL_SITE, 1_000_000L);
        assertTrue(body.isPresent());
        @SuppressWarnings("unchecked")
        Map<String, Object> assertion = (Map<String, Object>) body.get().get("assertion");
        String canonical = FederationJoinService.canonicalAssertion(assertion);
        assertTrue(fixture.crypto.verify(fixture.crypto.publicKeyPem(),
                canonical.getBytes(StandardCharsets.UTF_8), String.valueOf(body.get().get("signature"))));
        assertEquals(PROFILE_ID, assertion.get("profileId"));
        assertEquals(LOCAL_SITE, assertion.get("aud"));
    }

    @Test
    void answerJoinQuerySilentlySkipsBannedName() {
        Fixture fixture = new Fixture();
        fixture.givenLocalJoin("griefer");
        fixture.givenLocalSite();
        fixture.bans.addBan("Griefer", "破坏", null, LOCAL_SITE, "admin", 900L);

        assertTrue(fixture.federation.answerJoinQuery("griefer", SERVER_ID, LOCAL_SITE, 1_000L).isEmpty());
    }

    @Test
    void verifyRemoteJoinAcceptsUniqueValidHit() {
        Fixture fixture = new Fixture();
        fixture.givenLocalSite();
        fixture.givenPeer("sitmc", newKeyPair(), LOCAL_SITE, System.currentTimeMillis() + 60_000L, "jti-1");

        Optional<UnionFederationService.FederatedProfile> hit = fixture.federation.verifyRemoteJoin("Notch", SERVER_ID);
        assertTrue(hit.isPresent());
        assertEquals(PROFILE_ID, hit.get().uuid());
        assertEquals(textureValue("https://sitmc.peer.test" + UnionDiscoveryService.SKIN_TEXTURE_LOCATION),
                hit.get().textureValue());
    }

    @Test
    void verifyRemoteJoinRejectsTamperedSignature() {
        Fixture fixture = new Fixture();
        fixture.givenLocalSite();
        fixture.givenPeer("sitmc", newKeyPair(), LOCAL_SITE, System.currentTimeMillis() + 60_000L, "jti-1");
        fixture.http.mutate = body -> body.replace("\"profileName\":\"Notch\"", "\"profileName\":\"Fake\"");

        assertTrue(fixture.federation.verifyRemoteJoin("Notch", SERVER_ID).isEmpty());
    }

    @Test
    void verifyRemoteJoinRejectsWrongAudience() {
        Fixture fixture = new Fixture();
        fixture.givenLocalSite();
        fixture.givenPeer("sitmc", newKeyPair(), "other-site", System.currentTimeMillis() + 60_000L, "jti-1");

        assertTrue(fixture.federation.verifyRemoteJoin("Notch", SERVER_ID).isEmpty());
    }

    @Test
    void verifyRemoteJoinRejectsExpiredAssertion() {
        Fixture fixture = new Fixture();
        fixture.givenLocalSite();
        fixture.givenPeer("sitmc", newKeyPair(), LOCAL_SITE, System.currentTimeMillis() - 1L, "jti-1");

        assertTrue(fixture.federation.verifyRemoteJoin("Notch", SERVER_ID).isEmpty());
    }

    @Test
    void verifyRemoteJoinRejectsConflictingHits() {
        Fixture fixture = new Fixture();
        fixture.givenLocalSite();
        fixture.givenPeer("sitmc", newKeyPair(), LOCAL_SITE, System.currentTimeMillis() + 60_000L, "jti-1");
        fixture.givenPeer("other", newKeyPair(), LOCAL_SITE, System.currentTimeMillis() + 60_000L, "jti-2");

        assertTrue(fixture.federation.verifyRemoteJoin("Notch", SERVER_ID).isEmpty());
        assertFalse(fixture.repository.pageAudit(1, 50, null, "at", true).isEmpty());
    }

    @Test
    void verifyRemoteJoinDropsReplayedJti() {
        Fixture fixture = new Fixture();
        fixture.givenLocalSite();
        fixture.givenPeer("sitmc", newKeyPair(), LOCAL_SITE, System.currentTimeMillis() + 60_000L, "fixed-jti");

        assertTrue(fixture.federation.verifyRemoteJoin("Notch", SERVER_ID).isPresent());
        assertTrue(fixture.federation.verifyRemoteJoin("Notch", SERVER_ID).isEmpty());
    }

    @Test
    void verifyRemoteJoinRejectsBannedNameOnReceivingSide() {
        Fixture fixture = new Fixture();
        fixture.givenLocalSite();
        fixture.givenPeer("sitmc", newKeyPair(), LOCAL_SITE, System.currentTimeMillis() + 60_000L, "jti-1");
        fixture.bans.addBan("Notch", "作弊", null, "sitmc", "admin", 1L);

        assertTrue(fixture.federation.verifyRemoteJoin("Notch", SERVER_ID).isEmpty());
    }

    @Test
    void trustedTextureHostsMergePeerHosts() {
        Fixture fixture = new Fixture();
        fixture.givenLocalSite();
        fixture.givenPeer("sitmc", newKeyPair(), LOCAL_SITE, System.currentTimeMillis() + 60_000L, "jti-1");

        assertEquals(List.of("sitmc.peer.test"), fixture.federation.trustedTextureHosts());
    }

    @Test
    void verifyRemoteJoinRewritesNameWithPeerPrefix() {
        Fixture fixture = new Fixture();
        fixture.givenLocalSite();
        fixture.givenPeer("sitmc", newKeyPair(), LOCAL_SITE, System.currentTimeMillis() + 60_000L, "jti-1",
                "Notch", "bs");

        Optional<UnionFederationService.FederatedProfile> hit =
                fixture.federation.verifyRemoteJoin("Notch", SERVER_ID);
        assertTrue(hit.isPresent());
        assertEquals("bs_notch", hit.get().name());
        assertEquals("bs_notch", fixture.repository.findMangledName("sitmc", "notch").orElse(""));
    }

    @Test
    void verifyRemoteJoinRejectsOverlongMangledName() {
        Fixture fixture = new Fixture();
        fixture.givenLocalSite();
        fixture.givenPeer("sitmc", newKeyPair(), LOCAL_SITE, System.currentTimeMillis() + 60_000L, "jti-1",
                "N123456789012345", "bsit");

        // 前缀 4 + 分隔符 1 + 16 位原名 = 21 > 16：fail-closed 拒绝联合登录
        assertTrue(fixture.federation.verifyRemoteJoin("N123456789012345", SERVER_ID).isEmpty());
        assertFalse(fixture.repository.pageAudit(1, 50, null, "at", true).isEmpty());
    }

    private static String textureValue(String textureBaseUrl) {
        return textureValue(textureBaseUrl, "Notch");
    }

    private static String textureValue(String textureBaseUrl, String profileName) {
        Map<String, Object> textures = new LinkedHashMap<>();
        textures.put("SKIN", Map.of("url", textureBaseUrl + "/abc123"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", 1L);
        payload.put("profileId", PROFILE_ID);
        payload.put("profileName", profileName);
        payload.put("textures", textures);
        return Base64.getEncoder().encodeToString(JsonSupport.write(payload).getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizedJti(String text) {
        String hex = Integer.toHexString(text.hashCode()).replace("-", "");
        return (hex + "0".repeat(32)).substring(0, 32);
    }

    private static String queryUrl(String peerOrigin, String username) {
        return peerOrigin + UnionDiscoveryService.UNION_API_PREFIX + "/peer/joins/query"
                + "?username=" + username + "&serverId=" + SERVER_ID + "&aud=" + LOCAL_SITE;
    }

    private static String pem(KeyPair keyPair) {
        return UnionCryptoService.pem("PUBLIC KEY",
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
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
