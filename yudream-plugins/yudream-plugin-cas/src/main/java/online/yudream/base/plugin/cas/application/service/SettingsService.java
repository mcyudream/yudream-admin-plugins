package online.yudream.base.plugin.cas.application.service;

import online.yudream.base.plugin.cas.application.dto.SsoSettingsDto;
import online.yudream.base.plugin.cas.domain.aggregate.SsoSettings;
import online.yudream.base.plugin.cas.domain.enumerate.SsoProtocol;
import online.yudream.base.plugin.cas.domain.repo.SsoSettingsRepository;
import online.yudream.base.plugin.cas.domain.service.SsoProtocolClient;
import online.yudream.base.plugin.cas.infrastructure.bridge.BridgeProtocolClient;
import online.yudream.base.plugin.cas.infrastructure.cas.CasProtocolClient;
import online.yudream.base.plugin.cas.infrastructure.oidc.OidcProtocolClient;
import online.yudream.base.plugin.cas.infrastructure.secret.ClientSecretStore;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class SettingsService {

    private final SsoSettingsRepository repository;
    private final ClientSecretStore secrets;
    private final CasProtocolClient casClient;
    private final OidcProtocolClient oidcClient;
    private final BridgeProtocolClient bridgeClient;
    private final AtomicReference<SsoSettings> cache = new AtomicReference<>();

    public SettingsService(
            SsoSettingsRepository repository,
            ClientSecretStore secrets,
            CasProtocolClient casClient,
            OidcProtocolClient oidcClient,
            BridgeProtocolClient bridgeClient
    ) {
        this.repository = repository;
        this.secrets = secrets;
        this.casClient = casClient;
        this.oidcClient = oidcClient;
        this.bridgeClient = bridgeClient;
    }

    public SsoSettings current() {
        SsoSettings cached = cache.get();
        if (cached != null) {
            return cached;
        }
        return remember(repository.get());
    }

    public SsoSettingsDto view() {
        return SsoSettingsDto.from(current());
    }

    public SsoSettingsDto save(SsoSettingsDto incoming, String clientSecret) {
        if (incoming == null) {
            throw new IllegalArgumentException("设置不能为空");
        }
        if (incoming.callbackUrl() == null || incoming.callbackUrl().isBlank()) {
            throw new IllegalArgumentException("本站回调地址不能为空");
        }
        if (incoming.casBaseUrl() == null || incoming.casBaseUrl().isBlank()) {
            throw new IllegalArgumentException("认证服务根地址不能为空");
        }
        SsoProtocol protocol = SsoProtocol.from(incoming.protocol());
        if ((protocol == SsoProtocol.OIDC || protocol == SsoProtocol.BRIDGE)
                && (incoming.clientId() == null || incoming.clientId().isBlank())) {
            throw new IllegalArgumentException(protocol == SsoProtocol.BRIDGE
                    ? "SSO 中转模式必须填写 clientId"
                    : "OIDC 模式必须填写 client_id");
        }
        if (clientSecret != null && !clientSecret.isBlank()) {
            secrets.put(clientSecret);
        }
        return SsoSettingsDto.from(remember(repository.save(incoming.toSettings(false))));
    }

    public SsoProtocolClient.ConnectivityResult test() {
        SsoSettings settings = current();
        return client(settings.protocol()).probe(settings, secrets.get().orElse(null));
    }

    public Map<String, Object> registerOidcClient(String clientName) {
        SsoSettings settings = current();
        if (settings.callbackUrl().isBlank()) {
            throw new IllegalArgumentException("动态注册前请先填写本站回调地址");
        }
        var node = oidcClient.registerClient(settings, clientName);
        String clientId = node.path("client_id").asText("");
        String clientSecret = node.path("client_secret").asText("");
        if (!clientSecret.isBlank()) {
            secrets.put(clientSecret);
        }
        if (!clientId.isBlank()) {
            SsoSettings updated = new SsoSettings(
                    settings.enabled(),
                    SsoProtocol.OIDC,
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
                    clientId,
                    secrets.configured(),
                    settings.scopes(),
                    settings.callbackUrl(),
                    settings.loginWarmup()
            );
            remember(repository.save(updated));
        } else {
            cache.set(null);
            current();
        }
        return Map.of(
                "clientId", clientId,
                "clientSecretIssued", !clientSecret.isBlank()
        );
    }

    public String authorizationUrl(String state) {
        SsoSettings settings = requireReady();
        return client(settings.protocol()).authorizationUrl(settings, state);
    }

    public SsoProtocolClient.ExternalIdentity exchange(String ticket, String state) {
        SsoSettings settings = requireReady();
        return client(settings.protocol()).exchange(settings, ticket, state, secrets.get().orElse(null));
    }

    public SsoProtocolClient client(SsoProtocol protocol) {
        return switch (protocol) {
            case OIDC -> oidcClient;
            case BRIDGE -> bridgeClient;
            case CAS -> casClient;
        };
    }

    private SsoSettings remember(SsoSettings settings) {
        SsoSettings withSecret = settings.withClientSecretConfigured(secrets.configured());
        cache.set(withSecret);
        return withSecret;
    }

    private SsoSettings requireReady() {
        SsoSettings settings = current();
        if (!settings.loginEnabled()) {
            throw new IllegalStateException("统一身份认证未启用或配置不完整");
        }
        return settings;
    }
}
