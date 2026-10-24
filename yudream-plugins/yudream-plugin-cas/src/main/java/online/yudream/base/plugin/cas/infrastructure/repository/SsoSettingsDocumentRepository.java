package online.yudream.base.plugin.cas.infrastructure.repository;

import online.yudream.base.plugin.cas.domain.aggregate.SsoSettings;
import online.yudream.base.plugin.cas.domain.enumerate.SsoProtocol;
import online.yudream.base.plugin.cas.domain.repo.SsoSettingsRepository;
import online.yudream.base.plugin.cas.infrastructure.support.DocValues;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SsoSettingsDocumentRepository implements SsoSettingsRepository {

    static final String COLLECTION = "settings";
    static final String ID = "global";

    private final PluginDocumentStore documents;

    public SsoSettingsDocumentRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    @Override
    public SsoSettings get() {
        return documents.findById(COLLECTION, ID).map(this::toSettings).orElseGet(SsoSettings::defaults);
    }

    @Override
    public SsoSettings save(SsoSettings settings) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("enabled", settings.enabled());
        document.put("protocol", settings.protocol().name());
        document.put("displayName", settings.displayName());
        document.put("icon", settings.icon());
        document.put("casBaseUrl", settings.casBaseUrl());
        document.put("loginPath", settings.loginPath());
        document.put("validatePath", settings.validatePath());
        document.put("oidcIssuer", settings.oidcIssuer());
        document.put("oidcAuthorizePath", settings.oidcAuthorizePath());
        document.put("oidcTokenPath", settings.oidcTokenPath());
        document.put("oidcUserinfoPath", settings.oidcUserinfoPath());
        document.put("oidcJwksPath", settings.oidcJwksPath());
        document.put("oidcRegisterPath", settings.oidcRegisterPath());
        document.put("clientId", settings.clientId());
        document.put("scopes", settings.scopes());
        document.put("callbackUrl", settings.callbackUrl());
        document.put("loginWarmup", settings.loginWarmup());
        document.put("atrustWakeUrl", settings.atrustWakeUrl());
        document.put("atrustGuideUrl", settings.atrustGuideUrl());
        document.put("bridgeNotice", settings.bridgeNotice());
        return toSettings(documents.save(COLLECTION, ID, DocValues.stripNulls(document)));
    }

    private SsoSettings toSettings(Map<String, Object> document) {
        SsoSettings defaults = SsoSettings.defaults();
        return new SsoSettings(
                DocValues.bool(document, "enabled", defaults.enabled()),
                SsoProtocol.from(DocValues.string(document, "protocol")),
                orDefault(DocValues.string(document, "displayName"), defaults.displayName()),
                orDefault(DocValues.string(document, "icon"), defaults.icon()),
                orDefault(DocValues.string(document, "casBaseUrl"), defaults.casBaseUrl()),
                orDefault(DocValues.string(document, "loginPath"), defaults.loginPath()),
                orDefault(DocValues.string(document, "validatePath"), defaults.validatePath()),
                orDefault(DocValues.string(document, "oidcIssuer"), defaults.oidcIssuer()),
                orDefault(DocValues.string(document, "oidcAuthorizePath"), defaults.oidcAuthorizePath()),
                orDefault(DocValues.string(document, "oidcTokenPath"), defaults.oidcTokenPath()),
                orDefault(DocValues.string(document, "oidcUserinfoPath"), defaults.oidcUserinfoPath()),
                orDefault(DocValues.string(document, "oidcJwksPath"), defaults.oidcJwksPath()),
                orDefault(DocValues.string(document, "oidcRegisterPath"), defaults.oidcRegisterPath()),
                orDefault(DocValues.string(document, "clientId"), ""),
                false,
                orDefault(DocValues.string(document, "scopes"), defaults.scopes()),
                orDefault(DocValues.string(document, "callbackUrl"), ""),
                DocValues.bool(document, "loginWarmup", defaults.loginWarmup()),
                DocValues.string(document, "atrustWakeUrl"),
                DocValues.string(document, "atrustGuideUrl"),
                DocValues.string(document, "bridgeNotice")
        );
    }

    private static String orDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
