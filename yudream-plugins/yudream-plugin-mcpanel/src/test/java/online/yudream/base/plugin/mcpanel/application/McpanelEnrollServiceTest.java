package online.yudream.base.plugin.mcpanel.application;

import online.yudream.base.plugin.mcpanel.application.cmd.BootstrapCmd;
import online.yudream.base.plugin.mcpanel.application.cmd.NodeCreateCmd;
import online.yudream.base.plugin.mcpanel.application.dto.EnrollResultDTO;
import online.yudream.base.plugin.mcpanel.application.dto.EnrollTokenDTO;
import online.yudream.base.plugin.mcpanel.application.service.McpanelEnrollService;
import online.yudream.base.plugin.mcpanel.application.service.McpanelNodeAppService;
import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.acceptance.InMemorySecretStore;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.EnrollTokenRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.valobj.EnrollToken;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentEnrollTokenRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets;
import online.yudream.base.plugin.mcpanel.support.TestStubs.RecordingControlPlane;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpanelEnrollServiceTest {

    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final InMemorySecretStore secretStore = new InMemorySecretStore();
    private final McpanelNodeRepository nodeRepository =
            new DocumentNodeRepository(documents, McpanelJson.mapper());
    private final EnrollTokenRepository tokenRepository =
            new DocumentEnrollTokenRepository(documents, McpanelJson.mapper());
    private final McpanelNodeAppService nodeService = new McpanelNodeAppService(nodeRepository,
            tokenRepository, new NodeSecrets(secretStore), new RecordingControlPlane(), clock::get);
    private final McpanelEnrollService enrollService = new McpanelEnrollService(nodeRepository,
            tokenRepository, new NodeSecrets(secretStore), clock::get);

    private EnrollTokenDTO issue(String name, String endpoint, String tlsMode, String pin) {
        var created = nodeService.create(new NodeCreateCmd(name, endpoint, tlsMode, pin, false, null, true, null, null, null, null, null));
        return nodeService.issueEnrollment(created.id());
    }

    @Test
    void bootstrapReturnsSecretOnceAndRecordsIdentity() {
        EnrollTokenDTO token = issue("节点G", "wss://node-g.example.com:9701", "pkix", null);
        EnrollResultDTO result = enrollService.bootstrap(new BootstrapCmd(
                token.token(), "node-g", "0.1.0", List.of("node.hello", "node.stats"), null));
        assertEquals(43, result.nodeSecret().length());
        assertEquals("direct", result.controlPlan().get("mode"));
        McpanelNode node = nodeRepository.findById(result.nodeId()).orElseThrow();
        assertEquals("node-g", node.reportedHost());
        assertEquals("0.1.0", node.agentVersion());
        assertTrue(node.enrolled());
        assertTrue(node.enrolledAtMs() > 0);
        // 已注册节点不可再签发（M1 无 rekey）
        McpanelBusinessException reissue = assertThrows(McpanelBusinessException.class,
                () -> nodeService.issueEnrollment(result.nodeId()));
        assertEquals(409, reissue.httpStatus());
    }

    @Test
    void sameTokenConsumableExactlyOnce() {
        EnrollTokenDTO token = issue("节点H", "wss://node-h.example.com:9701", "pkix", null);
        enrollService.bootstrap(new BootstrapCmd(token.token(), "node-h", "0.1.0", List.of(), null));
        McpanelBusinessException second = assertThrows(McpanelBusinessException.class,
                () -> enrollService.bootstrap(new BootstrapCmd(token.token(), "node-h", "0.1.0", List.of(), null)));
        assertEquals(409, second.httpStatus());
        assertEquals("auth.tokenConsumed", second.code());
    }

    @Test
    void unknownAndExpiredTokensShareGeneralizedError() {
        McpanelBusinessException unknown = assertThrows(McpanelBusinessException.class,
                () -> enrollService.bootstrap(new BootstrapCmd("not-a-real-token", "h", "0.1.0", List.of(), null)));
        assertEquals(401, unknown.httpStatus());
        assertEquals("auth.badToken", unknown.code());
        EnrollTokenDTO token = issue("节点I", "wss://node-i.example.com:9701", "pkix", null);
        clock.addAndGet(11 * 60 * 1000L);
        McpanelBusinessException expired = assertThrows(McpanelBusinessException.class,
                () -> enrollService.bootstrap(new BootstrapCmd(token.token(), "h", "0.1.0", List.of(), null)));
        assertEquals(401, expired.httpStatus());
        assertEquals("auth.badToken", expired.code());
        // 过期令牌未被消费：管理员重签后旧 token 仍不可用，新 token 正常。
        assertNotEquals(token.token(), issue("节点I2", "wss://node-i2.example.com:9701", "pkix", null).token());
    }

    @Test
    void pinnedNodeRequiresMatchingCertDigestAndDoesNotConsumeTokenOnMismatch() {
        String pin = "a".repeat(64);
        EnrollTokenDTO token = issue("节点J", "wss://node-j.example.com:9701", "pinned", pin);
        McpanelBusinessException mismatch = assertThrows(McpanelBusinessException.class,
                () -> enrollService.bootstrap(new BootstrapCmd(token.token(), "node-j", "0.1.0",
                        List.of(), "b".repeat(64))));
        assertEquals(401, mismatch.httpStatus());
        assertEquals("auth.pinMismatch", mismatch.code());
        // 失败不消费：同 token 在指纹修正后可重试成功。
        EnrollResultDTO retry = enrollService.bootstrap(new BootstrapCmd(token.token(), "node-j",
                "0.1.0", List.of(), pin));
        assertEquals(43, retry.nodeSecret().length());
        McpanelNode node = nodeRepository.findById(retry.nodeId()).orElseThrow();
        assertEquals(pin, node.pinSha256());
        assertEquals(pin, node.reportedCertSha256());
    }

    @Test
    void pinnedNodeWithoutPresetPinRegistersReportedDigestAsInitialPin() {
        // TOFU：pinned 且管理员未预填指纹时，注册上报指纹自动登记为初始 pin，
        // 管理员无需登节点取指纹；登记与注册同一 CAS 写入。
        String reported = "c".repeat(64);
        EnrollTokenDTO token = issue("节点K", "wss://node-k.example.com:9701", "pinned", null);
        EnrollResultDTO result = enrollService.bootstrap(new BootstrapCmd(
                token.token(), "node-k", "0.1.0", List.of(), reported));
        McpanelNode node = nodeRepository.findById(result.nodeId()).orElseThrow();
        assertEquals(reported, node.pinSha256());
        assertEquals(reported, node.reportedCertSha256());
        assertTrue(node.enrolled());
    }

    @Test
    void pkixNodeNeverGetsPinFromReportedDigest() {
        // pkix 节点不因上报指纹被强加 pinned：管理员选择的校验模式不被注册改变。
        String reported = "d".repeat(64);
        EnrollTokenDTO token = issue("节点L", "wss://node-l.example.com:9701", "pkix", null);
        EnrollResultDTO result = enrollService.bootstrap(new BootstrapCmd(
                token.token(), "node-l", "0.1.0", List.of(), reported));
        McpanelNode node = nodeRepository.findById(result.nodeId()).orElseThrow();
        assertEquals("pkix", node.tlsMode());
        assertEquals(null, node.pinSha256());
        assertEquals(reported, node.reportedCertSha256());
    }

    @Test
    void reissueInvalidatesOutstandingTokens() {
        EnrollTokenDTO first = issue("节点R1", "wss://node-r1.example.com:9701", "pkix", null);
        String nodeId = tokenRepository.findByDigest(NodeSecrets.sha256Hex(first.token()))
                .orElseThrow().nodeId();
        EnrollTokenDTO second = nodeService.issueEnrollment(nodeId);
        assertNotEquals(first.token(), second.token());
        // 旧 token 被吊销（expiresAtMs 提前 → 过期 → 401，且未被消费语义）。
        McpanelBusinessException stale = assertThrows(McpanelBusinessException.class,
                () -> enrollService.bootstrap(new BootstrapCmd(first.token(), "node-r1", "0.1.0", List.of(), null)));
        assertEquals(401, stale.httpStatus());
        assertEquals("auth.badToken", stale.code());
        // 新 token 正常注册一次，之后重放 409。
        EnrollResultDTO result = enrollService.bootstrap(new BootstrapCmd(second.token(), "node-r1", "0.1.0", List.of(), null));
        assertEquals(43, result.nodeSecret().length());
        McpanelBusinessException replay = assertThrows(McpanelBusinessException.class,
                () -> enrollService.bootstrap(new BootstrapCmd(second.token(), "node-r1", "0.1.0", List.of(), null)));
        assertEquals(409, replay.httpStatus());
        assertEquals("auth.tokenConsumed", replay.code());
    }

    @Test
    void disabledNodeCannotEnrollAndBootstrapRejectsWithNodeDisabled() {
        var created = nodeService.create(new NodeCreateCmd("节点K", "wss://node-k.example.com:9701",
                "pkix", null, false, null, true, null, null, null, null, null));
        EnrollTokenDTO token = nodeService.issueEnrollment(created.id());
        nodeService.update(new online.yudream.base.plugin.mcpanel.application.cmd.NodeUpdateCmd(
                created.id(), null, null, null, null, null, null, false, null, null, null, null, null));
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> enrollService.bootstrap(new BootstrapCmd(token.token(), "node-k", "0.1.0", List.of(), null)));
        assertEquals(409, error.httpStatus());
        assertEquals("node-disabled", error.code());
    }

    @Test
    void bootstrapPayloadLimitsReturn400InvalidRequest() {
        EnrollTokenDTO token = issue("节点L", "wss://node-l.example.com:9701", "pkix", null);
        assertPayloadLimit(new BootstrapCmd(token.token(), "h".repeat(256), "0.1.0", List.of(), null));
        assertPayloadLimit(new BootstrapCmd(token.token(), "h", "v".repeat(65), List.of(), null));
        assertPayloadLimit(new BootstrapCmd(token.token(), "h", "0.1.0",
                List.of("c".repeat(65)), null));
        List<String> tooManyCaps = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            tooManyCaps.add("cap");
        }
        assertPayloadLimit(new BootstrapCmd(token.token(), "h", "0.1.0", tooManyCaps, null));
        // 非 64 位小写 hex 的指纹 → 400（pkix 节点也要求格式合法）
        assertPayloadLimit(new BootstrapCmd(token.token(), "h", "0.1.0", List.of(), "XYZ"));
        // 合法边界值可用：64 位 hex
        McpanelBusinessException notConsumedCheck = assertThrows(McpanelBusinessException.class,
                () -> enrollService.bootstrap(new BootstrapCmd("invalid-token-boundary", "h", "0.1.0",
                        List.of("c".repeat(64)), "a".repeat(64))));
        assertEquals("auth.badToken", notConsumedCheck.code());
    }

    private void assertPayloadLimit(BootstrapCmd cmd) {
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> enrollService.bootstrap(cmd));
        assertEquals(400, error.httpStatus());
        assertEquals("invalid-request", error.code());
    }

    @Test
    void bootstrapRateLimitedAfterWindowBurst() {
        assertThrows(McpanelBusinessException.class, () -> {
            for (int i = 0; i <= McpanelEnrollService.RATE_LIMIT; i++) {
                try {
                    enrollService.bootstrap(new BootstrapCmd("invalid-" + i, "h", "0.1.0", List.of(), null));
                } catch (McpanelBusinessException error) {
                    if (i == McpanelEnrollService.RATE_LIMIT) {
                        assertEquals("auth.rateLimited", error.code());
                        assertEquals(429, error.httpStatus());
                        throw error;
                    }
                    assertEquals("auth.badToken", error.code());
                }
            }
        });
    }

    // ---------- 并发：同节点只注册一次、只发一个 secret ----------

    private record BootstrapOutcome(EnrollResultDTO result, McpanelBusinessException error) {
    }

    private List<BootstrapOutcome> runConcurrent(List<String> rawTokens, String hostname) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(rawTokens.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BootstrapOutcome>> futures = new ArrayList<>();
        for (String raw : rawTokens) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return new BootstrapOutcome(
                            enrollService.bootstrap(new BootstrapCmd(raw, hostname, "0.1.0", List.of(), null)), null);
                } catch (McpanelBusinessException error) {
                    return new BootstrapOutcome(null, error);
                }
            }));
        }
        start.countDown();
        List<BootstrapOutcome> outcomes = new ArrayList<>();
        for (Future<BootstrapOutcome> future : futures) {
            outcomes.add(future.get(15, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return outcomes;
    }

    private void assertSingleEnrollment(List<BootstrapOutcome> outcomes, String expectedNodeId) {
        long successes = outcomes.stream().filter(outcome -> outcome.result() != null).count();
        assertEquals(1, successes, "同节点并发注册必须恰好成功一次");
        EnrollResultDTO result = outcomes.stream().filter(outcome -> outcome.result() != null)
                .findFirst().orElseThrow().result();
        assertEquals(expectedNodeId, result.nodeId());
        assertEquals(43, result.nodeSecret().length());
        for (BootstrapOutcome outcome : outcomes) {
            if (outcome.error() != null) {
                assertEquals(409, outcome.error().httpStatus());
                assertEquals("auth.tokenConsumed", outcome.error().code());
            }
        }
    }

    @Test
    void concurrentBootstrapSameTokenEnrollsExactlyOnce() throws Exception {
        EnrollTokenDTO token = issue("节点P1", "wss://node-p1.example.com:9701", "pkix", null);
        List<String> rawTokens = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            rawTokens.add(token.token());
        }
        List<BootstrapOutcome> outcomes = runConcurrent(rawTokens, "node-p1");
        assertSingleEnrollment(outcomes, tokenRepository.findByDigest(NodeSecrets.sha256Hex(token.token()))
                .orElseThrow().nodeId());
        assertTrue(nodeRepository.findById(
                tokenRepository.findByDigest(NodeSecrets.sha256Hex(token.token())).orElseThrow().nodeId()
        ).orElseThrow().enrolled());
    }

    @Test
    void concurrentDistinctTokensEnrollExactlyOnce() throws Exception {
        var created = nodeService.create(new NodeCreateCmd("节点P2", "wss://node-p2.example.com:9701",
                "pkix", null, false, null, true, null, null, null, null, null));
        long now = clock.get();
        // 绕过重签吊销，手工放置两枚同节点有效 token，模拟多 token 并发领取。
        String rawA = NodeSecrets.newToken();
        String rawB = NodeSecrets.newToken();
        tokenRepository.save(new EnrollToken("manual-a", created.id(),
                NodeSecrets.sha256Hex(rawA), now + 60_000L, 0L, now));
        tokenRepository.save(new EnrollToken("manual-b", created.id(),
                NodeSecrets.sha256Hex(rawB), now + 60_000L, 0L, now));
        List<String> rawTokens = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            rawTokens.add(i % 2 == 0 ? rawA : rawB);
        }
        List<BootstrapOutcome> outcomes = runConcurrent(rawTokens, "node-p2");
        assertSingleEnrollment(outcomes, created.id());
        assertTrue(nodeRepository.findById(created.id()).orElseThrow().enrolled());
    }
}
