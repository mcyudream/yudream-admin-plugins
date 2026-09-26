package online.yudream.base.plugin.mcpanel.interfaces.controller;

import online.yudream.base.plugin.mcpanel.application.service.CoreDownloadService;
import online.yudream.base.plugin.mcpanel.bootstrap.McpanelPlugin;
import online.yudream.base.plugin.mcpanel.interfaces.http.HttpGuards;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.security.PluginSecurityService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 核心类型 / 版本列表 / 下载计划（YdTablePicker 取数 + 创建向导解析）。 */
public class McpanelCoreController {

    private final CoreDownloadService cores;
    private final PluginSecurityService security;

    public McpanelCoreController(CoreDownloadService cores, PluginSecurityService security) {
        this.cores = cores;
        this.security = security;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/cores", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse cores(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("records", cores.listCores());
            return PluginHttpResponse.ok(body);
        });
    }

    /** YdTablePicker fetcher：GET /admin/cores/versions?kind=&page=&size=&keyword= */
    @PluginHttpEndpoint(method = "GET", path = "/admin/cores/versions", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse versions(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(cores.pageVersions(
                        q(request, "kind"), intQ(request, "page", 1), intQ(request, "size", 10),
                        q(request, "keyword"))));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/cores/resolve", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse resolve(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () -> {
            online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson.MapReader body =
                    online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson.readMap(request.body());
            CoreDownloadService.CoreDownloadPlan plan = cores.resolve(body.string("kind"), body.string("mcVersion"));
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("kind", plan.kind());
            res.put("mcVersion", plan.mcVersion());
            res.put("url", plan.url());
            res.put("source", plan.source());
            res.put("fileName", plan.fileName());
            res.put("note", plan.note());
            return PluginHttpResponse.ok(res);
        });
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
