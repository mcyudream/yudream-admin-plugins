package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROXY protocol 与接入方式的自动同步：入口模式开、其它模式关；
 * 不支持的类型/缺失文件只记为 skipped，不影响其它实例与切换本身。
 */
class ProxyProtocolSyncServiceTest {

    private static final String PAPER_FILE = """
            proxies:
              proxy-protocol: false
            """;

    private static final class FakeFiles implements ServerConfigService.InstanceFiles {
        final Map<String, String> store = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();

        FakeFiles(Map<String, String> initial) {
            store.putAll(initial);
        }

        @Override
        public Map<String, Object> read(String scopeKey, String instanceId, Map<String, Object> args) {
            String content = store.get(String.valueOf(args.get("path")));
            if (content == null) {
                throw new IllegalStateException("file.notFound");
            }
            return Map.of("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public Map<String, Object> write(String scopeKey, String instanceId, Map<String, Object> args) {
            String content = new String(Base64.getDecoder().decode(String.valueOf(args.get("content"))),
                    StandardCharsets.UTF_8);
            store.put(String.valueOf(args.get("path")), content);
            writes.add(instanceId + ":" + content.contains("proxy-protocol: true"));
            return Map.of("ok", true);
        }
    }

    private static McpanelInstance instance(String id, String nodeId, String kind, String tenantId) {
        return McpanelInstance.create(id, nodeId, "服务器" + id, kind, "1.21", "", "img",
                List.of("java"), Map.of(), 1024, 1000, 2048, List.of(), Map.of(), tenantId, "",
                System.currentTimeMillis());
    }

    private static ProxyProtocolSyncService service(FakeFiles files, List<McpanelInstance> instances,
                                                   List<String> audits) {
        return new ProxyProtocolSyncService(
                nodeId -> instances.stream().filter(item -> nodeId.equals(item.nodeId())).toList(),
                new ServerConfigService(files),
                (actor, action, targetType, targetId, detail, tenant) -> audits.add(action + ":" + detail));
    }

    @Test
    void entryModeTurnsProxyProtocolOnForSupportedServers() {
        FakeFiles files = new FakeFiles(Map.of("config/paper-global.yml", PAPER_FILE));
        List<McpanelInstance> instances = List.of(instance("i1", "node-1", "paper", ""));
        ProxyProtocolSyncService service = service(files, instances, new ArrayList<>());

        Map<String, Object> result = service.syncForNode("system", "node-1", "entry");

        assertEquals(1, result.get("checked"));
        assertEquals(1, result.get("changed"));
        assertTrue(files.store.get("config/paper-global.yml").contains("proxy-protocol: true"));
        assertEquals(true, result.get("enabled"));
    }

    @Test
    void switchingBackToDirectTurnsItOff() {
        FakeFiles files = new FakeFiles(Map.of("config/paper-global.yml",
                PAPER_FILE.replace("false", "true")));
        List<McpanelInstance> instances = List.of(instance("i1", "node-1", "paper", ""));
        ProxyProtocolSyncService service = service(files, instances, new ArrayList<>());

        Map<String, Object> result = service.syncForNode("system", "node-1", "direct");

        assertEquals(1, result.get("changed"));
        assertTrue(files.store.get("config/paper-global.yml").contains("proxy-protocol: false"),
                "非入口模式必须自动关闭，否则直连玩家会被 PROXY 头校验拒绝");
        assertEquals(false, result.get("enabled"));
    }

    @Test
    void unsupportedKindsAndMissingFilesAreSkippedNotFailed() {
        FakeFiles files = new FakeFiles(Map.of("config/paper-global.yml", PAPER_FILE));
        List<McpanelInstance> instances = List.of(
                instance("i1", "node-1", "paper", ""),
                instance("i2", "node-1", "fabric", ""),   // 模组端：不需要
                instance("i3", "node-1", "velocity", "")); // velocity：文件不存在
        List<String> audits = new ArrayList<>();
        ProxyProtocolSyncService service = service(files, instances, audits);

        Map<String, Object> result = service.syncForNode("system", "node-1", "entry");

        assertEquals(3, result.get("checked"));
        assertEquals(1, result.get("changed"));
        List<?> skipped = (List<?>) result.get("skipped");
        assertEquals(2, skipped.size(), "不支持的与缺文件的都记为 skipped");
        assertEquals(List.of(), result.get("errors"));
        assertTrue(audits.stream().anyMatch(item -> item.contains("接入方式切换为 单端口入口")));
    }

    @Test
    void otherNodesAreNotTouched() {
        FakeFiles files = new FakeFiles(Map.of("config/paper-global.yml", PAPER_FILE));
        List<McpanelInstance> instances = List.of(
                instance("i1", "node-1", "paper", ""),
                instance("i2", "node-2", "paper", ""));
        ProxyProtocolSyncService service = service(files, instances, new ArrayList<>());

        Map<String, Object> result = service.syncForNode("system", "node-1", "entry");

        assertEquals(1, result.get("checked"));
        assertEquals(1, files.writes.size());
        assertTrue(files.writes.get(0).startsWith("i1:"), "只动该节点上的实例");
    }
}
