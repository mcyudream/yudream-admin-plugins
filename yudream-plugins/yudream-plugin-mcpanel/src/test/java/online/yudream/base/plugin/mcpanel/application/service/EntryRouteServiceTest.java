package online.yudream.base.plugin.mcpanel.application.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import online.yudream.base.plugin.mcpanel.application.dto.PanelSettings;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单端口入口（mc-router）适配：路由下发/删除、对账补推与清理，以及
 * 「只在入口模式 + 入口配置齐备时才外呼」的启用门（其余情况零请求）。
 */
class EntryRouteServiceTest {

    /** 假 mc-router：内存路由表 + 调用记录（JDK 内置 HttpServer，无额外依赖）。 */
    private static final class FakeRouter implements AutoCloseable {
        final Map<String, String> routes = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        private final HttpServer server;

        FakeRouter() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/routes", this::handle);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            calls.add(method + " " + path);
            if ("GET".equals(method)) {
                Map<String, Object> body = new LinkedHashMap<>();
                routes.forEach((name, backend) -> body.put(name, Map.of("backend", backend)));
                respond(exchange, 200, McpanelJson.mapper().writeValueAsString(body));
                return;
            }
            if ("POST".equals(method)) {
                String payload = readBody(exchange);
                var node = McpanelJson.mapper().readTree(payload);
                routes.put(node.path("serverAddress").asText(), node.path("backend").asText());
                respond(exchange, 201, "{}");
                return;
            }
            if ("DELETE".equals(method)) {
                routes.remove(path.substring("/routes/".length()));
                respond(exchange, 204, "");
                return;
            }
            respond(exchange, 405, "{}");
        }

        private static String readBody(HttpExchange exchange) throws IOException {
            try (InputStream stream = exchange.getRequestBody()) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private FakeRouter router;
    private final List<McpanelInstance> instances = new ArrayList<>();
    private final online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore documents =
            new online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore();
    private PanelSettings.Entry entry = new PanelSettings.Entry("", "", 25565);

    private EntryRouteService service() {
        EntryRouteService.InstanceSource source = new EntryRouteService.InstanceSource() {
            @Override
            public List<McpanelInstance> all() {
                return instances;
            }

            @Override
            public String directHostOf(McpanelNode node) {
                return node.sftpHost() == null || node.sftpHost().isBlank() ? "203.0.113.9" : node.sftpHost();
            }
        };
        McpanelNodeRepository repository = new DocumentNodeRepository(documents, McpanelJson.mapper());
        return new EntryRouteService(source, repository, () -> entry, () -> "mc.example.com");
    }

    private static McpanelInstance instance(String id, String slug, int port, boolean enabled) {
        McpanelInstance base = McpanelInstance.create(id, "node-1", "服务器" + id, "paper", "1.21", "", "img",
                List.of("java"), Map.of(), 1024, 1000, 2048,
                List.of(new McpanelInstance.PortMapping(port, 25565, "tcp")), Map.of(), null, "",
                System.currentTimeMillis());
        return enabled ? base.withDomain(slug, true, System.currentTimeMillis()) : base;
    }

    private void node(String id, String accessMode, String accessHost) {
        new DocumentNodeRepository(documents, McpanelJson.mapper()).save(
                McpanelNode.create(id, "节点" + id, "wss://10.0.0.8:7000", "pkix", null, false, "", true,
                        null, null, "", accessMode, accessHost, System.currentTimeMillis()));
    }

    @AfterEach
    void tearDown() {
        if (router != null) {
            router.close();
        }
    }

    @Test
    void publishPushesRouteForEntryModeInstance() throws Exception {
        router = new FakeRouter();
        entry = new PanelSettings.Entry(router.base(), "entry.example.com", 25565);
        node("node-1", "entry", "127.0.0.1");
        instances.add(instance("i1", "survival", 25565, true));

        Map<String, Object> result = service().publish(instances.get(0));

        assertEquals(true, result.get("published"));
        assertEquals("survival.mc.example.com", result.get("serverAddress"));
        assertEquals("127.0.0.1:25565", result.get("backend"));
        assertEquals("127.0.0.1:25565", router.routes.get("survival.mc.example.com"));
    }

    @Test
    void publishSkipsNonEntryNodesWithoutCallingRouter() throws Exception {
        router = new FakeRouter();
        entry = new PanelSettings.Entry(router.base(), "entry.example.com", 25565);
        node("node-1", "direct", "");
        instances.add(instance("i1", "survival", 25565, true));

        Map<String, Object> result = service().publish(instances.get(0));

        assertEquals(false, result.get("published"));
        assertTrue(router.calls.isEmpty(), "非入口节点：不得外呼 router");
    }

    @Test
    void reconcilePushesMissingAndRemovesStaleRoutes() throws Exception {
        router = new FakeRouter();
        entry = new PanelSettings.Entry(router.base(), "entry.example.com", 25565);
        node("node-1", "entry", "127.0.0.1");
        instances.add(instance("i1", "survival", 25565, true));
        instances.add(instance("i2", "creative", 25566, true));
        // router 已知状态：一条错误后端 + 一条面板已无实例的陈旧路由 + 一条别人的域名
        router.routes.put("survival.mc.example.com", "10.0.0.9:25565");
        router.routes.put("gone.mc.example.com", "10.0.0.9:25570");
        router.routes.put("other.example.com", "10.0.0.9:25571");

        Map<String, Object> result = service().reconcile();

        assertEquals(2, result.get("expected"));
        assertEquals(2, result.get("pushed"), "错误后端与缺失路由都要补推");
        assertEquals(1, result.get("removed"), "只清理本面板域后缀下的陈旧路由");
        assertEquals("127.0.0.1:25565", router.routes.get("survival.mc.example.com"));
        assertEquals("127.0.0.1:25566", router.routes.get("creative.mc.example.com"));
        assertFalse(router.routes.containsKey("gone.mc.example.com"));
        assertEquals("10.0.0.9:25571", router.routes.get("other.example.com"), "别人的域名不得动");
    }

    @Test
    void reconcileWithoutEntryInstancesNeverCallsRouter() throws Exception {
        router = new FakeRouter();
        entry = new PanelSettings.Entry(router.base(), "entry.example.com", 25565);
        node("node-1", "manual", "198.51.100.7");
        instances.add(instance("i1", "survival", 25565, true));

        Map<String, Object> result = service().reconcile();

        assertEquals(true, result.get("skipped"));
        assertEquals(0, result.get("pushed"));
        assertTrue(router.calls.isEmpty(), "没有入口模式实例：零外呼（不打扰 router）");
    }

    @Test
    void unconfiguredEntryIsDisabledWithoutCalls() throws Exception {
        router = new FakeRouter();
        entry = new PanelSettings.Entry("", "", 25565);
        node("node-1", "entry", "127.0.0.1");
        instances.add(instance("i1", "survival", 25565, true));

        EntryRouteService service = service();
        Map<String, Object> status = service.status();

        assertEquals(false, status.get("configured"));
        assertTrue(String.valueOf(status.get("reason")).contains("未配置入口 API 地址"));
        assertEquals(false, service.publish(instances.get(0)).get("published"));
        assertTrue(router.calls.isEmpty());
    }

    @Test
    void revokeDeletesRouteAndStatusReportsDrift() throws Exception {
        router = new FakeRouter();
        entry = new PanelSettings.Entry(router.base(), "entry.example.com", 25565);
        node("node-1", "entry", "127.0.0.1");
        instances.add(instance("i1", "survival", 25565, true));
        router.routes.put("survival.mc.example.com", "127.0.0.1:25565");

        EntryRouteService service = service();
        Map<String, Object> status = service.status();
        assertEquals(true, status.get("reachable"));
        assertEquals(1, status.get("routerRoutes"));
        assertEquals(List.of(), status.get("drift"));

        service.revoke(instances.get(0));

        assertFalse(router.routes.containsKey("survival.mc.example.com"));
        assertTrue(router.calls.stream().anyMatch(call -> call.startsWith("DELETE /routes/survival.mc.example.com")));
    }

    @Test
    void unattachedConfigMeansZeroExternalCalls() {
        EntryRouteService service = service();
        Map<String, Object> status = service.status();
        assertNotNull(status.get("reason"));
        assertFalse((Boolean) status.get("configured"));
    }
}
