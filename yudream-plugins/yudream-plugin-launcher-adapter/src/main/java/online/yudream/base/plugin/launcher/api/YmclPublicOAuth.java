package online.yudream.base.plugin.launcher.api;

import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.security.PluginOAuthPublicClientSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * YMCL 公开 OAuth 客户端约定。贡献插件在 onEnable 通过本类把自身权限码合并进 ymcl 客户端，
 * 不要在适配器里硬编码其它插件的 scope。
 */
public final class YmclPublicOAuth {

    public static final String CLIENT_ID = "ymcl";
    public static final String CLIENT_NAME = "YMCL";
    public static final String REDIRECT_URI = "ymcl://auth/callback";
    public static final List<String> IDENTITY_SCOPES = List.of("openid", "profile");

    private YmclPublicOAuth() {
    }

    public static List<String> adapterScopes() {
        return List.of(
                "plugin:launcher-adapter:view",
                "plugin:launcher-adapter:manage"
        );
    }

    public static List<String> pluginScopes(String pluginCode) {
        if (pluginCode == null || pluginCode.isBlank()) {
            return List.of();
        }
        String code = pluginCode.trim();
        return List.of("plugin:" + code + ":view", "plugin:" + code + ":manage");
    }

    /**
     * 幂等合并 scope 到宿主 ymcl 公开客户端。已存在同 clientId 时宿主会并集回调与范围。
     */
    public static void contribute(PluginContext context, Collection<String> scopes) {
        if (context == null) {
            return;
        }
        List<String> normalized = normalize(scopes);
        if (normalized.isEmpty()) {
            return;
        }
        context.oauth().ensurePublicClient(new PluginOAuthPublicClientSpec(
                CLIENT_ID,
                CLIENT_NAME,
                List.of(REDIRECT_URI),
                normalized
        ));
    }

    public static void contribute(PluginContext context, LauncherProvider provider) {
        if (provider == null) {
            return;
        }
        contribute(context, provider.oauthScopes());
    }

    public static List<String> normalize(Collection<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope == null) {
                continue;
            }
            String trimmed = scope.trim();
            if (!trimmed.isEmpty()) {
                unique.add(trimmed);
            }
        }
        return new ArrayList<>(unique);
    }
}
