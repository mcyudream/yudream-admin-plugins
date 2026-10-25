package online.yudream.base.plugin.interop.interfaces.controller;

import online.yudream.base.plugin.interop.application.service.CatalogService;
import online.yudream.base.plugin.interop.domain.McInteropBusinessException;
import online.yudream.base.plugin.interop.domain.aggregate.CatalogEntry;
import online.yudream.base.plugin.interop.infrastructure.repository.InteropRepository;
import online.yudream.base.plugin.interop.infrastructure.support.JsonSupport;
import online.yudream.base.plugin.interop.interfaces.support.PathSupport;
import online.yudream.base.plugin.interop.infrastructure.support.RateLimiter;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理面（/admin/**）：站点身份、目录条目 CRUD 与友站声明。 */
public class InteropAdminController {

    private final CatalogService catalog;
    private final InteropRepository repository;
    private final RateLimiter limiter = new RateLimiter(60, 60_000L);

    public InteropAdminController(CatalogService catalog, InteropRepository repository) {
        this.catalog = catalog;
        this.repository = repository;
    }

    // ---- settings ----

    @PluginHttpEndpoint(method = "GET", path = "/admin/settings", permission = "plugin:mc-interop:view")
    public Object settings(PluginHttpRequest request) {
        return catalog.settings();
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/settings", permission = "plugin:mc-interop:manage")
    public Object saveSettings(PluginHttpRequest request) {
        Map<String, Object> body = JsonSupport.readMap(request.body());
        return catalog.saveSettings(
                text(body.get("siteId")),
                text(body.get("siteName")),
                text(body.get("description")),
                text(body.get("domain")));
    }

    // ---- entries ----

    @PluginHttpEndpoint(method = "GET", path = "/admin/entries", permission = "plugin:mc-interop:view")
    public Object entries(PluginHttpRequest request) {
        int page = intParam(request, "page", 1);
        int size = intParam(request, "pageSize", 20);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", catalog.pageEntries(query(request, "keyword"),
                Boolean.parseBoolean(query(request, "published")), page, size).stream()
                .map(this::entryView).toList());
        body.put("total", catalog.countEntries(query(request, "keyword"),
                Boolean.parseBoolean(query(request, "published"))));
        return body;
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/entries", permission = "plugin:mc-interop:manage")
    public Object saveEntry(PluginHttpRequest request) {
        Map<String, Object> body = JsonSupport.readMap(request.body());
        CatalogEntry entry = catalog.saveEntry(new CatalogEntry(
                text(body.get("id")),
                text(body.get("name")),
                text(body.get("summary")),
                nullableLong(body.get("revision")),
                text(body.get("gameVersion")),
                text(body.get("loader")),
                text(body.get("loaderVersion")),
                text(body.get("artifactFormat")),
                text(body.get("artifactUrl")),
                nullableLong(body.get("artifactSize")),
                text(body.get("sha1")),
                text(body.get("sha512")),
                text(body.get("distSystem")),
                text(body.get("distPackId")),
                text(body.get("distVersionId")),
                text(body.get("serverAddress")),
                nullableInt(body.get("serverPort")),
                Boolean.TRUE.equals(body.get("published")),
                System.currentTimeMillis()));
        return entryView(entry);
    }

    /** 合并视图：本站已发布条目 + peers 互采的友站条目（协议出口形状，source 溯源）。 */

    /** 合并视图：本站已发布条目 + peers 互采的友站条目（协议出口形状，source 溯源）。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/entries/merged", permission = "plugin:mc-interop:view")
    public Object merged(PluginHttpRequest request) {
        return catalog.siteDocument();
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/entries/import-adapter", permission = "plugin:mc-interop:manage")
    public Object importAdapter(PluginHttpRequest request) {
        return catalog.importFromAdapter();
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/entries/{id}/publish", permission = "plugin:mc-interop:manage")
    public Object publish(PluginHttpRequest request) {
        Map<String, Object> body = JsonSupport.readMap(request.body());
        return entryView(catalog.setPublished(PathSupport.segment(request.path(), 2),
                Boolean.TRUE.equals(body.get("published"))));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/entries/{id}", permission = "plugin:mc-interop:manage")
    public PluginHttpResponse deleteEntry(PluginHttpRequest request) {
        catalog.deleteEntry(PathSupport.segment(request.path(), 2));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    // ---- peers ----

    @PluginHttpEndpoint(method = "GET", path = "/admin/peers", permission = "plugin:mc-interop:view")
    public Object peers(PluginHttpRequest request) {
        return Map.of("records", catalog.peers(), "total", catalog.peers().size());
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/peers", permission = "plugin:mc-interop:manage")
    public Object savePeer(PluginHttpRequest request) {
        Map<String, Object> body = JsonSupport.readMap(request.body());
        catalog.savePeer(text(body.get("siteId")), text(body.get("name")),
                text(body.get("description")), text(body.get("domain")), text(body.get("manifestUrl")));
        return Map.of("records", catalog.peers(), "total", catalog.peers().size());
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/peers/{siteId}", permission = "plugin:mc-interop:manage")
    public PluginHttpResponse deletePeer(PluginHttpRequest request) {
        catalog.deletePeer(PathSupport.segment(request.path(), 2));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    // ---- helpers ----

    private Map<String, Object> entryView(CatalogEntry entry) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", entry.id());
        view.put("name", entry.name());
        view.put("summary", entry.summary());
        view.put("revision", entry.revision());
        view.put("gameVersion", entry.gameVersion());
        view.put("loader", entry.loader());
        view.put("loaderVersion", entry.loaderVersion());
        view.put("artifactFormat", entry.artifactFormat());
        view.put("artifactUrl", entry.artifactUrl());
        view.put("artifactSize", entry.artifactSize());
        view.put("sha1", entry.sha1());
        view.put("sha512", entry.sha512());
        view.put("distSystem", entry.distSystem());
        view.put("distPackId", entry.distPackId());
        view.put("distVersionId", entry.distVersionId());
        view.put("serverAddress", entry.serverAddress());
        view.put("serverPort", entry.serverPort());
        view.put("published", entry.published());
        view.put("updatedAt", entry.updatedAt());
        return view;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long nullableLong(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static Integer nullableInt(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static String query(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static int intParam(PluginHttpRequest request, String key, int fallback) {
        String value = query(request, key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
