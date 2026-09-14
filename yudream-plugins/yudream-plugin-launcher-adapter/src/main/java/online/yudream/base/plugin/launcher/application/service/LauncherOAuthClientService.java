package online.yudream.base.plugin.launcher.application.service;

import online.yudream.base.plugin.launcher.api.LauncherProvider;
import online.yudream.base.plugin.launcher.api.YmclPublicOAuth;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.security.PluginOAuthClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 启用时向宿主幂等登记 YMCL 公开 OAuth 客户端，并把 clientId / 已登记范围写入 manifest。
 * 其它插件的范围由各 LauncherProvider 在启用时通过 {@link YmclPublicOAuth#contribute} 合并，不在此硬编码。
 */
public class LauncherOAuthClientService {

    public static final String CLIENT_ID = YmclPublicOAuth.CLIENT_ID;
    public static final String CLIENT_NAME = YmclPublicOAuth.CLIENT_NAME;
    public static final String REDIRECT_URI = YmclPublicOAuth.REDIRECT_URI;

    private final PluginContext context;

    public LauncherOAuthClientService(PluginContext context) {
        this.context = context;
    }

    public Optional<PluginOAuthClient> ensureRegistered() {
        List<String> scopes = new ArrayList<>();
        scopes.addAll(YmclPublicOAuth.IDENTITY_SCOPES);
        scopes.addAll(YmclPublicOAuth.adapterScopes());
        YmclPublicOAuth.contribute(context, scopes);
        mergeRegisteredProviders();
        return context.oauth().findClient(CLIENT_ID);
    }

    public void mergeRegisteredProviders() {
        Set<String> extra = new LinkedHashSet<>();
        for (LauncherProvider provider : context.extensions(LauncherProvider.class)) {
            try {
                extra.addAll(YmclPublicOAuth.normalize(provider.oauthScopes()));
            } catch (RuntimeException ignored) {
                // 单个 provider 异常不影响 OAuth 登记
            }
        }
        if (!extra.isEmpty()) {
            YmclPublicOAuth.contribute(context, extra);
        }
    }

    public String publishedClientId() {
        return context.oauth().findClient(CLIENT_ID)
                .map(PluginOAuthClient::clientId)
                .filter(id -> id != null && !id.isBlank())
                .orElse(CLIENT_ID);
    }

    /**
     * YMCL 公开客户端当前是否已在宿主登记（非变更性检查，用于 v2 methods 决定是否广告 oauth-web）。
     */
    public boolean clientRegistered() {
        return context.oauth().findClient(CLIENT_ID).isPresent();
    }

    public List<String> publishedScopes() {
        return context.oauth().findClient(CLIENT_ID)
                .map(PluginOAuthClient::scopes)
                .filter(scopes -> scopes != null && !scopes.isEmpty())
                .orElseGet(this::baseScopes);
    }

    private List<String> baseScopes() {
        List<String> scopes = new ArrayList<>();
        scopes.addAll(YmclPublicOAuth.IDENTITY_SCOPES);
        scopes.addAll(YmclPublicOAuth.adapterScopes());
        return List.copyOf(scopes);
    }
}
