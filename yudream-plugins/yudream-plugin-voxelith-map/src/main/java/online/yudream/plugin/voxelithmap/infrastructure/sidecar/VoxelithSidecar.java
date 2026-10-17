package online.yudream.plugin.voxelithmap.infrastructure.sidecar;

import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithBundledRuntime;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithCliRenderEngine;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithProcessRunner;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithRuntimeConfig;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Voxelith 边车进程：把**完全未修改**的 {@code voxelith-server.jar} 作为本地服务拉起。
 *
 * <p>职责边界（重要）：边车只承担它真正独有的能力——</p>
 * <ul>
 *   <li>二维地表预览（{@code WorldPreviewRenderer} 用 BiomeTintResolver + TextureColorSampler
 *       出带群系染色的框选底图，只存在于 Spring 上下文里，没有 CLI 入口）；</li>
 *   <li>存档编目（解压 zip、识别维度、给出 level.dat/版本等元信息）。</li>
 * </ul>
 * <p><b>渲染绝不经由边车</b>：fat jar 形态下 Voxelith 的
 * {@code RenderProcessLauncher.canSpawn()} 恒为 false，网页触发的渲染会退化成
 * 「在 web 进程内跑」，大图直接 OOM 打死边车（连带预览一起失效）。渲染统一走
 * {@link VoxelithCliRenderEngine} 的独立子进程。</p>
 *
 * <p>与 Voxelith 的契约全在命令行上，不碰它一行代码：{@code --server.address=127.0.0.1}
 * （只监听本机）、{@code --server.port}（可自动挑空闲端口）、
 * {@code --yudream.voxelith.{work-dir,publish-dir,upload-dir}} 把它的全部读写
 * 收进插件运行根、{@code --logging.file.name} 落盘日志。Voxelith 自己不带鉴权，
 * 因此「只监听 127.0.0.1 + 随机端口」是必须的边界，而不是可选项。</p>
 */
public final class VoxelithSidecar {

    /** 就绪探测的最长等待（Spring Boot 首次启动 + 类扫描，冷启动通常 3~8 秒）。 */
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    /** 探测间隔。 */
    private static final long PROBE_INTERVAL_MILLIS = 500;
    /** 启动失败后的冷却期，避免前端反复点预览把宿主刷爆。 */
    private static final Duration FAILURE_COOLDOWN = Duration.ofSeconds(30);

    private final VoxelithRuntimeConfig config;
    private final VoxelithCliRenderEngine.RuntimeAccessor runtimeAccessor;

    private final Object lock = new Object();
    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private Process process;
    private int port;
    private Path sidecarDir;
    private Path logFile;
    private String lastError;
    private Instant failedAt;
    private volatile boolean shuttingDown;

    public VoxelithSidecar(VoxelithRuntimeConfig config) {
        this.config = config;
        this.runtimeAccessor = VoxelithCliRenderEngine.defaultAccessor(config);
    }

    /** 边车状态。 */
    public enum State {
        /** 未启动（或已关闭）。 */
        STOPPED,
        /** 启动中。 */
        STARTING,
        /** 已就绪，可以调用。 */
        RUNNING,
        /** 启动失败（{@link #lastError()} 有原因，{@link #failureCountdown()} 秒后允许重试）。 */
        FAILED,
        /** 配置里关闭了边车。 */
        DISABLED,
    }

    /**
     * 确保边车可用；返回 true 表示可以调用 {@link #baseUri()}。
     *
     * <p>懒启动 + 失败冷却：预览是可选能力，边车起不来只应让预览功能不可用，
     * 不能让整个插件不可用。</p>
     */
    public boolean ensureRunning() {
        if (!config.sidecarEnabled()) {
            state.set(State.DISABLED);
            return false;
        }
        synchronized (lock) {
            if (state.get() == State.RUNNING && process != null && process.isAlive()) {
                return true;
            }
            if (state.get() == State.FAILED && failedAt != null
                    && Instant.now().isBefore(failedAt.plus(FAILURE_COOLDOWN))) {
                return false;
            }
            if (shuttingDown) {
                return false;
            }
            try {
                start();
            } catch (IOException e) {
                markFailed("边车启动失败: " + e.getMessage());
                return false;
            }
            return state.get() == State.RUNNING;
        }
    }

    /** 边车基址（未就绪时抛异常）。 */
    public URI baseUri() {
        if (state.get() != State.RUNNING) {
            throw new IllegalStateException("边车未就绪（状态 " + state.get() + "）");
        }
        return URI.create("http://127.0.0.1:" + port);
    }

    public State state() {
        return state.get();
    }

    public int port() {
        return port;
    }

    public String lastError() {
        return lastError;
    }

    /** 失败冷却剩余秒数（0 = 可重试）。 */
    public long failureCountdown() {
        if (state.get() != State.FAILED || failedAt == null) {
            return 0;
        }
        long elapsed = Duration.between(failedAt, Instant.now()).toSeconds();
        return Math.max(0, FAILURE_COOLDOWN.toSeconds() - elapsed);
    }

    public Path logFile() {
        return logFile;
    }

    /** 边车的存档目录（上传的 zip 由 Voxelith 自己解到 {@code <upload-dir>/<id>/world/}）。 */
    public Path uploadsDir() {
        return sidecarDir == null ? config.sidecarDir().resolve("work/uploads") : sidecarDir.resolve("work/uploads");
    }

    /** 关闭边车（插件卸载/禁用时调用）。 */
    public void stop() {
        synchronized (lock) {
            shuttingDown = true;
            Process current = process;
            process = null;
            if (current != null) {
                VoxelithProcessRunner.terminate(current);
            }
            deletePidFile();
            state.set(State.STOPPED);
        }
    }

    /** 允许在 stop 之后重新启动（宿主可能重复 enable/disable）。 */
    public void reset() {
        synchronized (lock) {
            shuttingDown = false;
            state.set(State.STOPPED);
        }
    }

    private void start() throws IOException {
        state.set(State.STARTING);
        // 绝对化：后面的配置文件路径有的写成相对（-jar 用的 serverJar），而子进程工作目录
        // 是 sidecarDir 本身，相对路径会被拼成 sidecarDir/<相对路径>（见 VoxelithRuntimeConfig 类注释）
        sidecarDir = config.sidecarDir().toAbsolutePath().normalize();
        Files.createDirectories(sidecarDir.resolve("work/uploads"));
        Files.createDirectories(sidecarDir.resolve("data/maps"));
        logFile = sidecarDir.resolve("sidecar.log");

        // 上一次进程若还活着（宿主被强杀导致孤儿），先清掉再起：否则端口/目录会被两边同时用
        killOrphan();

        VoxelithCliRenderEngine.Materialized materialized = runtimeAccessor.materialize();
        port = config.sidecarPort() > 0 ? config.sidecarPort() : findFreePort();
        List<String> command = new ArrayList<>();
        command.add(runtimeAccessor.javaExecutable().toString());
        if (config.sidecarHeapMiB() > 0) {
            command.add("-Xmx" + config.sidecarHeapMiB() + "m");
        }
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.add("-jar");
        command.add(materialized.serverJar().toAbsolutePath().toString());
        command.add("--server.address=127.0.0.1");
        command.add("--server.port=" + port);
        command.add("--yudream.voxelith.work-dir=" + sidecarDir.resolve("work").toAbsolutePath());
        command.add("--yudream.voxelith.publish-dir=" + sidecarDir.resolve("data/maps").toAbsolutePath());
        command.add("--yudream.voxelith.upload-dir=" + sidecarDir.resolve("work/uploads").toAbsolutePath());
        // 让 Voxelith 认领我们放进去的目录（它只在自己允许的根下搜索存档）
        command.add("--yudream.voxelith.upload.world-roots=" + sidecarDir.resolve("work/uploads").toAbsolutePath());
        command.add("--logging.file.name=" + logFile.toAbsolutePath());
        // incremental 默认关闭，显式写出来表明「我们不用它」，避免将来上游改默认值后静默 fail-fast
        command.add("--yudream.voxelith.incremental.enabled=false");

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(sidecarDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        process = builder.start();
        writePidFile(process.pid());

        if (awaitReady()) {
            state.set(State.RUNNING);
            lastError = null;
            failedAt = null;
            return;
        }
        String tail = readLogTail();
        VoxelithProcessRunner.terminate(process);
        process = null;
        deletePidFile();
        markFailed("边车在 " + STARTUP_TIMEOUT.toSeconds() + " 秒内未就绪"
                + (tail.isBlank() ? "" : "：" + tail));
    }

    /** 就绪探测：{@code GET /api/maps} 返回 200 即认为 Spring 已开始服务。 */
    private boolean awaitReady() {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Process current = process;
            if (current == null || !current.isAlive()) {
                return false;
            }
            if (probe()) {
                return true;
            }
            try {
                Thread.sleep(PROBE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean probe() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/api/maps")
                    .toURL().openConnection();
            connection.setConnectTimeout(1_000);
            connection.setReadTimeout(2_000);
            connection.setRequestMethod("GET");
            return connection.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void markFailed(String message) {
        lastError = message;
        failedAt = Instant.now();
        state.set(State.FAILED);
    }

    /** 挑一个空闲端口（关掉即释放；边车启动前有极小概率被抢占，届时启动失败会走冷却重试）。 */
    static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private Path pidFile() {
        return sidecarDir.resolve("sidecar.pid");
    }

    private void writePidFile(long pid) throws IOException {
        Files.writeString(pidFile(), Long.toString(pid), StandardCharsets.UTF_8);
    }

    private void deletePidFile() {
        if (sidecarDir == null) {
            return;
        }
        try {
            Files.deleteIfExists(pidFile());
        } catch (IOException ignored) {
            // pid 文件删不掉不影响功能
        }
    }

    /**
     * 清理上一轮遗留的边车。
     *
     * <p>只杀「命令行里带本插件边车 jar 路径」的进程：宿主进程被强杀时插件没有机会
     * 执行 {@code onDispose}，pid 文件就成了孤儿线索；但 pid 可能被复用，
     * 所以必须核对命令行，不能见 pid 就杀。</p>
     */
    private void killOrphan() {
        Optional<String> recorded = readPidFile();
        if (recorded.isEmpty()) {
            return;
        }
        try {
            long pid = Long.parseLong(recorded.get().trim());
            ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(handle -> {
                String commandLine = handle.info().commandLine().orElse("");
                if (!commandLine.contains("voxelith-server.jar")) {
                    // pid 被复用了，这是我们不认识的进程——绝不误杀
                    return;
                }
                // 先收子进程再杀自己：边车的渲染/预览线程可能在别的 JVM 里
                handle.descendants().forEach(ProcessHandle::destroyForcibly);
                handle.destroyForcibly();
            });
        } catch (NumberFormatException ignored) {
            // pid 文件内容坏了：直接删掉重来
        }
        deletePidFile();
    }

    private Optional<String> readPidFile() {
        Path file = pidFile();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private String readLogTail() {
        if (logFile == null || !Files.isRegularFile(logFile)) {
            return "";
        }
        try {
            String log = Files.readString(logFile, StandardCharsets.UTF_8);
            return VoxelithProcessRunner.logTail(log, 800);
        } catch (IOException e) {
            return "";
        }
    }

    /** 供排障：边车启动命令（脱敏后）——管理页可展示，便于确认端口与目录是否如预期。 */
    public List<String> describeLaunchPlan() {
        List<String> plan = new ArrayList<>();
        plan.add("java ‑Xmx" + config.sidecarHeapMiB() + "m ‑jar voxelith-server.jar");
        plan.add("--server.address=127.0.0.1");
        plan.add("--server.port=" + (config.sidecarPort() > 0 ? config.sidecarPort() : "auto"));
        plan.add("--yudream.voxelith.work-dir=" + config.sidecarDir().resolve("work"));
        plan.add("--yudream.voxelith.publish-dir=" + config.sidecarDir().resolve("data/maps"));
        return List.copyOf(plan);
    }

    /** 供管理页展示：内置引擎资源是否齐全。 */
    public static boolean bundledEnginePresent() {
        return VoxelithBundledRuntime.describeBundledResources().values().stream().allMatch(Boolean.TRUE::equals);
    }
}
