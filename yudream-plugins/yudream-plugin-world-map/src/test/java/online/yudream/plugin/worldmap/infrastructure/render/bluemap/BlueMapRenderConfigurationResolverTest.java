package online.yudream.plugin.worldmap.infrastructure.render.bluemap;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueMapRenderConfigurationResolverTest {

    @Test
    void usesTheBundledRuntimeWithoutLegacySettings() {
        BlueMapRenderConfiguration config = new BlueMapRenderConfigurationResolver().resolve(framework(Map.of())).orElseThrow();

        assertTrue(config.bundledRuntime());
        assertEquals("output", config.storageRoot().toString());
        assertNull(config.minecraftVersion());
        assertEquals(BlueMapRenderConfigurationResolver.defaultRenderThreadCount(
                Runtime.getRuntime().availableProcessors()), config.renderThreadCount());
    }

    @Test
    void resolvesOnlyTaskLocalStorageRoot() {
        Map<String, String> settings = Map.of(
                BlueMapRenderConfigurationResolver.JAVA_PATH, "C:/java/bin/java.exe",
                BlueMapRenderConfigurationResolver.CLI_PATH, "C:/bluemap/bluemap-cli.jar",
                BlueMapRenderConfigurationResolver.CONFIG_TEMPLATE, "C:/bluemap/config",
                BlueMapRenderConfigurationResolver.STORAGE_ROOT, "output/maps/world",
                BlueMapRenderConfigurationResolver.EXTERNAL_RUNTIME_ENABLED, "true",
                BlueMapRenderConfigurationResolver.RESOURCE_CACHE_ROOT, "C:/world-map/bluemap-cache"
        );
        BlueMapRenderConfiguration config = new BlueMapRenderConfigurationResolver().resolve(framework(settings)).orElseThrow();
        assertEquals("output\\maps\\world", config.storageRoot().toString());
        assertEquals("C:\\world-map\\bluemap-cache", config.resourceCacheRoot().toString());
        Map<String, String> invalidSettings = new java.util.HashMap<>(settings);
        invalidSettings.put(BlueMapRenderConfigurationResolver.STORAGE_ROOT, "../outside");
        assertThrows(IllegalStateException.class, () -> new BlueMapRenderConfigurationResolver().resolve(framework(invalidSettings)));
        invalidSettings.put(BlueMapRenderConfigurationResolver.STORAGE_ROOT, "output/maps/world");
        invalidSettings.put(BlueMapRenderConfigurationResolver.RESOURCE_CACHE_ROOT, "relative-cache");
        assertThrows(IllegalStateException.class, () -> new BlueMapRenderConfigurationResolver().resolve(framework(invalidSettings)));
    }

    @Test
    void usesExternalPathsOnlyWhenExplicitlyEnabled() {
        Map<String, String> settings = Map.of(
                BlueMapRenderConfigurationResolver.JAVA_PATH, "C:/java/bin/java.exe",
                BlueMapRenderConfigurationResolver.CLI_PATH, "C:/bluemap/bluemap-cli.jar",
                BlueMapRenderConfigurationResolver.CONFIG_TEMPLATE, "C:/bluemap/config",
                BlueMapRenderConfigurationResolver.STORAGE_ROOT, "output/maps/world"
        );

        assertTrue(new BlueMapRenderConfigurationResolver().resolve(framework(settings)).orElseThrow().bundledRuntime());

        Map<String, String> external = new java.util.HashMap<>(settings);
        external.put(BlueMapRenderConfigurationResolver.EXTERNAL_RUNTIME_ENABLED, "true");
        assertTrue(!new BlueMapRenderConfigurationResolver().resolve(framework(external)).orElseThrow().bundledRuntime());
    }

    @Test
    void acceptsAnExplicitDetailedTileThreadCount() {
        BlueMapRenderConfiguration config = new BlueMapRenderConfigurationResolver()
                .resolve(framework(Map.of(BlueMapRenderConfigurationResolver.RENDER_THREAD_COUNT, "12")))
                .orElseThrow();

        assertEquals(12, config.renderThreadCount());
        assertEquals(1, BlueMapRenderConfigurationResolver.defaultRenderThreadCount(1));
        assertEquals(6, BlueMapRenderConfigurationResolver.defaultRenderThreadCount(8));
        assertEquals(16, BlueMapRenderConfigurationResolver.defaultRenderThreadCount(64));
    }

    @Test
    void rejectsAnUnsafeDetailedTileThreadCount() {
        assertThrows(IllegalStateException.class, () -> new BlueMapRenderConfigurationResolver()
                .resolve(framework(Map.of(BlueMapRenderConfigurationResolver.RENDER_THREAD_COUNT, "0"))));
        assertThrows(IllegalStateException.class, () -> new BlueMapRenderConfigurationResolver()
                .resolve(framework(Map.of(BlueMapRenderConfigurationResolver.RENDER_THREAD_COUNT, "65"))));
    }

    private static FrameworkServices framework(Map<String, String> settings) {
        return (FrameworkServices) Proxy.newProxyInstance(BlueMapRenderConfigurationResolverTest.class.getClassLoader(),
                new Class<?>[]{FrameworkServices.class}, (proxy, method, arguments) ->
                "setting".equals(method.getName()) ? java.util.Optional.ofNullable(settings.get(arguments[0])) : null);
    }
}
