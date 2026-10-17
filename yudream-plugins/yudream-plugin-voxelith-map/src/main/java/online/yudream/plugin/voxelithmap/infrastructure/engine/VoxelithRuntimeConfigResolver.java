package online.yudream.plugin.voxelithmap.infrastructure.engine;

import online.yudream.base.plugin.spi.system.FrameworkServices;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 从宿主只读设置项解析 {@link VoxelithRuntimeConfig}。
 *
 * <p>宿主没有「插件私有数据目录」这类 SPI，因此运行根是一个**相对宿主工作目录的路径**
 * （默认 {@code data/voxelith-map}）—— 这与 world-map 用 {@code bluemap.storage-root}
 * 相对路径的既有做法一致。需要换盘时把设置项写成绝对路径即可。</p>
 *
 * <p>设置项（宿主 {@code FrameworkServices.setting} 只读，改值需在宿主侧配置）：</p>
 * <pre>
 * yudream.voxelith-map.runtime-root           运行根（默认 data/voxelith-map）
 * yudream.voxelith-map.java-path              java 可执行文件（默认当前 JVM 的 java.home/bin/java）
 * yudream.voxelith-map.engine-jar             外部引擎 fat jar（给排障用，默认用内置）
 * yudream.voxelith-map.worker-classpath       外部 worker classpath（逗号/分号分隔）
 * yudream.voxelith-map.models-file            显式 models.json.gz
 * yudream.voxelith-map.heap-mib               渲染子进程堆（默认 4096）
 * yudream.voxelith-map.timeout-minutes        单次渲染超时（默认 180）
 * yudream.voxelith-map.max-concurrent-tasks   并发任务数（默认 1）
 * yudream.voxelith-map.sidecar-enabled        是否启用边车（默认 true）
 * yudream.voxelith-map.sidecar-port           边车端口（默认 0 = 自动）
 * yudream.voxelith-map.sidecar-heap-mib       边车堆（默认 2048）
 * yudream.voxelith-map.oom-retry              渲染 OOM 时自动双倍堆重试一次（默认 true）
 * </pre>
 */
public final class VoxelithRuntimeConfigResolver {

    public static final String KEY_PREFIX = "yudream.voxelith-map.";

    private final FrameworkServices framework;

    public VoxelithRuntimeConfigResolver(FrameworkServices framework) {
        this.framework = framework;
    }

    /** 解析配置；任何取值缺失都用安全默认值，不抛异常（配置错不该让插件起不来）。 */
    public VoxelithRuntimeConfig resolve() {
        return new VoxelithRuntimeConfig(
                setting("java-path").map(Path::of).orElseGet(VoxelithRuntimeConfigResolver::defaultJavaExecutable),
                Path.of(setting("runtime-root").orElse("data/voxelith-map")),
                setting("engine-jar").map(Path::of).orElse(null),
                setting("worker-classpath").map(VoxelithRuntimeConfigResolver::splitPaths).orElse(List.of()),
                setting("models-file").map(Path::of).orElse(null),
                intSetting("heap-mib", VoxelithRuntimeConfig.DEFAULT_HEAP_MIB),
                intSetting("timeout-minutes", VoxelithRuntimeConfig.DEFAULT_TIMEOUT_MINUTES),
                intSetting("max-concurrent-tasks", 1),
                booleanSetting("sidecar-enabled", true),
                intSetting("sidecar-port", 0),
                intSetting("sidecar-heap-mib", VoxelithRuntimeConfig.DEFAULT_SIDECAR_HEAP_MIB),
                booleanSetting("oom-retry", true));
    }

    private Optional<String> setting(String key) {
        try {
            return framework.setting(KEY_PREFIX + key)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private int intSetting(String key, int fallback) {
        return setting(key).flatMap(value -> {
            try {
                return Optional.of(Integer.parseInt(value));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }).orElse(fallback);
    }

    private boolean booleanSetting(String key, boolean fallback) {
        return setting(key).map(value -> switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> fallback;
        }).orElse(fallback);
    }

    /** 逗号/分号分隔的路径列表。 */
    static List<String> splitPaths(String raw) {
        List<String> paths = new ArrayList<>();
        for (String piece : raw.split("[,;]")) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                paths.add(trimmed);
            }
        }
        return List.copyOf(paths);
    }

    /** 当前 JVM 同款的 java 可执行文件（渲染子进程与宿主同版本最省心）。 */
    public static Path defaultJavaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    }
}
