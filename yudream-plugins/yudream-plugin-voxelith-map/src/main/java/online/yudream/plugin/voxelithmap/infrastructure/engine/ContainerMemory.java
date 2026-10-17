package online.yudream.plugin.voxelithmap.infrastructure.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 运行环境的内存水位（容器 cgroup 上限 + 主机内存），用于把「进程被系统杀掉」讲清楚。
 *
 * <p>为什么需要它：渲染子进程被 OOM Killer 杀掉时，进程只留下一个退出码 137（128+9，SIGKILL），
 * 日志里看不出任何原因，而「调大堆」这种常规建议恰恰会让它更容易被杀——被杀的原因通常是
 * <b>容器内存上限</b>：宿主 JVM + voxelith 边车 + 渲染子进程的<b>总和</b>越过 cgroup 上限，
 * 内核挑最大的那个进程开刀。把上限、当前用量与主机可用内存一起写进失败原因，用户不必登机器
 * 翻 dmesg 就能判断是「该扩容器」还是「该缩窗口」。</p>
 *
 * <p>只读、尽力而为：非 Linux、cgroup 路径缺失、权限不足都返回未知（{@code -1}），绝不影响渲染本身。</p>
 */
public record ContainerMemory(long limitBytes, long currentBytes, long hostTotalBytes, long hostAvailableBytes) {

    /** 未知值。 */
    public static final long UNKNOWN = -1L;

    private static final ContainerMemory EMPTY = new ContainerMemory(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);

    /** 读取当前环境（失败即未知）。 */
    public static ContainerMemory read() {
        return readFrom(Path.of("/sys/fs/cgroup"), Path.of("/proc/meminfo"));
    }

    /** 便于测试的入口：cgroup 根目录与 meminfo 路径可注入。 */
    static ContainerMemory readFrom(Path cgroupRoot, Path meminfo) {
        long limit = UNKNOWN;
        long current = UNKNOWN;
        // cgroup v2
        limit = readLong(cgroupRoot.resolve("memory.max"));
        current = readLong(cgroupRoot.resolve("memory.current"));
        if (limit == UNKNOWN) {
            // cgroup v1
            limit = readLong(cgroupRoot.resolve("memory/memory.limit_in_bytes"));
            current = readLong(cgroupRoot.resolve("memory/memory.usage_in_bytes"));
        }
        long hostTotal = UNKNOWN;
        long hostAvailable = UNKNOWN;
        try {
            if (Files.isReadable(meminfo)) {
                for (String line : Files.readAllLines(meminfo, StandardCharsets.UTF_8)) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("memtotal:")) {
                        hostTotal = parseKib(line);
                    }
                    else if (lower.startsWith("memavailable:")) {
                        hostAvailable = parseKib(line);
                    }
                }
            }
        }
        catch (IOException ignored) {
            // 读不到就保持未知
        }
        if (limit == UNKNOWN && current == UNKNOWN && hostTotal == UNKNOWN) {
            return EMPTY;
        }
        return new ContainerMemory(limit, current, hostTotal, hostAvailable);
    }

    /** cgroup v1 的 {@code limit_in_bytes} 会用超大值表示「无限制」。 */
    private static final long EFFECTIVELY_UNLIMITED = 1L << 60;

    private static long readLong(Path path) {
        try {
            if (!Files.isReadable(path)) {
                return UNKNOWN;
            }
            String text = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (text.isEmpty() || "max".equals(text)) {
                return UNKNOWN;
            }
            long value = Long.parseLong(text);
            return value >= EFFECTIVELY_UNLIMITED ? UNKNOWN : value;
        }
        catch (IOException | NumberFormatException e) {
            return UNKNOWN;
        }
    }

    /** 把 {@code MemTotal:  32768148 kB} 转成字节。 */
    private static long parseKib(String line) {
        String[] parts = line.split("\\s+");
        for (String part : parts) {
            try {
                return Long.parseLong(part.trim()) * 1024L;
            }
            catch (NumberFormatException ignored) {
                // 继续找数字段（字段间是空白，首个能解析的就是数值）
            }
        }
        return UNKNOWN;
    }

    /** 是否有可用信息。 */
    public boolean known() {
        return limitBytes != UNKNOWN || hostTotalBytes != UNKNOWN;
    }

    /** 人读描述：{@code 容器内存上限 24.0 GiB（已用 21.3 GiB）；主机 32.0 GiB（可用 4.1 GiB）}。 */
    public String describe() {
        StringBuilder text = new StringBuilder();
        if (limitBytes != UNKNOWN) {
            text.append("容器内存上限 ").append(gib(limitBytes));
            if (currentBytes != UNKNOWN) {
                text.append("（已用 ").append(gib(currentBytes)).append('）');
            }
        }
        else if (hostTotalBytes != UNKNOWN) {
            text.append("未设置容器内存上限（读到的是主机内存）");
        }
        if (hostTotalBytes != UNKNOWN) {
            if (text.length() > 0) {
                text.append("；");
            }
            text.append("主机 ").append(gib(hostTotalBytes));
            if (hostAvailableBytes != UNKNOWN) {
                text.append("（可用 ").append(gib(hostAvailableBytes)).append('）');
            }
        }
        return text.toString();
    }

    private static String gib(long bytes) {
        return String.format(Locale.ROOT, "%.1f GiB", bytes / 1073741824.0);
    }
}
