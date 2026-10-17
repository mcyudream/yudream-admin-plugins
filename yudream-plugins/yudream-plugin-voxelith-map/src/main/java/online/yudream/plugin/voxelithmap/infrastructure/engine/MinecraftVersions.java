package online.yudream.plugin.voxelithmap.infrastructure.engine;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MC 版本号与资源包文件名处理（纯函数，便于单测）。
 *
 * <p>为什么插件也要认版本号：引擎的「采集版本自动判定」把<b>资源包文件名</b>排在存档版本之前
 * ——{@code models.json.gz} 里的模型引用的贴图名来自同一个包，包与采集版本不一致就会整片品红
 * （1.20.3 起 {@code grass → short_grass}）。而采集 CLI 自己没有这套判定（{@code HarvestCliOptions}
 * 默认写死 1.20.1），插件必须在<b>采集之前</b>就把版本定下来，因此得自己能读文件名。</p>
 *
 * <p>口径与上游 {@code online.yudream.voxelith.server.support.McVersionResolver} 保持一致：
 * 同一套正则，上游改了要同步改这里。</p>
 */
public final class MinecraftVersions {

    /** 与上游 {@code McVersionResolver.VERSION} 同式：{@code client-1.21.1.jar} → {@code 1.21.1}。 */
    private static final Pattern VERSION = Pattern.compile("(1\\.\\d+(?:\\.\\d+)?)");

    /** Windows/POSIX 都不允许出现在文件名里的字符（含控制字符）。 */
    private static final Pattern ILLEGAL_IN_NAME = Pattern.compile("[<>:\"/\\\\|?*\\p{Cntrl}]");

    /** 原名长度上限（防止把超长上传名拼进路径）。 */
    private static final int MAX_NAME_LENGTH = 140;

    /** 上传原名不可用时 client jar 的中性名。 */
    public static final String FALLBACK_JAR_NAME = "client.jar";

    private MinecraftVersions() {
    }

    /**
     * 文件名里的 MC 版本号。
     *
     * @return 版本号；文件名里没有（或为 {@code null}）时返回 {@code null}
     */
    public static String versionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        Matcher matcher = VERSION.matcher(fileName);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 落盘用的 client jar 文件名：原名可用就用原名（引擎要靠它判版本），否则用中性名。 */
    public static String jarFileName(String originalName) {
        String safe = safeJarFileName(originalName);
        return safe == null ? FALLBACK_JAR_NAME : safe;
    }

    /**
     * 把上传时的原始文件名收敛成可安全落盘的 jar 文件名。
     *
     * <p>为什么值得保留原名：引擎按「资源包文件名 → 存档版本」判定采集版本，我们把 client jar
     * 落成中性的 {@code client.jar} 等于把最可靠的那条判据丢掉（引擎给的版本体检也会因此变哑）。
     * 原名不可用（带路径、非法字符、不是 jar）时返回 {@code null}，由调用方退回中性名。</p>
     *
     * @param originalName 上传时的文件名（可能带路径分隔符）
     * @return 可安全落盘的文件名；不可用时返回 {@code null}
     */
    public static String safeJarFileName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return null;
        }
        String name = originalName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = ILLEGAL_IN_NAME.matcher(name.trim()).replaceAll("_").trim();
        // Windows 上以点或空格结尾的名字会被静默截断，先去掉
        while (!name.isEmpty() && (name.endsWith(".") || name.endsWith(" "))) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            return null;
        }
        return name.toLowerCase(Locale.ROOT).endsWith(".jar") ? name : null;
    }
}
