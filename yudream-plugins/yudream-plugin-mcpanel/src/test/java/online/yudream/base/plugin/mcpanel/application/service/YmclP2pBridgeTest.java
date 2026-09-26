package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.acceptance.InMemorySecretStore;
import online.yudream.base.plugin.ymcl.api.YmclP2pCandidateView;
import online.yudream.base.plugin.ymcl.api.YmclP2pException;
import online.yudream.base.plugin.ymcl.api.YmclP2pProvider;
import online.yudream.base.plugin.ymcl.api.YmclP2pSessionView;
import online.yudream.base.plugin.mcpanel.application.dto.PanelSettings;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentMcpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面板对启动器适配器的 P2P 贡献（{@link YmclP2pProvider} 扩展点）：适配器只需这一个接口
 * 就能驱动「开会话 → 拿票据与候选 → 查状态 → 关闭」，失败带机器码便于给玩家可读提示。
 * 方向为「面板实现适配器声明的扩展点」，因此适配器无需依赖面板（不成环）。
 */
class YmclP2pBridgeTest {

    private static final class FakeNode implements P2PSessionService.NodePort {
        final List<String> caps = new ArrayList<>(List.of(
                P2PSessionService.CAP_OPEN, P2PSessionService.CAP_SIGNAL, P2PSessionService.CAP_CLOSE));
        final List<Map<String, Object>> calls = new ArrayList<>();

        @Override
        public boolean supports(String nodeId, String capability) {
            return caps.contains(capability);
        }

        @Override
        public Map<String, Object> call(String nodeId, String method, Map<String, Object> payload) {
            calls.add(payload);
            return Map.of("accepted", true);
        }

        @Override
        public String advertisedHost(String nodeId) {
            return "203.0.113.9";
        }
    }

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final DocumentMcpanelInstanceRepository instances =
            new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
    private final FakeNode node = new FakeNode();
    /** 域名反查只读实例仓库（写入路径由 DomainServiceTest 覆盖）。 */
    private final class InstanceList implements DomainService.InstancePort {
        @Override
        public List<McpanelInstance> all() {
            return instances.findAll();
        }

        @Override
        public void setDomain(String instanceId, String slug, boolean enabled) {
        }
    }

    private PanelSettings.P2p p2pConfig = new PanelSettings.P2p(true, 2048, 3);
    private P2PSessionService sessions;
    private DomainService domains;
    private YmclP2pProvider api;

    @BeforeEach
    void setUp() {
        sessions = new P2PSessionService(instances, () -> p2pConfig, node, null);
        domains = new DomainService(domainSettings(), new InstanceList(), null,
                (driver, zone, apiBase, creds) -> null);
        api = new YmclP2pBridge(sessions, domains);
    }

    /** 域名服务只用于反查：把生效后缀写进设置文档（驱动不参与反查）。 */
    private SettingsService domainSettings() {
        SettingsService service = new SettingsService(documents, McpanelJson.mapper(),
                new NodeSecrets(new InMemorySecretStore()), Optional::empty);
        documents.save("mcpanel_settings", "settings", Map.of(
                "dns", Map.of("mode", "aliyun", "suffix", "mc.example.com",
                        "ttlSeconds", 600, "provider", Map.of("zone", "example.com", "apiBase", ""))));
        return service;
    }

    @AfterEach
    void tearDown() {
        sessions.close();
    }

    private void instance(String id, boolean p2pEnabled, List<String> whitelist, String state) {
        instance(id, p2pEnabled, whitelist, state, "");
    }

    private void instance(String id, boolean p2pEnabled, List<String> whitelist, String state,
                          String domainSlug) {
        McpanelInstance base = McpanelInstance.create(id, "node-1", "服务器" + id, "paper", "1.21", "", "img",
                List.of("java"), Map.of(), 1024, 1000, 2048,
                List.of(new McpanelInstance.PortMapping(25600, 25565, "tcp")), Map.of(), null, "",
                System.currentTimeMillis());
        instances.save(new McpanelInstance(base.id(), base.nodeId(), base.name(), base.kind(),
                base.mcVersion(), base.templateKey(), base.image(), base.command(), base.env(),
                base.memoryMb(), base.cpuMillis(), base.diskMb(), base.ports(), base.config(), state,
                null, null, null, "", domainSlug, !domainSlug.isEmpty(), p2pEnabled, whitelist,
                base.nodeTrust(), base.modpack(), base.coreFallbackHistory(), base.startDetect(),
                base.autoRestart(), base.autoStart(), base.createdAt(), base.updatedAt()));
    }

    @Test
    void launcherDrivesSessionThroughTheStableContract() {
        instance("i1", true, List.of(), "running");
        assertTrue(api.available());
        assertTrue(api.instanceOpen("i1"));
        assertFalse(api.instanceOpen("missing"));

        YmclP2pSessionView opened = api.open(1001L, "i1");
        assertTrue(opened.sessionId() != null && !opened.sessionId().isBlank());
        assertFalse(opened.ticket().isBlank(), "开会话必须带票据（玩家侧据此与节点握手）");
        assertEquals(25600, opened.targetPort(), "票据钉死目标端口");
        assertEquals("waiting", opened.state());
        assertEquals(1, api.activeSessions());

        // 节点回报候选（host 由面板补齐）→ 契约视图可见
        sessions.onNodeState("node-1", Map.of("sessionId", opened.sessionId(), "state", "connecting",
                "candidates", List.of(Map.of("proto", "tcp", "host", "", "port", 41234))));
        YmclP2pSessionView connected = api.session(1001L, opened.sessionId()).orElseThrow();
        assertEquals("connecting", connected.state());
        assertEquals(1, connected.candidates().size());
        assertEquals("203.0.113.9", connected.candidates().get(0).host());
        assertEquals(41234, connected.candidates().get(0).port());

        // 中继本端候选（打洞路径）后仍是同一会话
        YmclP2pSessionView signalled = api.signal(1001L, opened.sessionId(),
                List.of(new YmclP2pCandidateView("udp", "198.51.100.7", 52100, 0)));
        assertEquals(opened.sessionId(), signalled.sessionId());
        assertEquals("", signalled.ticket(), "非开会话响应不得回显票据");

        api.close(1001L, opened.sessionId(), "启动器退出");
        assertEquals(0, api.activeSessions());
        assertTrue(api.session(1001L, opened.sessionId()).isEmpty());
    }

    @Test
    void mapsServerAddressToP2pEnabledInstance() {
        instance("i1", true, List.of(), "running", "survival");
        instance("i2", false, List.of(), "running", "creative");
        instance("i3", true, List.of(), "running");

        // 只广告开了 P2P 的实例：启动器据此把服务器条目关联到实例
        assertEquals(Optional.of("i1"), api.instanceForAddress("survival.mc.example.com"));
        assertEquals(Optional.of("i1"), api.instanceForAddress("SURVIVAL.mc.example.com:25565"));
        assertEquals(Optional.empty(), api.instanceForAddress("creative.mc.example.com"),
                "未开 P2P 的实例不广告（否则启动器会为注定被拒的实例建隧道）");
        assertEquals(Optional.empty(), api.instanceForAddress("i3.mc.example.com"));
        assertEquals(Optional.empty(), api.instanceForAddress("unknown.mc.example.com"));
        assertEquals(Optional.empty(), api.instanceForAddress(null));
    }

    @Test
    void failuresCarryMachineReadableCodes() {
        instance("i1", false, List.of(), "running");
        assertEquals("p2p.instance-disabled", assertThrows(YmclP2pException.class,
                () -> api.open(1001L, "i1")).code());

        instance("i2", true, List.of("2002"), "running");
        assertEquals("p2p.not-allowed", assertThrows(YmclP2pException.class,
                () -> api.open(1001L, "i2")).code());

        instance("i3", true, List.of(), "exited");
        assertEquals("p2p.not-running", assertThrows(YmclP2pException.class,
                () -> api.open(1001L, "i3")).code());

        p2pConfig = new PanelSettings.P2p(false, 2048, 3);
        assertFalse(api.available());
        assertEquals("p2p.disabled", assertThrows(YmclP2pException.class,
                () -> api.open(1001L, "i1")).code());
    }

    @Test
    void nodeCapabilityGapIsHonestNotFaked() {
        instance("i1", true, List.of(), "running");
        node.caps.clear();

        YmclP2pException error = assertThrows(YmclP2pException.class,
                () -> api.open(1001L, "i1"));
        assertEquals("p2p.node-capability", error.code());
        assertTrue(error.getMessage().contains("P2P"));
        assertEquals(0, api.activeSessions(), "能力缺口不得留下会话");
    }

    @Test
    void otherPlayersCannotSeeOrCloseTheSession() {
        instance("i1", true, List.of(), "running");
        YmclP2pSessionView opened = api.open(1001L, "i1");

        assertTrue(api.session(2002L, opened.sessionId()).isEmpty(), "别人的会话不可见");
        assertEquals("p2p.not-found", assertThrows(YmclP2pException.class,
                () -> api.signal(2002L, opened.sessionId(), List.of())).code());
        assertEquals("p2p.not-found", assertThrows(YmclP2pException.class,
                () -> api.close(2002L, opened.sessionId(), "x")).code());
        assertTrue(api.session(1001L, opened.sessionId()).isPresent(), "本人会话不受影响");
        Optional<YmclP2pSessionView> own = api.session(1001L, opened.sessionId());
        assertTrue(own.isPresent());
    }
}
