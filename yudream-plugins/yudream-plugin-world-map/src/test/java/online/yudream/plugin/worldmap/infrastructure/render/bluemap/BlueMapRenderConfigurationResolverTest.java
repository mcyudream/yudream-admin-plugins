package online.yudream.plugin.worldmap.infrastructure.render.bluemap;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlueMapRenderConfigurationResolverTest {

    @Test
    void remainsDisabledUntilAllRequiredSettingsExist() {
        assertFalse(new BlueMapRenderConfigurationResolver().resolve(framework(Map.of())).isPresent());
    }

    @Test
    void resolvesOnlyTaskLocalStorageRoot() {
        Map<String, String> settings = Map.of(
                BlueMapRenderConfigurationResolver.JAVA_PATH, "C:/java/bin/java.exe",
                BlueMapRenderConfigurationResolver.CLI_PATH, "C:/bluemap/bluemap-cli.jar",
                BlueMapRenderConfigurationResolver.CONFIG_TEMPLATE, "C:/bluemap/config",
                BlueMapRenderConfigurationResolver.STORAGE_ROOT, "output/maps/world"
        );
        BlueMapRenderConfiguration config = new BlueMapRenderConfigurationResolver().resolve(framework(settings)).orElseThrow();
        assertEquals("output\\maps\\world", config.storageRoot().toString());
        Map<String, String> invalidSettings = new java.util.HashMap<>(settings);
        invalidSettings.put(BlueMapRenderConfigurationResolver.STORAGE_ROOT, "../outside");
        assertThrows(IllegalStateException.class, () -> new BlueMapRenderConfigurationResolver().resolve(framework(invalidSettings)));
    }

    private static FrameworkServices framework(Map<String, String> settings) {
        return (FrameworkServices) Proxy.newProxyInstance(BlueMapRenderConfigurationResolverTest.class.getClassLoader(),
                new Class<?>[]{FrameworkServices.class}, (proxy, method, arguments) ->
                "setting".equals(method.getName()) ? java.util.Optional.ofNullable(settings.get(arguments[0])) : null);
    }
}
