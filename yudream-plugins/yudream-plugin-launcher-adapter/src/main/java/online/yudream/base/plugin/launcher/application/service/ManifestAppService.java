package online.yudream.base.plugin.launcher.application.service;

import online.yudream.base.plugin.launcher.api.LauncherDataSource;
import online.yudream.base.plugin.launcher.api.LauncherPage;
import online.yudream.base.plugin.launcher.api.LauncherProvider;
import online.yudream.base.plugin.launcher.bootstrap.LauncherAdapterPlugin;
import online.yudream.base.plugin.spi.core.PluginContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构建启动器侧 manifest。启动器是协议解释器：manifest 是唯一契约源。
 */
public class ManifestAppService {

    public static final String MANIFEST_VERSION = "1";

    /**
     * v2 规范渲染器名 → v1 客户端认识的旧 type。只作用于 v1 manifest 视图，聚合层始终用规范名。
     */
    private static final Map<String, String> V1_TYPE_ALIASES = Map.of("pack-catalog", "pack-list");

    private final PluginContext context;
    private final LauncherProviderAggregator aggregator;
    private final PackAppService packAppService;
    private final YmclChromeAppService chromeAppService;

    public ManifestAppService(
            PluginContext context,
            LauncherProviderAggregator aggregator,
            PackAppService packAppService,
            YmclChromeAppService chromeAppService
    ) {
        this.context = context;
        this.aggregator = aggregator;
        this.packAppService = packAppService;
        this.chromeAppService = chromeAppService;
    }

    public Map<String, Object> buildManifest() {
        List<LauncherPage> pages = aggregator.aggregatePages();
        List<LauncherDataSource> dataSources = aggregator.aggregateDataSources();
        List<Map<String, Object>> packs = packAppService.listPacks().stream()
                .map(p -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", p.id());
                    item.put("name", p.name());
                    item.put("description", p.description() == null ? "" : p.description());
                    item.put("icon", p.icon() == null ? "" : p.icon());
                    item.put("recommendedVersion", p.recommendedVersionId() == null ? "" : p.recommendedVersionId());
                    item.put("retainedVersions", p.retainedVersionIds());
                    item.put("createdAt", String.valueOf(p.createdAt()));
                    item.put("updatedAt", String.valueOf(p.updatedAt()));
                    return item;
                })
                .toList();

        Map<String, Object> theme = chromeAppService.themeView();
        String displayName = String.valueOf(theme.getOrDefault("displayName", "")).trim();
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
        manifest.put("capabilities", Map.of(
                "packs", true,
                "pages", !pages.isEmpty(),
                "providers", context.extensions(LauncherProvider.class).size()
        ));
        manifest.put("pageVisibility", Map.of(
                "discover", "replaced",
                "downloads", "domain",
                "settings.intelligence", "domain",
                "instances.addImport", "advanced"
        ));
        LauncherOAuthClientService oauthService = new LauncherOAuthClientService(context);
        oauthService.mergeRegisteredProviders();
        Map<String, Object> oauth = new LinkedHashMap<>();
        oauth.put("clientId", oauthService.publishedClientId());
        oauth.put("scopes", oauthService.publishedScopes());
        manifest.put("oauth", oauth);
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("manifest", "/api/plugins/launcher-adapter/v1/manifest");
        endpoints.put("packs", "/api/plugins/launcher-adapter/v1/packs");
        endpoints.put("pageData", "/api/plugins/launcher-adapter/v1/pages/{providerCode}/{dataSourceCode}");
        endpoints.put("authExchange", "/api/plugins/launcher-adapter/v1/auth/ygg");
        endpoints.put("authProfiles", "/api/plugins/launcher-adapter/v1/auth/ygg/profiles");
        endpoints.put("yggdrasil", "/api/plugins/authlib-injector");
        endpoints.put("rbacDepts", "/api/user/me/depts");
        endpoints.put("rbacRoles", "/api/user/me/roles");
        endpoints.put("rbacContext", "/api/user/me/context");
        endpoints.put("rbacSwitchDept", "/api/user/me/switch-dept");
        endpoints.put("rbacSwitchRole", "/api/user/me/switch-role");
        endpoints.put("rbacPermissions", "/api/user/permissions");
        manifest.put("endpoints", endpoints);
        manifest.put("pages", pages.stream().map(ManifestAppService::v1PageView).toList());
        manifest.put("navigation", chromeAppService.navigationView(pages));
        manifest.put("theme", theme);
        manifest.put("dataSources", dataSources);
        manifest.put("packs", packs);
        return manifest;
    }

    /**
     * v1 页面视图：字段集与 v1 记录序列化保持一致（观测期老客户端不感知 props/requiresPermission），
     * type 经 {@link #V1_TYPE_ALIASES} 翻回旧名。
     */
    private static Map<String, Object> v1PageView(LauncherPage page) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("code", page.code());
        view.put("title", page.title());
        view.put("icon", page.icon());
        view.put("navOrder", page.navOrder());
        view.put("type", V1_TYPE_ALIASES.getOrDefault(page.type(), page.type()));
        view.put("path", page.path());
        view.put("dataSourceCode", page.dataSourceCode());
        view.put("extensionPackageId", page.extensionPackageId());
        view.put("providerCode", page.providerCode());
        return view;
    }
}
