package online.yudream.base.plugin.union;

import online.yudream.base.plugin.union.application.service.UnionBanService;
import online.yudream.base.plugin.union.domain.UnionBusinessException;
import online.yudream.base.plugin.union.domain.aggregate.UnionBan;
import online.yudream.base.plugin.union.domain.aggregate.UnionPeer;
import online.yudream.base.plugin.union.infrastructure.UnionCryptoService;
import online.yudream.base.plugin.union.infrastructure.repository.UnionRepository;
import online.yudream.base.plugin.union.support.UnionTestSupport;
import online.yudream.base.plugin.union.support.UnionTestSupport.FakePeerHttpClient;
import online.yudream.base.plugin.union.support.UnionTestSupport.InMemoryDocumentStore;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 联合封禁：规范化、本地/外来合并、覆盖防复活与回推鉴权。 */
class UnionBanServiceTest {

    @Test
    void normalizesNameCaseAndColorCodes() {
        assertEquals("notch", UnionBanService.normalizeName("  Notch§a "));
        assertEquals("griefer_x", UnionBanService.normalizeName("§cGriefer§4_X"));
    }

    @Test
    void addLiftAndOverrideLifecycle() {
        UnionRepository repository = repo();
        UnionBanService bans = service(repository);
        bans.addBan("Notch", "作弊", "证据A", "yda", "admin", 1_000L);
        assertTrue(bans.isBanned("NOTCH", 2_000L));

        bans.liftBan(" notch ", "admin2", "yda", 3_000L);
        assertFalse(bans.isBanned("Notch", 4_000L));
        assertThrows(UnionBusinessException.class, () -> bans.liftBan("nobody", "admin", "yda", 5_000L));
    }

    @Test
    void expiredBanIsInactive() {
        UnionRepository repository = repo();
        UnionBanService bans = service(repository);
        long seq = repository.banCursor() + 1;
        repository.saveBan(new UnionBan(seq, "Temp", "temp", "短期", "yda", "", null, "admin",
                1_000L, 5_000L, UnionBan.STATUS_ACTIVE, null, null));
        repository.saveBanCursor(seq);

        assertTrue(bans.isBanned("temp", 4_999L));
        assertFalse(bans.isBanned("temp", 5_001L));
    }

    @Test
    void localLiftAppendsEventForPeerCursorPropagation() {
        UnionRepository repository = repo();
        UnionBanService bans = service(repository);
        bans.addBan("Notch", "作弊", null, "yda", "admin", 1_000L);
        long activeSeq = repository.banCursor();

        bans.liftBan("Notch", "admin", "yda", 2_000L);

        assertTrue(repository.banCursor() > activeSeq);
        assertEquals(UnionBan.STATUS_ACTIVE, repository.findBan(activeSeq).orElseThrow().status());
        assertEquals(UnionBan.STATUS_LIFTED, repository.findBan(repository.banCursor()).orElseThrow().status());
        assertFalse(bans.isBanned("Notch", 3_000L));
    }

    @Test
    void pullMergesLedgerAndLiftRespectsSourceSite() {
        UnionRepository repository = repo();
        UnionBanService bans = service(repository);
        UnionPeer peer = saveTrustedPeer(repository);
        long now = 10_000L;

        assertEquals(1, bans.appliedFrom(peer, ledger("griefer", "active", 41), now));
        assertTrue(bans.isBanned("Griefer", now));
        assertEquals(41, repository.findPeer("sitmc").orElseThrow().banCursor());

        // 同源 lift：解除该站贡献
        assertEquals(1, bans.appliedFrom(peer, ledger("griefer", "lifted", 42), now));
        assertFalse(bans.isBanned("griefer", now));
    }

    @Test
    void localOverridePreventsPullResurrection() {
        UnionRepository repository = repo();
        UnionBanService bans = service(repository);
        UnionPeer peer = saveTrustedPeer(repository);
        long now = 10_000L;
        bans.appliedFrom(peer, ledger("griefer", "active", 41), now);
        bans.liftBan("griefer", "admin", "yda", now + 1);

        bans.appliedFrom(peer, ledger("griefer", "active", 43), now + 2);

        assertFalse(bans.isBanned("griefer", now + 2));
    }

    @Test
    void reportRequiresIssuedPeerSecret() {
        UnionRepository repository = repo();
        UnionBanService bans = service(repository);
        UnionPeer peer = new UnionPeer("sitmc", "SIT", "https://sitmc.peer.test",
                "https://sitmc.peer.test/api/plugins/yudream-union", "https://sitmc.peer.test/t",
                keyPem(), null, "f".repeat(64),
                UnionCryptoService.sha256Hex("shared-secret"), UnionPeer.STATUS_TRUSTED,
                "admin", 1L, "", "", 0L);
        repository.savePeer(peer);
        long now = 10_000L;

        assertThrows(UnionBusinessException.class,
                () -> bans.applyReport("wrong-secret", banBody("griefer"), now));

        bans.applyReport("shared-secret", banBody("griefer"), now);
        assertTrue(bans.isBanned("Griefer", now));
        assertEquals("sitmc", repository.bansByName("griefer").get(0).sourceSite());
    }

    // ---- helpers ----

    private UnionRepository repo() {
        return new UnionRepository(new InMemoryDocumentStore());
    }

    @Test
    void subjectScopedBansDoNotCrossSites() {
        UnionRepository repository = repo();
        UnionBanService bans = service(repository);
        repository.saveBan(new UnionBan(repository.banCursor() + 1, "Notch", "notch", "作弊", "sitmc", "sitmc", null,
                "peer:sitmc", 1_000L, null, UnionBan.STATUS_ACTIVE, null, null));
        repository.saveBanCursor(repository.banCursor() + 1);

        assertTrue(bans.isBannedForSubject("sitmc", "Notch", 2_000L));
        assertFalse(bans.isBannedForSubject("yda", "Notch", 2_000L));

        // v1 旧事件无归属：全域生效（向后兼容）
        repository.saveBan(new UnionBan(repository.banCursor() + 1, "Notch", "notch", "作弊", "other", "", null,
                "peer:other", 2_000L, null, UnionBan.STATUS_ACTIVE, null, null));
        repository.saveBanCursor(repository.banCursor() + 1);
        assertTrue(bans.isBannedForSubject("yda", "Notch", 3_000L));
    }

    @Test
    void mangledBanInputResolvesToOriginNameAndSubject() {
        UnionRepository repository = repo();
        UnionBanService bans = service(repository);
        repository.saveNameMapping("sitmc", "123456", "123456", "bs_123456");

        bans.addBan("bs_123456", "破坏", null, "yda", "admin", 1_000L);

        UnionBan event = repository.bansByName("123456").getFirst();
        assertEquals("sitmc", event.subjectSite());
        assertTrue(bans.isBannedForSubject("sitmc", "123456", 2_000L));
        assertFalse(bans.isBannedForSubject("yda", "123456", 2_000L));
    }

    private UnionBanService service(UnionRepository repository) {
        UnionCryptoService crypto = new UnionCryptoService(
                UnionTestSupport.context(new UnionTestSupport.TestConfig() {
                }, new InMemoryDocumentStore()), repository);
        return new UnionBanService(repository, new FakePeerHttpClient(), crypto);
    }

    private UnionPeer saveTrustedPeer(UnionRepository repository) {
        UnionPeer peer = new UnionPeer("sitmc", "SIT", "https://sitmc.peer.test",
                "https://sitmc.peer.test/api/plugins/yudream-union", "https://sitmc.peer.test/t",
                keyPem(), null, "f".repeat(64), null, UnionPeer.STATUS_TRUSTED, "admin", 1L, "", "", 0L);
        repository.savePeer(peer);
        return peer;
    }

    private Map<String, Object> ledger(String name, String status, long seq) {
        Map<String, Object> ban = new LinkedHashMap<>();
        ban.put("name", name);
        ban.put("reason", "破坏");
        ban.put("sourceSite", "sitmc");
        ban.put("createdAt", 1_000L);
        ban.put("status", status);
        ban.put("seq", seq);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bans", java.util.List.of(ban));
        return body;
    }

    private Map<String, Object> banBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("reason", "推送封禁");
        return body;
    }

    private static String keyPem() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return UnionCryptoService.pem("PUBLIC KEY",
                    Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
