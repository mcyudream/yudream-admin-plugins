package online.yudream.plugin.voxelithmap.infrastructure.storage;

import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithRuntimeConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;

/**
 * 任务完整日志的落盘与读取。
 *
 * <p>为什么需要它：任务文档里只留日志尾部（4000 字），因为渲染日志动辄几万行（每个批次一行），
 * 全量写进文档存储既撑爆文档也毫无必要；而子进程的完整输出其实**已经**写在任务临时目录里，
 * 只是任务一结束就随目录一起删了。于是「日志不完整、只有尾部」成了必然。这里在任务收尾时把那份
 * 完整日志搬到持久目录（{@code <runtime-root>/logs/}），并对外提供读取入口，让需要排查的人能拿到全量。</p>
 *
 * <p>尽力而为：搬迁失败只影响「完整日志」这一项，任务本身照常落状态；读取时文件不存在返回空，
 * 由调用方给可读提示（老任务、或日志已被清理）。</p>
 */
public final class TaskLogStore {

    private static final String SUFFIX = ".log";

    private final Path logsDir;

    public TaskLogStore(VoxelithRuntimeConfig config) {
        this.logsDir = config.logsDir();
    }

    /** 把任务临时目录里的完整日志搬到持久目录（同名覆盖）。失败返回 false。 */
    public boolean keep(String taskId, Path source) {
        if (taskId == null || taskId.isBlank() || source == null || !Files.isRegularFile(source)) {
            return false;
        }
        try {
            Files.createDirectories(logsDir);
            Files.copy(source, logFile(taskId), StandardCopyOption.REPLACE_EXISTING);
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    /** 完整日志文件；不存在返回空。 */
    public Optional<Path> find(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        Path file = logFile(taskId);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    /** 完整日志字节数；不存在返回 0。 */
    public long sizeOf(String taskId) {
        return find(taskId).map(file -> {
            try {
                return Files.size(file);
            }
            catch (IOException e) {
                return 0L;
            }
        }).orElse(0L);
    }

    /** 清理若干任务的日志（地图被彻底删除时调用）。返回删除个数。 */
    public int deleteFor(Iterable<String> taskIds) {
        int removed = 0;
        if (taskIds == null) {
            return 0;
        }
        for (String taskId : taskIds) {
            try {
                if (taskId != null && Files.deleteIfExists(logFile(taskId))) {
                    removed++;
                }
            }
            catch (IOException ignored) {
                // 清理失败不影响删除流程
            }
        }
        return removed;
    }

    /**
     * 清理超过保留期的日志文件。
     *
     * <p>每次渲染都留一份完整日志，长期跑下来目录会一直长大；按修改时间清理即可，
     * 任务文档里的日志尾部不受影响（那是另一份数据）。</p>
     *
     * @return 删除的文件数
     */
    public int prune(java.time.Duration retention) {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            return 0;
        }
        if (!Files.isDirectory(logsDir)) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - retention.toMillis();
        int removed = 0;
        try (var entries = Files.list(logsDir)) {
            for (Path file : entries.toList()) {
                try {
                    if (Files.isRegularFile(file) && Files.getLastModifiedTime(file).toMillis() < cutoff) {
                        Files.deleteIfExists(file);
                        removed++;
                    }
                }
                catch (IOException ignored) {
                    // 单个文件清理失败继续
                }
            }
        }
        catch (IOException ignored) {
            // 目录不可读：下次启动再试
        }
        return removed;
    }

    private Path logFile(String taskId) {
        // 任务 id 由插件生成（task-xxxx），仍做一次白名单化，避免任何路径拼接风险
        String safe = taskId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return logsDir.resolve(safe + SUFFIX);
    }
}
