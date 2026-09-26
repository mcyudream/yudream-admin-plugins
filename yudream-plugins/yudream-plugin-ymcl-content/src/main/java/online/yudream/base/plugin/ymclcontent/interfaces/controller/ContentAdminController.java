package online.yudream.base.plugin.ymclcontent.interfaces.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.ymclcontent.application.service.ContentDistributionService;
import online.yudream.base.plugin.ymclcontent.bootstrap.YmclContentPlugin;
import online.yudream.base.plugin.ymclcontent.interfaces.support.UpdateHttpSupport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * YMCL 内容分发管理端。路径相对插件命名空间：/api/plugins/ymcl-content/admin/content/**
 */
public class ContentAdminController {

    private final ContentDistributionService content;
    private final ObjectMapper mapper = new ObjectMapper();

    public ContentAdminController(ContentDistributionService content) {
        this.content = content;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/content/records",
            permission = YmclContentPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse listRecords(PluginHttpRequest request) {
        String kind = UpdateHttpSupport.firstQuery(request, "kind");
        int page = intQuery(request, "page", 1);
        int size = intQuery(request, "size", 10);
        return PluginHttpResponse.ok(content.listAdmin(kind, page, size));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/content/records",
            permission = YmclContentPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveRecord(PluginHttpRequest request) {
        Map<String, Object> payload = readJsonObject(request);
        if (payload == null) {
            return error(400, "invalid_body", "Request body must be a JSON object");
        }
        try {
            return PluginHttpResponse.ok(content.saveRecord(payload).toAdminRecord());
        } catch (IllegalArgumentException invalid) {
            return error(400, "invalid_record", invalid.getMessage());
        }
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/content/records/{id}",
            permission = YmclContentPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deleteRecord(PluginHttpRequest request) {
        String id = UpdateHttpSupport.segmentAfter(request.path(), "records", 0);
        if (!content.deleteRecord(id)) {
            return error(404, "not_found", "record not found");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", true);
        body.put("id", id);
        return PluginHttpResponse.ok(body);
    }

    private static int intQuery(PluginHttpRequest request, String name, int fallback) {
        String raw = UpdateHttpSupport.firstQuery(request, name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Map<String, Object> readJsonObject(PluginHttpRequest request) {
        try {
            return mapper.readValue(request.body(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception error) {
            return null;
        }
    }

    private static PluginHttpResponse error(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message == null ? code : message);
        return PluginHttpResponse.rawJson(status, body);
    }
}
