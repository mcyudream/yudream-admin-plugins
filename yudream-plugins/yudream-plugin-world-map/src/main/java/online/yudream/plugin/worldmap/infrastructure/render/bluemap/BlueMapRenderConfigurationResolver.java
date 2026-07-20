package online.yudream.plugin.worldmap.infrastructure.render.bluemap;

import online.yudream.base.plugin.spi.system.FrameworkServices;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** Resolves the optional worker only from administrator-provided settings. */
public final class BlueMapRenderConfigurationResolver {

    public static final String JAVA_PATH = "yudream.world-map.bluemap.java-path";
    public static final String CLI_PATH = "yudream.world-map.bluemap.cli-path";
    public static final String CONFIG_TEMPLATE = "yudream.world-map.bluemap.config-template";
    public static final String STORAGE_ROOT = "yudream.world-map.bluemap.storage-root";
    public static final String HEAP_MIB = "yudream.world-map.bluemap.heap-mib";
    public static final String TIMEOUT_MINUTES = "yudream.world-map.bluemap.timeout-minutes";
    public static final String MINECRAFT_VERSION = "yudream.world-map.bluemap.minecraft-version";
    /** Optional persistent BlueMap data cache. Each Minecraft version receives its own child directory. */
    public static final String RESOURCE_CACHE_ROOT = "yudream.world-map.bluemap.resource-cache-root";

    public Optional<BlueMapRenderConfiguration> resolve(FrameworkServices framework) {
        if (framework == null) return Optional.empty();
        Optional<String> java = framework.setting(JAVA_PATH);
        Optional<String> cli = framework.setting(CLI_PATH);
        Optional<String> template = framework.setting(CONFIG_TEMPLATE);
        Optional<String> storage = framework.setting(STORAGE_ROOT);
        if (java.isEmpty() || cli.isEmpty() || template.isEmpty() || storage.isEmpty()) return Optional.empty();
        Path storageRoot = Path.of(storage.get());
        if (storageRoot.isAbsolute() || storageRoot.toString().contains("..")) {
            throw new IllegalStateException("BlueMap storage root must be a relative task-local path");
        }
        String minecraftVersion = framework.setting(MINECRAFT_VERSION).orElse("1.21.4").trim();
        if (!minecraftVersion.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalStateException("Invalid BlueMap setting " + MINECRAFT_VERSION);
        }
        Path cacheRoot = framework.setting(RESOURCE_CACHE_ROOT)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Path::of)
                .orElse(null);
        if (cacheRoot != null && !cacheRoot.isAbsolute()) {
            throw new IllegalStateException("BlueMap resource cache root must be an absolute path");
        }
        return Optional.of(new BlueMapRenderConfiguration(
                Path.of(java.get()), Path.of(cli.get()), Path.of(template.get()), storageRoot,
                parsePositive(framework.setting(HEAP_MIB).orElse("1024"), 256, HEAP_MIB),
                Duration.ofMinutes(parsePositive(framework.setting(TIMEOUT_MINUTES).orElse("60"), 1, TIMEOUT_MINUTES)),
                minecraftVersion, cacheRoot
        ));
    }

    private int parsePositive(String text, int min, String setting) {
        try {
            int value = Integer.parseInt(text);
            if (value < min) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid BlueMap setting " + setting);
        }
    }
}
