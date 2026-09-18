package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.ymcl.api.YmclPageDescriptor;
import online.yudream.base.plugin.ymcl.application.service.YmclContributionAggregator;
import online.yudream.base.plugin.ymcl.application.service.YmclEventBus;
import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;
import online.yudream.base.plugin.ymcl.interfaces.support.PathSegments;
import online.yudream.base.plugin.ymcl.interfaces.support.YmclCustomPages;
import online.yudream.base.plugin.ymcl.interfaces.support.YmclNativePages;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义页面管理端点（宿主管理页消费，wrapped 信封）。
 *
 * 管理员无需编写业务插件即可注册域页面：先用 PUT /v1/bundles/{id}/{version}
 * 上传页面 zip 包，再在此登记页面（渲染器 extension=沙箱 iframe /
 * module=可信 ESM 模块）。登记后的页面进入页面注册表，可被导航树引用，
 * 随 manifest.pages 下发。
 *
 * - GET    /v1/chrome/pages          列表
 * - PUT    /v1/chrome/pages          新建/更新（body 含 id 则按 id 覆盖）
 * - DELETE /v1/chrome/pages/{id}     删除（导航树中残留的引用会在 manifest
 *   解析时静默跳过，设计器标记失效）
 */
public class YmclCustomPagesController {

    private static final String BUNDLE_COLLECTION = "ymcl_bundles";

    private final PluginDocumentStore documents;
    private final YmclEventBus eventBus;
    private final YmclContributionAggregator aggregator;

    public YmclCustomPagesController(PluginDocumentStore documents, YmclEventBus eventBus,
            YmclContributionAggregator aggregator) {
        this.documents = documents;
        this.eventBus = eventBus;
        this.aggregator = aggregator;
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/chrome/pages",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse list(PluginHttpRequest request) {
        List<Map<String, Object>> pages = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(YmclCustomPages.COLLECTION, page, 200);
            if (batch.isEmpty()) {
                break;
            }
            pages.addAll(batch);
            page++;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pages", pages);
        return PluginHttpResponse.json(200, payload);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/v1/chrome/pages",
            permission = YmclAdapterPlugin.DESIGN_PERMISSION)
    public PluginHttpResponse upsert(PluginHttpRequest request) {
        Map<String, Object> body;
        try {
            body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(request.body(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception error) {
            return PluginHttpResponse.json(400, YmclSessionController.error(
                    "invalid_body", "Request body must be a JSON page config"));
        }

        String id = str(body.get("id"));
        boolean creating = id == null;
        if (creating) {
            id = "custom-" + Long.toHexString(System.nanoTime());
        }
        if (!YmclCustomPages.isValidId(id)) {
            return PluginHttpResponse.json(400, YmclSessionController.error(
                    "invalid_id", "页面 id 须为小写字母/数字开头的小写 slug（. _ -），且不得以 native: 开头"));
        }
        if (creating && collision(id)) {
            return PluginHttpResponse.json(409, YmclSessionController.error(
                    "page_exists", "页面 id 与内置页或提供方页面冲突: " + id));
        }

        String renderer = str(body.get("renderer"));
        if (renderer == null || !YmclCustomPages.RENDERERS.contains(renderer)) {
            return PluginHttpResponse.json(400, YmclSessionController.error(
                    "invalid_renderer", "renderer 仅支持 extension（沙箱页面）/ module（可信模块）"));
        }

        if (!(body.get("bundle") instanceof Map<?, ?> rawBundle)) {
            return PluginHttpResponse.json(400, YmclSessionController.error(
                    "invalid_bundle", "bundle 描述必填：{id, version, entry, sha256}"));
        }
        Map<String, Object> bundle = YmclCustomPages.normalizeBundle(rawBundle);
        if (bundle == null) {
            return PluginHttpResponse.json(400, YmclSessionController.error(
                    "invalid_bundle", "bundle 描述必填：{id, version, entry, sha256}，且 id/version 不含路径"));
        }
        String bundleKey = bundle.get("id") + "@" + bundle.get("version");
        if (documents.findById(BUNDLE_COLLECTION, bundleKey).isEmpty()) {
            return PluginHttpResponse.json(400, YmclSessionController.error(
                    "bundle_not_uploaded", "页面包 " + bundleKey + " 尚未上传，请先在「扩展页面包」上传"));
        }

        String title = str(body.get("title"));
        if (title == null) {
            return PluginHttpResponse.json(400, YmclSessionController.error(
                    "invalid_title", "页面标题必填"));
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", id);
        doc.put("title", title);
        doc.put("renderer", renderer);
        String icon = str(body.get("icon"));
        if (icon != null) {
            doc.put("icon", icon);
        }
        String requiredPermission = str(body.get("requiredPermission"));
        if (requiredPermission != null) {
            doc.put("requiredPermission", requiredPermission);
        }
        if (body.get("params") instanceof Map<?, ?> params) {
            Map<String, Object> paramsMap = new LinkedHashMap<>();
            params.forEach((key, value) -> paramsMap.put(String.valueOf(key), value));
            doc.put("params", paramsMap);
        }
        doc.put("bundle", bundle);
        doc.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        documents.save(YmclCustomPages.COLLECTION, id, doc);

        eventBus.publish(YmclEventBus.TYPE_MANIFEST_UPDATED, Map.of("reason", "chrome.pages"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", true);
        result.put("id", id);
        return PluginHttpResponse.json(200, result);
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/v1/chrome/pages/{id}",
            permission = YmclAdapterPlugin.DESIGN_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) {
        String id = PathSegments.segment(request.path(), 3);
        if (id == null || id.isBlank()) {
            return PluginHttpResponse.json(400, YmclSessionController.error(
                    "invalid_id", "缺少页面 id"));
        }
        documents.delete(YmclCustomPages.COLLECTION, id);
        eventBus.publish(YmclEventBus.TYPE_MANIFEST_UPDATED, Map.of("reason", "chrome.pages"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", true);
        return PluginHttpResponse.json(200, result);
    }

    /** 新建时防冲突：内置页 id 与 SPI 提供方页面 id 均不可占用。 */
    private boolean collision(String id) {
        if (YmclNativePages.isNativePageId(id) || YmclNativePages.find(id) != null) {
            return true;
        }
        for (YmclPageDescriptor page : aggregator.pages()) {
            if (page.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
