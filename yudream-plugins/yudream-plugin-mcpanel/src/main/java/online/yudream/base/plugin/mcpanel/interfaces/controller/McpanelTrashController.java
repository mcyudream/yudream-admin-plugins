package online.yudream.base.plugin.mcpanel.interfaces.controller;

import online.yudream.base.plugin.mcpanel.application.service.McpanelTrashAppService;
import online.yudream.base.plugin.mcpanel.bootstrap.McpanelPlugin;
import online.yudream.base.plugin.mcpanel.interfaces.http.HttpGuards;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.security.PluginSecurityService;

import java.util.List;
import java.util.Map;

/** 回收站管理端点。Controller 仅做边界校验与委托（管理面，全量 /admin/**）。 */
public class McpanelTrashController {

    private final McpanelTrashAppService trash;
    private final PluginSecurityService security;

    public McpanelTrashController(McpanelTrashAppService trash, PluginSecurityService security) {
        this.trash = trash;
        this.security = security;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/trash", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse page(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () ->
                PluginHttpResponse.ok(trash.page(intQ(request, "page", 1), intQ(request, "size", 10),
                        q(request, "keyword"))));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/trash/{trashId}/restore",
            permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse restore(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            String body = request.body();
            String name = body == null || body.isBlank() ? null : McpanelJson.readMap(body).string("name");
            return PluginHttpResponse.ok(trash.restore(HttpGuards.actorOf(request), scope(request),
                    lastId(request.path()), name));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/trash/{trashId}",
            permission = McpanelPlugin.DELETE_PERMISSION)
    public PluginHttpResponse remove(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.DELETE_PERMISSION, () ->
                PluginHttpResponse.ok(trash.remove(HttpGuards.actorOf(request), lastId(request.path()))));
    }

    // ---------- 内部 ----------

    private static String scope(PluginHttpRequest request) {
        Long userId = HttpGuards.principalUserId(request);
        return userId == null ? "anonymous" : "user:" + userId;
    }

    private static String q(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() || values.get(0).isBlank() ? null : values.get(0).trim();
    }

    private static int intQ(PluginHttpRequest request, String key, int defaultValue) {
        String value = q(request, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return defaultValue;
        }
    }

    private static String lastId(String path) {
        String trimmed = path == null ? "" : path.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int slash = trimmed.lastIndexOf('/');
        String last = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        if (last.isBlank()) {
            throw new IllegalArgumentException("路径资源 ID 不能为空");
        }
        return java.net.URLDecoder.decode(last, java.nio.charset.StandardCharsets.UTF_8);
    }
}
