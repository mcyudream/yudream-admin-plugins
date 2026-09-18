package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.ymcl.api.YmclContributionProvider;
import online.yudream.base.plugin.ymcl.api.YmclPageDescriptor;
import online.yudream.base.plugin.ymcl.application.service.YmclContributionAggregator;
import online.yudream.base.plugin.ymcl.application.service.YmclEventBus;
import online.yudream.base.plugin.ymcl.interfaces.support.YmclCustomPages;
import online.yudream.base.plugin.ymcl.interfaces.support.YmclNativePages;
import online.yudream.base.plugin.ymcl.interfaces.support.YmclNavigationDefaults;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 域外观管理端点（宿主管理页消费，wrapped 信封）。
 *
 * - /v1/chrome/home：域首页卡片布局（YAP §6.5 home 能力）；
 * - /v1/chrome/domain：域身份（display name / description / logo_url）；
 * - /v1/chrome/navigation：域导航树。支持 page 节点（引用页面注册表
 *   pageId，可选覆盖 title/icon）与 directory 节点（纯目录，无路由），
 *   做排列、层级（两级：tab → 子菜单）与启停；路由/权限是页面属性。
 * - /v1/chrome/theme：域外观主题（YAP §6.9 ThemeProfile），随 manifest.theme
 *   下发；启动器据此托管成员的外观设置。写路径广播 manifest.updated（YAP §6.10）。
 */
public class YmclChromeHomeController {

    private static final String COLLECTION = "ymcl_chrome_home";
    private static final String DOC_ID = "home";
    private static final String DOMAIN_CONFIG_COLLECTION = "ymcl_config";
    private static final String DOMAIN_CONFIG_DOC = "domain";
    private static final String NAVIGATION_CONFIG_DOC = "navigation";
    private static final String THEME_CONFIG_DOC = "theme";
    /** 嵌套导航深度上限：顶栏 → 内容侧栏 → 更深分组，8 层足够且防止脏数据递归。 */
    private static final int MAX_NAVIGATION_DEPTH = 8;

    private final PluginDocumentStore documents;
    private final YmclEventBus eventBus;
    private final YmclContributionAggregator aggregator;

    public YmclChromeHomeController(PluginDocumentStore documents, YmclEventBus eventBus, YmclContributionAggregator aggregator) {
        this.documents = documents;
        this.eventBus = eventBus;
        this.aggregator = aggregator;
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/chrome/home", permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse get(PluginHttpRequest request) {
        Optional<Map<String, Object>> config = documents.findById(COLLECTION, DOC_ID);
        if (config.isEmpty()) {
            // Empty layout: the launcher treats this as "no home hosting".
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("schemaVersion", 1);
            empty.put("locked", false);
            empty.put("cards", List.of());
            return PluginHttpResponse.json(200, empty);
        }
        return PluginHttpResponse.json(200, config.get());
    }

    @PluginHttpEndpoint(method = "PUT", path = "/v1/chrome/home", permission = YmclAdapterPlugin.DESIGN_PERMISSION)
    public PluginHttpResponse put(PluginHttpRequest request) {
        Map<String, Object> config;
        try {
            config = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(request.body(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception error) {
            return PluginHttpResponse.json(400, YmclSessionController.error(
                    "invalid_body", "Request body must be a JSON home config"));
        }
        if (!Boolean.TRUE.equals(config.get("locked")) && !Boolean.FALSE.equals(config.get("locked"))) {
            config.put("locked", Boolean.FALSE);
        }
        config.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        documents.save(COLLECTION, DOC_ID, config);

        eventBus.publish(YmclEventBus.TYPE_MANIFEST_UPDATED, Map.of("reason", "chrome.home"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", true);
        return PluginHttpResponse.json(200, result);
    }

    /**
     * 域身份配置（管理端）：display name / description / logo_url，经
     * capabilities 端点下发给启动器（name 留空则回退 origin）。
     */
    @PluginHttpEndpoint(method = "GET", path = "/v1/chrome/domain", permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse getDomain(PluginHttpRequest request) {
        Map<String, Object> config = documents.findById(DOMAIN_CONFIG_COLLECTION, DOMAIN_CONFIG_DOC)
                .orElseGet(LinkedHashMap::new);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", strOrNull(config.get("name")));
        payload.put("description", strOrNull(config.get("description")));
        payload.put("logo_url", strOrNull(config.get("logo_url")));
        payload.put("updatedAt", strOrNull(config.get("updatedAt")));
        return PluginHttpResponse.json(200, payload);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/v1/chrome/domain", permission = YmclAdapterPlugin.DESIGN_PERMISSION)
    public PluginHttpResponse putDomain(PluginHttpRequest request) {
        Map<String, Object> body;
        try {
            body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(request.body(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception error) {
            return PluginHttpResponse.json(400, YmclSessionController.error(
                    "invalid_body", "Request body must be a JSON domain config"));
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", blankToNull(body.get("name")));
        config.put("description", blankToNull(body.get("description")));
        config.put("logo_url", blankToNull(body.get("logo_url")));
        config.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        documents.save(DOMAIN_CONFIG_COLLECTION, DOMAIN_CONFIG_DOC, config);

        eventBus.publish(YmclEventBus.TYPE_MANIFEST_UPDATED, Map.of("reason", "chrome.domain"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", true);
        return PluginHttpResponse.json(200, result);
    }

    /**
     * 域导航树配置：未配置时返回内置默认树（configured=false）作为初始
     * 可编辑列表；同时下发页面注册表（pages），管理端从中挑选页面加入
     * 导航。节点支持 {@code kind=page}（引用注册表）与 {@code kind=directory}
     * （纯目录）。PUT {@code {"items":[...]}} 保存，PUT {@code {"reset":true}}
     * 恢复默认。
     */
    @PluginHttpEndpoint(method = "GET", path = "/v1/chrome/navigation", permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse getNavigation(PluginHttpRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("configured", false);
        payload.put("items", YmclNavigationDefaults.items());
        payload.put("pages", pageRegistry());
        documents.findById(DOMAIN_CONFIG_COLLECTION, NAVIGATION_CONFIG_DOC)
                .ifPresent(config -> {
                    payload.put("configured", true);
                    Object items = config.get("items");
                    payload.put("items", items == null ? YmclNavigationDefaults.items() : items);
                    payload.put("updatedAt", strOrNull(config.get("updatedAt")));
                });
        return PluginHttpResponse.json(200, payload);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/v1/chrome/navigation", permission = YmclAdapterPlugin.DESIGN_PERMISSION)
    public PluginHttpResponse putNavigation(PluginHttpRequest request) {
        Map<String, Object> body;
        try {
            body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(request.body(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception error) {
            return PluginHttpResponse.json(400, YmclSessionController.error(
                    "invalid_body", "Request body must be a JSON navigation config"));
        }

        if (Boolean.TRUE.equals(body.get("reset"))) {
            documents.delete(DOMAIN_CONFIG_COLLECTION, NAVIGATION_CONFIG_DOC);
            eventBus.publish(YmclEventBus.TYPE_MANIFEST_UPDATED, Map.of("reason", "chrome.navigation"));
            Map<String, Object> resetResult = new LinkedHashMap<>();
            resetResult.put("saved", true);
            resetResult.put("reset", true);
            return PluginHttpResponse.json(200, resetResult);
        }

        if (!(body.get("items") instanceof List<?> items)) {
            return PluginHttpResponse.json(400, YmclSessionController.error(
                    "invalid_body", "items must be a JSON array"));
        }
        Set<String> knownPageIds = knownPageIds();
        List<Map<String, Object>> cleaned = normalizeNavItems(items, knownPageIds, 1);
        if (cleaned == null) {
            return PluginHttpResponse.json(400, YmclSessionController.error(
                    "invalid_body", "导航项无效：page 引用不存在、directory 缺标题，或层级超过 "
                            + MAX_NAVIGATION_DEPTH + " 级"));
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("items", cleaned);
        config.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        documents.save(DOMAIN_CONFIG_COLLECTION, NAVIGATION_CONFIG_DOC, config);

        eventBus.publish(YmclEventBus.TYPE_MANIFEST_UPDATED, Map.of("reason", "chrome.navigation"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", true);
        return PluginHttpResponse.json(200, result);
    }

    /**
     * 域主题配置（管理端）：YAP §6.9 ThemeProfile，随 manifest.theme 下发给
     * 启动器。启动器只在 manifest 含 theme 节点时启用域托管外观（受管集合
     * 整体只读，未配置字段回退成员个人偏好）。未配置时返回可编辑模板
     * （configured=false）。PUT {@code {"reset":true}} 撤销托管。
     */
    @PluginHttpEndpoint(method = "GET", path = "/v1/chrome/theme", permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse getTheme(PluginHttpRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("configured", false);
        payload.put("theme", themeTemplate());
        Optional<Map<String, Object>> config = documents.findById(DOMAIN_CONFIG_COLLECTION, THEME_CONFIG_DOC);
        if (config.isPresent()) {
            payload.put("configured", true);
            Map<String, Object> theme = config.get();
            payload.put("theme", theme);
            payload.put("updatedAt", strOrNull(theme.get("updatedAt")));
        }
        return PluginHttpResponse.json(200, payload);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/v1/chrome/theme", permission = YmclAdapterPlugin.DESIGN_PERMISSION)
    public PluginHttpResponse putTheme(PluginHttpRequest request) {
        Map<String, Object> theme;
        try {
            theme = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(request.body(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception error) {
            return PluginHttpResponse.json(400, YmclSessionController.error(
                    "invalid_body", "Request body must be a JSON theme profile"));
        }

        if (Boolean.TRUE.equals(theme.get("reset"))) {
            documents.delete(DOMAIN_CONFIG_COLLECTION, THEME_CONFIG_DOC);
            eventBus.publish(YmclEventBus.TYPE_MANIFEST_UPDATED, Map.of("reason", "chrome.theme"));
            Map<String, Object> resetResult = new LinkedHashMap<>();
            resetResult.put("saved", true);
            resetResult.put("reset", true);
            return PluginHttpResponse.json(200, resetResult);
        }

        theme.remove("reset");
        if (theme.get("schemaVersion") == null) {
            theme.put("schemaVersion", 1);
        }
        theme.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        documents.save(DOMAIN_CONFIG_COLLECTION, THEME_CONFIG_DOC, theme);

        eventBus.publish(YmclEventBus.TYPE_MANIFEST_UPDATED, Map.of("reason", "chrome.theme"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", true);
        return PluginHttpResponse.json(200, result);
    }

    /** 空主题模板：全部节点留空 = 域不覆盖任何字段，成员回退个人偏好。 */
    private static Map<String, Object> themeTemplate() {
        Map<String, Object> theme = new LinkedHashMap<>();
        theme.put("schemaVersion", 1);
        theme.put("mode", new LinkedHashMap<String, Object>());
        theme.put("accentColor", new LinkedHashMap<String, Object>());
        theme.put("background", new LinkedHashMap<String, Object>());
        theme.put("window", new LinkedHashMap<String, Object>());
        theme.put("advancedRendering", new LinkedHashMap<String, Object>());
        theme.put("pageTransitions", new LinkedHashMap<String, Object>());
        return theme;
    }

    /** 页面注册表视图：native 固定页 + 提供方贡献页，路由/权限为页面固定属性。 */
    private List<Map<String, Object>> pageRegistry() {
        List<Map<String, Object>> pages = new ArrayList<>();
        for (YmclNativePages.NativePage page : YmclNativePages.all()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("pageId", YmclNativePages.pageId(page.id()));
            view.put("source", "native");
            view.put("title", page.title());
            view.put("route", page.route());
            view.put("icon", page.icon());
            view.put("requiredPermission", null);
            pages.add(view);
        }
        for (YmclContributionProvider provider : aggregator.providers()) {
            for (YmclPageDescriptor page : provider.pages()) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("pageId", page.id());
                view.put("source", "provider");
                view.put("title", page.title());
                view.put("route", null);
                view.put("icon", page.icon());
                view.put("requiredPermission", page.requiredPermission());
                pages.add(view);
            }
        }
        for (YmclPageDescriptor page : YmclCustomPages.list(documents)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("pageId", page.id());
            view.put("source", "custom");
            view.put("title", page.title());
            view.put("route", null);
            view.put("icon", page.icon());
            view.put("requiredPermission", page.requiredPermission());
            pages.add(view);
        }
        return pages;
    }

    private Set<String> knownPageIds() {
        Set<String> ids = new HashSet<>();
        for (YmclNativePages.NativePage page : YmclNativePages.all()) {
            ids.add(YmclNativePages.pageId(page.id()));
        }
        for (YmclContributionProvider provider : aggregator.providers()) {
            for (YmclPageDescriptor page : provider.pages()) {
                ids.add(page.id());
            }
        }
        for (YmclPageDescriptor page : YmclCustomPages.list(documents)) {
            ids.add(page.id());
        }
        return ids;
    }

    /**
     * 递归规范化导航项：page 校验注册表引用并保留可选 title/icon 覆盖；
     * directory 保留 id/title/icon（标题必填）。enabled 默认 true，sort 重算。
     * 层级超限或结构非法返回 null。
     */
    private List<Map<String, Object>> normalizeNavItems(List<?> rawItems, Set<String> knownPageIds, int depth) {
        if (depth > MAX_NAVIGATION_DEPTH) {
            return null;
        }
        List<Map<String, Object>> cleaned = new ArrayList<>();
        int sort = 0;
        for (Object entry : rawItems) {
            if (!(entry instanceof Map<?, ?> raw)) {
                continue;
            }
            String kind = navKind(raw);
            Map<String, Object> item = new LinkedHashMap<>();
            if ("directory".equals(kind)) {
                String id = blankToNull(raw.get("id"));
                String title = blankToNull(raw.get("title"));
                if (title == null) {
                    return null;
                }
                item.put("kind", "directory");
                item.put("id", id == null || id.isBlank() ? "dir-" + System.nanoTime() : id.trim());
                item.put("title", title);
                String icon = blankToNull(raw.get("icon"));
                if (icon != null) {
                    item.put("icon", icon);
                }
            }
            else {
                String pageId = blankToNull(raw.get("pageId"));
                if (pageId == null || !knownPageIds.contains(pageId)) {
                    return null;
                }
                item.put("kind", "page");
                item.put("pageId", pageId);
                String title = blankToNull(raw.get("title"));
                if (title != null) {
                    item.put("title", title);
                }
                String icon = blankToNull(raw.get("icon"));
                if (icon != null) {
                    item.put("icon", icon);
                }
            }
            item.put("enabled", !Boolean.FALSE.equals(raw.get("enabled")));
            item.put("sort", sort);
            sort += 10;
            Object children = raw.get("children");
            List<Map<String, Object>> childItems = children instanceof List<?> childList
                    ? normalizeNavItems(childList, knownPageIds, depth + 1)
                    : new ArrayList<>();
            if (childItems == null) {
                return null;
            }
            item.put("children", childItems);
            cleaned.add(item);
        }
        return cleaned;
    }

    /** 节点类型：显式 kind 优先；仅有 pageId 的旧数据视为 page。 */
    private static String navKind(Map<?, ?> raw) {
        String kind = blankToNull(raw.get("kind"));
        if (kind != null) {
            return kind;
        }
        return blankToNull(raw.get("pageId")) != null ? "page" : "directory";
    }

    private static String strOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String blankToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
