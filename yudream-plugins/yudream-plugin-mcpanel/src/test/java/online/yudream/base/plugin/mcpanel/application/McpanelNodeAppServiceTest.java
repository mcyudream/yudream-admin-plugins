package online.yudream.base.plugin.mcpanel.application;

import online.yudream.base.plugin.mcpanel.application.cmd.NodeCreateCmd;
import online.yudream.base.plugin.mcpanel.application.cmd.NodeQueryCmd;
import online.yudream.base.plugin.mcpanel.application.dto.NodeDTO;
import online.yudream.base.plugin.mcpanel.application.service.McpanelNodeAppService;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.enumerate.NodeStatus;
import online.yudream.base.plugin.mcpanel.domain.repo.EnrollTokenRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.valobj.EnrollToken;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentEnrollTokenRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets;
import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.acceptance.InMemorySecretStore;
import online.yudream.base.plugin.mcpanel.support.TestStubs.RecordingControlPlane;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpanelNodeAppServiceTest {

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final InMemorySecretStore secretStore = new InMemorySecretStore();
    private final RecordingControlPlane controlPlane = new RecordingControlPlane();
    private final McpanelNodeRepository nodeRepository =
            new DocumentNodeRepository(documents, McpanelJson.mapper());
    private final EnrollTokenRepository tokenRepository =
            new DocumentEnrollTokenRepository(documents, McpanelJson.mapper());
    private final McpanelNodeAppService service = new McpanelNodeAppService(
            nodeRepository, tokenRepository, new NodeSecrets(secretStore), controlPlane);

    private NodeCreateCmd cmd(String name, String endpoint) {
        return new NodeCreateCmd(name, endpoint, "pkix", null, false, null, true, null, null, null, null, null);
    }

    @Test
    void createRequiresEndpointEvenWhenNamePresent() {
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.create(new NodeCreateCmd("只有名字", null, null, null, false, null, true, null, null, null, null, null)));
        assertEquals(400, error.httpStatus());
    }

    @Test
    void createNormalizesAndStoresConfig() {
        NodeDTO node = service.create(cmd("节点A", "wss://node-a.example.com:9701"));
        assertNotNull(node.id());
        assertEquals("wss://node-a.example.com:9701", node.endpoint());
        assertEquals("pkix", node.tlsMode());
        assertEquals("enrolling", node.status());
        // F4b：hasSecret = 已完成注册且有凭据；创建期预生成不作为"已注册"信号。
        assertFalse(node.hasSecret());
        assertFalse(node.connected());
        assertNull(node.tenantId());
        assertTrue(controlPlane.synced.contains(node.id()));
    }

    @Test
    void statusDerivesConnectingWhenDialing() {
        NodeDTO node = service.create(cmd("节点B", "wss://node-b.example.com:9701"));
        // 未注册节点恒为 enrolling，即使 runtime 显示拨号中（enrolled 门禁）。
        controlPlane.connected = true;
        assertEquals("enrolling", service.detail(node.id()).status());
        // 注册后：runtime 拨号中 → connecting；在线 → online；断开 → offline。
        nodeRepository.save(nodeRepository.findById(node.id()).orElseThrow()
                .withReported("0.1.0", "node-b", null, 1L));
        assertEquals("connecting", service.detail(node.id()).status());
        controlPlane.online = true;
        assertEquals("online", service.detail(node.id()).status());
        controlPlane.online = false;
        controlPlane.connected = false;
        assertEquals("offline", service.detail(node.id()).status());
    }

    @Test
    void pageReturnsTrueTotalByFullTraversal() {
        for (int i = 0; i < 210; i++) {
            McpanelNode node = McpanelNode.create("n" + i, "node-" + i,
                    "wss://node" + i + ".example.com:9701", "pkix", null, false, null, true, i);
            nodeRepository.save(node);
        }
        // 无过滤：按 200/页遍历全部节点后真实总数，无截断。
        var unfiltered = service.page(new NodeQueryCmd(1, 10, null, null));
        assertEquals(210, unfiltered.total());
        assertEquals(10, unfiltered.records().size());
        // 过滤：全量遍历后过滤，真实总数。
        var filtered = service.page(new NodeQueryCmd(1, 10, null, "node-199"));
        assertEquals(1, filtered.total());
        assertEquals("wss://node199.example.com:9701", filtered.records().get(0).endpoint());
        // 尾页仍返回真实数据
        var lastPage = service.page(new NodeQueryCmd(21, 10, null, null));
        assertEquals(10, lastPage.records().size());
    }

    @Test
    void updatePreservesUnspecifiedFieldsAndTogglesEnabled() {
        NodeDTO created = service.create(cmd("节点C", "wss://node-c.example.com:9701"));
        NodeDTO updated = service.update(new online.yudream.base.plugin.mcpanel.application.cmd.NodeUpdateCmd(
                created.id(), "节点C2", null, null, null, null, "备注", false, null, null, null, null, null));
        assertEquals("节点C2", updated.name());
        assertEquals("wss://node-c.example.com:9701", updated.endpoint());
        assertEquals("备注", updated.remark());
        assertFalse(updated.enabled());
        assertEquals("enrolling", updated.status());
    }

    @Test
    void deleteRemovesNodeSecretsAndTokens() {
        NodeDTO created = service.create(cmd("节点D", "wss://node-d.example.com:9701"));
        var token = service.issueEnrollment(created.id());
        assertFalse(token.token().isBlank());
        service.delete(created.id());
        assertTrue(nodeRepository.findById(created.id()).isEmpty());
        assertTrue(secretStore.get(online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets.key(created.id())).isEmpty());
        assertTrue(tokenRepository.findByNodeId(created.id()).isEmpty());
        assertTrue(controlPlane.removed.contains(created.id()));
        // 幂等：重复删除不再 404，仍完成清扫并停控制面。
        service.delete(created.id());
        assertTrue(secretStore.get(online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets.key(created.id())).isEmpty());
        assertTrue(controlPlane.removed.contains(created.id()));
    }

    @Test
    void deleteAfterPartialFailureStillSweepsLeftovers() {
        NodeDTO created = service.create(cmd("节点D2", "wss://node-d2.example.com:9701"));
        service.issueEnrollment(created.id());
        // 模拟"删 doc 成功后中断"：doc 缺失但 token/secret/控制面残留。
        nodeRepository.delete(created.id());
        assertFalse(tokenRepository.findByNodeId(created.id()).isEmpty());
        // 重试删除：不 404，清扫全部残留。
        service.delete(created.id());
        assertTrue(tokenRepository.findByNodeId(created.id()).isEmpty());
        assertTrue(secretStore.get(online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets.key(created.id())).isEmpty());
        assertTrue(controlPlane.removed.contains(created.id()));
    }

    @Test
    void deleteRunsCascadeCleanerBeforeRemovingNodeDoc() {
        List<String> order = new java.util.ArrayList<>();
        McpanelNodeAppService cascading = new McpanelNodeAppService(nodeRepository, tokenRepository,
                new NodeSecrets(secretStore), controlPlane, System::currentTimeMillis,
                nodeId -> {
                    // 级联必须发生在节点 doc 仍在时执行（此时实例尚可见、可关联清理）
                    order.add("cascade:" + nodeId + ":doc-present=" + nodeRepository.findById(nodeId).isPresent());
                    return 0;
                });
        NodeDTO created = cascading.create(cmd("节点K", "wss://node-k.example.com:9701"));
        cascading.delete(created.id());
        assertEquals(List.of("cascade:" + created.id() + ":doc-present=true"), order);
        assertTrue(nodeRepository.findById(created.id()).isEmpty());
        assertTrue(controlPlane.removed.contains(created.id()));
        // doc 已删的重试删除：级联仍执行（清扫残留实例），不 404
        cascading.delete(created.id());
        assertEquals(2, order.size());
        assertEquals("cascade:" + created.id() + ":doc-present=false", order.get(1));
    }

    @Test
    void pageFilterUsesDerivedFourStateStatusWithTrueTotal() {
        // node1：未注册 → enrolling
        NodeDTO enrolling = service.create(cmd("节点S1", "wss://node-s1.example.com:9701"));
        // node2：已注册 + 持久 offline → offline
        NodeDTO offline = service.create(cmd("节点S2", "wss://node-s2.example.com:9701"));
        nodeRepository.save(nodeRepository.findById(offline.id()).orElseThrow()
                .withReported("0.1.0", "node-s2", null, 1L));
        // node3：已注册 + 持久 online → online
        NodeDTO online = service.create(cmd("节点S3", "wss://node-s3.example.com:9701"));
        nodeRepository.save(nodeRepository.findById(online.id()).orElseThrow()
                .withReported("0.1.0", "node-s3", null, 1L)
                .withRuntime(NodeStatus.ONLINE,
                        "0.1.0", "node-s3", "session", List.of(), null, null, 1L, 1L));
        assertEquals("enrolling", service.detail(enrolling.id()).status());
        assertEquals("offline", service.detail(offline.id()).status());
        assertEquals("online", service.detail(online.id()).status());

        assertEquals(1, service.page(new NodeQueryCmd(1, 10, "enrolling", null)).total());
        assertEquals(1, service.page(new NodeQueryCmd(1, 10, "offline", null)).total());
        assertEquals(1, service.page(new NodeQueryCmd(1, 10, "online", null)).total());
        assertEquals(0, service.page(new NodeQueryCmd(1, 10, "connecting", null)).total());
        // 未注册节点即使持久 offline 也不计入 offline 筛选（enrolling 优先）。
        assertEquals(enrolling.id(), service.page(new NodeQueryCmd(1, 10, "enrolling", null))
                .records().get(0).id());
        assertEquals(offline.id(), service.page(new NodeQueryCmd(1, 10, "offline", null))
                .records().get(0).id());
    }

    @Test
    void statusFilterTraversesBeyondSinglePageWithTrueTotal() {
        // 255 个节点跨 3 页（批次 100）：210 enrolling + 45 online。
        for (int i = 0; i < 210; i++) {
            nodeRepository.save(McpanelNode.create("e" + i, "node-e" + i,
                    "wss://nodee" + i + ".example.com:9701", "pkix", null, false, null, true, i));
        }
        for (int i = 0; i < 45; i++) {
            nodeRepository.save(nodeRepository.findById(
                    service.create(cmd("节点O" + i, "wss://nodeo" + i + ".example.com:9701")).id())
                    .orElseThrow()
                    .withReported("0.1.0", "node-o" + i, null, 1L)
                    .withRuntime(NodeStatus.ONLINE, "0.1.0", "node-o" + i, "s", List.of(), null, null, 1L, 1L));
        }
        var enrolling = service.page(new NodeQueryCmd(1, 100, "enrolling", null));
        org.junit.jupiter.api.Assertions.assertEquals(210, enrolling.total(), "enrolling 必须跨页遍历统计真实总数");
        org.junit.jupiter.api.Assertions.assertEquals(100, enrolling.records().size());
        var enrollingPage3 = service.page(new NodeQueryCmd(3, 100, "enrolling", null));
        org.junit.jupiter.api.Assertions.assertEquals(10, enrollingPage3.records().size());
        var online = service.page(new NodeQueryCmd(1, 100, "online", null));
        org.junit.jupiter.api.Assertions.assertEquals(45, online.total(), "online 必须跨页遍历统计真实总数");
        org.junit.jupiter.api.Assertions.assertEquals(45, online.records().size());
    }

    @Test
    void persistedDocumentsContainNoNullValues() {
        NodeDTO created = service.create(cmd("节点N", "wss://node-n.example.com:9701"));
        var raw = documents.findById("nodes", created.id()).orElseThrow();
        raw.forEach((key, value) -> assertNotNull(value, "文档字段 " + key + " 不应持久化 null"));
        // CAS 路径（token 兑换文档）同样无 null。
        var token = service.issueEnrollment(created.id());
        String digest = online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets.sha256Hex(token.token());
        var tokenDoc = documents.findByField("enroll-tokens", "tokenDigest", digest, 1, 5).get(0);
        tokenDoc.forEach((key, value) -> assertNotNull(value, "token 文档字段 " + key + " 不应持久化 null"));
    }

    @Test
    void issueEnrollmentRejectsAlreadyEnrolledNodes() {
        NodeDTO created = service.create(cmd("节点E", "wss://node-e.example.com:9701"));
        McpanelNode node = nodeRepository.findById(created.id()).orElseThrow();
        nodeRepository.save(node.withReported("0.1.0", "node-e", null, 1L));
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.issueEnrollment(created.id()));
        assertEquals(409, error.httpStatus());
        assertEquals("node-already-enrolled", error.code());
    }

    @Test
    void enrollmentTokenDigestStoredNotPlaintext() {
        NodeDTO created = service.create(cmd("节点F", "wss://node-f.example.com:9701"));
        var token = service.issueEnrollment(created.id());
        List<EnrollToken> tokens = tokenRepository.findByNodeId(created.id());
        assertEquals(1, tokens.size());
        assertFalse(tokens.get(0).toString().contains(token.token()));
        assertEquals(online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets.sha256Hex(token.token()),
                tokens.get(0).tokenDigest());
        assertTrue(token.expiresAt() > System.currentTimeMillis());
    }
}
