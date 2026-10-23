package online.yudream.base.plugin.cas.application.dto;

import online.yudream.base.plugin.cas.domain.aggregate.SsoSettings;
import online.yudream.base.plugin.cas.domain.enumerate.SsoProtocol;

public record SsoSettingsDto(
        boolean enabled,
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
        boolean clientSecretConfigured,
        String scopes,
        String callbackUrl,
        boolean loginWarmup,
        String atrustWakeUrl,
        String atrustGuideUrl,
        boolean ready
) {
    public static SsoSettingsDto from(SsoSettings settings) {
        return new SsoSettingsDto(
                settings.enabled(),
                settings.protocol().name(),
                settings.displayName(),
                settings.icon(),
                settings.casBaseUrl(),
                settings.loginPath(),
                settings.validatePath(),
                settings.oidcIssuer(),
                settings.oidcAuthorizePath(),
                settings.oidcTokenPath(),
                settings.oidcUserinfoPath(),
                settings.oidcJwksPath(),
                settings.oidcRegisterPath(),
                settings.clientId(),
                settings.clientSecretConfigured(),
                settings.scopes(),
                settings.callbackUrl(),
                settings.loginWarmup(),
                settings.atrustWakeUrl(),
                settings.atrustGuideUrl(),
                settings.ready()
        );
    }

    public SsoSettings toSettings(boolean clientSecretConfigured) {
        return new SsoSettings(
                enabled,
                SsoProtocol.from(protocol),
                displayName,
                icon,
                casBaseUrl,
                loginPath,
                validatePath,
                oidcIssuer,
                oidcAuthorizePath,
                oidcTokenPath,
                oidcUserinfoPath,
                oidcJwksPath,
                oidcRegisterPath,
                clientId,
                clientSecretConfigured,
                scopes,
                callbackUrl,
                loginWarmup,
                atrustWakeUrl,
                atrustGuideUrl
        );
    }
}
