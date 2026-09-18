package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * YAP §6.3 GET /v1/capabilities：匿名可达的域发现端点。
 *
 * 返回字段与启动器侧 Rust serde 结构（YmclCapabilities）逐字对应
 * （snake_case），`join domain` 联调即依赖此契约。
 */
public class YmclCapabilitiesController {

    private static final int PROTOCOL_VERSION = 1;
    private static final String DOMAIN_CONFIG_COLLECTION = "ymcl_config";
    private static final String DOMAIN_CONFIG_DOC = "domain";
    private static final String THEME_CONFIG_DOC = "theme";

    private final String adapterVersion;
    private final PluginDocumentStore documents;
    /** 宿主站点名称（settings.siteName），域显示名称未配置时的回退。 */
    private final Supplier<String> siteName;
    /** 宿主配置的站点地址（APP_WEB_URL），自报 origin 的首选来源。 */
    private final Supplier<String> configuredOrigin;
    /** 皮肤站插件是否可用（懒求值），决定是否宣告 skins 能力（YAP §6.11）。 */
    private final Supplier<Boolean> skinFaceAvailable;

    public YmclCapabilitiesController(String pluginCode, String adapterVersion, PluginDocumentStore documents,
            Supplier<String> siteName, Supplier<String> configuredOrigin, Supplier<Boolean> skinFaceAvailable) {
        this.adapterVersion = adapterVersion;
        this.documents = documents;
        this.siteName = siteName;
        this.configuredOrigin = configuredOrigin;
        this.skinFaceAvailable = skinFaceAvailable;
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/capabilities", wrapResult = false)
    public PluginHttpResponse capabilities(PluginHttpRequest request) {
        return PluginHttpResponse.rawJson(200, buildCapabilities(resolveEffectiveOrigin(request)));
    }

    /** 自报 origin：宿主配置的 APP_WEB_URL 优先（它就是站点地址），否则按代理头重构。 */
    String resolveEffectiveOrigin(PluginHttpRequest request) {
        String configured = configuredOrigin.get();
        if (configured != null && !configured.isBlank()) {
            return configured.trim().replaceAll("/+$", "");
        }
        return resolveOrigin(request);
    }

    /** Payload shared with the manifest endpoint's domain section. */
    Map<String, Object> buildCapabilities(String origin) {
        Map<String, Object> domain = domainSection(origin);

        List<Map<String, Object>> methods = new ArrayList<>();
        methods.add(passwordMethod(origin));
        methods.add(oauthWebMethod(origin));
        methods.add(externalMethod(origin));

        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("required", true);
        auth.put("methods", methods);
        auth.put("registration", registrationSection(origin));

        List<String> capabilities = new ArrayList<>();
        // 主题下发（YAP §6.9）：管理员配置了 ThemeProfile（/v1/chrome/theme）
        // 时宣告 theme 能力；主题本身随 manifest.theme 传递。
        if (!documents.findById(DOMAIN_CONFIG_COLLECTION, THEME_CONFIG_DOC).isEmpty()) {
            capabilities.add("theme");
        }
        // 皮肤衣柜能力（YAP §6.11）：皮肤站插件可用时才宣告，未启用时启动器
        // 保持只读回退， wardrobe 端点自身也统一降级 501。
        if (Boolean.TRUE.equals(skinFaceAvailable.get())) {
            capabilities.add("skins");
        }
        // MIP 分发面（YAP §7）：ingest/delta/objects/versions/绑定端点均已随本
        // 插件发布，无条件宣告；启动器据此启用启动前更新检查与发布控制台，
        // 未托管实例的更新检查在启动器侧优雅跳过（无 .pack-state.json 即 no-op）。
        capabilities.add("mip");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("protocol_version", 1);
        payload.put("adapter_version", adapterVersion);
        payload.put("domain", domain);
        payload.put("capabilities", capabilities);
        payload.put("mip", Map.of("base_url", origin + "/api/plugins/ymcl-adapter/mip"));
        payload.put("auth", auth);
        return payload;
    }

    /**
     * Domain identity section: the admin-configured display name takes
     * precedence, then the host site name (settings.siteName, what the site
     * already calls itself); the reconstructed origin is the last-resort
     * fallback so a fresh deployment joins by address out of the box. The
     * launcher and the join card both label the domain with this name — a
     * bare address must never surface in their UIs.
     */
    Map<String, Object> domainSection(String origin) {
        Optional<Map<String, Object>> config = documents.findById(DOMAIN_CONFIG_COLLECTION, DOMAIN_CONFIG_DOC);
        String name = config.map(doc -> str(doc.get("name"))).filter(value -> !value.isBlank())
                .or(() -> Optional.ofNullable(siteName.get()).filter(value -> !value.isBlank()))
                .orElse(origin);
        Map<String, Object> domain = new LinkedHashMap<>();
        domain.put("name", name);
        domain.put("description", config.map(doc -> str(doc.get("description"))).orElse(null));
        domain.put("logo_url", config.map(doc -> str(doc.get("logo_url"))).orElse(null));
        return domain;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static Map<String, Object> passwordMethod(String origin) {
        Map<String, Object> method = new LinkedHashMap<>();
        method.put("type", "password");
        method.put("endpoint", origin + "/api/user/login");
        return method;
    }

    static Map<String, Object> oauthWebMethod(String origin) {
        Map<String, Object> method = new LinkedHashMap<>();
        method.put("type", "oauth-web");
        // 浏览器侧授权入口是站点路由 /oauth/authorize（携带 Web 会话、未登录时
        // 引导登录）；/api/oauth/authorize 是裸 API，未登录只会返回 JSON 401。
        // 启动器只允许打开站点地址，绝不直接跳转后端。
        method.put("authorize_url", origin + "/oauth/authorize");
        method.put("token_url", origin + "/api/oauth/token");
        method.put("client_id", YmclAdapterPlugin.LAUNCHER_CLIENT_ID);
        List<String> scopes = new ArrayList<>();
        scopes.add("profile");
        scopes.add(YmclAdapterPlugin.VIEW_PERMISSION);
        scopes.add(YmclAdapterPlugin.PUBLISH_PERMISSION);
        scopes.add(YmclAdapterPlugin.DESIGN_PERMISSION);
        method.put("scopes", scopes);
        method.put("pkce", "S256");
        return method;
    }

    static Map<String, Object> externalMethod(String origin) {
        Map<String, Object> method = new LinkedHashMap<>();
        method.put("type", "external");
        method.put("title", "第三方账号登录");
        method.put("providers_endpoint", origin + "/api/plugins/ymcl-adapter/v1/auth/external/providers");
        return method;
    }

    static Map<String, Object> registrationSection(String origin) {
        Map<String, Object> registration = new LinkedHashMap<>();
        registration.put("enabled", true);
        registration.put("endpoint", origin + "/api/user/register");
        registration.put("verification_methods_endpoint",
                origin + "/api/user/register/verification-methods");
        return registration;
    }

    /**
     * Reconstructs the public origin from proxy/Host headers so returned
     * endpoint URLs are absolute and reachable from the launcher.
     */
    static String resolveOrigin(PluginHttpRequest request) {
        Map<String, List<String>> headers = request.headers();
        String scheme = firstHeader(headers, "X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) {
            scheme = "http";
        }
        String host = firstHeader(headers, "X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = firstHeader(headers, "Host");
        }
        if (host == null || host.isBlank()) {
            return scheme + "://localhost";
        }
        return scheme + "://" + host;
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        if (values == null || values.isEmpty()) {
            // Headers may arrive case-insensitively; scan as a fallback.
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                        && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }
        return values.get(0);
    }
}
