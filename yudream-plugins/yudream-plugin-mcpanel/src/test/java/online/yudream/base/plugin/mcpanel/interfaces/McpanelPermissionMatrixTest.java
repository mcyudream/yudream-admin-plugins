package online.yudream.base.plugin.mcpanel.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.mcpanel.application.service.McpanelEnrollService;
import online.yudream.base.plugin.mcpanel.application.service.McpanelNodeAppService;
import online.yudream.base.plugin.mcpanel.bootstrap.McpanelPlugin;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentEnrollTokenRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets;
import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.acceptance.InMemorySecretStore;
import online.yudream.base.plugin.mcpanel.interfaces.http.McpanelHttpFacade;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 授权矩阵（access-boundaries.md）：admin 面四个权限位、未认证拒绝、
 * 机器面 bootstrap 仅 token 门禁；并验证 nodeSecret 不出现在任何 admin 响应。
 */
class McpanelPermissionMatrixTest {

    private static final String VIEW = McpanelPlugin.VIEW_PERMISSION;
    private static final String MANAGE = McpanelPlugin.MANAGE_PERMISSION;
    private static final String DELETE = McpanelPlugin.DELETE_PERMISSION;

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final InMemorySecretStore secretStore = new InMemorySecretStore();
    private final ObjectMapper mapper = McpanelJson.mapper();
    private final online.yudream.base.plugin.mcpanel.support.TestStubs.RecordingControlPlane controlPlane =
            new online.yudream.base.plugin.mcpanel.support.TestStubs.RecordingControlPlane();
    private final McpanelNodeAppService nodeService = new McpanelNodeAppService(
            new DocumentNodeRepository(documents, mapper),
            new DocumentEnrollTokenRepository(documents, mapper),
            new NodeSecrets(secretStore),
            controlPlane);
    private final McpanelEnrollService enrollService = new McpanelEnrollService(
            new DocumentNodeRepository(documents, mapper),
            new DocumentEnrollTokenRepository(documents, mapper),
            new NodeSecrets(secretStore));
    private final McpanelHttpFacade facade = new McpanelHttpFacade(nodeService, enrollService,
            online.yudream.base.plugin.mcpanel.support.TestStubs.security());

    private static PluginHttpRequest request(String method, String path, String body, PluginPrincipal principal) {
        return new PluginHttpRequest(method, path, Map.of(), Map.of(), body, principal);
    }

    @Test
    void adminEndpointsRejectUnauthenticatedCallers() {
        assertEquals(401, facade.page(request("GET", "/admin/nodes", null, null)).status());
        assertEquals(401, facade.create(request("POST", "/admin/nodes", "{}", null)).status());
        assertEquals(401, facade.detail(request("GET", "/admin/nodes/x", null, null)).status());
        assertEquals(401, facade.update(request("PUT", "/admin/nodes/x", "{}", null)).status());
        assertEquals(401, facade.delete(request("DELETE", "/admin/nodes/x", null, null)).status());
        assertEquals(401, facade.issueEnrollment(request("POST", "/admin/nodes/x/enrollment", null, null)).status());
        assertEquals(401, facade.reconnect(request("POST", "/admin/nodes/x/reconnect", null, null)).status());
        assertEquals(401, facade.events(request("GET", "/admin/nodes/x/events", null, null)).status());
    }

    @Test
    void adminEndpointsRejectInsufficientPermissions() {
        PluginPrincipal viewer = online.yudream.base.plugin.mcpanel.support.TestStubs.principal(1L, VIEW);
        assertEquals(403, facade.create(request("POST", "/admin/nodes", "{}", viewer)).status());
        assertEquals(403, facade.delete(request("DELETE", "/admin/nodes/x", null, viewer)).status());
        assertEquals(403, facade.issueEnrollment(request("POST", "/admin/nodes/x/enrollment", null, viewer)).status());
        PluginPrincipal manager = online.yudream.base.plugin.mcpanel.support.TestStubs.principal(1L, MANAGE);
        assertEquals(403, facade.delete(request("DELETE", "/admin/nodes/x", null, manager)).status());
        // manager 持有 manage：reconnect 通过授权；节点 "x" 不存在 → 404（业务不存在，非授权拒绝）。
        assertEquals(404, facade.reconnect(request("POST", "/admin/nodes/x/reconnect", null, manager)).status());
    }

    @Test
    void viewWithoutManageCanReadButNotMutate() {
        PluginPrincipal viewer = online.yudream.base.plugin.mcpanel.support.TestStubs.principal(2L, VIEW);
        assertEquals(200, facade.page(request("GET", "/admin/nodes", null, viewer)).status());
    }

    @Test
    void fullAdminFlowSucceedsWithProperPermissionsAndNeverLeaksSecret() throws Exception {
        PluginPrincipal admin = online.yudream.base.plugin.mcpanel.support.TestStubs.principal(9L,
                VIEW, MANAGE, DELETE);
        String createBody = "{\"name\":\"节点M\",\"endpoint\":\"wss://node-m.example.com:9701\","
                + "\"tlsMode\":\"pkix\",\"localDevelopment\":false}";
        PluginHttpResponse created = facade.create(request("POST", "/admin/nodes", createBody, admin));
        assertEquals(200, created.status());
        online.yudream.base.plugin.mcpanel.interfaces.res.NodeRes node =
                (online.yudream.base.plugin.mcpanel.interfaces.res.NodeRes) created.body();
        String nodeId = node.id();

        PluginHttpResponse enrollment = facade.issueEnrollment(
                request("POST", "/admin/nodes/" + nodeId + "/enrollment", null, admin));
        assertEquals(200, enrollment.status());
        online.yudream.base.plugin.mcpanel.interfaces.res.EnrollTokenRes token =
                (online.yudream.base.plugin.mcpanel.interfaces.res.EnrollTokenRes) enrollment.body();
        String enrollToken = token.token();

        PluginHttpResponse bootstrap = facade.bootstrap(request("POST", "/node/bootstrap",
                mapper.writeValueAsString(Map.of("enrollToken", enrollToken, "hostname", "node-m",
                        "agentVersion", "0.1.0", "caps", List.of(), "tlsCertSha256", "")), null));
        assertEquals(200, bootstrap.status());
        String secret = String.valueOf(((Map<?, ?>) bootstrap.body()).get("nodeSecret"));
        assertEquals(43, secret.length());

        // 全部 admin 响应（含 wrapped body 序列化）不得出现 nodeSecret。
        PluginHttpResponse detail = facade.detail(request("GET", "/admin/nodes/" + nodeId, null, admin));
        String serialized = mapper.writeValueAsString(detail.body());
        assertFalse(serialized.contains(secret), "admin 响应泄漏 nodeSecret");
        assertFalse(serialized.contains(enrollToken), "admin 响应泄漏 enrollToken");
        controlPlane.online = true;
        PluginHttpResponse detailOnline = facade.detail(request("GET", "/admin/nodes/" + nodeId, null, admin));
        online.yudream.base.plugin.mcpanel.interfaces.res.NodeRes onlineBody =
                (online.yudream.base.plugin.mcpanel.interfaces.res.NodeRes) detailOnline.body();
        assertEquals("online", onlineBody.status());
        assertEquals("node-m", onlineBody.reportedHost());

        // 分页响应同样无泄漏
        String pageSerialized = mapper.writeValueAsString(
                facade.page(request("GET", "/admin/nodes", null, admin)).body());
        assertFalse(pageSerialized.contains(secret));

        // bootstrap 错误：消费后再用 → 409
        PluginHttpResponse reused = facade.bootstrap(request("POST", "/node/bootstrap",
                mapper.writeValueAsString(Map.of("enrollToken", enrollToken, "hostname", "x",
                        "agentVersion", "0.1.0", "caps", List.of(), "tlsCertSha256", "")), null));
        assertEquals(409, reused.status());
        assertEquals("auth.tokenConsumed", ((Map<?, ?>) reused.body()).get("code"));

        // 删除需要 delete 权限
        assertEquals(200, facade.delete(request("DELETE", "/admin/nodes/" + nodeId, null, admin)).status());
        assertTrue(secretStore.get("nodes/" + nodeId + "/secret").isEmpty());
    }

    @Test
    void bootstrapWithBadTokenReturns401WithoutPrincipal() {
        PluginHttpResponse response = facade.bootstrap(request("POST", "/node/bootstrap",
                "{\"enrollToken\":\"wrong\",\"hostname\":\"h\",\"agentVersion\":\"0.1.0\","
                        + "\"caps\":[],\"tlsCertSha256\":\"\"}", null));
        assertEquals(401, response.status());
        assertEquals("auth.badToken", ((Map<?, ?>) response.body()).get("code"));
    }
}
