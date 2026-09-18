package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.ymcl.api.YmclContributionProvider;
import online.yudream.base.plugin.ymcl.api.YmclDataSourceDescriptor;
import online.yudream.base.plugin.ymcl.api.YmclPageDescriptor;
import online.yudream.base.plugin.ymcl.application.service.YmclContributionAggregator;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import online.yudream.base.plugin.ymcl.interfaces.support.YmclCustomPages;
import online.yudream.base.plugin.ymcl.interfaces.support.YmclNativePages;
import online.yudream.base.plugin.ymcl.interfaces.support.YmclNavigationDefaults;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * YAP §6.5 GET /v1/manifest：域导航树、页面注册表、数据源与动作白名单。
 *
 * 匿名可得公开版；带有效 token 时按 principal 权限裁剪（YAP §6.5）。
 * 字段名与启动器侧 Rust serde 结构（YmclManifest 等）逐字一致（snake_case）。
 */
public class YmclManifestController {

    private static final String CHROME_HOME_COLLECTION = "ymcl_chrome_home";
    private static final String CHROME_HOME_DOC = "home";
    private static final String NAVIGATION_CONFIG_COLLECTION = "ymcl_config";
    private static final String NAVIGATION_CONFIG_DOC = "navigation";
    private static final String THEME_CONFIG_COLLECTION = "ymcl_config";
    private static final String THEME_CONFIG_DOC = "theme";
    /** 与 YmclChromeHomeController.MAX_NAVIGATION_DEPTH 保持一致。 */
    private static final int MAX_NAVIGATION_DEPTH = 8;

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
        // 主题下发（theme 能力，YAP §6.9）：管理员在 /v1/chrome/theme 配置的
        // ThemeProfile 原样随 manifest 下发；缺省时启动器不启用域托管外观。
        documents.findById(THEME_CONFIG_COLLECTION, THEME_CONFIG_DOC)
                .ifPresent(theme -> payload.put("theme", theme));
        return PluginHttpResponse.rawJson(200, payload);
    }

    /**
     * 域导航树：管理员配置（chrome/navigation）引用页面注册表 pageId 做排列、
     * 嵌套层级与启停，也支持纯目录节点（type=group，无路由）。
     * 页面属性（route/权限）由注册表解析；title/icon 可被配置覆盖。
     * 仅在「从未配置过」时把未引用的提供方页面自动追加为顶层项（保持初始
     * 自动聚合语义）；一旦管理员保存过导航树，下发的导航严格等于配置的树，
     * 不再兜底追加——保证管理端所见与启动器所得一致。
     * 所有输出按 principal 权限裁剪。
     */
    private List<Map<String, Object>> buildNavigation(PluginPrincipal principal) {
        var storedConfig = documents.findById(NAVIGATION_CONFIG_COLLECTION, NAVIGATION_CONFIG_DOC);
        List<Map<String, Object>> navConfig = storedConfig
                .map(config -> castItemList(config.get("items")))
                .orElseGet(YmclNavigationDefaults::items);

        Set<String> referencedProviders = new HashSet<>();
        collectReferencedProviders(navConfig, referencedProviders);

        List<Map<String, Object>> navigation = new ArrayList<>();
        int sort = 0;
        for (Map<String, Object> item : navConfig) {
            Map<String, Object> resolved = resolveNavItem(item, principal, referencedProviders, 1);
            if (resolved == null) {
                continue;
            }
            resolved.put("sort", sort);
            sort += 10;
            navigation.add(resolved);
        }

        if (storedConfig.isEmpty()) {
            for (YmclPageDescriptor page : allPages()) {
                if (referencedProviders.contains(page.id())) {
                    continue;
                }
                Map<String, Object> item = providerNavEntry(page, null, principal);
                if (item != null) {
                    item.put("sort", sort);
                    sort += 10;
                    navigation.add(item);
                }
            }
        }
        return navigation;
    }

    /** 页面全集：SPI 提供方页面 + 管理员自定义页面（同权参与导航与下发）。 */
    private List<YmclPageDescriptor> allPages() {
        List<YmclPageDescriptor> pages = new ArrayList<>(aggregator.pages());
        pages.addAll(YmclCustomPages.list(documents));
        return pages;
    }

    /** 解析单个配置项为 manifest 导航项；停用/权限不足/空目录返回 null。 */
    private Map<String, Object> resolveNavItem(
            Map<String, Object> item, PluginPrincipal principal, Set<String> referencedProviders, int depth) {
        if (!Boolean.TRUE.equals(item.get("enabled"))) {
            return null;
        }
        String kind = navKind(item);
        String titleOverride = strOrNull(item.get("title"));
        String iconOverride = strOrNull(item.get("icon"));

        Map<String, Object> resolved = new LinkedHashMap<>();
        if ("directory".equals(kind)) {
            String title = titleOverride == null || titleOverride.isBlank() ? "目录" : titleOverride.trim();
            resolved.put("id", directoryId(item));
            resolved.put("type", "group");
            resolved.put("title", title);
            resolved.put("icon", iconOverride == null || iconOverride.isBlank() ? "i-ri:folder-line" : iconOverride.trim());
            resolved.put("required_permission", null);
        }
        else {
            String pageId = item.get("pageId") == null ? null : String.valueOf(item.get("pageId")).trim();
            if (pageId == null || pageId.isBlank()) {
                return null;
            }
            if (pageId.startsWith(YmclNativePages.PREFIX)) {
                YmclNativePages.NativePage page = YmclNativePages.find(pageId.substring(YmclNativePages.PREFIX.length()));
                if (page == null) {
                    return null;
                }
                resolved.put("id", page.id());
                resolved.put("type", "native");
                resolved.put("route", page.route());
                resolved.put("title", titleOverride == null || titleOverride.isBlank() ? page.title() : titleOverride.trim());
                resolved.put("icon", iconOverride == null || iconOverride.isBlank() ? page.icon() : iconOverride.trim());
                resolved.put("required_permission", null);
            }
            else {
                YmclPageDescriptor page = findProviderPage(pageId);
                if (page == null) {
                    return null;
                }
                referencedProviders.add(page.id());
                Map<String, Object> entry = providerNavEntry(page, null, principal);
                if (entry == null) {
                    return null;
                }
                resolved.putAll(entry);
                if (titleOverride != null && !titleOverride.isBlank()) {
                    resolved.put("title", titleOverride.trim());
                }
                if (iconOverride != null && !iconOverride.isBlank()) {
                    resolved.put("icon", iconOverride.trim());
                }
            }
        }

        List<Map<String, Object>> resolvedChildren = new ArrayList<>();
        if (item.get("children") instanceof List<?> children && depth < MAX_NAVIGATION_DEPTH) {
            int childSort = 0;
            for (Object child : children) {
                if (!(child instanceof Map<?, ?> childItem)) {
                    continue;
                }
                Map<String, Object> resolvedChild = resolveNavItem(
                        castItem(childItem), principal, referencedProviders, depth + 1);
                if (resolvedChild == null) {
                    continue;
                }
                resolvedChild.put("sort", childSort);
                childSort += 10;
                resolvedChildren.add(resolvedChild);
            }
        }
        // 空目录不下发（启动器没有可跳转目标）
        if ("group".equals(resolved.get("type")) && resolvedChildren.isEmpty()) {
            return null;
        }
        if (!resolvedChildren.isEmpty()) {
            resolved.put("children", resolvedChildren);
        }
        return resolved;
    }

    private static String navKind(Map<?, ?> item) {
        String kind = strOrNull(item.get("kind"));
        if (kind != null && !kind.isBlank()) {
            return kind;
        }
        return item.get("pageId") != null && !String.valueOf(item.get("pageId")).isBlank()
                ? "page" : "directory";
    }

    private static String directoryId(Map<String, Object> item) {
        String id = strOrNull(item.get("id"));
        return id == null || id.isBlank() ? "group" : id.trim();
    }

    private Map<String, Object> castItem(Object raw) {
        Map<String, Object> item = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> item.put(String.valueOf(key), value));
        }
        return item;
    }

    private YmclPageDescriptor findProviderPage(String pageId) {
        for (YmclPageDescriptor page : allPages()) {
            if (page.id().equals(pageId)) {
                return page;
            }
        }
        return null;
    }

    /** 提供方页面的导航项（type: "page"，YAP §6.5）；权限不足返回 null。 */
    private Map<String, Object> providerNavEntry(YmclPageDescriptor page, Integer sort, PluginPrincipal principal) {
        if (page.requiredPermission() != null
                && (principal == null || !principal.hasPermission(page.requiredPermission()))) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", page.id());
        item.put("type", "page");
        item.put("page_id", page.id());
        item.put("title", page.title());
        if (page.icon() != null && !page.icon().isBlank()) {
            item.put("icon", page.icon().trim());
        }
        if (sort != null) {
            item.put("sort", sort);
        }
        item.put("required_permission", page.requiredPermission());
        return item;
    }

    private void collectReferencedProviders(List<Map<String, Object>> items, Set<String> referenced) {
        for (Map<String, Object> item : items) {
            String pageId = item.get("pageId") == null ? null : String.valueOf(item.get("pageId")).trim();
            if (pageId != null && !pageId.isBlank() && !pageId.startsWith(YmclNativePages.PREFIX)) {
                referenced.add(pageId);
            }
            if (item.get("children") instanceof List<?> children) {
                collectReferencedProviders(castItemList(children), referenced);
            }
        }
    }

    private List<Map<String, Object>> buildPages(PluginPrincipal principal) {
        List<Map<String, Object>> pages = new ArrayList<>();
        for (YmclPageDescriptor page : allPages()) {
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
        // 每条声明都带 provider/source 路由对（YAP §6.6）：启动器数据卡据此
        // 拼出 /v1/data/{providerCode}/{sourceCode} 拉取地址。
        // title 随声明下发，启动器首页卡片标题不再回落到原始 id。
        for (YmclContributionProvider provider : aggregator.providers()) {
            for (YmclDataSourceDescriptor source : provider.dataSources()) {
                Map<String, Object> descriptor = new LinkedHashMap<>();
                descriptor.put("id", provider.providerCode() + "." + source.sourceCode());
                descriptor.put("provider_code", provider.providerCode());
                descriptor.put("source_code", source.sourceCode());
                if (source.title() != null && !source.title().isBlank()) {
                    descriptor.put("title", source.title().trim());
                }
                descriptor.put("schema_version", source.schemaVersion());
                descriptor.put("cache_ttl", source.cacheTtl());
                sources.add(descriptor);
            }
        }
        return sources;
    }

    /**
     * 文档条目转为可变 Map 列表（导航配置项引用页面的递归处理用）。
     */
    private List<Map<String, Object>> castItemList(Object items) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(items instanceof List<?> list)) {
            return result;
        }
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> raw) {
                Map<String, Object> item = new LinkedHashMap<>();
                raw.forEach((key, value) -> item.put(String.valueOf(key), value));
                result.add(item);
            }
        }
        return result;
    }

    private static String strOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
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
