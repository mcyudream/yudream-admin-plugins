package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.ymcl.api.YmclDataSourceDescriptor;
import online.yudream.base.plugin.ymcl.api.YmclPageDescriptor;
import online.yudream.base.plugin.ymcl.application.service.YmclContributionAggregator;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YAP §6.5 GET /v1/manifest：域导航树、页面注册表、数据源与动作白名单。
 *
 * 匿名可得公开版；带有效 token 时按 principal 权限裁剪（YAP §6.5）。
 * 字段名与启动器侧 Rust serde 结构（YmclManifest 等）逐字一致（snake_case）。
 */
public class YmclManifestController {

    private static final String CHROME_HOME_COLLECTION = "ymcl_chrome_home";
    private static final String CHROME_HOME_DOC = "home";

    private final YmclContributionAggregator aggregator;
    private final PluginDocumentStore documents;

    public YmclManifestController(YmclContributionAggregator aggregator, PluginDocumentStore documents) {
        this.aggregator = aggregator;
        this.documents = documents;
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/manifest", wrapResult = false)
    public PluginHttpResponse manifest(PluginHttpRequest request) {
        PluginPrincipal principal = request.principal();
        List<Map<String, Object>> navigation = buildNavigation(principal);
        navigation.addAll(buildProviderNavigation(principal));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("protocol_version", 1);
        payload.put("navigation", navigation);
        payload.put("pages", buildPages(principal));
        payload.put("data_sources", buildDataSources());
        payload.put("actions", buildActions());
        // Home layout hosting (home capability): members receive the
        // designer-published card layout through the manifest.
        documents.findById(CHROME_HOME_COLLECTION, CHROME_HOME_DOC)
                .ifPresent(home -> payload.put("home", home));
        return PluginHttpResponse.rawJson(200, payload);
    }

    /**
     * Provider-contributed pages become domain navigation entries
     * (`type: "page"`, YAP §6.5), pruned when the caller lacks the
     * page's required permission.
     */
    private List<Map<String, Object>> buildProviderNavigation(PluginPrincipal principal) {
        List<Map<String, Object>> navigation = new ArrayList<>();
        for (YmclPageDescriptor page : aggregator.pages()) {
            if (page.requiredPermission() != null
                    && (principal == null || !principal.hasPermission(page.requiredPermission()))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", page.id());
            item.put("type", "page");
            item.put("page_id", page.id());
            item.put("title", page.title());
            item.put("sort", page.sort());
            item.put("required_permission", page.requiredPermission());
            navigation.add(item);
        }
        return navigation;
    }

    private List<Map<String, Object>> buildPages(PluginPrincipal principal) {
        List<Map<String, Object>> pages = new ArrayList<>();
        for (YmclPageDescriptor page : aggregator.pages()) {
            if (page.requiredPermission() != null
                    && (principal == null || !principal.hasPermission(page.requiredPermission()))) {
                continue;
            }
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("id", page.id());
            descriptor.put("renderer", page.renderer());
            descriptor.put("title", page.title());
            descriptor.put("data_source", page.dataSource());
            descriptor.put("permissions",
                    page.requiredPermission() == null ? List.of() : List.of(page.requiredPermission()));
            descriptor.put("params", page.params());
            descriptor.put("bundle", page.bundle());
            pages.add(descriptor);
        }
        return pages;
    }

    private List<Map<String, Object>> buildDataSources() {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (YmclDataSourceDescriptor source : aggregator.dataSources()) {
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("id", source.sourceCode());
            descriptor.put("schema_version", source.schemaVersion());
            descriptor.put("cache_ttl", source.cacheTtl());
            sources.add(descriptor);
        }
        return sources;
    }

    private List<Map<String, Object>> buildNavigation(PluginPrincipal principal) {
        List<Map<String, Object>> navigation = new ArrayList<>();
        navigation.add(nativeItem("home", "/", "首页", "home", 0, null));
        navigation.add(nativeItem("discover", "/browse/mod", "发现内容", "discover", 10, null));
        navigation.add(nativeItem("library", "/library", "实例库", "library", 20, null));
        // Permission-gated entries: a domain may surface native pages only
        // to authenticated members (YAP §6.5 requiredPermission pruning).
        if (principal != null && principal.userId() != null) {
            navigation.add(nativeItem("skins", "/skins", "皮肤", "skins", 30, null));
        }
        navigation.add(nativeItem("settings", "/settings", "设置", "settings", 100, null));
        return navigation;
    }

    private Map<String, Object> nativeItem(
            String id, String route, String title, String icon, int sort, String requiredPermission) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("type", "native");
        item.put("route", route);
        item.put("title", title);
        item.put("icon", icon);
        item.put("sort", sort);
        item.put("required_permission", requiredPermission);
        return item;
    }

    private Map<String, Object> buildActions() {
        Map<String, Object> actions = new LinkedHashMap<>();
        List<String> allow = new ArrayList<>();
        allow.add("client:launch-server");
        allow.add("client:launch-instance");
        allow.add("client:open-url");
        allow.add("client:copy");
        allow.add("client:install-pack");
        allow.add("client:reload");
        allow.add("server:*");
        actions.put("allow", allow);
        return actions;
    }
}
