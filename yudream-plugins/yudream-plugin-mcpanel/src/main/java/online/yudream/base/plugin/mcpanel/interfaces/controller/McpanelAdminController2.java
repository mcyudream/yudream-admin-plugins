package online.yudream.base.plugin.mcpanel.interfaces.controller;

import online.yudream.base.plugin.mcpanel.application.dto.PanelSettings;
import online.yudream.base.plugin.mcpanel.application.service.ArtifactStoreService;
import online.yudream.base.plugin.mcpanel.application.service.ContributionService;
import online.yudream.base.plugin.mcpanel.application.service.SettingsService;
import online.yudream.base.plugin.mcpanel.application.service.TemplateService;
import online.yudream.base.plugin.mcpanel.bootstrap.McpanelPlugin;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpPart;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.security.PluginSecurityService;

import java.util.List;
import java.util.Map;

/** 模板 + 设置 + 贡献管理端点。Controller 仅做边界校验与委托。 */
public class McpanelAdminController2 {

    private final TemplateService templates;
    private final SettingsService settings;
    private final ContributionService contributions;
    private final ArtifactStoreService artifacts;
    private final online.yudream.base.plugin.mcpanel.application.service.MinecraftLinkService link;
    private final PluginSecurityService security;

    public McpanelAdminController2(TemplateService templates, SettingsService settings,
                                   ContributionService contributions,
                                   ArtifactStoreService artifacts,
                                   online.yudream.base.plugin.mcpanel.application.service.MinecraftLinkService link,
                                   PluginSecurityService security) {
        this.templates = templates;
        this.settings = settings;
        this.contributions = contributions;
        this.artifacts = artifacts;
        this.link = link;
        this.security = security;
    }

    // ---------- 联动（M5） ----------

    @PluginHttpEndpoint(method = "GET", path = "/admin/link/options", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse linkOptions(PluginHttpRequest request) {
        return guardedView(request, () -> PluginHttpResponse.ok(link.options()));
    }

    // ---------- 模板（M4） ----------

    @PluginHttpEndpoint(method = "GET", path = "/admin/templates", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse templates(PluginHttpRequest request) {
        return guardedView(request, () -> PluginHttpResponse.ok(templates.page(
                intQ(request, "page", 1), intQ(request, "size", 10), q(request, "kind"), q(request, "keyword"))));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/templates", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveTemplate(PluginHttpRequest request) {
        return guardedManage(request, () -> {
            var node = McpanelJson.readMap(request.body()).node();
            Map<String, Object> body = McpanelJson.mapper().convertValue(node,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            return PluginHttpResponse.ok(templates.save(body));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/templates/{key}", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse templateDetail(PluginHttpRequest request) {
        return guardedView(request, () -> PluginHttpResponse.ok(templates.get(lastId(request.path()))));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/templates/{key}", permission = McpanelPlugin.DELETE_PERMISSION)
    public PluginHttpResponse deleteTemplate(PluginHttpRequest request) {
        return guardedDelete(request, () -> {
            templates.delete(lastId(request.path()));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    // ---------- 设置（M4/M7/M8） ----------

    @PluginHttpEndpoint(method = "GET", path = "/admin/settings", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse viewSettings(PluginHttpRequest request) {
        return guardedManage(request, () -> PluginHttpResponse.ok(settings.view()));
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/settings", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveSettings(PluginHttpRequest request) {
        return guardedManage(request, () -> {
            PanelSettings updated = McpanelJson.read(request.body(), PanelSettings.class);
            return PluginHttpResponse.ok(settings.save(updated, readSecretUpdates(request.body())));
        });
    }

    /**
     * 密钥更新字段（body 的 secrets 对象）：缺省 / JSON null = 保持不变，空串 = 清除。
     * 键名白名单由 SettingsService 把关，这里只做形状解析。
     */
    static Map<String, String> readSecretUpdates(String body) {
        com.fasterxml.jackson.databind.JsonNode node = McpanelJson.readMap(body).node().path("secrets");
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, String> updates = new java.util.LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isNull()) {
                updates.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return updates;
    }

    // ---------- 制品库（注入源 jar 上传/代理下载） ----------

    @PluginHttpEndpoint(method = "POST", path = "/admin/artifacts", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse uploadArtifact(PluginHttpRequest request) {
        return guardedManage(request, () -> {
            PluginHttpPart file = null;
            for (PluginHttpPart part : request.parts().values()) {
                if (part.isFile()) {
                    file = part;
                    break;
                }
            }
            if (file == null) {
                throw online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException.invalid("缺少制品文件");
            }
            return PluginHttpResponse.ok(artifacts.upload(file.filename(), file.data()));
        });
    }

    /** fileId 含 '/'，走 query 传递，不做路径段。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/artifacts/download", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse downloadArtifact(PluginHttpRequest request) {
        return guardedManage(request, () -> {
            byte[] data = artifacts.download(q(request, "fileId"));
            return new PluginHttpResponse(200, Map.of("Content-Disposition", "attachment"),
                    "application/octet-stream", data, false);
        });
    }

    // ---------- 贡献（M8 管理面） ----------

    @PluginHttpEndpoint(method = "GET", path = "/admin/contributions", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse contributions(PluginHttpRequest request) {
        return guardedManage(request, () -> PluginHttpResponse.ok(contributions.adminPage(
                intQ(request, "page", 1), intQ(request, "size", 10), q(request, "status"))));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/contributions/{id}/review", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse review(PluginHttpRequest request) {
        return guardedManage(request, () -> {
            McpanelJson.MapReader body = McpanelJson.readMap(request.body());
            String approveText = body.string("approve");
            if (approveText == null) {
                throw new IllegalArgumentException("缺少 approve 布尔值");
            }
            return PluginHttpResponse.ok(contributions.review(actor(request), pathIdBefore(request.path(), "review"),
                    Boolean.parseBoolean(approveText), body.string("reason")));
        });
    }

    // ---------- 玩家面（M8）：/me/contributions ----------

    @PluginHttpEndpoint(method = "GET", path = "/me/contributions")
    public PluginHttpResponse myContributions(PluginHttpRequest request) {
        return me(request, () -> PluginHttpResponse.ok(Map.of("records",
                contributions.myContributions(userId(request)))));
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/contributions")
    public PluginHttpResponse submitContribution(PluginHttpRequest request) {
        return me(request, () -> {
            McpanelJson.MapReader body = McpanelJson.readMap(request.body());
            return PluginHttpResponse.ok(contributions.submit(userId(request), body.string("machineSpec"),
                    body.string("bandwidth"), body.string("onlineHours"), body.string("note")));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/me/contributions/{id}")
    public PluginHttpResponse withdraw(PluginHttpRequest request) {
        return me(request, () -> {
            contributions.withdraw(userId(request), lastId(request.path()));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    // ---------- 内部 ----------

    private PluginHttpResponse guardedView(PluginHttpRequest request, java.util.function.Supplier<PluginHttpResponse> action) {
        return online.yudream.base.plugin.mcpanel.interfaces.http.HttpGuards.guarded(
                request, security, McpanelPlugin.VIEW_PERMISSION, action);
    }

    private PluginHttpResponse guardedManage(PluginHttpRequest request, java.util.function.Supplier<PluginHttpResponse> action) {
        return online.yudream.base.plugin.mcpanel.interfaces.http.HttpGuards.guarded(
                request, security, McpanelPlugin.MANAGE_PERMISSION, action);
    }

    private PluginHttpResponse guardedDelete(PluginHttpRequest request, java.util.function.Supplier<PluginHttpResponse> action) {
        return online.yudream.base.plugin.mcpanel.interfaces.http.HttpGuards.guarded(
                request, security, McpanelPlugin.DELETE_PERMISSION, action);
    }

    private PluginHttpResponse me(PluginHttpRequest request, java.util.function.Supplier<PluginHttpResponse> action) {
        return online.yudream.base.plugin.mcpanel.interfaces.http.HttpGuards.me(request, action);
    }

    private static long userId(PluginHttpRequest request) {
        Long id = online.yudream.base.plugin.mcpanel.interfaces.http.HttpGuards.principalUserId(request);
        if (id == null) {
            throw new IllegalStateException("未认证请求不得进入玩家面");
        }
        return id;
    }

    private static String actor(PluginHttpRequest request) {
        return online.yudream.base.plugin.mcpanel.interfaces.http.HttpGuards.actorOf(request);
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

    /** 路径以 /{id} 结尾：取最后一段，兼容是否有插件 code 前缀。 */
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

    /** /…/{id}/{action} → action 之前一段。 */
    private static String pathIdBefore(String path, String action) {
        String trimmed = path == null ? "" : path.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String[] parts = trimmed.split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("路径资源 ID 不能为空");
        }
        int actionIndex = -1;
        for (int i = parts.length - 1; i >= 0; i--) {
            if (action.equals(parts[i])) {
                actionIndex = i;
                break;
            }
        }
        int idIndex = actionIndex > 0 ? actionIndex - 1 : parts.length - 2;
        if (idIndex < 0 || parts[idIndex].isBlank()) {
            throw new IllegalArgumentException("路径资源 ID 不能为空");
        }
        return java.net.URLDecoder.decode(parts[idIndex], java.nio.charset.StandardCharsets.UTF_8);
    }
}
