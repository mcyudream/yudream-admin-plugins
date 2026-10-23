package online.yudream.base.plugin.cas.interfaces.request;

public record SettingsSaveRequest(
        Boolean enabled,
        String protocol,
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
        String clientSecret,
        String scopes,
        String callbackUrl,
        /** 登录前是否走本站预热页（先跨站请求拿网关会话 cookie 再跳认证地址）；null 表示不修改。 */
        Boolean loginWarmup
) {
}
