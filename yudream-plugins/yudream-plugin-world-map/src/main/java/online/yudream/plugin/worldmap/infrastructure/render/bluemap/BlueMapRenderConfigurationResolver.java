package online.yudream.plugin.worldmap.infrastructure.render.bluemap;

import online.yudream.base.plugin.spi.system.FrameworkServices;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** Resolves the bundled worker by default, with an explicit external-runtime escape hatch. */
public final class BlueMapRenderConfigurationResolver {

    public static final String JAVA_PATH = "yudream.world-map.bluemap.java-path";
    public static final String CLI_PATH = "yudream.world-map.bluemap.cli-path";
    public static final String CONFIG_TEMPLATE = "yudream.world-map.bluemap.config-template";
    public static final String STORAGE_ROOT = "yudream.world-map.bluemap.storage-root";
    public static final String EXTERNAL_RUNTIME_ENABLED = "yudream.world-map.bluemap.external-runtime-enabled";
    public static final String HEAP_MIB = "yudream.world-map.bluemap.heap-mib";
    public static final String TIMEOUT_MINUTES = "yudream.world-map.bluemap.timeout-minutes";
    public static final String MINECRAFT_VERSION = "yudream.world-map.bluemap.minecraft-version";
    /** Optional persistent BlueMap data cache. Each Minecraft version receives its own child directory. */
    public static final String RESOURCE_CACHE_ROOT = "yudream.world-map.bluemap.resource-cache-root";
    /** Number of BlueMap worker threads used for detailed-tile rendering. */
    public static final String RENDER_THREAD_COUNT = "yudream.world-map.bluemap.render-thread-count";
    private static final int MAX_RENDER_THREADS = 64;

    public Optional<BlueMapRenderConfiguration> resolve(FrameworkServices framework) {
        if (framework == null) return Optional.empty();
        String minecraftVersion = minecraftVersion(framework);
        Path cacheRoot = resourceCacheRoot(framework);
        if (!Boolean.parseBoolean(framework.setting(EXTERNAL_RUNTIME_ENABLED).orElse("false"))) {
            return Optional.of(new BlueMapRenderConfiguration(defaultJavaExecutable(), null, null, Path.of("output"),
                    heapMiB(framework), timeout(framework), minecraftVersion, cacheRoot, true, renderThreadCount(framework)));
        }
        Optional<String> java = framework.setting(JAVA_PATH);
        Optional<String> cli = framework.setting(CLI_PATH);
        Optional<String> template = framework.setting(CONFIG_TEMPLATE);
        Optional<String> storage = framework.setting(STORAGE_ROOT);
        if (java.isEmpty() || cli.isEmpty() || template.isEmpty() || storage.isEmpty()) {
            throw new IllegalStateException("External BlueMap runtime requires java-path, cli-path, config-template and storage-root");
        }
        Path storageRoot = Path.of(storage.get());
        if (storageRoot.isAbsolute() || storageRoot.toString().contains("..")) {
            throw new IllegalStateException("BlueMap storage root must be a relative task-local path");
        }
        return Optional.of(new BlueMapRenderConfiguration(
                Path.of(java.get()), Path.of(cli.get()), Path.of(template.get()), storageRoot,
                heapMiB(framework), timeout(framework), minecraftVersion, cacheRoot, false, renderThreadCount(framework)
        ));
    }

    private String minecraftVersion(FrameworkServices framework) {
        String minecraftVersion = framework.setting(MINECRAFT_VERSION).map(String::trim).orElse("");
        if (minecraftVersion.isEmpty()) {
            return null;
        }
        if (!minecraftVersion.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalStateException("Invalid BlueMap setting " + MINECRAFT_VERSION);
        }
        return minecraftVersion;
    }

    private Path resourceCacheRoot(FrameworkServices framework) {
        Path cacheRoot = framework.setting(RESOURCE_CACHE_ROOT)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Path::of)
                .orElse(null);
        if (cacheRoot != null && !cacheRoot.isAbsolute()) {
            throw new IllegalStateException("BlueMap resource cache root must be an absolute path");
        }
        return cacheRoot;
    }

    private int heapMiB(FrameworkServices framework) {
        return parsePositive(framework.setting(HEAP_MIB).orElse("1024"), 256, HEAP_MIB);
    }

    private Duration timeout(FrameworkServices framework) {
        return Duration.ofMinutes(parsePositive(framework.setting(TIMEOUT_MINUTES).orElse("60"), 1, TIMEOUT_MINUTES));
    }

    private int renderThreadCount(FrameworkServices framework) {
        String configured = framework.setting(RENDER_THREAD_COUNT).map(String::trim).orElse("");
        if (configured.isEmpty()) {
            return defaultRenderThreadCount(Runtime.getRuntime().availableProcessors());
        }
        int threads = parsePositive(configured, 1, RENDER_THREAD_COUNT);
        if (threads > MAX_RENDER_THREADS) {
            throw new IllegalStateException("Invalid BlueMap setting " + RENDER_THREAD_COUNT);
        }
        return threads;
    }

    static int defaultRenderThreadCount(int availableProcessors) {
        return Math.min(16, Math.max(1, availableProcessors - 2));
    }

    private Path defaultJavaExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
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
