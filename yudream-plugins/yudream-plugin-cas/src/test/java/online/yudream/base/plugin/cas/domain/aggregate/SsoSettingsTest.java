package online.yudream.base.plugin.cas.domain.aggregate;

import online.yudream.base.plugin.cas.bootstrap.CasSsoPlugin;
import online.yudream.base.plugin.cas.domain.enumerate.SsoProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsoSettingsTest {

    @Test
    void casReadyWhenCallbackPresent() {
        SsoSettings settings = new SsoSettings(
                true, SsoProtocol.CAS, "塔大", "i-ri:school-line",
                "https://cas.example/", "/authserver/login", "/cas/p3/serviceValidate",
                SsoSettings.DEFAULT_OIDC_ISSUER, SsoSettings.DEFAULT_OIDC_AUTHORIZE_PATH, SsoSettings.DEFAULT_OIDC_TOKEN_PATH,
                SsoSettings.DEFAULT_OIDC_USERINFO_PATH, SsoSettings.DEFAULT_OIDC_JWKS_PATH, SsoSettings.DEFAULT_OIDC_REGISTER_PATH,
                "", false, SsoSettings.DEFAULT_SCOPES, "https://site.example/api/external-login/callback", true,
                "", "",
                ""
        );
        assertTrue(settings.ready());
        assertTrue(settings.loginEnabled());
        assertEquals("https://cas.example", settings.casBaseUrl());
        assertEquals("https://site.example/api/external-login/callback?state=abc", settings.casServiceUrl("abc"));
    }

    @Test
    void oidcNotReadyWithoutSecret() {
        SsoSettings settings = new SsoSettings(
                true, SsoProtocol.OIDC, SsoSettings.DEFAULT_DISPLAY_NAME, SsoSettings.DEFAULT_ICON,
                "https://cas.example", SsoSettings.DEFAULT_LOGIN_PATH, SsoSettings.DEFAULT_VALIDATE_PATH,
                SsoSettings.DEFAULT_OIDC_ISSUER, SsoSettings.DEFAULT_OIDC_AUTHORIZE_PATH, SsoSettings.DEFAULT_OIDC_TOKEN_PATH,
                SsoSettings.DEFAULT_OIDC_USERINFO_PATH, SsoSettings.DEFAULT_OIDC_JWKS_PATH, SsoSettings.DEFAULT_OIDC_REGISTER_PATH,
                "client", false, SsoSettings.DEFAULT_SCOPES, "https://site.example/callback", true,
                "", "",
                ""
        );
        assertFalse(settings.ready());
        assertTrue(settings.withClientSecretConfigured(true).ready());
    }

    @Test
    void appendsStateWhenCallbackAlreadyHasQuery() {
        SsoSettings settings = SsoSettings.defaults();
        SsoSettings withCallback = new SsoSettings(
                false, SsoProtocol.CAS, settings.displayName(), settings.icon(), settings.casBaseUrl(),
                settings.loginPath(), settings.validatePath(), settings.oidcIssuer(), settings.oidcAuthorizePath(),
                settings.oidcTokenPath(), settings.oidcUserinfoPath(), settings.oidcJwksPath(), settings.oidcRegisterPath(),
                "", false, settings.scopes(), "https://site.example/callback?from=login", true,
                "", "",
                ""
        );
        assertEquals("https://site.example/callback?from=login&state=s1", withCallback.casServiceUrl("s1"));
    }

    @Test
    void loginWarmupDefaultsOnAndCanBeTurnedOff() {
        SsoSettings defaults = SsoSettings.defaults();
        assertTrue(defaults.loginWarmup());
        SsoSettings off = defaults.withLoginWarmup(false);
        assertFalse(off.loginWarmup());
        // 关掉预热只影响这一项，其余字段原样保留
        assertEquals(defaults.displayName(), off.displayName());
        assertEquals(defaults.casBaseUrl(), off.casBaseUrl());
        assertEquals(defaults.callbackUrl(), off.callbackUrl());
    }

    @Test
    void warmupUrlCarriesOnlyTheState() {
        SsoSettings settings = SsoSettings.defaults();
        assertEquals(SsoSettings.WARMUP_PATH + "?state=abc", settings.warmupUrl("abc"));
        // 只有 state 这一个参数：不存在可被外部利用的任意跳转目标
        assertFalse(settings.warmupUrl("abc").contains("target"));
        assertFalse(settings.warmupUrl("abc").contains("url="));
    }

    @Test
    void warmupPathMatchesPluginCode() {
        assertTrue(SsoSettings.WARMUP_PATH.startsWith("/api/plugins/" + CasSsoPlugin.CODE + "/"),
                "WARMUP_PATH 必须位于插件 code 前缀下：" + SsoSettings.WARMUP_PATH);
    }
}
