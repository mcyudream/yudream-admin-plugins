package online.yudream.plugin.voxelithmap.infrastructure.engine;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 从实例根目录的文件名判定存档版本。
 *
 * <p>与 {@link MinecraftVersions#versionOf(String)} 同式（同一个 {@code 1.x[.y]} 正则口径），
 * 但多了"挑哪一个"的策略：实例根目录里同时躺着 server/fabric/forge 等核心 jar 与一堆无关
 * 依赖时，优先服务端核心命名——那才是这套实例真正在跑的版本。</p>
 *
 * <p>不读 {@code level.dat}：存档本体还没拉回来，而且引擎自己在渲染阶段会读它兜底。</p>
 */
public final class InstanceJarVersions {

    /** 服务端核心命名（与前端旧实现、渲染引擎的判定口径一致）。 */
    private static final Pattern SERVER_CORE = Pattern.compile(
            "server|fabric|forge|paper|purpur|spigot|mohist|arclight|vanilla", Pattern.CASE_INSENSITIVE);

    private InstanceJarVersions() {
    }

    /**
     * 从文件名集合里挑出存档版本。
     *
     * @param fileNames 实例根目录的文件名（可含目录）
     * @return 版本号（如 {@code 1.21.1}）；识别不出返回空
     */
    public static Optional<String> pickVersion(Collection<String> fileNames) {
        if (fileNames == null || fileNames.isEmpty()) {
            return Optional.empty();
        }
        String fallbackVersion = null;
        for (String name : fileNames) {
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".jar")) {
                continue;
            }
            String version = MinecraftVersions.versionOf(name);
            if (version == null) {
                continue;
            }
            if (SERVER_CORE.matcher(name).find()) {
                return Optional.of(version);
            }
            if (fallbackVersion == null) {
                fallbackVersion = version;
            }
        }
        return Optional.ofNullable(fallbackVersion);
    }
}
