package online.yudream.plugin.qqbotautomation.application.service;

import online.yudream.base.plugin.spi.system.secret.PluginSecretStore;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Milky shared-media locations are deployment credentials. They live in the plugin SecretStore,
 * which the host encrypts with the unified YUDREAM_CREDENTIAL_KEY, instead of plugin-specific
 * environment variables.
 */
public class MediaStorageSettings {
    public static final String DEFAULT_CONTAINER_DIRECTORY = "/media";
    private static final String HOST_DIRECTORY_KEY = "milky-media-host-directory";
    private static final String CONTAINER_DIRECTORY_KEY = "milky-media-container-directory";
    private static final String LEGACY_HOST_DIRECTORY_ENV = "YUDREAM_QQBOT_MILKY_MEDIA_HOST_DIRECTORY";
    private static final String LEGACY_CONTAINER_DIRECTORY_ENV = "YUDREAM_QQBOT_MILKY_MEDIA_DIRECTORY";
    private static final Logger LOGGER = Logger.getLogger(MediaStorageSettings.class.getName());
    private final PluginSecretStore secrets;

    public MediaStorageSettings(PluginSecretStore secrets) {
        this.secrets = secrets;
    }

    /**
     * One-time move of the legacy environment variables into the encrypted SecretStore so existing
     * deployments keep working after the env vars are removed.
     */
    public void migrateLegacyEnvironment() {
        migrateValue(System.getenv(LEGACY_HOST_DIRECTORY_ENV), HOST_DIRECTORY_KEY);
        migrateValue(System.getenv(LEGACY_CONTAINER_DIRECTORY_ENV), CONTAINER_DIRECTORY_KEY);
    }

    void migrateLegacyValues(String hostDirectory, String containerDirectory) {
        migrateValue(hostDirectory, HOST_DIRECTORY_KEY);
        migrateValue(containerDirectory, CONTAINER_DIRECTORY_KEY);
    }

    private void migrateValue(String value, String key) {
        if (value == null || value.isBlank() || secrets.get(key).isPresent()) return;
        secrets.put(key, value.trim().getBytes(StandardCharsets.UTF_8));
        LOGGER.info("[YuDreamAdmin] [QQ 群自动化] migrated legacy media storage setting " + key + " into the plugin SecretStore");
    }

    public String hostDirectory() {
        return read(HOST_DIRECTORY_KEY);
    }

    public String containerDirectory() {
        String value = read(CONTAINER_DIRECTORY_KEY);
        return value == null ? DEFAULT_CONTAINER_DIRECTORY : value;
    }

    public Map<String, Object> view() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("hostDirectory", hostDirectory());
        view.put("containerDirectory", containerDirectory());
        return view;
    }

    public Map<String, Object> save(String hostDirectory, String containerDirectory) {
        write(HOST_DIRECTORY_KEY, hostDirectory);
        write(CONTAINER_DIRECTORY_KEY, containerDirectory);
        return view();
    }

    private void write(String key, String value) {
        if (value == null || value.isBlank()) secrets.delete(key);
        else secrets.put(key, value.trim().getBytes(StandardCharsets.UTF_8));
    }

    private String read(String key) {
        return secrets.get(key)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .filter(value -> !value.isBlank())
                .orElse(null);
    }
}
