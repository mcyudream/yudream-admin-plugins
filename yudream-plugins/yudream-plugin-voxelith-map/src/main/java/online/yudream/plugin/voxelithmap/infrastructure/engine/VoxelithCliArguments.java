package online.yudream.plugin.voxelithmap.infrastructure.engine;

import online.yudream.plugin.voxelithmap.domain.value.RegionWindow;
import online.yudream.plugin.voxelithmap.domain.value.RenderSettings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Voxelith CLI 参数与命令行构造。
 *
 * <p>纯函数，便于单测：参数名必须与 Voxelith 的 {@code RenderMapOptions.parse} 严格一致
 * （{@code --kebab-case}），拼错一个参数名会让子进程以退出码 2 直接退出。</p>
 *
 * <p>CLI 调用姿势（关键，踩过）：{@code java -jar app.jar -Dloader.main=X} **无效**——
 * {@code -jar} 固定走 fat jar MANIFEST 里的 {@code JarLauncher}。正确写法是把
 * {@code PropertiesLauncher} 作为主类、用 {@code -cp} 把 fat jar 挂上，再由
 * {@code -Dloader.main} 指定真正要跑的主类。</p>
 */
public final class VoxelithCliArguments {

    /** 渲染入口主类。 */
    public static final String RENDER_MAIN = "online.yudream.voxelith.server.cli.RenderMapCli";
    /** 模型采集入口主类。 */
    public static final String HARVEST_MAIN = "online.yudream.voxelith.server.cli.HarvestModelsCli";
    /** 全景图入口主类。 */
    public static final String PANORAMA_MAIN = "online.yudream.voxelith.server.cli.PanoramaCli";
    /** 分布式分片 worker 入口主类。 */
    public static final String SHARD_WORKER_MAIN = "online.yudream.voxelith.server.cli.ShardWorkerCli";

    private VoxelithCliArguments() {
    }

    /**
     * 构造 {@code RenderMapCli} 的参数列表。
     *
     * <p>顺序与 {@code RenderMapOptions.toArgs()} 保持一致，方便与上游对照排错。</p>
     */
    public static List<String> renderMap(RenderInvocation invocation) {
        List<String> args = new ArrayList<>();
        add(args, "--world-dir", invocation.worldDir().toString());
        add(args, "--dimension", invocation.dimension());
        add(args, "--map-id", invocation.mapId());
        add(args, "--map-name", invocation.mapName());
        add(args, "--packs", invocation.packs().stream()
                .map(Path::toString)
                .collect(Collectors.joining(",")));
        add(args, "--work-dir", invocation.workDir().toString());
        add(args, "--publish-dir", invocation.publishDir().toString());
        if (invocation.modelsFile() != null) {
            add(args, "--models-file", invocation.modelsFile().toString());
        }
        RegionWindow window = invocation.region();
        if (window != null) {
            add(args, "--min-x", String.valueOf(window.minX()));
            add(args, "--max-x", String.valueOf(window.maxX()));
            add(args, "--min-z", String.valueOf(window.minZ()));
            add(args, "--max-z", String.valueOf(window.maxZ()));
        }
        RenderSettings settings = invocation.settings();
        add(args, "--max-level", String.valueOf(settings.maxLevel()));
        add(args, "--sample-chunks", String.valueOf(settings.sampleChunks()));
        if (!settings.lodAtlas()) {
            args.add("--no-lod-atlas");
        }
        add(args, "--min-y", String.valueOf(settings.minY()));
        // 留空 = 自动：不传，让引擎按「资源包文件名 → 存档版本 → 兜底」判定（上游 McVersionResolver）。
        // 显式传一个写死的版本会把这套判定整个关掉，任何别的版本都变成品红方块/空洞
        if (!settings.usesAutoVersion()) {
            add(args, "--mc-version", settings.mcVersion());
        }
        add(args, "--loader-version", settings.loaderVersion());
        if (settings.skipHarvest()) {
            args.add("--skip-harvest");
        }
        for (String entry : invocation.workerClasspath()) {
            add(args, "--worker-classpath", entry);
        }
        if (settings.maxChunks() > 0) {
            add(args, "--max-chunks", String.valueOf(settings.maxChunks()));
        }
        if (settings.batchChunks() > 0) {
            add(args, "--batch-chunks", String.valueOf(settings.batchChunks()));
        }
        if (!settings.meshopt()) {
            args.add("--no-meshopt");
        }
        return List.copyOf(args);
    }

    /**
     * 组装完整命令行：java 可执行文件 + JVM 参数 + PropertiesLauncher + CLI 参数。
     *
     * @param mainClass CLI 主类（见本类常量）
     * @param extraJvmArgs 额外 JVM 参数（例如渲染的 GC 日志）
     */
    public static List<String> command(Path javaExecutable, Path fatJar, String mainClass, int heapMiB,
                                       List<String> extraJvmArgs, List<String> cliArgs) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        if (heapMiB > 0) {
            command.add("-Xmx" + heapMiB + "m");
        }
        // 中文路径与中文日志：Windows 上子进程默认用系统码页，不显式指定会得到乱码日志
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.add("-Dloader.main=" + mainClass);
        command.addAll(extraJvmArgs);
        command.add("-cp");
        command.add(fatJar.toString());
        command.add(VoxelithBundledRuntime.propertiesLauncherClass());
        command.addAll(cliArgs);
        return List.copyOf(command);
    }

    /**
     * 构造 {@code PanoramaCli} 的参数列表。
     *
     * <p>注意输入依赖：它读 {@code <work-dir>/surface.png} + {@code surface.json} +
     * {@code heightfield.bin}（都是 renderMap 的中间产物），不重跑管线。所以必须传
     * <b>渲染时用过的那个工作目录</b>，否则会以「找不到地表栅格」退出码 1 结束。</p>
     */
    public static List<String> panorama(PanoramaInvocation invocation) {
        List<String> args = new ArrayList<>();
        add(args, "--work-dir", invocation.workDir().toString());
        add(args, "--publish-dir", invocation.publishDir().toString());
        add(args, "--map-id", invocation.mapId());
        add(args, "--width", String.valueOf(invocation.width()));
        add(args, "--height", String.valueOf(invocation.height()));
        add(args, "--yaw", String.valueOf(invocation.yaw()));
        add(args, "--pitch", String.valueOf(invocation.pitch()));
        add(args, "--fov", String.valueOf(invocation.fov()));
        add(args, "--distance", String.valueOf(invocation.distance()));
        add(args, "--output", invocation.outputName());
        return List.copyOf(args);
    }

    /**
     * 构造 {@code ShardWorkerCli} 的参数列表。
     *
     * <p>{@code --packs} 是「一次一个值、可重复」的参数（不是逗号串），必须逐个追加；
     * 传成逗号串会被当做一个不存在的路径，worker 启动即失败。</p>
     */
    public static List<String> shardWorker(ShardWorkerInvocation invocation) {
        List<String> args = new ArrayList<>();
        add(args, "--world-dir", invocation.worldDir().toString());
        add(args, "--map-id", invocation.mapId());
        add(args, "--dimension", invocation.dimension());
        for (Path pack : invocation.packs()) {
            add(args, "--packs", pack.toString());
        }
        add(args, "--work-dir", invocation.workDir().toString());
        add(args, "--publish-dir", invocation.publishDir().toString());
        add(args, "--queue-dir", invocation.queueDir().toString());
        if (invocation.modelsFile() != null) {
            add(args, "--models-file", invocation.modelsFile().toString());
        }
        add(args, "--worker-id", invocation.workerId());
        add(args, "--threads", String.valueOf(invocation.threads()));
        add(args, "--lease-minutes", String.valueOf(invocation.leaseMinutes()));
        add(args, "--idle-minutes", String.valueOf(invocation.idleMinutes()));
        if (!invocation.meshopt()) {
            args.add("--no-meshopt");
        }
        return List.copyOf(args);
    }

    private static void add(List<String> args, String name, String value) {
        args.add(name);
        args.add(value);
    }

    /**
     * 一次 {@code PanoramaCli} 调用的输入。
     *
     * @param workDir    渲染时用过的持久工作目录（含 surface/heightfield）
     * @param publishDir 全景产物根目录
     * @param mapId      地图 id（产物落在 {@code <publishDir>/<mapId>/}）
     * @param width      输出像素宽
     * @param height     输出像素高
     * @param yaw        水平朝向（度，0 = 朝北）
     * @param pitch      俯角（度）
     * @param fov        垂直视场角（度）
     * @param distance   相机距离系数（× 地图对角线）
     * @param outputName 输出文件名（同目录下还会写 {@code panorama.json} 与 {@code aerial.png}）
     */
    public record PanoramaInvocation(
            Path workDir,
            Path publishDir,
            String mapId,
            int width,
            int height,
            double yaw,
            double pitch,
            double fov,
            double distance,
            String outputName) {

        public PanoramaInvocation {
            outputName = outputName == null || outputName.isBlank() ? "panorama.png" : outputName;
        }
    }

    /**
     * 一次 {@code ShardWorkerCli} 调用的输入。
     *
     * @param worldDir    存档根目录（含 region/）
     * @param dimension   维度 id
     * @param mapId       已发布地图 id（worker 直接把产物写进它的目录）
     * @param packs       资源包（低 → 高优先级，第一个应是原版 client jar）
     * @param workDir     工作目录（用于找 {@code models.json.gz}）
     * @param publishDir  发布根目录
     * @param modelsFile  显式 models 文件；可为 {@code null}
     * @param queueDir    分片队列目录（多机共享）
     * @param workerId    worker 标识
     * @param threads     本地并发线程数
     * @param leaseMinutes 分片租约（分钟）
     * @param idleMinutes  队列空闲多久后退出
     * @param meshopt     是否启用 EXT_meshopt_compression
     */
    public record ShardWorkerInvocation(
            Path worldDir,
            String dimension,
            String mapId,
            List<Path> packs,
            Path workDir,
            Path publishDir,
            Path modelsFile,
            Path queueDir,
            String workerId,
            int threads,
            int leaseMinutes,
            int idleMinutes,
            boolean meshopt) {

        public ShardWorkerInvocation {
            packs = packs == null ? List.of() : List.copyOf(packs);
            if (threads <= 0) {
                threads = 1;
            }
            if (leaseMinutes <= 0) {
                leaseMinutes = 30;
            }
            if (idleMinutes <= 0) {
                idleMinutes = 10;
            }
        }
    }

    /**
     * 一次 {@code RenderMapCli} 调用的全部输入。
     *
     * @param worldDir       存档根目录（含 region/ 与 level.dat）
     * @param dimension      维度 id
     * @param mapId          渲染输出的地图 id（= 插件内的发布代目录名）
     * @param mapName        展示名
     * @param packs          资源包，从低到高优先级（第一个应是原版 client jar）
     * @param workDir        中间产物目录
     * @param publishDir     发布根目录（产物落在 {@code <publishDir>/<mapId>/}）
     * @param modelsFile     models.json.gz；可为 {@code null}
     * @param settings       渲染参数
     * @param region         方块范围窗口；可为 {@code null}（不裁剪）
     * @param workerClasspath 采集 worker 的普通 classpath（可为空 = 静态解析）
     */
    public record RenderInvocation(
            Path worldDir,
            String dimension,
            String mapId,
            String mapName,
            List<Path> packs,
            Path workDir,
            Path publishDir,
            Path modelsFile,
            RenderSettings settings,
            RegionWindow region,
            List<String> workerClasspath) {

        public RenderInvocation {
            packs = packs == null ? List.of() : List.copyOf(packs);
            workerClasspath = workerClasspath == null ? List.of() : List.copyOf(workerClasspath);
        }
    }
}
