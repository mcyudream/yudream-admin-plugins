package online.yudream.plugin.voxelithmap.infrastructure.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 通用子进程执行器：启动 java CLI / 边车、逐行收集输出、超时与取消、进程树终止。
 *
 * <p>为什么统一收在这里：world-map 的 BlueMap 封装把「拼命令、读日志、超时、kill」都塞在
 * 渲染引擎里，一旦要跑第二种 CLI（全景图、分片 worker）就得复制一遍。Voxelith 有 4 个 CLI，
 * 所以把进程生命周期抽成独立组件，各引擎只负责自己的参数与进度语义。</p>
 *
 * <p>进程树终止是重点：渲染期间 Voxelith 会再拉起采集 worker 孙进程，只 kill 父进程会留下
 * 孤儿 worker 继续吃 CPU。这里先取 {@code ProcessHandle.descendants()} 快照再逐个
 * {@code destroyForcibly}，最后兜底杀父进程。</p>
 */
public final class VoxelithProcessRunner {

    /** 取消检查间隔：太密没意义，太疏用户点了取消半天没反应。 */
    private static final long POLL_MILLIS = 200;
    /** 优雅终止的等待时间，超时后强杀。 */
    private static final Duration GRACEFUL_TERMINATION = Duration.ofSeconds(10);
    private static final Duration FORCED_TERMINATION = Duration.ofSeconds(10);

    /** 逐行回调（子进程 stdout/stderr 已合并）。 */
    @FunctionalInterface
    public interface LineConsumer {
        void accept(String line);
    }

    private VoxelithProcessRunner() {
    }

    /**
     * 执行一条命令直到结束、超时或取消。
     *
     * @param spec         命令与工作目录
     * @param onLine       逐行回调（在读取线程里调用；回调里不要做重活）
     * @param canceled     取消探针；返回 {@code true} 时终止进程
     * @throws IOException 进程无法启动
     */
    public static RunResult run(Spec spec, LineConsumer onLine, BooleanSupplier canceled) throws IOException {
        Files.createDirectories(spec.workingDirectory());
        if (spec.logFile() != null && spec.logFile().getParent() != null) {
            Files.createDirectories(spec.logFile().getParent());
        }
        ProcessBuilder builder = new ProcessBuilder(spec.command())
                .directory(spec.workingDirectory().toFile())
                // stderr 并入 stdout：渲染的失败原因经常只在 stderr，分开读会漏
                .redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder logBuffer = new StringBuilder();
        AtomicBoolean reading = new AtomicBoolean(true);
        Thread reader = new Thread(() -> {
            try (BufferedReader reader1 = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader1.readLine()) != null) {
                    logBuffer.append(line).append('\n');
                    if (onLine != null) {
                        try {
                            onLine.accept(line);
                        } catch (RuntimeException ignored) {
                            // 回调异常不该打断读取（否则管道写满会把子进程卡死）
                        }
                    }
                }
            } catch (IOException ignored) {
                // 进程被强杀时流会断开，属预期
            } finally {
                reading.set(false);
            }
        }, "voxelith-cli-reader");
        reader.setDaemon(true);
        reader.start();

        long deadline = System.nanoTime() + spec.timeout().toNanos();
        boolean timedOut = false;
        boolean canceledByUser = false;
        int exitCode;
        while (true) {
            try {
                if (process.waitFor(POLL_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    exitCode = process.exitValue();
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                terminate(process);
                throw new IllegalStateException("渲染线程被中断", e);
            }
            if (canceled != null && canceled.getAsBoolean()) {
                canceledByUser = true;
                terminate(process);
                exitCode = waitQuietly(process);
                break;
            }
            if (System.nanoTime() - deadline > 0) {
                timedOut = true;
                terminate(process);
                exitCode = waitQuietly(process);
                break;
            }
        }

        // 给读取线程一点时间把管道里剩下的行读完，再落盘完整日志
        for (int i = 0; i < 20 && reading.get(); i++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        String log = logBuffer.toString();
        if (spec.logFile() != null) {
            try {
                Files.writeString(spec.logFile(), log, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // 日志落盘失败不影响任务结果
            }
        }
        return new RunResult(exitCode, timedOut, canceledByUser, log, spec.logFile(), heapFromCommand(spec.command()));
    }

    /**
     * 启动一个长驻子进程（分片 worker）并立即返回句柄，不阻塞调用线程。
     *
     * <p>与 {@link #run} 的分工：{@code run} 是「跑完才返回」，适合有明确终点的渲染/全景 CLI；
     * worker 是常驻进程（一直抢占队列直到空或超时），必须能异步启动、随时取日志、随时停。
     * 输出由守护线程持续泵入有界内存缓冲，{@link ActiveProcess#logTail(int)} 取尾部。</p>
     */
    public static ActiveProcess start(Spec spec, LineConsumer onLine) throws IOException {
        Files.createDirectories(spec.workingDirectory());
        if (spec.logFile() != null && spec.logFile().getParent() != null) {
            Files.createDirectories(spec.logFile().getParent());
        }
        Process process = new ProcessBuilder(spec.command())
                .directory(spec.workingDirectory().toFile())
                .redirectErrorStream(true)
                .start();
        return new ActiveProcess(process, spec, onLine);
    }

    /**
     * 终止进程树：先拿快照再逐个强杀，最后兜底杀父进程。
     *
     * <p>快照必须在杀之前取——父进程一死，{@code descendants()} 就查不到了，
     * 孙进程会变成孤儿。</p>
     */
    public static void terminate(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroy();
        if (waitFor(process, GRACEFUL_TERMINATION)) {
            forceKill(descendants);
            return;
        }
        forceKill(descendants);
        process.destroyForcibly();
        waitFor(process, FORCED_TERMINATION);
    }

    private static void forceKill(List<ProcessHandle> handles) {
        for (ProcessHandle handle : handles) {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        }
    }

    private static boolean waitFor(Process process, Duration duration) {
        try {
            return process.waitFor(duration.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static int waitQuietly(Process process) {
        try {
            process.waitFor(FORCED_TERMINATION.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            return process.isAlive() ? -1 : process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /**
     * 从完整日志里取尾部若干字符作为失败原因（NUL 换成空格，免得上游把二进制混进日志）。
     */
    public static String logTail(String log, int maxChars) {
        if (log == null || log.isEmpty()) {
            return "";
        }
        String sanitized = log.replace('\0', ' ').trim();
        if (sanitized.length() <= maxChars) {
            return sanitized;
        }
        return sanitized.substring(sanitized.length() - maxChars);
    }

    /** 从启动命令里取渲染子进程的 -Xmx 值（如 4096m）；未设置返回 null。 */
    public static String heapFromCommand(List<String> command) {
        if (command == null) {
            return null;
        }
        for (String part : command) {
            if (part.startsWith("-Xmx")) {
                return part.substring(4);
            }
        }
        return null;
    }

    /** Voxelith CLI 的退出码语义（见 {@code RenderMapCli}）。 */
    public static String describeExitCode(int exitCode) {
        return switch (exitCode) {
            case 0 -> "成功";
            case 1 -> "渲染失败";
            case 2 -> "参数错误";
            case 3 -> "超出单次渲染区块上限：max-chunks 是防内存溢出的护栏（不是批次大小）。"
                    + "当前 CLI 模式下引擎会把整个窗口的网格一次性驻留内存，切片/并行渲染需要引擎侧分片管线（暂未提供入口）。"
                    + "确需整图渲染时，请把渲染设置里的 max-chunks 调大到覆盖全部非空区块，"
                    + "并同步大幅调大宿主设置 yudream.voxelith-map.heap-mib";
            case 4 -> "内存不足：窗口内区块的网格放不进渲染子进程堆。"
                    + "请在宿主设置调大 yudream.voxelith-map.heap-mib（默认 4096，大图建议 8192 或更高），"
                    + "或缩小渲染范围。注意批次参数 batch-chunks 只作用于采集阶段，不减少烘焙阶段的内存驻留";
            // 128+N：进程被信号终止。137 = SIGKILL（128+9），最典型的来源是容器/主机的 OOM Killer。
            // 这一类绝不能建议「调大堆」——堆调得越大，进程总占用越高，越容易被内核挑中杀掉。
            case 137 -> "渲染进程被系统强制终止（SIGKILL，退出码 137）——不是 Java 堆溢出，而是进程被外部杀掉："
                    + "常见原因是容器（cgroup）或主机内存上限触发 OOM Killer（宿主 JVM + voxelith 边车 + "
                    + "本渲染子进程的总和越界），也可能是有人/脚本 kill -9 或平台重启。"
                    + "请核对下方内存水位与 dmesg 的 OOM 记录；处理方向是「减少总占用或放宽容器内存」，"
                    + "而不是调大 heap-mib：可以缩小渲染范围、调小 max-chunks、提高 Y 轴裁剪下限、"
                    + "或在大图渲染期间关闭边车（yudream.voxelith-map.sidecar-enabled=false，省约 2GB）";
            case 143 -> "渲染进程收到 SIGTERM（退出码 143）被停止：通常是容器停服/重启或外部优雅停止";
            case -1 -> "进程未正常退出";
            default -> "未知退出码 " + exitCode;
        };
    }

    /**
     * 该退出码是否适合给出「调大 heap-mib」的建议。
     *
     * <p>被信号杀掉的（137/143）与堆无关：调大堆只会让进程总占用更高、更容易被 OOM Killer 挑中，
     * 因此这两类退出码不追加堆提示，改由内存水位描述给出判断依据。</p>
     */
    public static boolean heapHintApplies(int exitCode) {
        return exitCode < 128 && exitCode != 0;
    }

    /** 命令描述（日志用，不含环境变量）。 */
    public static String describe(List<String> command) {
        return String.join(" ", command.stream()
                .map(part -> part.contains(" ") ? "\"" + part + "\"" : part)
                .toList());
    }

    /** 已启动的长驻子进程句柄（见 {@link #start}）。 */
    public static final class ActiveProcess {

        /** 内存里保留的日志上限：worker 可能跑很久，无界缓冲会把堆吃光。 */
        private static final int LOG_LIMIT_CHARS = 64 * 1024;

        private final Process process;
        private final Spec spec;
        private final StringBuilder log = new StringBuilder();
        private final AtomicBoolean reading = new AtomicBoolean(true);

        private ActiveProcess(Process process, Spec spec, LineConsumer onLine) {
            this.process = process;
            this.spec = spec;
            Thread reader = new Thread(() -> {
                try (BufferedReader source = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = source.readLine()) != null) {
                        synchronized (log) {
                            log.append(line).append('\n');
                            if (log.length() > LOG_LIMIT_CHARS) {
                                log.delete(0, log.length() - LOG_LIMIT_CHARS);
                            }
                        }
                        if (onLine != null) {
                            try {
                                onLine.accept(line);
                            } catch (RuntimeException ignored) {
                                // 回调异常不该打断读取（否则管道写满会卡死子进程）
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // 被强杀时流会断开，属预期
                } finally {
                    reading.set(false);
                    flushLog();
                }
            }, "voxelith-" + spec.label() + "-reader");
            reader.setDaemon(true);
            reader.start();
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        public long pid() {
            return process.pid();
        }

        public Path logFile() {
            return spec.logFile();
        }

        public String label() {
            return spec.label();
        }

        /** 是否仍在读取输出（用于判断日志是否已完整落盘）。 */
        public boolean reading() {
            return reading.get();
        }

        public String logTail(int maxChars) {
            synchronized (log) {
                // 必须显式限定：同名实例方法会遮蔽外部类的静态 logTail(String,int)
                return VoxelithProcessRunner.logTail(log.toString(), maxChars);
            }
        }

        /** 停止进程树并返回退出码；{@code -1} 表示没等到正常退出。 */
        public int stop() {
            terminate(process);
            int exitCode = waitQuietly(process);
            flushLog();
            return exitCode;
        }

        private void flushLog() {
            if (spec.logFile() == null) {
                return;
            }
            String text;
            synchronized (log) {
                text = log.toString();
            }
            try {
                Files.writeString(spec.logFile(), text, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // 日志落盘失败不影响 worker 生命周期
            }
        }
    }

    /**
     * 一次执行的输入。
     *
     * @param command          完整命令行
     * @param workingDirectory 子进程工作目录
     * @param logFile          完整日志落盘位置；可为 {@code null}
     * @param timeout          超时
     * @param label            展示名（日志里标明是哪个入口）
     */
    public record Spec(List<String> command, Path workingDirectory, Path logFile, Duration timeout, String label) {

        public Spec {
            if (command == null || command.isEmpty()) {
                throw new IllegalArgumentException("命令不能为空");
            }
            if (workingDirectory == null) {
                throw new IllegalArgumentException("工作目录不能为空");
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("超时必须为正");
            }
            label = label == null || label.isBlank() ? "voxelith-cli" : label;
        }
    }

    /**
     * 一次执行的结果。
     *
     * @param exitCode 退出码（{@code -1} 表示进程未正常退出）
     * @param timedOut 是否因超时被终止
     * @param canceled 是否被用户取消
     * @param log      完整日志
     * @param logFile  日志落盘位置
     */
    public record RunResult(int exitCode, boolean timedOut, boolean canceled, String log, Path logFile,
                            String heapLabel) {

        /** 兼容构造：启动即失败的场景没有堆信息。 */
        public RunResult(int exitCode, boolean timedOut, boolean canceled, String log, Path logFile) {
            this(exitCode, timedOut, canceled, log, logFile, null);
        }

        /** 是否正常成功。 */
        public boolean succeeded() {
            return exitCode == 0 && !timedOut && !canceled;
        }

        /**
         * 被信号终止时的内存水位描述（容器上限 / 已用 / 主机可用）与内存参数。
         *
         * <p>读不到（非 Linux、cgroup 不可见）就返回空串——排查信息"有则更好"，绝不影响失败原因本身。</p>
         */
        private static String memoryHint(String heap) {
            ContainerMemory memory = ContainerMemory.read();
            StringBuilder text = new StringBuilder();
            if (memory.known()) {
                text.append("（").append(memory.describe());
            }
            else if (heap != null) {
                text.append("（本渲染堆 -Xmx").append(heap);
            }
            else {
                return "";
            }
            if (heap != null && memory.known()) {
                text.append("，本渲染堆 -Xmx").append(heap);
            }
            return text.append("）").toString();
        }

        /** 人类可读的失败原因；成功时返回空串。 */
        public String failureReason() {
            if (timedOut) {
                return "渲染超时被终止";
            }
            if (canceled) {
                return "渲染已取消";
            }
            if (exitCode == 0) {
                return "";
            }
            String tail = logTail(log, 1_500);
            String code = describeExitCode(exitCode);
            String heap = heapLabel();
            String heapHint = heap == null || !heapHintApplies(exitCode)
                    ? ""
                    : "（当前渲染堆 -Xmx" + heap + "，宿主设置 yudream.voxelith-map.heap-mib 可调）";
            // 被信号终止时给出内存水位：这是判断「容器该扩容」还是「窗口该缩小」的唯一依据，
            // 否则用户只能登机器翻 dmesg 才知道 137 是怎么回事
            String memoryHint = exitCode >= 128 ? memoryHint(heap) : "";
            String prefix = code + heapHint + memoryHint;
            return tail.isEmpty() ? prefix : prefix + "：" + tail;
        }



        /** 供 {@code toString()} / 日志用的简短描述。 */
        public String summary() {
            return String.format(Locale.ROOT, "exit=%d timedOut=%s canceled=%s", exitCode, timedOut, canceled);
        }
    }
}
