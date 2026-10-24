package online.yudream.base.plugin.cas.application.service;

import online.yudream.base.plugin.spi.system.auth.PluginExternalLoginAuthorizeRequest;
import online.yudream.base.plugin.spi.system.auth.PluginExternalLoginExchangeRequest;
import online.yudream.base.plugin.spi.system.auth.PluginExternalLoginPresentation;
import online.yudream.base.plugin.spi.system.secret.PluginSecretStore;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.cas.application.dto.SsoSettingsDto;
import online.yudream.base.plugin.cas.domain.aggregate.SsoSettings;
import online.yudream.base.plugin.cas.infrastructure.bridge.BridgeProtocolClient;
import online.yudream.base.plugin.cas.infrastructure.cas.CasProtocolClient;
import online.yudream.base.plugin.cas.infrastructure.oidc.OidcProtocolClient;
import online.yudream.base.plugin.cas.infrastructure.repository.SsoSettingsDocumentRepository;
import online.yudream.base.plugin.cas.infrastructure.secret.ClientSecretStore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CasSsoLoginProviderTest {

    @Test
    void disabledUntilCallbackConfigured() {
        SettingsService service = service();
        CasSsoLoginProvider provider = new CasSsoLoginProvider(service, studentInfo());
        assertFalse(provider.enabled());
        assertEquals("cas", provider.descriptor().providerCode());
        assertEquals("cas", provider.descriptor().supportedTypes().getFirst());
    }

    @Test
    void loginEntryIsPresentedAsLoginTab() {
        SettingsService service = service();
        CasSsoLoginProvider provider = new CasSsoLoginProvider(service, studentInfo());
        // 宿主 SPI 2.32.0：入口与「账号密码登录 / Passkey 登录」并列成 Tab
        assertEquals(PluginExternalLoginPresentation.TAB, provider.presentation());
        assertEquals("cas", provider.descriptor().providerCode());
        // 内置 Tab 基线是账号密码 100 / Passkey 200，0 让本插件排在首位
        assertEquals(0, provider.descriptor().sort());
    }

    @Test
    void casAuthorizeGoesThroughTheWarmupPageByDefault() {
        SettingsService service = service();
        service.save(casReady(), null);
        CasSsoLoginProvider provider = new CasSsoLoginProvider(service, studentInfo());
        assertTrue(provider.enabled());
        String url = provider.authorizationUrl(new PluginExternalLoginAuthorizeRequest("cas", "st-state"));
        // 默认开启预热：先落到本站预热页，目标地址由服务端重建
        assertEquals(SsoSettings.WARMUP_PATH + "?state=st-state", url);
    }

    @Test
    void casAuthorizeGoesStraightToCasWhenWarmupDisabled() {
        SettingsService service = service();
        service.save(casReady(false), null);
        CasSsoLoginProvider provider = new CasSsoLoginProvider(service, studentInfo());
        String url = provider.authorizationUrl(new PluginExternalLoginAuthorizeRequest("cas", "st-state"));
        assertTrue(url.startsWith("https://cas.example/authserver/login?service="), url);
        assertTrue(url.contains("state%3Dst-state") || url.contains("state=st-state"));
    }

    @Test
    void oidcAuthorizeIsNotWrappedByWarmup() {
        SettingsService service = service();
        service.save(oidcReady(), "s3cret");
        CasSsoLoginProvider provider = new CasSsoLoginProvider(service, studentInfo());
        String url = provider.authorizationUrl(new PluginExternalLoginAuthorizeRequest("oidc", "oidc-state"));
        // 预热只作用于 CAS：OIDC 的 redirect_uri 必须与登记值精确一致，不能改跳本站页面
        assertTrue(url.startsWith("https://cas.example/authserver/oidc/authorize?"), url);
    }

    @Test
    void oidcRequiresSecret() {
        SettingsService service = service();
        SsoSettingsDto oidc = new SsoSettingsDto(
                true, "OIDC", SsoSettings.DEFAULT_DISPLAY_NAME, SsoSettings.DEFAULT_ICON,
                "https://cas.example", SsoSettings.DEFAULT_LOGIN_PATH, SsoSettings.DEFAULT_VALIDATE_PATH,
                SsoSettings.DEFAULT_OIDC_ISSUER, SsoSettings.DEFAULT_OIDC_AUTHORIZE_PATH, SsoSettings.DEFAULT_OIDC_TOKEN_PATH,
                SsoSettings.DEFAULT_OIDC_USERINFO_PATH, SsoSettings.DEFAULT_OIDC_JWKS_PATH, SsoSettings.DEFAULT_OIDC_REGISTER_PATH,
                "client-1", false, SsoSettings.DEFAULT_SCOPES, "https://site.example/api/external-login/callback", true,
                "", "", "", false);
        service.save(oidc, null);
        CasSsoLoginProvider provider = new CasSsoLoginProvider(service, studentInfo());
        assertFalse(provider.enabled());
        service.save(oidc, "s3cret");
        assertTrue(provider.enabled());
        assertEquals("oidc", provider.descriptor().supportedTypes().getFirst());
        String url = provider.authorizationUrl(new PluginExternalLoginAuthorizeRequest("oidc", "oidc-state"));
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("state=oidc-state"));
        assertTrue(url.contains("client_id=client-1"));
    }

    @Test
    void rejectsMismatchedType() {
        SettingsService service = service();
        service.save(casReady(), null);
        CasSsoLoginProvider provider = new CasSsoLoginProvider(service, studentInfo());
        assertThrows(IllegalArgumentException.class,
                () -> provider.authorizationUrl(new PluginExternalLoginAuthorizeRequest("oidc", "x")));
        assertThrows(IllegalArgumentException.class,
                () -> provider.exchange(new PluginExternalLoginExchangeRequest("oidc", "ST-1", "x")));
    }

    private static SsoSettingsDto casReady() {
        return casReady(true);
    }

    private static SsoSettingsDto casReady(boolean loginWarmup) {
        return new SsoSettingsDto(
                true, "CAS", SsoSettings.DEFAULT_DISPLAY_NAME, SsoSettings.DEFAULT_ICON,
                "https://cas.example", SsoSettings.DEFAULT_LOGIN_PATH, SsoSettings.DEFAULT_VALIDATE_PATH,
                SsoSettings.DEFAULT_OIDC_ISSUER, SsoSettings.DEFAULT_OIDC_AUTHORIZE_PATH, SsoSettings.DEFAULT_OIDC_TOKEN_PATH,
                SsoSettings.DEFAULT_OIDC_USERINFO_PATH, SsoSettings.DEFAULT_OIDC_JWKS_PATH, SsoSettings.DEFAULT_OIDC_REGISTER_PATH,
                "", false, SsoSettings.DEFAULT_SCOPES, "https://site.example/api/external-login/callback", loginWarmup,
                "", "", "", false);
    }

    private static SsoSettingsDto oidcReady() {
        return new SsoSettingsDto(
                true, "OIDC", SsoSettings.DEFAULT_DISPLAY_NAME, SsoSettings.DEFAULT_ICON,
                "https://cas.example", SsoSettings.DEFAULT_LOGIN_PATH, SsoSettings.DEFAULT_VALIDATE_PATH,
                SsoSettings.DEFAULT_OIDC_ISSUER, SsoSettings.DEFAULT_OIDC_AUTHORIZE_PATH, SsoSettings.DEFAULT_OIDC_TOKEN_PATH,
                SsoSettings.DEFAULT_OIDC_USERINFO_PATH, SsoSettings.DEFAULT_OIDC_JWKS_PATH, SsoSettings.DEFAULT_OIDC_REGISTER_PATH,
                "client-1", true, SsoSettings.DEFAULT_SCOPES, "https://site.example/api/external-login/callback", true,
                "", "", "", false);
    }

    private static StudentInfoService studentInfo() {
        return new StudentInfoService(
                new online.yudream.base.plugin.cas.infrastructure.repository.StudentProfileDocumentRepository(new MemoryDocuments())
        );
    }

    private static SettingsService service() {
        MemoryDocuments documents = new MemoryDocuments();
        MemorySecrets secrets = new MemorySecrets();
        ClientSecretStore store = new ClientSecretStore(secrets);
        return new SettingsService(
                new SsoSettingsDocumentRepository(documents),
                store,
                new CasProtocolClient(),
                new OidcProtocolClient(),
                new BridgeProtocolClient()
        );
    }

    private static final class MemoryDocuments implements PluginDocumentStore {
        private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();

        @Override
        public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            Map<String, Object> copy = new LinkedHashMap<>(document);
            store.put(collection + "/" + id, copy);
            return copy;
        }

        @Override
        public Optional<Map<String, Object>> findById(String collection, String id) {
            Map<String, Object> document = store.get(collection + "/" + id);
            return document == null ? Optional.empty() : Optional.of(new LinkedHashMap<>(document));
        }

        @Override
        public List<Map<String, Object>> findAll(String collection, int page, int size) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
            return List.of();
        }

        @Override
        public long count(String collection) {
            return store.size();
        }

        @Override
        public void delete(String collection, String id) {
            store.remove(collection + "/" + id);
        }
    }

    private static final class MemorySecrets implements PluginSecretStore {
        private final Map<String, byte[]> store = new ConcurrentHashMap<>();

        @Override
        public void put(String key, byte[] secret) {
            store.put(key, secret);
        }

        @Override
        public Optional<byte[]> get(String key) {
            byte[] value = store.get(key);
            return value == null ? Optional.empty() : Optional.of(value);
        }

        @Override
        public boolean delete(String key) {
            return store.remove(key) != null;
        }
    }
}
