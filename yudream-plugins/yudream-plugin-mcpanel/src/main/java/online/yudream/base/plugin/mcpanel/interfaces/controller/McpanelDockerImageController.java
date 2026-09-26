package online.yudream.base.plugin.mcpanel.interfaces.controller;

import online.yudream.base.plugin.mcpanel.application.service.DockerImageService;
import online.yudream.base.plugin.mcpanel.bootstrap.McpanelPlugin;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.mcpanel.interfaces.http.HttpGuards;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.security.PluginSecurityService;

import java.util.List;
import java.util.Map;

/** 镜像目录：内部名 + 多标签（与核心管理解耦）。 */
public class McpanelDockerImageController {

    private final DockerImageService dockerImages;
    private final PluginSecurityService security;

    public McpanelDockerImageController(DockerImageService dockerImages, PluginSecurityService security) {
        this.dockerImages = dockerImages;
        this.security = security;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/docker-images", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse page(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(dockerImages.page(
                        intQ(request, "page", 1), intQ(request, "size", 10), q(request, "keyword"))));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/docker-images/options", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse options(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(Map.of("records", dockerImages.options())));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/docker-images/{id}", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse detail(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(dockerImages.get(requireId(request.path()))));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/docker-images", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse save(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            Map<String, Object> body = McpanelJson.mapper().convertValue(
                    McpanelJson.readMap(request.body()).node(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            return PluginHttpResponse.ok(dockerImages.save(body));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/docker-images/{id}", permission = McpanelPlugin.DELETE_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.DELETE_PERMISSION, () -> {
            dockerImages.delete(requireId(request.path()));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    /**
     * 路径变量 ID：兼容插件相对路径是否含 pluginCode 前缀。
     * /admin/docker-images/{id} 与 /api/plugins/mcpanel/admin/docker-images/{id}
     * 的 id 一律取最后一段，避免固定下标在不同挂载前缀下取到 null。
     */
    private static String requireId(String path) {
        String id = lastSegment(path);
        if (id == null || id.isBlank()) {
            throw online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException.invalid("镜像目录项 ID 不能为空");
        }
        return id;
    }

    private static String lastSegment(String path) {
        String trimmed = path == null ? "" : path.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int slash = trimmed.lastIndexOf('/');
        String last = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        if (last.isBlank()) {
            return null;
        }
        return java.net.URLDecoder.decode(last, java.nio.charset.StandardCharsets.UTF_8);
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
}
