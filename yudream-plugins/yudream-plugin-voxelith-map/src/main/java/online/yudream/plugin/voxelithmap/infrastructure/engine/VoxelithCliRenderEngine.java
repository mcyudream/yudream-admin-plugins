package online.yudream.plugin.voxelithmap.infrastructure.engine;

import online.yudream.plugin.voxelithmap.domain.enumerate.RenderPhase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 基于子进程的 Voxelith 渲染引擎。
 *
 * <p>为什么一定要子进程，而不是让边车自己跑：边车是 Spring Boot fat jar，其
 * {@code RenderProcessLauncher.canSpawn()} 在这种部署形态下判定为 false，于是
 * {@code RenderJobService} 会**退化成在 web 进程内渲染**——大图直接 OOM 打死边车。
 * 因此边车只承担预览与编目，渲染一律由插件自己拉 {@code RenderMapCli} 子进程，
 * 堆大小独立、崩了不影响宿主，也能随时按进程树终止。</p>
 *
 * <p>命令行形态：</p>
 * <pre>
 * java -Xmx{heap}m -Dfile.encoding=UTF-8
 *      -Dloader.main=online.yudream.voxelith.server.cli.RenderMapCli
 *      -cp voxelith-server.jar org.springframework.boot.loader.launch.PropertiesLauncher
 *      --world-dir ... --map-id ... --packs ... （见 VoxelithCliArguments）
 * </pre>
 */
public final class VoxelithCliRenderEngine implements VoxelithRenderEngine {

    /** 任务临时目录下的固定文件名。 */
    public static final String LOG_FILE_NAME = "render.log";

    private final VoxelithRuntimeConfig config;
    private final RuntimeAccessor runtimeAccessor;

    public VoxelithCliRenderEngine(VoxelithRuntimeConfig config, RuntimeAccessor runtimeAccessor) {
        this.config = config;
        this.runtimeAccessor = runtimeAccessor;
    }

    @Override
    public RenderOutcome render(VoxelithCliArguments.RenderInvocation invocation,
                                Path taskDir,
                                BooleanSupplier canceled,
                                ProgressSink progress,
                                VoxelithProcessRunner.LineConsumer onLine) {
        return render(invocation, taskDir, canceled, progress, onLine, 0);
    }

    @Override
    public RenderOutcome render(VoxelithCliArguments.RenderInvocation invocation,
                                Path taskDir,
                                BooleanSupplier canceled,
                                ProgressSink progress,
                                VoxelithProcessRunner.LineConsumer onLine,
                                int heapMiBOverride) {
        return render(invocation, taskDir, canceled, progress, onLine, heapMiBOverride, null);
    }

    @Override
    public RenderOutcome render(VoxelithCliArguments.RenderInvocation invocation,
                                Path taskDir,
                                BooleanSupplier canceled,
                                ProgressSink progress,
                                VoxelithProcessRunner.LineConsumer onLine,
                                int heapMiBOverride,
                                java.time.Duration timeout) {
        // taskDir 本身会当子进程的工作目录，日志还会作为 -Xlog 下发：必须是绝对路径，
        // 否则子进程会把它拼到自己（= taskDir）的工作目录后面（见 VoxelithRuntimeConfig）
        taskDir = taskDir.toAbsolutePath().normalize();
        // --work-dir / --publish-dir 必须用 invocation 携带的持久目录，不能换成 taskDir 私有目录：
        // renderMap 把 surface.png/surface.json/heightfield.bin 留在 work-dir（全景 CLI 的输入）、
        // 把 models.json.gz 缓存也落在那里（跨任务复用），并把成品发布到 <publish-dir>/<mapId>
        // （mapId=发布代 id，分片 worker 增量更新就写这个目录）。曾因换成 taskDir 私有目录，
        // 任务结束即被清空，全景烘焙与分片 worker 全部只剩「请先重新渲染一次」一条路。
        // taskDir 只承担子进程工作目录与日志落点，任务结束整体删除。
        Path workDir = invocation.workDir().toAbsolutePath().normalize();
        Path publishDir = invocation.publishDir().toAbsolutePath().normalize();
        Path logFile = taskDir.resolve(LOG_FILE_NAME);
        Path publishRoot = publishDir.resolve(invocation.mapId());
        LineProgressRelay relay = new LineProgressRelay(progress, onLine);
        try {
            Files.createDirectories(workDir);
            Files.createDirectories(publishDir);
            Materialized materialized = runtimeAccessor.materialize();
            List<String> cliArgs = VoxelithCliArguments.renderMap(invocation);
            int heapMiB = heapMiBOverride > 0 ? heapMiBOverride : config.heapMiB();
            List<String> command = VoxelithCliArguments.command(
                    runtimeAccessor.javaExecutable(), materialized.serverJar(),
                    VoxelithCliArguments.RENDER_MAIN, heapMiB,
                    List.of("-Xlog:gc:file=" + taskDir.resolve("render-gc.log") + ":time,uptime,level,tags"),
                    cliArgs);
            relay.accept("[voxelith-map] " + VoxelithProcessRunner.describe(command));
            VoxelithProcessRunner.RunResult result = VoxelithProcessRunner.run(
                    new VoxelithProcessRunner.Spec(command, taskDir, logFile,
                            timeout == null ? java.time.Duration.ofMinutes(config.timeoutMinutes()) : timeout,
                            "renderMap"),
                    relay::accept, canceled);
            return new RenderOutcome(result, publishRoot);
        } catch (IOException e) {
            // 启动失败（java 不存在、fat jar 解不出）也走同一套失败语义，不要抛到编排层之外
            VoxelithProcessRunner.RunResult failed = new VoxelithProcessRunner.RunResult(
                    -1, false, false, "引擎启动失败: " + e.getMessage(), logFile);
            relay.accept("[voxelith-map] 引擎启动失败: " + e.getMessage());
            return new RenderOutcome(failed, publishRoot);
        }
    }

    /**
     * 跑一次模型采集，把 {@code models.json.gz} 缓存进 {@code workDir}。
     *
     * <p>参数名以 Voxelith 的 {@code HarvestCliOptions} 为准（注意它**不叫** {@code --packs}，
     * 而是 {@code --pack-dir} 单值；也**没有** {@code --models-file}，产物固定落在
     * {@code <work-dir>/models.json.gz}）。</p>
     *
     * <p>采集会经 {@code HttpRuntimeProvisioner} 联网下载 MC 客户端与 Fabric loader 到
     * {@code <provision-cache>}，离线环境必然失败——失败不是致命的：渲染会退回静态模型解析
     * （uvlock/元素旋转与原版有偏差），这一点必须记进任务日志，不能静默。</p>
     *
     * @param workDir       采集工作目录（{@code models.json.gz} 落在这里）
     * @param vanillaJar    原版 client jar
     * @param modJars       mod jar（可为空）
     * @param mcVersion     采集版本（必填：CLI 自己不判版本，默认值写死 1.20.1）
     * @param loaderVersion Fabric loader 版本
     * @param provisionCache 依赖下载缓存目录；跨版本共用一份
     * @param timeout       超时
     */
    @Override
    public VoxelithProcessRunner.RunResult harvestModels(Path workDir, Path vanillaJar, List<Path> modJars,
                                                         String mcVersion, String loaderVersion,
                                                         Path provisionCache, java.time.Duration timeout,
                                                         VoxelithProcessRunner.LineConsumer onLine) {
        workDir = workDir.toAbsolutePath().normalize();
        provisionCache = provisionCache == null ? null : provisionCache.toAbsolutePath().normalize();
        try {
            Files.createDirectories(workDir);
            Materialized materialized = runtimeAccessor.materialize();
            List<String> workerClasspath = resolveWorkerClasspath(materialized);
            if (workerClasspath.isEmpty()) {
                throw new IllegalStateException("缺少采集 worker classpath");
            }
            List<String> cliArgs = new ArrayList<>();
            cliArgs.add("--work-dir");
            cliArgs.add(workDir.toString());
            cliArgs.add("--pack-dir");
            cliArgs.add(vanillaJar.toString());
            // 版本必须显式给：CLI 的 --mc-version 默认写死 1.20.1，与渲染侧自动判定的版本
            // 不一致时，烘出来的模型引用的贴图名与包里对不上 —— 整片品红
            cliArgs.add("--mc-version");
            cliArgs.add(mcVersion);
            cliArgs.add("--loader-version");
            cliArgs.add(loaderVersion);
            if (provisionCache != null) {
                // 不指路的话缓存会落在采集工作目录下：每个版本桶、每张地图都重新下载几十 MB
                cliArgs.add("--provision-cache");
                cliArgs.add(provisionCache.toString());
            }
            for (Path modJar : modJars) {
                cliArgs.add("--mod");
                cliArgs.add(modJar.toString());
            }
            cliArgs.add("--timeout-minutes");
            cliArgs.add(String.valueOf(Math.max(1, timeout.toMinutes())));
            for (String entry : workerClasspath) {
                cliArgs.add("--worker-classpath");
                cliArgs.add(entry);
            }
            List<String> command = VoxelithCliArguments.command(
                    runtimeAccessor.javaExecutable(), materialized.serverJar(),
                    VoxelithCliArguments.HARVEST_MAIN, config.heapMiB(), List.of(), cliArgs);
            if (onLine != null) {
                onLine.accept("[voxelith-map] " + VoxelithProcessRunner.describe(command));
            }
            return VoxelithProcessRunner.run(
                    new VoxelithProcessRunner.Spec(command, workDir, workDir.resolve("harvest.log"),
                            timeout, "harvestModels"),
                    onLine, () -> false);
        } catch (IOException e) {
            return new VoxelithProcessRunner.RunResult(-1, false, false,
                    "采集启动失败: " + e.getMessage(), workDir == null ? null : workDir.resolve("harvest.log"));
        }
    }

    /**
     * 跑一次全景图烘焙。
     *
     * <p>关键点：{@code --work-dir} 必须是<b>渲染时用过的那个持久工作目录</b>。全景 CLI 只读
     * {@code surface.png}/{@code surface.json}/{@code heightfield.bin}，不重跑管线；给错目录会立刻
     * 以「找不到地表栅格」退出码 1 结束。</p>
     */
    @Override
    public VoxelithProcessRunner.RunResult renderPanorama(VoxelithCliArguments.PanoramaInvocation invocation,
                                                          Path workDir, java.time.Duration timeout,
                                                          VoxelithProcessRunner.LineConsumer onLine) {
        workDir = workDir == null ? null : workDir.toAbsolutePath().normalize();
        Path logFile = workDir == null ? null : workDir.resolve("panorama.log");
        try {
            Files.createDirectories(workDir);
            Materialized materialized = runtimeAccessor.materialize();
            List<String> command = VoxelithCliArguments.command(
                    runtimeAccessor.javaExecutable(), materialized.serverJar(),
                    VoxelithCliArguments.PANORAMA_MAIN, config.heapMiB(), List.of(),
                    VoxelithCliArguments.panorama(invocation));
            if (onLine != null) {
                onLine.accept("[voxelith-map] " + VoxelithProcessRunner.describe(command));
            }
            return VoxelithProcessRunner.run(
                    new VoxelithProcessRunner.Spec(command, workDir, logFile, timeout, "panorama"),
                    onLine, () -> false);
        } catch (IOException e) {
            return new VoxelithProcessRunner.RunResult(-1, false, false,
                    "全景烘焙启动失败: " + e.getMessage(), logFile);
        }
    }

    /** 启动分片 worker：常驻进程，日志持续写 {@code shard-worker.log}，由调用方负责停止。 */
    @Override
    public VoxelithProcessRunner.ActiveProcess startShardWorker(
            VoxelithCliArguments.ShardWorkerInvocation invocation,
            Path workDir, int heapMiB,
            VoxelithProcessRunner.LineConsumer onLine) throws IOException {
        workDir = workDir.toAbsolutePath().normalize();
        Files.createDirectories(workDir);
        // worker 自己会写队列目录，先建出来避免它因目录不存在而启动失败
        Files.createDirectories(invocation.queueDir());
        Materialized materialized = runtimeAccessor.materialize();
        List<String> command = VoxelithCliArguments.command(
                runtimeAccessor.javaExecutable(), materialized.serverJar(),
                VoxelithCliArguments.SHARD_WORKER_MAIN, heapMiB, List.of(),
                VoxelithCliArguments.shardWorker(invocation));
        if (onLine != null) {
            onLine.accept("[voxelith-map] " + VoxelithProcessRunner.describe(command));
        }
        // worker 是常驻进程：Spec 要求正超时，用一个足够大的值表示「不主动超时」
        return VoxelithProcessRunner.start(
                new VoxelithProcessRunner.Spec(command, workDir,
                        workDir.resolve("shard-worker.log"), java.time.Duration.ofDays(30), "shard-worker"),
                onLine);
    }

    @Override
    public List<String> renderWorkerClasspath() {
        try {
            return resolveWorkerClasspath(runtimeAccessor.materialize());
        } catch (IOException e) {
            // 解不出随包 worker：渲染本身不受影响，只是引擎无法就地采集（静态解析）
            return List.of();
        }
    }

    /** 采集用的普通 classpath：外部显式配置优先，否则用随包提取的两件普通 jar。 */
    private List<String> resolveWorkerClasspath(Materialized materialized) {
        if (!config.externalWorkerClasspath().isEmpty()) {
            return config.externalWorkerClasspath();
        }
        return VoxelithBundledRuntime.workerClasspath(materialized.runtime());
    }

    /** 运行时的解出结果（会被缓存，避免每次任务都重解 23MB）。 */
    public record Materialized(Path serverJar, VoxelithBundledRuntime.MaterializedRuntime runtime) {
    }

    /** 运行时与 java 可执行文件的提供者（把解出与校验的时机交给上层控制）。 */
    public interface RuntimeAccessor {

        Materialized materialize() throws IOException;

        Path javaExecutable();
    }

    /** 默认实现：解出 bundled 运行时（外部 engine-jar 配置优先）。 */
    public static RuntimeAccessor defaultAccessor(VoxelithRuntimeConfig config) {
        return new RuntimeAccessor() {
            private Materialized cached;

            @Override
            public synchronized Materialized materialize() throws IOException {
                if (cached != null) {
                    return cached;
                }
                if (config.externalServerJar() != null) {
                    if (!Files.isRegularFile(config.externalServerJar())) {
                        throw new IOException("外部引擎 jar 不存在: " + config.externalServerJar());
                    }
                    cached = new Materialized(config.externalServerJar(), null);
                    return cached;
                }
                VoxelithBundledRuntime.MaterializedRuntime runtime =
                        VoxelithBundledRuntime.materialize(config.runtimeDir());
                cached = new Materialized(runtime.serverJar(), runtime);
                return cached;
            }

            @Override
            public Path javaExecutable() {
                return config.javaExecutable();
            }
        };
    }

    /** 供编排层在任务日志里标注阶段用。 */
    public static RenderPhase importPhase() {
        return RenderPhase.IMPORT;
    }
}
