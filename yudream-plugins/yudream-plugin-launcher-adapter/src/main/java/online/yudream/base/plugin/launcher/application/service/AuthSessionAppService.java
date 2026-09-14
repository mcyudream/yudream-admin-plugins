package online.yudream.base.plugin.launcher.application.service;

import online.yudream.base.plugin.launcher.api.YmclPublicOAuth;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 协议 v2 认证方式发现与规范化会话（§2）。
 * <p>
 * 一期零宿主改动：账密登录、刷新、OAuth token 交换均由 YMCL 直连宿主公开端点，
 * 适配器只负责广告可用方式（methods）与把宿主已解析的 principal 规范化为会话视图（session）。
 */
public class AuthSessionAppService {

    public static final String LOGIN_ENDPOINT = "/api/user/login";
    public static final String REFRESH_ENDPOINT = "/api/user/token/refresh";
    public static final String REGISTER_ENDPOINT = "/api/user/register";
    public static final String TOKEN_ENDPOINT = "/api/oauth/token";

    private final PluginContext context;
    private final LauncherOAuthClientService oauthClientService;

    public AuthSessionAppService(PluginContext context, LauncherOAuthClientService oauthClientService) {
        this.context = context;
        this.oauthClientService = oauthClientService;
    }

    /**
     * 认证方式发现视图。password 恒在（宿主账密体系是基线能力）；
     * oauth-web 仅当 YMCL 公开客户端已在宿主登记；device-code / external 为二期，不广告。
     */
    public Map<String, Object> methodsView() {
        List<Map<String, Object>> methods = new ArrayList<>();
        Map<String, Object> password = new LinkedHashMap<>();
        password.put("type", "password");
        password.put("title", "账号密码登录");
        password.put("endpoint", LOGIN_ENDPOINT);
        password.put("refreshEndpoint", REFRESH_ENDPOINT);
        password.put("registerEndpoint", REGISTER_ENDPOINT);
        methods.add(password);
        if (oauthClientService.clientRegistered()) {
            Map<String, Object> oauth = new LinkedHashMap<>();
            oauth.put("type", "oauth-web");
            oauth.put("title", "网页授权登录");
            oauth.put("authorizeUrl", buildAuthorizeUrl());
            oauth.put("tokenEndpoint", TOKEN_ENDPOINT);
            methods.add(oauth);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("methods", methods);
        // 宿主注册公开开放（/api/user/register），无开关
        view.put("registrationEnabled", true);
        return view;
    }

    /**
     * 规范化会话视图：宿主分发时已把令牌解析为 principal（sa-token=全量 RBAC，OAuth=已授权 scope），
     * 此处只补用户资料并透传权限。token/refreshToken/expiresIn 由 YMCL 在登录响应中持有并本地合并。
     */
    public Map<String, Object> sessionView(PluginPrincipal principal) {
        Long userId = principal.userId();
        Optional<PluginUserProfile> profile = context.framework().users().findById(userId);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", String.valueOf(userId));
        user.put("username", profile.map(PluginUserProfile::username).orElse(""));
        user.put("nickname", profile.map(PluginUserProfile::nickname).orElse(""));
        user.put("avatar", profile.map(PluginUserProfile::avatar).orElse(""));
        user.put("email", profile.map(PluginUserProfile::email).orElse(""));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("user", user);
        List<String> permissions = principal.permissions();
        view.put("permissions", permissions == null ? List.of() : permissions);
        view.put("tokenName", "Authorization");
        return view;
    }

    private String buildAuthorizeUrl() {
        String redirect = URLEncoder.encode(YmclPublicOAuth.REDIRECT_URI, StandardCharsets.UTF_8);
        String scope = URLEncoder.encode(String.join(" ", oauthClientService.publishedScopes()), StandardCharsets.UTF_8);
        return "/oauth/authorize?response_type=code&client_id=" + oauthClientService.publishedClientId()
                + "&redirect_uri=" + redirect
                + "&scope=" + scope
                + "&state={state}";
    }
}
