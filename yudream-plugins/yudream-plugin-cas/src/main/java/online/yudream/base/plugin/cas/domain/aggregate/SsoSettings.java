package online.yudream.base.plugin.cas.domain.aggregate;

import online.yudream.base.plugin.cas.domain.enumerate.SsoProtocol;

/**
 * SSO 全局设置（CAS / OIDC / SSO 中转）。clientSecret 不进入本聚合，由密钥库单独保管。
 */
public final class SsoSettings {

    /** 认证服务根地址不再预填任何站点：留空即未就绪，避免静默指向错误主机。 */
    public static final String DEFAULT_CAS_BASE_URL = "";
    public static final String DEFAULT_LOGIN_PATH = "/authserver/login";
    public static final String DEFAULT_VALIDATE_PATH = "/cas/p3/serviceValidate";
    public static final String DEFAULT_OIDC_AUTHORIZE_PATH = "/authserver/oidc/authorize";
    public static final String DEFAULT_OIDC_TOKEN_PATH = "/authserver/oidc/accessToken";
    public static final String DEFAULT_OIDC_USERINFO_PATH = "/authserver/oidc/profile";
    public static final String DEFAULT_OIDC_JWKS_PATH = "/authserver/oidc/jwks";
    public static final String DEFAULT_OIDC_REGISTER_PATH = "/authserver/oidc/register";
    public static final String DEFAULT_OIDC_ISSUER = "";
    public static final String DEFAULT_SCOPES = "openid profile email";
    public static final String DEFAULT_DISPLAY_NAME = "统一身份认证";
    public static final String DEFAULT_ICON = "i-ri:graduation-cap-line";

    /** SSO 中转（BRIDGE）协议的固定端点路径：由中转服务实现约定，不可配置。 */
    public static final String BRIDGE_START_PATH = "/sso/start";
    public static final String BRIDGE_EXCHANGE_PATH = "/sso/exchange";
    public static final String BRIDGE_HEALTH_PATH = "/sso/health";

    /**
     * 登录前预热页（站点自身源上的中转页）：先发一次跨站请求把前置网关的会话 cookie（如 
oute）
     * 种下来，再跳真正的认证地址。部分网关首次不带该 cookie 的请求会直接 404，靠这一步规避。
     * 路径必须与 CasSsoAdminController 的 /public/warmup 及插件 code(cas) 一致。
     */
    public static final String WARMUP_PATH = "/api/plugins/cas/public/warmup";

    private final boolean enabled;
    private final SsoProtocol protocol;
    private final String displayName;
    private final String icon;
    private final String casBaseUrl;
    private final String loginPath;
    private final String validatePath;
    private final String oidcIssuer;
    private final String oidcAuthorizePath;
    private final String oidcTokenPath;
    private final String oidcUserinfoPath;
    private final String oidcJwksPath;
    private final String oidcRegisterPath;
    private final String clientId;
    private final boolean clientSecretConfigured;
    private final String scopes;
    private final String callbackUrl;
    /** 登录前是否走预热页（默认开；网关不需要时可以关掉以少一次请求）。 */
    private final boolean loginWarmup;
    /** aTrust 客户端唤起链接（部署方自定义协议，如 atrustclient://…）；空=预热页不展示 aTrust 引导。 */
    private final String atrustWakeUrl;
    /** aTrust 下载/帮助页（http/https），引导页新窗口打开；空=不展示。 */
    private final String atrustGuideUrl;
    /** 校外接入引导页的自定义提示文案（可换行）；空=不展示。 */
    private final String bridgeNotice;

    public SsoSettings(
            boolean enabled,
            SsoProtocol protocol,
            String displayName,
            String icon,
            String casBaseUrl,
            String loginPath,
            String validatePath,
            String oidcIssuer,
            String oidcAuthorizePath,
            String oidcTokenPath,
            String oidcUserinfoPath,
            String oidcJwksPath,
            String oidcRegisterPath,
            String clientId,
            boolean clientSecretConfigured,
            String scopes,
            String callbackUrl,
            boolean loginWarmup,
            String atrustWakeUrl,
            String atrustGuideUrl,
            String bridgeNotice
    ) {
        this.enabled = enabled;
        this.protocol = protocol == null ? SsoProtocol.CAS : protocol;
        this.displayName = blankToDefault(displayName, DEFAULT_DISPLAY_NAME);
        this.icon = blankToDefault(icon, DEFAULT_ICON);
        this.casBaseUrl = trimTrailingSlash(blankToDefault(casBaseUrl, DEFAULT_CAS_BASE_URL));
        this.loginPath = normalizePath(blankToDefault(loginPath, DEFAULT_LOGIN_PATH));
        this.validatePath = normalizePath(blankToDefault(validatePath, DEFAULT_VALIDATE_PATH));
        this.oidcIssuer = blankToDefault(oidcIssuer, DEFAULT_OIDC_ISSUER);
        this.oidcAuthorizePath = normalizePath(blankToDefault(oidcAuthorizePath, DEFAULT_OIDC_AUTHORIZE_PATH));
        this.oidcTokenPath = normalizePath(blankToDefault(oidcTokenPath, DEFAULT_OIDC_TOKEN_PATH));
        this.oidcUserinfoPath = normalizePath(blankToDefault(oidcUserinfoPath, DEFAULT_OIDC_USERINFO_PATH));
        this.oidcJwksPath = normalizePath(blankToDefault(oidcJwksPath, DEFAULT_OIDC_JWKS_PATH));
        this.oidcRegisterPath = normalizePath(blankToDefault(oidcRegisterPath, DEFAULT_OIDC_REGISTER_PATH));
        this.clientId = trimToEmpty(clientId);
        this.clientSecretConfigured = clientSecretConfigured;
        this.scopes = blankToDefault(scopes, DEFAULT_SCOPES);
        this.callbackUrl = trimToEmpty(callbackUrl);
        this.loginWarmup = loginWarmup;
        this.atrustWakeUrl = trimToEmpty(atrustWakeUrl);
        this.atrustGuideUrl = trimToEmpty(atrustGuideUrl);
        this.bridgeNotice = trimToEmpty(bridgeNotice);
    }

    public static SsoSettings defaults() {
        return new SsoSettings(
                false,
                SsoProtocol.CAS,
                DEFAULT_DISPLAY_NAME,
                DEFAULT_ICON,
                DEFAULT_CAS_BASE_URL,
                DEFAULT_LOGIN_PATH,
                DEFAULT_VALIDATE_PATH,
                DEFAULT_OIDC_ISSUER,
                DEFAULT_OIDC_AUTHORIZE_PATH,
                DEFAULT_OIDC_TOKEN_PATH,
                DEFAULT_OIDC_USERINFO_PATH,
                DEFAULT_OIDC_JWKS_PATH,
                DEFAULT_OIDC_REGISTER_PATH,
                "",
                false,
                DEFAULT_SCOPES,
                "",
                true,
                "",
                "",
                ""
        );
    }

    public SsoSettings withClientSecretConfigured(boolean configured) {
        return new SsoSettings(
                enabled, protocol, displayName, icon, casBaseUrl, loginPath, validatePath,
                oidcIssuer, oidcAuthorizePath, oidcTokenPath, oidcUserinfoPath, oidcJwksPath,
                oidcRegisterPath, clientId, configured, scopes, callbackUrl, loginWarmup,
                atrustWakeUrl, atrustGuideUrl, bridgeNotice
        );
    }

    public boolean ready() {
        if (blank(callbackUrl) || blank(casBaseUrl)) {
            return false;
        }
        if (protocol == SsoProtocol.OIDC || protocol == SsoProtocol.BRIDGE) {
            return !blank(clientId) && clientSecretConfigured;
        }
        return true;
    }

    public boolean loginEnabled() {
        return enabled && ready();
    }

    public String absolute(String path) {
        return casBaseUrl + normalizePath(path);
    }

    public String loginUrl() {
        return absolute(loginPath);
    }

    public String validateUrl() {
        return absolute(validatePath);
    }

    public String oidcAuthorizeUrl() {
        return absolute(oidcAuthorizePath);
    }

    public String oidcTokenUrl() {
        return absolute(oidcTokenPath);
    }

    public String oidcUserinfoUrl() {
        return absolute(oidcUserinfoPath);
    }

    public String oidcJwksUrl() {
        return absolute(oidcJwksPath);
    }

    public String oidcRegisterUrl() {
        return absolute(oidcRegisterPath);
    }

    /** SSO 中转授权入口：带引导页（校外客户端提示），redirect 为本站前端回调路由，state 由宿主签发。 */
    public String bridgeStartUrl(String state) {
        return casBaseUrl + BRIDGE_START_PATH
                + "?client=" + encode(clientId)
                + "&redirect=" + encode(callbackUrl)
                + "&state=" + encode(state);
    }

    public String bridgeExchangeUrl() {
        return casBaseUrl + BRIDGE_EXCHANGE_PATH;
    }

    public String bridgeHealthUrl() {
        return casBaseUrl + BRIDGE_HEALTH_PATH;
    }

    /** CAS service URL：回调地址附加 state，CAS 会原样带回并追加 ticket。 */
    public String casServiceUrl(String state) {
        String separator = callbackUrl.contains("?") ? "&" : "?";
        return callbackUrl + separator + "state=" + state;
    }

    public boolean enabled() {
        return enabled;
    }

    public SsoProtocol protocol() {
        return protocol;
    }

    public String displayName() {
        return displayName;
    }

    public String icon() {
        return icon;
    }

    public String casBaseUrl() {
        return casBaseUrl;
    }

    public String loginPath() {
        return loginPath;
    }

    public String validatePath() {
        return validatePath;
    }

    public String oidcIssuer() {
        return oidcIssuer;
    }

    public String oidcAuthorizePath() {
        return oidcAuthorizePath;
    }

    public String oidcTokenPath() {
        return oidcTokenPath;
    }

    public String oidcUserinfoPath() {
        return oidcUserinfoPath;
    }

    public String oidcJwksPath() {
        return oidcJwksPath;
    }

    public String oidcRegisterPath() {
        return oidcRegisterPath;
    }

    public String clientId() {
        return clientId;
    }

    public boolean clientSecretConfigured() {
        return clientSecretConfigured;
    }

    public String scopes() {
        return scopes;
    }

    public String callbackUrl() {
        return callbackUrl;
    }

    /** 登录前是否走预热页。 */
    public boolean loginWarmup() {
        return loginWarmup;
    }

    public String atrustWakeUrl() {
        return atrustWakeUrl;
    }

    public String atrustGuideUrl() {
        return atrustGuideUrl;
    }

    public String bridgeNotice() {
        return bridgeNotice;
    }

    /** 校外接入引导是否已配置（唤醒链接或自定义提示任一）：配置后预热页先展示引导，而非直接自动跳转。 */
    public boolean atrustGuideEnabled() {
        return !blank(atrustWakeUrl) || !blank(bridgeNotice);
    }

    public SsoSettings withLoginWarmup(boolean warmup) {
        return new SsoSettings(
                enabled, protocol, displayName, icon, casBaseUrl, loginPath, validatePath,
                oidcIssuer, oidcAuthorizePath, oidcTokenPath, oidcUserinfoPath, oidcJwksPath,
                oidcRegisterPath, clientId, clientSecretConfigured, scopes, callbackUrl, warmup,
                atrustWakeUrl, atrustGuideUrl, bridgeNotice
        );
    }

    /**
     * 预热页地址：只带宿主签发的 state，真正的认证地址由服务端重建，
     * 因此这里不会成为可被外部利用的跳转（无任意 target 参数）。
     */
    public String warmupUrl(String state) {
        return WARMUP_PATH + "?state=" + encode(state == null ? "" : state);
    }

    private static String blankToDefault(String value, String defaultValue) {
        String trimmed = trimToEmpty(value);
        return trimmed.isEmpty() ? defaultValue : trimmed;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String normalizePath(String path) {
        String trimmed = trimToEmpty(path);
        if (trimmed.isEmpty()) {
            return "/";
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }
}
