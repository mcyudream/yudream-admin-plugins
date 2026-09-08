package online.yudream.plugin.qqbotautomation.application.service;

import online.yudream.base.plugin.spi.system.secret.PluginSecretStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaStorageSettingsTest {

    @Test
    void migratesLegacyValuesOnlyWhenNoSecretExists() {
        InMemorySecrets secrets = new InMemorySecrets();
        MediaStorageSettings settings = new MediaStorageSettings(secrets);

        settings.migrateLegacyValues(" D:/media/host ", " /milky ");

        assertEquals("D:/media/host", settings.hostDirectory());
        assertEquals("/milky", settings.containerDirectory());

        settings.save("D:/media/custom", "/custom");
        settings.migrateLegacyValues("D:/media/other", "/other");

        assertEquals("D:/media/custom", settings.hostDirectory());
        assertEquals("/custom", settings.containerDirectory());
    }

    @Test
    void saveClearsBlankValuesAndFallsBackToDefaultContainerDirectory() {
        MediaStorageSettings settings = new MediaStorageSettings(new InMemorySecrets());

        assertNull(settings.hostDirectory());
        assertEquals(MediaStorageSettings.DEFAULT_CONTAINER_DIRECTORY, settings.containerDirectory());

        settings.save("D:/media/host", null);
        assertEquals("D:/media/host", settings.hostDirectory());
        assertEquals(MediaStorageSettings.DEFAULT_CONTAINER_DIRECTORY, settings.containerDirectory());

        Map<String, Object> view = settings.save("", "");
        assertNull(settings.hostDirectory());
        assertNull(view.get("hostDirectory"));
        assertEquals(MediaStorageSettings.DEFAULT_CONTAINER_DIRECTORY, view.get("containerDirectory"));
        assertTrue(settings.view().containsKey("containerDirectory"));
    }

    private static final class InMemorySecrets implements PluginSecretStore {
        private final Map<String, byte[]> values = new HashMap<>();

        @Override public void put(String key, byte[] secret) { values.put(key, secret); }
        @Override public Optional<byte[]> get(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public boolean delete(String key) { return values.remove(key) != null; }
    }
}
