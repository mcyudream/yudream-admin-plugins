package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端配置视图：节点上还没有实例数据（instance.notFound）时按空配置视图处理，
 * 页面展示空态引导而不是把 409 报错；节点不可达等其他错误仍如实上抛。
 */
class ServerConfigServiceTest {

    /** 可编程 fake：按路径返回内容或抛指定异常。 */
    private static final class StubFiles implements ServerConfigService.InstanceFiles {
        final Map<String, String> store = new LinkedHashMap<>();
        RuntimeException failure;

        @Override
        public Map<String, Object> read(String scopeKey, String instanceId, Map<String, Object> args) {
            if (failure != null) {
                throw failure;
            }
            String content = store.get(String.valueOf(args.get("path")));
            if (content == null) {
                throw new IllegalStateException("file.notFound");
            }
            return Map.of("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public Map<String, Object> write(String scopeKey, String instanceId, Map<String, Object> args) {
            return Map.of("ok", true);
        }
    }

    @Test
    void viewParsesExistingProperties() {
        StubFiles files = new StubFiles();
        files.store.put("server.properties", "motd=hello\nmax-players=20\n");
        ServerConfigService service = new ServerConfigService(files);

        Map<String, Object> view = service.view("user:1", "inst-1", "server.properties");

        assertEquals(Boolean.TRUE, view.get("exists"));
        @SuppressWarnings("unchecked")
        Map<String, String> properties = (Map<String, String>) view.get("properties");
        assertEquals("hello", properties.get("motd"));
        assertEquals("20", properties.get("max-players"));
    }

    @Test
    void viewReturnsEmptyViewWhenNodeHasNoInstanceData() {
        StubFiles files = new StubFiles();
        files.failure = new McpanelBusinessException(
                McpanelBusinessException.CODE_INSTANCE_NOT_FOUND, 409,
                "instance.notFound：节点上不存在该实例的容器/数据。");
        ServerConfigService service = new ServerConfigService(files);

        Map<String, Object> view = service.view("user:1", "inst-1", "server.properties");

        assertNotNull(view, "instance.notFound 必须转成空视图而不是抛 409");
        assertEquals(Boolean.FALSE, view.get("exists"));
        assertEquals("", view.get("content"));
        assertTrue(((Map<?, ?>) view.get("properties")).isEmpty());
    }

    @Test
    void viewStillFailsWhenNodeIsUnreachable() {
        StubFiles files = new StubFiles();
        files.failure = new McpanelBusinessException("node.unreachable", 502, "节点调用失败");
        ServerConfigService service = new ServerConfigService(files);

        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.view("user:1", "inst-1", "server.properties"));
        assertEquals("node.unreachable", error.code());
        assertFalse(McpanelBusinessException.CODE_INSTANCE_NOT_FOUND.equals(error.code()));
    }
}
