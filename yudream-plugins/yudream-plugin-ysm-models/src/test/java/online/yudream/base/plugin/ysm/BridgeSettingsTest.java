package online.yudream.base.plugin.ysm;

import java.net.URI;
import java.util.*;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.*;
import online.yudream.base.plugin.spi.system.secret.PluginSecretStore;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;
import online.yudream.base.plugin.ysm.application.ModelService;
import online.yudream.base.plugin.ysm.bootstrap.YsmModelsPlugin;
import online.yudream.base.plugin.ysm.domain.*;
import online.yudream.base.plugin.ysm.infrastructure.*;
import online.yudream.base.plugin.ysm.interfaces.*;
import online.yudream.ysm.model.ModelAsset;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BridgeSettingsTest {
    private static final String OLD = "old-credential-" + "a".repeat(50), NEW = "b".repeat(64);
    private final PluginSecretStore store = mock(PluginSecretStore.class);
    private final Map<String,byte[]> encryptedSlots = new HashMap<>();
    @BeforeEach void store() {
        when(store.get(anyString())).thenAnswer(i -> Optional.ofNullable(encryptedSlots.get(i.getArgument(0))).map(byte[]::clone));
        doAnswer(i -> { encryptedSlots.put(i.getArgument(0), ((byte[])i.getArgument(1)).clone()); return null; }).when(store).put(anyString(), any(byte[].class));
    }
    @Test void legacyEnvironmentWorksUntilPageSettingsAreSavedAndPersistAcrossRestart() {
        var links = new AssetLinks(store, "https://legacy.example/", OLD);
        assertTrue(links.bridge(OLD)); assertEquals("https://legacy.example", links.settings().publicBaseUrl());
        links.settings(new BridgeSettings("https://skin.example/site/", NEW));
        var restarted = new AssetLinks(store, "https://legacy.example", OLD);
        assertEquals(new BridgeSettings("https://skin.example/site", NEW), restarted.settings());
        assertTrue(restarted.bridge(NEW)); assertFalse(restarted.bridge(OLD));
        assertEquals(1, encryptedSlots.size());
    }
    @Test void savingUpdatesAuthorizationAndDownloadSigningImmediately() {
        var links = new AssetLinks(store, "https://legacy.example", OLD);
        var oldUrl = URI.create(links.url("bundle", "a".repeat(64)));
        String[] oldQuery = oldUrl.getQuery().split("&");
        long expiry = Long.parseLong(oldQuery[0].substring("expires=".length())); String oldSig = oldQuery[1].substring(4);
        assertTrue(links.verify("bundle", "a".repeat(64), expiry, oldSig));
        links.settings(new BridgeSettings("https://skin.example/site", NEW));
        assertTrue(links.bridge(NEW)); assertFalse(links.bridge(OLD));
        assertFalse(links.verify("bundle", "a".repeat(64), expiry, oldSig));
        var fresh = URI.create(links.url("bundle", "a".repeat(64)));
        assertTrue(fresh.toString().startsWith("https://skin.example/site/api/plugins/ysm-models/download/"));
        assertFalse(fresh.toString().contains(NEW)); assertFalse(links.bridge(oldSig));
        var query = fresh.getQuery().split("&");
        assertTrue(links.verify("bundle", "a".repeat(64), Long.parseLong(query[0].substring(8)), query[1].substring(4)));
    }
    @Test void invalidSettingsNeverReplaceWorkingCredentials() {
        var links = new AssetLinks(store, "https://legacy.example", OLD);
        for (String url : List.of("", "javascript:alert(1)", "https://user:password@example.com", "https://example.com/?token=secret", "https://example.com/#fragment"))
            assertThrows(IllegalArgumentException.class, () -> links.settings(new BridgeSettings(url, NEW)));
        for (String token : List.of("", "a".repeat(31), "a".repeat(257), "a".repeat(32) + "\n", "a".repeat(32) + " "))
            assertThrows(IllegalArgumentException.class, () -> links.settings(new BridgeSettings("https://skin.example", token)));
        assertTrue(links.bridge(OLD)); verify(store, never()).put(anyString(), any());
        assertFalse(new BridgeSettings("https://example.com", NEW).toString().contains(NEW));
    }
    @Test void failedEncryptedWriteKeepsOldConfigAndCredentials() {
        var links = new AssetLinks(store, "https://legacy.example", OLD);
        doThrow(new IllegalStateException("test storage failure")).when(store).put(anyString(), any());
        assertThrows(IllegalStateException.class, () -> links.settings(new BridgeSettings("https://new.example", NEW)));
        assertTrue(links.bridge(OLD)); assertFalse(links.bridge(NEW));
        assertEquals("https://legacy.example", links.settings().publicBaseUrl());
    }
    @Test void secretReadAndWriteRequireConfigurationPermissionAndNeverReachPersonalPolicy() throws Exception {
        var links = new AssetLinks(store, "https://legacy.example", OLD);
        var repo = mock(ModelRepository.class); when(repo.settings()).thenReturn(ModelSettings.defaults());
        var service = new ModelService(repo, mock(SkinPort.class), links);
        var http = new ModelHttp(service, links); var admin = new ModelAdminController(http);
        String payload = ModelAsset.JSON.toJson(new BridgeSettings("https://new.example", NEW));
        for (var permissions : List.of(List.of(YsmModelsPlugin.USER), List.of(YsmModelsPlugin.MANAGE))) {
            var request = new PluginHttpRequest("GET", "/admin/bridge-settings", Map.of(), Map.of(), payload, new PluginPrincipal(42L, permissions));
            assertFalse(ModelAsset.JSON.toJson(admin.bridgeSettings(request).body()).contains(OLD));
            assertFalse(ModelAsset.JSON.toJson(admin.saveBridgeSettings(request).body()).contains(NEW));
        }
        var anonymous = new PluginHttpRequest("GET", "/admin/bridge-settings", Map.of(), Map.of(), payload, null);
        assertFalse(ModelAsset.JSON.toJson(admin.bridgeSettings(anonymous).body()).contains(OLD));
        for (var permissions : List.of(List.of(YsmModelsPlugin.CONFIG), List.of("*"))) {
            var request = new PluginHttpRequest("PUT", "/admin/bridge-settings", Map.of(), Map.of(), payload, new PluginPrincipal(42L, permissions));
            var saved = admin.saveBridgeSettings(request); var loaded = admin.bridgeSettings(request);
            assertInstanceOf(BridgeSettings.class, saved.body());
            assertEquals(NEW, ((BridgeSettings)loaded.body()).bridgeToken());
            assertEquals("no-store", saved.headers().get("Cache-Control"));
            assertEquals("no-store", loaded.headers().get("Cache-Control"));
        }
        var personal = new PluginHttpRequest("GET", "/me/upload-policy", Map.of(), Map.of(), "", new PluginPrincipal(42L, List.of(YsmModelsPlugin.USER)));
        String policy = ModelAsset.JSON.toJson(http.uploadPolicy(personal).body());
        assertFalse(policy.contains("bridgeToken")); assertFalse(policy.contains(NEW));
        assertFalse(ModelAsset.JSON.toJson(http.settings(personal).body()).contains(NEW));
        for (String method : List.of("bridgeSettings", "saveBridgeSettings"))
            assertEquals(YsmModelsPlugin.CONFIG, ModelAdminController.class.getMethod(method, PluginHttpRequest.class).getAnnotation(PluginHttpEndpoint.class).permission());
    }
}
