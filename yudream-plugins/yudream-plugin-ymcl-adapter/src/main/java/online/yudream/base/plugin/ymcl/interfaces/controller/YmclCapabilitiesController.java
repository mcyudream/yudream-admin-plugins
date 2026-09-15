package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YAP §6.3 GET /v1/capabilities：匿名可达的域发现端点。
 *
 * 返回字段与启动器侧 Rust serde 结构（YmclCapabilities）逐字对应
 * （snake_case），`join domain` 联调即依赖此契约。
 */
public class YmclCapabilitiesController {

    private static final int PROTOCOL_VERSION = 1;

    private final String adapterVersion;

    public YmclCapabilitiesController(String pluginCode, String adapterVersion) {
        this.adapterVersion = adapterVersion;
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/capabilities", wrapResult = false)
    public PluginHttpResponse capabilities(PluginHttpRequest request) {
        String origin = resolveOrigin(request);
        return PluginHttpResponse.rawJson(200, buildCapabilities(origin));
    }

    /** Payload shared with the manifest endpoint's domain section. */
    Map<String, Object> buildCapabilities(String origin) {
        Map<String, Object> domain = new LinkedHashMap<>();
        domain.put("name", origin);
        domain.put("description", null);
        domain.put("logo_url", null);

        List<Map<String, Object>> methods = new ArrayList<>();
        methods.add(passwordMethod(origin));
        methods.add(oauthWebMethod(origin));

        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("required", true);
        auth.put("methods", methods);

        // The MIP distribution face (`mip` node + capability, YAP §7) is
        // intentionally NOT declared yet: the ingest/object endpoints ship
        // in a later release. Declaring it now would make launch-time
        // update checks hit 404s. The launcher degrades gracefully while
        // the capability is absent.
        List<String> capabilities = new ArrayList<>();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("protocol_version", 1);
        payload.put("adapter_version", adapterVersion);
        payload.put("domain", domain);
        payload.put("capabilities", capabilities);
        payload.put("auth", auth);
        return payload;
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
        method.put("authorize_url", origin + "/api/oauth/authorize");
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
