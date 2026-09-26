package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.application.dto.PanelSettings;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentMcpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动器 P2P 信令与会话生命周期（面板即 rendezvous）：
 * 授权/白名单/限额 → 票据与目标端口 → 候选中继 → 节点状态回报 → 过期回收 → 管理端强制断开。
 *
 * <p>这条测试就是「驱动」：用假节点把整条信令链路跑通，节点侧真实传输由 Go 侧回环测试覆盖。
 */
class P2PSessionServiceTest {

    /** 记录调度的假节点：caps 可控，调用可注入失败。 */
    private static final class FakeNode implements P2PSessionService.NodePort {
        final List<String> caps = new ArrayList<>(List.of(
                P2PSessionService.CAP_OPEN, P2PSessionService.CAP_SIGNAL, P2PSessionService.CAP_CLOSE));
        final List<Map<String, Object>> calls = new ArrayList<>();
        final List<String> methods = new ArrayList<>();
        boolean failCall;

        @Override
        public boolean supports(String nodeId, String capability) {
            return caps.contains(capability);
        }

        @Override
        public String advertisedHost(String nodeId) {
            return "203.0.113.9";
        }

        @Override
        public Map<String, Object> call(String nodeId, String method, Map<String, Object> payload) {
            methods.add(method);
            Map<String, Object> recorded = new LinkedHashMap<>(payload);
            recorded.put("nodeId", nodeId);
            recorded.put("method", method);
            calls.add(recorded);
            if (failCall) {
                throw new IllegalStateException("节点不可达");
            }
            return Map.of("accepted", true);
        }

        Map<String, Object> lastCall(String method) {
            for (int index = calls.size() - 1; index >= 0; index--) {
                if (method.equals(calls.get(index).get("method"))) {
                    return calls.get(index);
                }
            }
            return null;
        }
    }

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final DocumentMcpanelInstanceRepository instances =
            new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
    private final FakeNode node = new FakeNode();
    private final List<String> audits = new ArrayList<>();
    private PanelSettings.P2p p2pConfig = new PanelSettings.P2p(true, 2048, 2);
    private P2PSessionService service;

    @BeforeEach
    void setUp() {
        service = new P2PSessionService(instances,
                () -> p2pConfig,
                node,
                (actor, action, targetType, targetId, detail, tenantId) -> audits.add(action + "|" + detail));
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    private void instance(String id, boolean p2pEnabled, List<String> whitelist, String state) {
        McpanelInstance base = McpanelInstance.create(id, "node-1", "服务器" + id, "paper", "1.21", "", "img",
                List.of("java"), Map.of(), 1024, 1000, 2048,
                List.of(new McpanelInstance.PortMapping(25600, 25565, "tcp")), Map.of(), null, "",
                System.currentTimeMillis());
        McpanelInstance withP2p = new McpanelInstance(base.id(), base.nodeId(), base.name(), base.kind(),
                base.mcVersion(), base.templateKey(), base.image(), base.command(), base.env(),
                base.memoryMb(), base.cpuMillis(), base.diskMb(), base.ports(), base.config(), state,
                null, null, null, "", base.domainSlug(), base.domainEnabled(), p2pEnabled, whitelist,
                base.nodeTrust(), base.modpack(), base.coreFallbackHistory(), base.startDetect(),
                base.autoRestart(), base.autoStart(), base.createdAt(), base.updatedAt());
        instances.save(withP2p);
    }

    @Test
    void openIssuesTicketBoundToInstanceAndPinsContainerPort() {
        instance("i1", true, List.of(), "running");

        Map<String, Object> session = service.open(1001L, "i1");

        assertNotNull(session.get("sessionId"));
        assertNotNull(session.get("ticket"));
        assertEquals("waiting", session.get("state"));
        assertEquals(25600, session.get("targetPort"), "票据钉死实例的宿主端口（节点侧 127.0.0.1:宿主端口 直达实例）");
        assertEquals(2048, session.get("rateKbps"), "速率档随票据下发，节点本地执行");
        Map<String, Object> open = node.lastCall(P2PSessionService.CAP_OPEN);
        assertNotNull(open, "开会话必须通知节点开面");
        assertEquals("i1", open.get("instanceId"));
        assertEquals(25600, open.get("targetPort"));
        assertEquals(session.get("ticket"), open.get("ticket"));
        assertTrue(audits.stream().anyMatch(item -> item.startsWith("p2p.session.open")));
    }

    @Test
    void openRejectsDisabledInstanceWhitelistAndOfflineServer() {
        instance("i1", false, List.of(), "running");
        assertEquals("p2p.instance-disabled", assertThrows(McpanelBusinessException.class,
                () -> service.open(1001L, "i1")).code());

        instance("i2", true, List.of("2002"), "running");
        assertEquals("p2p.not-allowed", assertThrows(McpanelBusinessException.class,
                () -> service.open(1001L, "i2")).code());

        instance("i3", true, List.of(), "exited");
        assertEquals("p2p.not-running", assertThrows(McpanelBusinessException.class,
                () -> service.open(1001L, "i3")).code());

        p2pConfig = new PanelSettings.P2p(false, 2048, 2);
        assertEquals("p2p.disabled", assertThrows(McpanelBusinessException.class,
                () -> service.open(1001L, "i1")).code());
    }

    @Test
    void openHonestlyReportsNodeCapabilityGap() {
        node.caps.clear(); // 节点未实现 p2p.*
        instance("i1", true, List.of(), "running");

        Map<String, Object> result = service.open(1001L, "i1");

        assertEquals(0, service.sessionCount(), "能力缺口时不得留下会话");
        @SuppressWarnings("unchecked")
        Map<String, Object> gap = (Map<String, Object>) result.get("capabilityGap");
        assertNotNull(gap);
        assertEquals("unavailable", gap.get("nodeCapability"));
        assertTrue(String.valueOf(gap.get("message")).contains("p2p"));
        assertTrue(node.methods.isEmpty(), "能力缺口不得调用节点");
    }

    @Test
    void perUserConcurrencyIsLimited() {
        instance("i1", true, List.of(), "running");
        service.open(1001L, "i1");
        service.open(1001L, "i1");

        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.open(1001L, "i1"));
        assertEquals("p2p.limit", error.code());
        assertTrue(error.getMessage().contains("2"));
        // 另一个玩家不受影响
        assertNotNull(service.open(1002L, "i1").get("sessionId"));
    }

    @Test
    void signallingRelaysCandidatesBothWays() {
        instance("i1", true, List.of(), "running");
        Map<String, Object> session = service.open(1001L, "i1");
        String sessionId = String.valueOf(session.get("sessionId"));

        // 节点先回报自己的候选（打洞需要双方互见）
        service.onNodeState("node-1", Map.of("sessionId", sessionId, "state", "connecting",
                "candidates", List.of(Map.of("proto", "udp", "host", "203.0.113.9", "port", 41000))));

        // 启动器提交自己的候选 → 转给节点，并把节点候选回给启动器
        Map<String, Object> result = service.signal(1001L, sessionId,
                List.of(Map.of("proto", "udp", "host", "198.51.100.7", "port", 52100)));

        assertEquals("connecting", result.get("state"));
        List<?> nodeCandidates = (List<?>) result.get("nodeCandidates");
        assertEquals(1, nodeCandidates.size());
        Map<String, Object> signal = node.lastCall(P2PSessionService.CAP_SIGNAL);
        assertNotNull(signal);
        assertEquals(session.get("ticket"), signal.get("ticket"));
        List<?> relayed = (List<?>) signal.get("candidates");
        assertEquals(1, relayed.size());
    }

    @Test
    void nodeStateDrivesSessionStateAndClosesIt() {
        instance("i1", true, List.of(), "running");
        String sessionId = String.valueOf(service.open(1001L, "i1").get("sessionId"));

        service.onNodeState("node-1", Map.of("sessionId", sessionId, "state", "direct",
                "bytesSent", 1024, "bytesReceived", 2048));
        Map<String, Object> status = service.status(1001L, sessionId);
        assertEquals("direct", status.get("state"));
        assertEquals(1024L, status.get("bytesSent"));

        service.onNodeState("node-1", Map.of("sessionId", sessionId, "state", "relayed"));
        assertEquals("relayed", service.status(1001L, sessionId).get("state"));

        service.onNodeState("node-1", Map.of("sessionId", sessionId, "state", "failed",
                "reason", "打洞失败且中继不可用"));
        assertEquals(0, service.sessionCount(), "终态会话应被回收");
        assertTrue(audits.stream().anyMatch(item -> item.startsWith("p2p.session.failed")));
        assertThrows(McpanelBusinessException.class, () -> service.status(1001L, sessionId));
    }

    @Test
    void nodeCandidatesGetHostFilledFromAdvertisedAddress() {
        instance("i1", true, List.of(), "running");
        String sessionId = String.valueOf(service.open(1001L, "i1").get("sessionId"));

        // 节点只报端口（不知道自己公网地址）→ 面板补齐为节点对外地址，启动器才能直连
        service.onNodeState("node-1", Map.of("sessionId", sessionId, "state", "connecting",
                "candidates", List.of(Map.of("proto", "tcp", "host", "", "port", 41234))));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates =
                (List<Map<String, Object>>) service.status(1001L, sessionId).get("nodeCandidates");
        assertEquals(1, candidates.size());
        assertEquals("203.0.113.9", candidates.get(0).get("host"));
        assertEquals(41234, candidates.get(0).get("port"));
    }

    @Test
    void foreignNodeOrUserCannotTouchTheSession() {
        instance("i1", true, List.of(), "running");
        String sessionId = String.valueOf(service.open(1001L, "i1").get("sessionId"));

        // 别的节点伪报状态：忽略
        service.onNodeState("node-2", Map.of("sessionId", sessionId, "state", "direct"));
        assertEquals("waiting", service.status(1001L, sessionId).get("state"));
        // 别的用户查/关：一律 404（不泄露是否存在）
        assertThrows(McpanelBusinessException.class, () -> service.status(2002L, sessionId));
        assertThrows(McpanelBusinessException.class, () -> service.close(2002L, sessionId, "x"));
        assertThrows(McpanelBusinessException.class, () -> service.signal(2002L, sessionId, List.of()));
    }

    @Test
    void closeNotifiesNodeAndIsIdempotent() {
        instance("i1", true, List.of(), "running");
        String sessionId = String.valueOf(service.open(1001L, "i1").get("sessionId"));

        Map<String, Object> closed = service.close(1001L, sessionId, "启动器退出");
        assertEquals(true, closed.get("closed"));
        Map<String, Object> call = node.lastCall(P2PSessionService.CAP_CLOSE);
        assertNotNull(call);
        assertEquals("启动器退出", call.get("reason"));
        assertThrows(McpanelBusinessException.class, () -> service.close(1001L, sessionId, "重复关闭"));
    }

    @Test
    void nodeUnreachableAtOpenFailsCleanly() {
        instance("i1", true, List.of(), "running");
        node.failCall = true;

        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.open(1001L, "i1"));
        assertEquals("p2p.node-unreachable", error.code());
        assertEquals(0, service.sessionCount(), "节点不可达不得留下悬挂会话");
    }

    @Test
    void adminCanListAndForceCloseSessions() {
        instance("i1", true, List.of(), "running");
        String sessionId = String.valueOf(service.open(1001L, "i1").get("sessionId"));

        List<Map<String, Object>> active = service.activeSessions();
        assertEquals(1, active.size());
        assertFalse(active.get(0).containsKey("ticket"), "管理端视图不得泄露票据明文");

        Map<String, Object> closed = service.forceClose(sessionId, "管理员断开");
        assertEquals(true, closed.get("closed"));
        assertEquals(0, service.sessionCount());
        assertTrue(audits.stream().anyMatch(item -> item.contains("管理员断开")));
        assertEquals(false, service.forceClose(sessionId, "再断一次").get("closed"));
    }
}
