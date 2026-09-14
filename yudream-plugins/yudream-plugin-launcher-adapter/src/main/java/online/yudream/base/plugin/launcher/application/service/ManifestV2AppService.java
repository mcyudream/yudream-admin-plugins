package online.yudream.base.plugin.launcher.application.service;

import online.yudream.base.plugin.launcher.api.LauncherDataSource;
import online.yudream.base.plugin.launcher.api.LauncherPage;
import online.yudream.base.plugin.launcher.bootstrap.LauncherAdapterPlugin;
import online.yudream.base.plugin.launcher.domain.valobj.YmclLauncherChrome;
import online.yudream.base.plugin.launcher.domain.valobj.YmclNavNode;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 构建协议 v2 manifest（§3）：导航单一事实源 + 页面渲染器视图 + 构建期权限裁剪。
 * <p>
 * 生成规则（§3.4/§3.5）：provider 页面聚合 → 套用 chrome 覆盖树（未引用页面自动追加到首个 tab 末尾，
 * 由 {@link YmclChromeAppService#loadForPages} 完成）→ 按当前用户 permissions 裁剪页面与引用它们的
 * 导航节点（空 group/tab 一并剪掉）。匿名（principal 为 null）只收到无 requiresPermission 的页面。
 */
public class ManifestV2AppService {

    public static final String MANIFEST_VERSION = "2";

    private final PluginContext context;
    private final LauncherProviderAggregator aggregator;
    private final YmclChromeAppService chromeAppService;
    private final AuthSessionAppService authSessionAppService;

    public ManifestV2AppService(
            PluginContext context,
            LauncherProviderAggregator aggregator,
            YmclChromeAppService chromeAppService,
            AuthSessionAppService authSessionAppService
    ) {
        this.context = context;
        this.aggregator = aggregator;
        this.chromeAppService = chromeAppService;
        this.authSessionAppService = authSessionAppService;
    }

    public Map<String, Object> buildManifest(PluginPrincipal principal) {
        List<String> permissions = permissionsOf(principal);
        List<LauncherPage> visiblePages = aggregator.aggregatePages().stream()
                .filter(page -> pageAllowed(page, permissions))
                .toList();
        Map<String, LauncherPage> byCode = pagesByCode(visiblePages);

        Map<String, Object> theme = chromeAppService.themeView();
        String displayName = text(String.valueOf(theme.getOrDefault("displayName", "")));
        if (displayName.isEmpty()) {
            displayName = "YuDream Site";
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("manifestVersion", MANIFEST_VERSION);
        manifest.put("domain", Map.of(
                "name", displayName,
                "pluginCode", context.pluginCode(),
                "version", LauncherAdapterPlugin.VERSION
        ));
        manifest.put("auth", Map.of("methods", authMethodTypes()));
        manifest.put("session", sessionView(principal, permissions));
        manifest.put("capabilities", Map.of(
                "packs", true,
                "distribution", true,
                "extensionPages", true
        ));
        manifest.put("navigation", navigationView(visiblePages, byCode));
        manifest.put("pages", visiblePages.stream().map(this::pageView).toList());
        manifest.put("dataSources", dataSourceViews(visiblePages));
        manifest.put("theme", theme);
        manifest.put("updatedAt", Instant.now().toString());
        return manifest;
    }

    private List<String> authMethodTypes() {
        Object raw = authSessionAppService.methodsView().get("methods");
        if (!(raw instanceof List<?> list)) {
            return List.of("password");
        }
        List<String> types = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object type = map.get("type");
                if (type != null) {
                    types.add(String.valueOf(type));
                }
            }
        }
        return types.isEmpty() ? List.of("password") : types;
    }

    private Map<String, Object> sessionView(PluginPrincipal principal, List<String> permissions) {
        Map<String, Object> session = new LinkedHashMap<>();
        if (principal == null || principal.userId() == null) {
            session.put("authenticated", false);
            return session;
        }
        Map<String, Object> sessionInfo = authSessionAppService.sessionView(principal);
        session.put("authenticated", true);
        session.put("user", sessionInfo.get("user"));
        session.put("permissions", permissions);
        return session;
    }

    /**
     * §3.2 NavNode 树：root=tab；非 root 带 pageCode=page、否则 group。
     * 引用不可见/不存在页面的节点剪掉；裁剪后无 pageCode 且无后代的 group/tab 一并剪掉。
     */
    private Map<String, Object> navigationView(List<LauncherPage> visiblePages, Map<String, LauncherPage> byCode) {
        YmclLauncherChrome chrome = chromeAppService.loadForPages(visiblePages);
        List<Map<String, Object>> tabs = new ArrayList<>();
        List<YmclNavNode> tree = chrome.navTree() == null ? List.of() : chrome.navTree();
        int order = 0;
        for (YmclNavNode node : tree) {
            Map<String, Object> item = navNodeView(node, byCode, true, order);
            if (item != null) {
                tabs.add(item);
                order++;
            }
        }
        Map<String, Object> navigation = new LinkedHashMap<>();
        navigation.put("mode", "tree");
        navigation.put("tabs", tabs);
        return navigation;
    }

    private Map<String, Object> navNodeView(YmclNavNode node, Map<String, LauncherPage> byCode, boolean root, int order) {
        if (node == null || !node.visible()) {
            return null;
        }
        String pageCode = text(node.pageCode());
        LauncherPage page = pageCode.isBlank() ? null : byCode.get(pageCode);
        if (!pageCode.isBlank() && page == null) {
            // 引用的页面被权限裁剪或 provider 已卸载
            return null;
        }
        List<Map<String, Object>> children = new ArrayList<>();
        int childOrder = 0;
        for (YmclNavNode child : node.childNodes() == null ? List.<YmclNavNode>of() : node.childNodes()) {
            Map<String, Object> item = navNodeView(child, byCode, false, childOrder);
            if (item != null) {
                children.add(item);
                childOrder++;
            }
        }
        if (page == null && children.isEmpty()) {
            return null;
        }
        String title = text(node.title());
        if (title.isBlank() && page != null) {
            title = text(page.title());
        }
        String icon = text(node.icon());
        if (icon.isBlank() && page != null) {
            icon = text(page.icon());
        }
        if (icon.isBlank()) {
            icon = root ? "i-ri:planet-line" : "i-ri:file-line";
        }
        String kind = root ? "tab" : (page != null ? "page" : "group");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", text(node.code()));
        item.put("kind", kind);
        item.put("title", title);
        item.put("icon", icon);
        item.put("order", order);
        item.put("pageCode", pageCode);
        item.put("path", page != null ? pagePath(page) : firstPath(children));
        if (!children.isEmpty()) {
            item.put("children", children);
        }
        return item;
    }

    private Map<String, Object> pageView(LauncherPage page) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("code", page.code());
        view.put("title", text(page.title()));
        view.put("icon", text(page.icon()));
        view.put("path", pagePath(page));
        view.put("providerCode", text(page.providerCode()));
        Map<String, Object> viewSpec = new LinkedHashMap<>();
        viewSpec.put("renderer", text(page.type()));
        Map<String, Object> props = new LinkedHashMap<>(page.props());
        if ("extension".equals(text(page.type())) || "extension-type".equals(text(page.type()))) {
            props.putIfAbsent("extensionPackageId", text(page.extensionPackageId()));
        }
        viewSpec.put("props", props);
        view.put("view", viewSpec);
        view.put("dataSourceCode", text(page.dataSourceCode()));
        view.put("requiresPermission", text(page.requiresPermission()));
        return view;
    }

    private List<Map<String, Object>> dataSourceViews(List<LauncherPage> visiblePages) {
        Set<String> visiblePageCodes = new LinkedHashSet<>();
        for (LauncherPage page : visiblePages) {
            visiblePageCodes.add(page.code());
        }
        Map<String, String> providerBySource = aggregator.dataSourceProviders();
        List<Map<String, Object>> result = new ArrayList<>();
        for (LauncherDataSource source : aggregator.aggregateDataSources()) {
            String pageCode = text(source.pageCode());
            if (!pageCode.isEmpty() && !visiblePageCodes.contains(pageCode)) {
                continue;
            }
            String providerCode = text(providerBySource.get(source.code()));
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("code", source.code());
            view.put("title", text(source.title()));
            view.put("schemaVersion", source.schemaVersion());
            view.put("pageCode", pageCode);
            view.put("providerCode", providerCode);
            view.put("endpoint", "/api/plugins/launcher-adapter/v2/data/" + providerCode + "/" + source.code());
            result.add(view);
        }
        return result;
    }

    /**
     * §3.2：path 由 adapter 统一下发；provider 未指定时回落 {@code /domain/{pageCode}}，YMCL 不自行造路径。
     */
    private String pagePath(LauncherPage page) {
        String path = text(page.path());
        return path.isEmpty() ? "/domain/" + page.code() : path;
    }

    private String firstPath(List<Map<String, Object>> children) {
        for (Map<String, Object> child : children) {
            String path = text(String.valueOf(child.getOrDefault("path", "")));
            if (!path.isBlank()) {
                return path;
            }
        }
        return "";
    }

    private boolean pageAllowed(LauncherPage page, List<String> permissions) {
        String required = page.requiresPermission();
        if (required == null || required.isBlank()) {
            return true;
        }
        return permissions.contains("*") || permissions.contains(required);
    }

    private List<String> permissionsOf(PluginPrincipal principal) {
        if (principal == null || principal.userId() == null || principal.permissions() == null) {
            return List.of();
        }
        return principal.permissions();
    }

    private Map<String, LauncherPage> pagesByCode(List<LauncherPage> pages) {
        Map<String, LauncherPage> byCode = new LinkedHashMap<>();
        for (LauncherPage page : pages) {
            if (page != null && page.code() != null && !page.code().isBlank()) {
                byCode.put(page.code(), page);
            }
        }
        return byCode;
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}
