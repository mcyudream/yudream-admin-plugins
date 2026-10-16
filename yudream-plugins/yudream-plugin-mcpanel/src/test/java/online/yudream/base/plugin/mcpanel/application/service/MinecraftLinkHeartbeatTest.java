package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentMcpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 联动状态心跳：只回推已绑定子服的实例；失败只日志不抛出。
 * minecraft-server 用该回推做「线路均不可达（同机部署的公网 NAT 回环）时按面板容器
 * 状态判在线」的兜底依据，回推必须周期性刷新，否则列表会一直显示离线。
 */
class MinecraftLinkHeartbeatTest {

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final McpanelInstanceRepository instances =
            new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());

    /** 记录回推目标与人数上限的假联动服务（不回推真实提供方）。 */
    private static final class RecordingLink extends MinecraftLinkService {
        private final List<String> written = new ArrayList<>();
        private final List<Integer> maxPlayers = new ArrayList<>();
        private boolean fail;

        private RecordingLink() {
            super(Optional::empty);
        }

        @Override
        public void writebackState(McpanelInstance instance, int reportedMaxPlayers) {
            if (fail) {
                throw new IllegalStateException("提供方缺失");
            }
            written.add(instance.id());
            maxPlayers.add(reportedMaxPlayers);
        }
    }

    /** 假 server.properties 读取：view() 会按 base64 解码 content 并解析 properties。 */
    private ServerConfigService configsWith(String content) {
        return new ServerConfigService(new ServerConfigService.InstanceFiles() {
            @Override
            public Map<String, Object> read(String scopeKey, String instanceId, Map<String, Object> args) {
                return Map.of("content", java.util.Base64.getEncoder()
                        .encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }

            @Override
            public Map<String, Object> write(String scopeKey, String instanceId, Map<String, Object> args) {
                return Map.of();
            }
        });
    }

    /** 假 server.properties 内容（平面 key=value 文件）。 */
    private static final String PROPERTIES = """
            max-players=20
            motd=hello
            """;

    @Test
    void pulseCarriesMaxPlayersReadFromServerProperties() {
        instances.save(instance("inst-bound", "server-1", "running"));
        RecordingLink link = new RecordingLink();

        new MinecraftLinkHeartbeat(instances, link, configsWith(PROPERTIES)).pulseSafely();

        // 人数上限随回推带给提供方：ping 不可达时列表仍能按「在线/上限」展示
        assertEquals(List.of(20), link.maxPlayers);
    }

    @Test
    void pulseFallsBackToZeroWhenConfigUnreadable() {
        instances.save(instance("inst-bound", "server-1", "running"));
        RecordingLink link = new RecordingLink();
        ServerConfigService broken = new ServerConfigService(new ServerConfigService.InstanceFiles() {
            @Override
            public Map<String, Object> read(String scopeKey, String instanceId, Map<String, Object> args) {
                throw new online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException(
                        "node.unreachable", 502, "节点不可达");
            }

            @Override
            public Map<String, Object> write(String scopeKey, String instanceId, Map<String, Object> args) {
                return Map.of();
            }
        });

        new MinecraftLinkHeartbeat(instances, link, broken).pulseSafely();

        // 读不到上限不阻断状态回推：仍回推、上限按 0
        assertEquals(List.of("inst-bound"), link.written);
        assertEquals(List.of(0), link.maxPlayers);
    }

    private McpanelInstance instance(String id, String mcServerId, String state) {
        McpanelInstance base = McpanelInstance.create(id, "node-1", "实例" + id, "paper", "1.21.4", "",
                "eclipse-temurin:21-jre", List.of("java", "-jar", "server.jar"), Map.of(),
                1024, 1000, 2048, List.of(), Map.of(), null, "", 1L);
        McpanelInstance bound = mcServerId == null || mcServerId.isBlank()
                ? base
                : base.withBinding(mcServerId, 1L);
        return bound.withState(state, null, 1L);
    }

    @Test
    void pulseWritesBackOnlyBoundInstances() {
        instances.save(instance("inst-bound", "server-1", "running"));
        instances.save(instance("inst-plain", null, "running"));
        RecordingLink link = new RecordingLink();

        new MinecraftLinkHeartbeat(instances, link).pulseSafely();

        // 只回推已绑定子服的实例：未绑定的不推送，避免污染对端面板态
        assertEquals(List.of("inst-bound"), link.written);
    }

    @Test
    void pulseSwallowsFailuresAndRetriesNextRound() {
        instances.save(instance("inst-bound", "server-1", "running"));
        RecordingLink link = new RecordingLink();
        link.fail = true;

        MinecraftLinkHeartbeat heartbeat = new MinecraftLinkHeartbeat(instances, link);
        heartbeat.pulseSafely();
        heartbeat.pulseSafely();

        // 失败不抛出（面板主流程不受影响），每轮都尝试回推
        link.fail = false;
        heartbeat.pulseSafely();
        assertEquals(List.of("inst-bound"), link.written);
    }

    @Test
    void pulseToleratesRepositoryFailure() {
        McpanelInstanceRepository broken = new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper()) {
            @Override
            public List<McpanelInstance> findAll() {
                throw new IllegalStateException("存储不可用");
            }
        };
        RecordingLink link = new RecordingLink();

        new MinecraftLinkHeartbeat(broken, link).pulseSafely();

        assertTrue(link.written.isEmpty());
    }

    @Test
    void startAndCloseAreIdempotent() {
        RecordingLink link = new RecordingLink();
        MinecraftLinkHeartbeat heartbeat = new MinecraftLinkHeartbeat(instances, link);
        heartbeat.start();
        heartbeat.start();
        heartbeat.close();
        heartbeat.close();
        // 关闭后不再有周期任务（幂等调用不抛出即视为通过）
        assertEquals(List.of(), link.written);
    }
}
