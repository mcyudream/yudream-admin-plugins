package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentMcpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 代理纳管：地址匹配、识别、绑定校验与父子关系装饰。 */
class ProxyGroupServiceTest {

    private static final String VELOCITY_CONFIG = String.join("\n",
            "config-version = \"2.7\"",
            "bind = \"0.0.0.0:25577\"",
            "player-info-forwarding = \"modern\"",
            "[servers]",
            "lobby = \"localhost:25566\"",
            "ext = \"203.0.113.10:25565\"",
            "try = [",
            "  \"lobby\",",
            "  \"ext\"",
            "]",
            "");

    private static McpanelInstance instance(String id, String nodeId, String name, int port) {
        return McpanelInstance.create(id, nodeId, name, "paper", "1.21", "", "img",
                List.of("java", "-jar", "server.jar"), Map.of(), 512, 500, 1024,
                port <= 0 ? List.of() : List.of(new McpanelInstance.PortMapping(port, port, "tcp")),
                Map.of(), null, "", System.currentTimeMillis());
    }

    private static ProxyGroupService service(InMemoryDocumentStore documents,
                                             McpanelInstanceRepository instances,
                                             McpanelNodeRepository nodes,
                                             ProxyGroupService.FilesGateway gateway) {
        return new ProxyGroupService(documents, instances, nodes, gateway,
                (actor, action, targetType, targetId, detail, tenantId) -> {
                });
    }

    /** 伪造节点文件通道：根目录固定为传入文件名，read 返回预置内容。 */
    private static final class FakeGateway implements ProxyGroupService.FilesGateway {
        final List<String> rootFiles;
        final Map<String, String> contents = new HashMap<>();

        FakeGateway(List<String> rootFiles) {
            this.rootFiles = rootFiles;
        }

        @Override
        public Map<String, Object> invoke(String scopeKey, String instanceId, String method, Map<String, Object> args) {
            Map<String, Object> result = new LinkedHashMap<>();
            if ("list".equals(method)) {
                List<Map<String, Object>> entries = new ArrayList<>();
                for (String name : rootFiles) {
                    entries.add(Map.of("name", name, "isDir", false, "path", name));
                }
                result.put("entries", entries);
                result.put("total", entries.size());
            }
            else if ("read".equals(method)) {
                String path = String.valueOf(args.get("path"));
                String content = contents.get(path);
                result.put("content", content == null ? ""
                        : Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
            }
            return result;
        }
    }

    @Test
    void matchByAddressLocalhostHitsSameNodePort() {
        List<McpanelInstance> candidates = List.of(
                instance("a", "nodeA", "A", 25566),
                instance("b", "nodeB", "B", 25566));
        Function<String, String> endpointHost = nodeId -> "nodeB".equals(nodeId)
                ? "nodeb.example.com" : "nodea.example.com";
        assertEquals("a", ProxyGroupService.matchByAddress("localhost:25566", "nodeA", candidates, endpointHost));
        assertEquals("a", ProxyGroupService.matchByAddress("127.0.0.1:25566", "nodeA", candidates, endpointHost));
        assertEquals("b", ProxyGroupService.matchByAddress("nodeb.example.com:25566", "nodeA", candidates, endpointHost));
        assertNull(ProxyGroupService.matchByAddress("localhost:25566", "nodeC", candidates, endpointHost),
                "本地地址只匹配代理同节点：nodeC 上没有实例");
    }

    @Test
    void matchByAddressAmbiguousReturnsNull() {
        List<McpanelInstance> candidates = List.of(
                instance("a", "nodeA", "A", 25566),
                instance("b", "nodeA", "B", 25566));
        assertNull(ProxyGroupService.matchByAddress("localhost:25566", "nodeA", candidates, nodeId -> ""));
    }

    @Test
    void matchByAddressRejectsInvalidInput() {
        List<McpanelInstance> candidates = List.of(instance("a", "nodeA", "A", 25566));
        Function<String, String> endpointHost = nodeId -> "nodeA.example.com";
        assertNull(ProxyGroupService.matchByAddress("localhost", "nodeA", candidates, endpointHost));
        assertNull(ProxyGroupService.matchByAddress("localhost:abc", "nodeA", candidates, endpointHost));
        assertNull(ProxyGroupService.matchByAddress("127.0.0.1:99999", "nodeA", candidates, endpointHost));
        assertNull(ProxyGroupService.matchByAddress("127.0.0.1:25565", "nodeA", candidates, endpointHost));
        assertNull(ProxyGroupService.matchByAddress("", "nodeA", candidates, endpointHost));
    }

    @Test
    void hostOfEndpointParsesSchemeHostPortAndPath() {
        assertEquals("node.example.com", ProxyGroupService.hostOfEndpoint("wss://Node.Example.com:7000/control"));
        assertEquals("node.example.com", ProxyGroupService.hostOfEndpoint("node.example.com:7000"));
        assertEquals("node.example.com", ProxyGroupService.hostOfEndpoint("node.example.com"));
        assertEquals("", ProxyGroupService.hostOfEndpoint(null));
    }

    @Test
    void detectVelocityConfigAndMatchInstance() {
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        McpanelInstanceRepository instances = new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
        McpanelNodeRepository nodes = new DocumentNodeRepository(documents, McpanelJson.mapper());
        nodes.save(McpanelNode.create("nodeA", "节点A", "wss://nodea.example.com:7000", "pkix", null, false, "", true,
                System.currentTimeMillis()));
        instances.save(instance("proxy-1", "nodeA", "代理", 25577));
        instances.save(instance("child-1", "nodeA", "大厅服", 25566));
        FakeGateway gateway = new FakeGateway(List.of("velocity.toml", "server.jar", "run.sh"));
        gateway.contents.put("velocity.toml", VELOCITY_CONFIG);
        ProxyGroupService service = service(documents, instances, nodes, gateway);

        Map<String, Object> result = service.detect("user:1", "proxy-1");
        assertEquals(Boolean.TRUE, result.get("detected"));
        assertEquals("velocity", result.get("kind"));
        assertEquals("velocity.toml", result.get("sourcePath"));
        assertEquals("modern", result.get("forwarding"));
        assertEquals("lobby", result.get("defaultServer"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> servers = (List<Map<String, Object>>) result.get("servers");
        assertEquals(2, servers.size());
        Map<String, Object> lobby = servers.get(0);
        assertEquals("lobby", lobby.get("name"));
        assertEquals("child-1", lobby.get("matchedInstanceId"));
        assertEquals("address", lobby.get("matchType"));
        Map<String, Object> ext = servers.get(1);
        assertEquals("none", ext.get("matchType"));
        assertNull(ext.get("matchedInstanceId"));
    }

    @Test
    void detectBackendWithServerPropertiesIsNotProxy() {
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        McpanelInstanceRepository instances = new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
        McpanelNodeRepository nodes = new DocumentNodeRepository(documents, McpanelJson.mapper());
        instances.save(instance("mc-1", "nodeA", "后端", 25566));
        ProxyGroupService service = service(documents, instances, nodes,
                new FakeGateway(List.of("server.properties", "config.yml")));

        Map<String, Object> result = service.detect("user:1", "mc-1");
        assertEquals(Boolean.FALSE, result.get("detected"));
    }

    @Test
    void saveGroupAndDecorateRelations() {
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        McpanelInstanceRepository instances = new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
        McpanelNodeRepository nodes = new DocumentNodeRepository(documents, McpanelJson.mapper());
        nodes.save(McpanelNode.create("nodeA", "节点A", "wss://nodea.example.com:7000", "pkix", null, false, "", true,
                System.currentTimeMillis()));
        instances.save(instance("proxy-1", "nodeA", "代理", 25577));
        instances.save(instance("child-1", "nodeA", "大厅服", 25566));
        ProxyGroupService service = service(documents, instances, nodes, new FakeGateway(List.of("velocity.toml")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "velocity");
        body.put("defaultServer", "lobby");
        Map<String, Object> bound = new LinkedHashMap<>();
        bound.put("name", "lobby");
        bound.put("address", "localhost:25566");
        bound.put("boundInstanceId", "child-1");
        Map<String, Object> external = new LinkedHashMap<>();
        external.put("name", "ext");
        external.put("address", "203.0.113.10:25565");
        body.put("servers", List.of(bound, external));
        Map<String, Object> saved = service.save("user:1", "proxy-1", body);
        assertEquals("velocity", saved.get("kind"));

        Map<String, Object> group = service.group("proxy-1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> servers = (List<Map<String, Object>>) group.get("servers");
        assertEquals(2, servers.size());
        assertEquals("大厅服", servers.get(0).get("boundName"));
        assertEquals(Boolean.TRUE, servers.get(1).get("external"));

        // 装饰：代理得到 proxyChildren，子服得到 proxy。
        Map<String, Object> proxyDto = new LinkedHashMap<>();
        proxyDto.put("id", "proxy-1");
        service.decorateDetail(proxyDto);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) proxyDto.get("proxyChildren");
        assertEquals(1, children.size());
        assertEquals("child-1", children.get(0).get("instanceId"));

        Map<String, Object> childDto = new LinkedHashMap<>();
        childDto.put("id", "child-1");
        service.decorateDetail(childDto);
        @SuppressWarnings("unchecked")
        Map<String, Object> proxyInfo = (Map<String, Object>) childDto.get("proxy");
        assertEquals("proxy-1", proxyInfo.get("proxyInstanceId"));
        assertEquals("代理", proxyInfo.get("proxyName"));
        assertEquals("lobby", proxyInfo.get("serverName"));

        // 解除纳管后装饰消失。
        service.delete("user:1", "proxy-1");
        assertNull(service.group("proxy-1"));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("id", "child-1");
        service.decorateDetail(after);
        assertFalse(after.containsKey("proxy"));
    }

    @Test
    void saveRejectsInvalidBindings() {
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        McpanelInstanceRepository instances = new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
        McpanelNodeRepository nodes = new DocumentNodeRepository(documents, McpanelJson.mapper());
        instances.save(instance("proxy-1", "nodeA", "代理", 25577));
        instances.save(instance("proxy-2", "nodeA", "代理2", 25578));
        instances.save(instance("child-1", "nodeA", "大厅服", 25566));
        instances.save(instance("child-2", "nodeA", "生存服", 25567));
        ProxyGroupService service = service(documents, instances, nodes, new FakeGateway(List.of("velocity.toml")));

        // 自绑
        assertThrows(McpanelBusinessException.class, () -> service.save("u", "proxy-1", Map.of(
                "kind", "velocity",
                "servers", List.of(Map.of("name", "self", "address", "x:1", "boundInstanceId", "proxy-1")))));
        // 重复名称
        assertThrows(McpanelBusinessException.class, () -> service.save("u", "proxy-1", Map.of(
                "kind", "velocity",
                "servers", List.of(Map.of("name", "s", "address", "x:1"),
                        Map.of("name", "s", "address", "x:2")))));
        // 绑定不存在的实例
        assertThrows(McpanelBusinessException.class, () -> service.save("u", "proxy-1", Map.of(
                "kind", "velocity",
                "servers", List.of(Map.of("name", "ghost", "address", "x:1", "boundInstanceId", "nope")))));

        // 合法保存后：同实例被第二个代理再绑定 → 冲突；代理串联 → 拒绝。
        service.save("u", "proxy-1", Map.of("kind", "velocity",
                "servers", List.of(Map.of("name", "lobby", "address", "localhost:25566",
                        "boundInstanceId", "child-1"))));
        assertThrows(McpanelBusinessException.class, () -> service.save("u", "proxy-2", Map.of(
                "kind", "velocity",
                "servers", List.of(Map.of("name", "lobby", "address", "localhost:25566",
                        "boundInstanceId", "child-1")))));
        assertThrows(McpanelBusinessException.class, () -> service.save("u", "proxy-2", Map.of(
                "kind", "velocity",
                "servers", List.of(Map.of("name", "chain", "address", "localhost:25578",
                        "boundInstanceId", "proxy-1")))));
        // 默认子服必须在列表内
        assertThrows(McpanelBusinessException.class, () -> service.save("u", "proxy-2", Map.of(
                "kind", "velocity", "defaultServer", "not-exist",
                "servers", List.of(Map.of("name", "lobby", "address", "x:1")))));
        // 非法 kind
        assertThrows(McpanelBusinessException.class, () -> service.save("u", "proxy-2", Map.of(
                "kind", "nginx",
                "servers", List.of(Map.of("name", "lobby", "address", "x:1")))));
    }

    @Test
    void detectCarriesConfirmedBindingsOnReRun() {
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        McpanelInstanceRepository instances = new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
        McpanelNodeRepository nodes = new DocumentNodeRepository(documents, McpanelJson.mapper());
        nodes.save(McpanelNode.create("nodeA", "节点A", "wss://nodea.example.com:7000", "pkix", null, false, "", true,
                System.currentTimeMillis()));
        instances.save(instance("proxy-1", "nodeA", "代理", 25577));
        instances.save(instance("child-1", "nodeA", "大厅服", 25566));
        instances.save(instance("child-2", "nodeA", "外部托管的子服", 25567));
        FakeGateway gateway = new FakeGateway(List.of("velocity.toml"));
        gateway.contents.put("velocity.toml", VELOCITY_CONFIG);
        ProxyGroupService service = service(documents, instances, nodes, gateway);

        // ext 地址无法自动匹配；管理员手动绑定 child-2 后重新识别应沿用（carried）。
        service.save("u", "proxy-1", Map.of(
                "kind", "velocity",
                "servers", List.of(
                        Map.of("name", "lobby", "address", "localhost:25566", "boundInstanceId", "child-1"),
                        Map.of("name", "ext", "address", "203.0.113.10:25565", "boundInstanceId", "child-2"))));
        Map<String, Object> again = service.detect("u", "proxy-1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> servers = (List<Map<String, Object>>) again.get("servers");
        Map<String, Object> ext = servers.stream()
                .filter(row -> "ext".equals(row.get("name"))).findFirst().orElseThrow();
        assertEquals("child-2", ext.get("matchedInstanceId"));
        assertEquals("carried", ext.get("matchType"));
        Map<String, Object> lobby = servers.stream()
                .filter(row -> "lobby".equals(row.get("name"))).findFirst().orElseThrow();
        // 管理员已确认的绑定（含与自动匹配一致的）在重新识别时一律沿用。
        assertEquals("carried", lobby.get("matchType"));
        assertEquals("child-1", lobby.get("matchedInstanceId"));
    }
}
