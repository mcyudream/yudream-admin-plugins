package online.yudream.base.plugin.ymclcontent.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.ymclcontent.application.service.ContentDistributionService;
import online.yudream.base.plugin.ymclcontent.interfaces.support.UpdateHttpSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YMCL 内容分发公开端点（匿名，wrapResult=false）。
 * 消费方：YMCL 启动器 helpers/ymcl-content.ts（公告/指引/在线内容）。
 * 路径前缀：/api/plugins/ymcl-content/v1/content/**、/api/plugins/ymcl-content/v1/catalog
 */
public class ContentPublicController {

    private final ContentDistributionService content;

    public ContentPublicController(ContentDistributionService content) {
        this.content = content;
    }

    /** 单分类列表：GET /v1/content/{guides|updates|online}。 */
    @PluginHttpEndpoint(method = "GET", path = "/v1/content/{kind}", wrapResult = false)
    public PluginHttpResponse listByKind(PluginHttpRequest request) {
        String kind = UpdateHttpSupport.segmentAfter(request.path(), "content", 0);
        if (kind != null) {
            kind = kind.trim().toLowerCase();
        }
        if (!content.isKnownKind(kind)) {
            return PluginHttpResponse.rawJson(404, Map.of(
                    "message", "unknown content kind",
                    "kind", kind == null ? "" : kind,
                    "supported", ContentDistributionService.KINDS));
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (var record : content.listVisibleByKind(kind)) {
            records.add(record.toPublicRecord());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apiVersion", ContentDistributionService.API_VERSION);
        payload.put("kind", kind);
        payload.put("records", records);
        payload.put("total", records.size());
        return PluginHttpResponse.rawJson(200, payload);
    }

    /** 全量目录：GET /v1/catalog，三个固定 kind 分组。 */
    @PluginHttpEndpoint(method = "GET", path = "/v1/catalog", wrapResult = false)
    public PluginHttpResponse catalog(PluginHttpRequest request) {
        return PluginHttpResponse.rawJson(200, content.catalog());
    }
}
