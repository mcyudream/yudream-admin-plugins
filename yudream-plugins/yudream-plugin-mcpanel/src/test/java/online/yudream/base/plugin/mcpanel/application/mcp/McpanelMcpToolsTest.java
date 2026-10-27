package online.yudream.base.plugin.mcpanel.application.mcp;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService;
import online.yudream.base.plugin.mcpanel.application.service.ServerConfigService;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.PortAllocationRepository;
import online.yudream.base.plugin.mcpanel.domain.service.PortAllocator;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentMcpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentPortAllocationRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.spi.system.ai.PluginAiExecutionContext;
import online.yudream.base.plugin.spi.system.ai.PluginAiTool;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolCall;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolDescriptor;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolResult;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolRisk;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** mcpanel MCP/AI 工具集：descriptor 形状、参数映射、文件编码与上限、配置保存链路。 */
class McpanelMcpToolsTest {

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final McpanelNodeRepository nodes = new DocumentNodeRepository(documents, McpanelJson.mapper());
    private final McpanelInstanceRepository instances =
            new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
    private final PortAllocationRepository ports =
            new DocumentPortAllocationRepository(documents, McpanelJson.mapper());
    private final Map<String, Map<String, Object>> nodeCalls = new ConcurrentHashMap<>();

    private McpanelInstanceAppService svc;
    private List<PluginAiTool> toolList;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        svc = service();
        toolList = McpanelMcpTools.create(svc, serverConfig(svc));
        nodes.save(McpanelNode.create("node-1", "节点一", "wss://127.0.0.1:9701", "pinned",
                "a".repeat(64), true, "test", true, 1L).withReported("0.2.0", "h", "a".repeat(64), 1L));
        instances.save(instA());
    }

    private static McpanelInstance instA() {
        return McpanelInstance.create("inst-a", "node-1", "生存服", "paper", "1.21.4", "",
                "eclipse-temurin:21-jre", List.of("java", "-jar", "server.jar"), Map.of(),
                1024, 1000, 2048, List.of(), Map.of(), null, "", 1L);
    }

    private McpanelInstanceAppService service() {
        BiFunction<String, Map<String, Object>, Map<String, Object>> behavior = (method, payload) -> {
            nodeCalls.put(method, payload);
            if ("instance.update".equals(method)) {
                return Map.of("state", "created");
            }
            if ("file.read".equals(method)) {
                return Map.of("content", Base64.getEncoder().encodeToString("gamemode=survival\n".getBytes()));
            }
            return Map.of();
        };
        return new McpanelInstanceAppService(instances, nodes, ports,
                (nodeId, method, payload) -> CompletableFuture.completedFuture(behavior.apply(method, payload)),
                new PortAllocator(ports),
                (actor, action, targetType, targetId, detail, tenantId) -> {
                },
                new McpanelInstanceAppService.TenancyScope() {
                    @Override
                    public boolean canAccess(String scopeKey, McpanelInstance instance) {
                        return true;
                    }

                    @Override
                    public String tenantOf(Object requestContext, McpanelNode node) {
                        return null;
                    }
                },
                new online.yudream.base.plugin.mcpanel.application.service.MinecraftLinkService(java.util.Optional::empty),
                null);
    }

    private static ServerConfigService serverConfig(McpanelInstanceAppService instances) {
        return new ServerConfigService(new ServerConfigService.InstanceFiles() {
            @Override
            public Map<String, Object> read(String scopeKey, String instanceId, Map<String, Object> args) {
                return instances.files(scopeKey, instanceId, "read", args);
            }

            @Override
            public Map<String, Object> write(String scopeKey, String instanceId, Map<String, Object> args) {
                return instances.files(scopeKey, instanceId, "write", args);
            }
        });
    }

    private PluginAiTool tool(String name) {
        return toolList.stream().filter(candidate -> candidate.descriptor().name().equals(name))
                .findFirst().orElseThrow();
    }

    private static final PluginAiExecutionContext CTX = new PluginAiExecutionContext(
            5L, "5", null, null, null, "MCP", null, List.of("*"));

    private PluginAiToolResult run(String name, Map<String, Object> args) {
        return tool(name).execute(CTX, new PluginAiToolCall(name, args));
    }

    @Test
    void descriptorShapeAndRiskClassification() {
        List<PluginAiTool> all = toolList;
        assertEquals(20, all.size());
        Set<String> names = all.stream().map(candidate -> candidate.descriptor().name())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(20, names.size());
        for (PluginAiTool candidate : all) {
            PluginAiToolDescriptor descriptor = candidate.descriptor();
            assertTrue(descriptor.name().startsWith("mcpanel_"), descriptor.name());
            assertTrue(descriptor.allowedTriggers().contains("MCP"), descriptor.name());
            assertTrue(descriptor.allowedTriggers().contains("MENTION"), descriptor.name());
            assertTrue("plugin:mcpanel:view".equals(descriptor.permissionCode())
                    || "plugin:mcpanel:manage".equals(descriptor.permissionCode()), descriptor.name());
            assertEquals(false, descriptor.requiresConfirmation());
            assertNotNull(descriptor.inputSchema().get("type"), descriptor.name());
        }
        Set<String> writeTools = all.stream()
                .filter(candidate -> candidate.descriptor().risk() == PluginAiToolRisk.WRITE)
                .map(candidate -> candidate.descriptor().name())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("mcpanel_instance_start", "mcpanel_instance_stop", "mcpanel_instance_restart",
                "mcpanel_instance_kill", "mcpanel_instance_command", "mcpanel_file_write", "mcpanel_file_delete",
                "mcpanel_file_move", "mcpanel_file_mkdir", "mcpanel_backup_create", "mcpanel_backup_restore",
                "mcpanel_config_set", "mcpanel_spec_update"), writeTools);
    }

    @Test
    void instanceListTrimsRecords() {
        PluginAiToolResult result = run("mcpanel_instance_list", Map.of());
        assertEquals("ok", result.action());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = result.payload();
        assertEquals(1L, ((Number) payload.get("total")).longValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) payload.get("records");
        assertEquals(1, records.size());
        assertEquals("生存服", records.get(0).get("name"));
        // 裁剪后的行不含环境变量等大字段
        assertTrue(!records.get(0).containsKey("env"));
        assertTrue(!records.get(0).containsKey("config"));
    }

    @Test
    void fileWriteEncodesBase64AndEnforcesCap() {
        PluginAiToolResult result = run("mcpanel_file_write", Map.of(
                "instanceId", "inst-a", "path", "server.properties", "content", "motd=hello"));
        assertEquals("ok", result.action());
        Map<String, Object> payload = nodeCalls.get("file.write");
        assertNotNull(payload);
        assertEquals("base64", payload.get("encoding"));
        assertEquals("motd=hello",
                new String(Base64.getDecoder().decode(String.valueOf(payload.get("content")))));

        String big = "x".repeat(8 * 1024 * 1024 + 1);
        assertThrows(IllegalArgumentException.class, () -> run("mcpanel_file_write", Map.of(
                "instanceId", "inst-a", "path", "big.bin", "content", big, "encoding", "text")));
    }

    @Test
    void fileReadDecodesContent() {
        PluginAiToolResult result = run("mcpanel_file_read", Map.of("instanceId", "inst-a", "path", "server.properties"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = result.payload();
        assertEquals("gamemode=survival\n", payload.get("content"));
    }

    @Test
    void configSetRequiresInputAndSavesProperties() {
        assertThrows(IllegalArgumentException.class,
                () -> run("mcpanel_config_set", Map.of("instanceId", "inst-a")));

        PluginAiToolResult result = run("mcpanel_config_set", Map.of(
                "instanceId", "inst-a", "properties", Map.of("gamemode", "creative", "max-players", "30")));
        assertEquals("ok", result.action());
        Map<String, Object> payload = nodeCalls.get("file.write");
        assertNotNull(payload);
        String content = new String(Base64.getDecoder().decode(String.valueOf(payload.get("content"))));
        assertTrue(content.contains("gamemode=creative"));
        assertTrue(content.contains("max-players=30"));
    }

    @Test
    void specUpdateMergesOverridesAndCallsNode() {
        PluginAiToolResult result = run("mcpanel_spec_update", Map.of(
                "instanceId", "inst-a", "memoryMb", 4096,
                "command", "java -Xms1G -Xmx4G -jar server.jar nogui"));
        assertEquals("ok", result.action());
        Map<String, Object> payload = nodeCalls.get("instance.update");
        assertNotNull(payload);
        assertEquals(4096L, ((Number) payload.get("memoryMb")).longValue());
        assertEquals(List.of("java", "-Xms1G", "-Xmx4G", "-jar", "server.jar", "nogui"), payload.get("command"));
        assertEquals(1000L, ((Number) payload.get("cpuMillis")).longValue());
    }

    @Test
    void startActionDelegatesWithActorAndScope() {
        PluginAiToolResult result = run("mcpanel_instance_start", Map.of("instanceId", "inst-a"));
        assertEquals("ok", result.action());
        Map<String, Object> payload = nodeCalls.get("instance.start");
        assertNotNull(payload);
        assertEquals("inst-a", payload.get("instanceId"));
    }
}
