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
        Boolean loginWarmup,
        /** aTrust 客户端唤起链接（自定义协议）；null 表示不修改。 */
        String atrustWakeUrl,
        /** aTrust 下载/帮助页（http/https）；null 表示不修改。 */
        String atrustGuideUrl
) {
}
