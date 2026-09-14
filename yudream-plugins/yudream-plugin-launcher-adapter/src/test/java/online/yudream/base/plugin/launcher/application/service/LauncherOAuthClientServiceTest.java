package online.yudream.base.plugin.launcher.application.service;

import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.security.PluginOAuthClient;
import online.yudream.base.plugin.spi.system.security.PluginOAuthPublicClientSpec;
import online.yudream.base.plugin.spi.system.security.PluginOAuthService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherOAuthClientServiceTest {

    @Test
    void registersPublicClientWithoutHardcodedForeignPluginScopes() {
        RecordingOAuth oauth = new RecordingOAuth();
        LauncherOAuthClientService service = new LauncherOAuthClientService(stubContext(oauth));

        Optional<PluginOAuthClient> registered = service.ensureRegistered();

        assertTrue(registered.isPresent());
        assertEquals("ymcl", registered.get().clientId());
        assertEquals("ymcl://auth/callback", oauth.lastSpec.get().redirectUris().getFirst());
        assertEquals("ymcl", service.publishedClientId());
        assertTrue(oauth.lastSpec.get().scopes().contains("openid"));
        assertTrue(oauth.lastSpec.get().scopes().contains("plugin:launcher-adapter:view"));
        assertTrue(oauth.lastSpec.get().scopes().contains("plugin:launcher-adapter:manage"));
        assertFalse(oauth.lastSpec.get().scopes().contains("plugin:minecraft-server:view"));
        assertFalse(oauth.lastSpec.get().scopes().contains("plugin:minecraft-server:manage"));
    }

    private static PluginContext stubContext(PluginOAuthService oauth) {
        FrameworkServices framework = (FrameworkServices) Proxy.newProxyInstance(
                FrameworkServices.class.getClassLoader(),
                new Class<?>[]{FrameworkServices.class},
                (proxy, method, args) -> {
                    if ("oauth".equals(method.getName())) {
                        return oauth;
                    }
                    return defaultValue(method.getReturnType());
                });
        return (PluginContext) Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(),
                new Class<?>[]{PluginContext.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "pluginCode" -> "launcher-adapter";
                        case "framework" -> framework;
                        case "oauth" -> oauth;
                        case "extensions" -> List.of();
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        return 0;
    }

    private static final class RecordingOAuth implements PluginOAuthService {
        private final AtomicReference<PluginOAuthPublicClientSpec> lastSpec = new AtomicReference<>();
        private final List<String> mergedScopes = new ArrayList<>();

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Optional<PluginOAuthClient> findClient(String clientId) {
            List<String> scopes = lastSpec.get() == null ? List.of() : lastSpec.get().scopes();
            if (scopes.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new PluginOAuthClient(
                    clientId,
                    "YMCL",
                    List.of(LauncherOAuthClientService.REDIRECT_URI),
                    scopes,
                    true
            ));
        }

        @Override
        public Optional<PluginOAuthClient> ensurePublicClient(PluginOAuthPublicClientSpec spec) {
            lastSpec.set(spec);
            mergedScopes.addAll(spec.scopes());
            return Optional.of(new PluginOAuthClient(
                    spec.clientId(),
                    spec.clientName(),
                    spec.redirectUris(),
                    spec.scopes(),
                    true
            ));
        }
    }
}
