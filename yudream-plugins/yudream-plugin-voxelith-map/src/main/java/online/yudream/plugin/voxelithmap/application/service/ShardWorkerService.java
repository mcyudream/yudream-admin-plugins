package online.yudream.plugin.voxelithmap.application.service;

import online.yudream.plugin.voxelithmap.application.dto.ShardWorkerStatusDTO;
import online.yudream.plugin.voxelithmap.domain.aggregate.MapProject;
import online.yudream.plugin.voxelithmap.domain.repo.MapProjectRepo;
import online.yudream.plugin.voxelithmap.domain.value.ModJarRef;
import online.yudream.plugin.voxelithmap.infrastructure.engine.MinecraftVersions;
import online.yudream.plugin.voxelithmap.infrastructure.engine.PlatformFileMaterializer;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithCliArguments;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithProcessRunner;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithRenderEngine;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithRuntimeConfig;
import online.yudream.plugin.voxelithmap.infrastructure.storage.MapArtifactStorage;
import online.yudream.plugin.voxelithmap.infrastructure.world.WorldArchiveExtractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 分布式分片 worker 托管。
 *
 * <p><b>职责边界</b>：插件托管 worker 进程与共享队列目录，<b>不生产队列</b>。Voxelith 的分片队列
 * 由它自己的管线侧（{@code RunPipelineUseCase} 的队列模式）写入，没有命令行入口；因此这里的定位是
 * 「把 worker 跑起来、看住它、在停止时把增量产物收回来」，队列内容由外部决定。</p>
 *
 * <p>产物回流是这块的关键：worker 直接把瓦片写进磁盘上的代目录
 * （{@code <publishRoot>/<mapId>/<generationId>}），磁盘目录因此成为「可再加工」的权威副本；
 * 对象存储是给前端读的发布副本。停止 worker 时按 {@code mtime >= 启动时间} 找出被改动的文件
 * （含 worker 更新过的 {@code manifest.json}）逐个回传，未改动的文件不重复上传。</p>
 */
public final class ShardWorkerService implements AutoCloseable {

    /** 停止后回传时的时间阈值容差：文件系统时间戳精度可能只到秒。 */
    private static final long MTIME_TOLERANCE_MILLIS = 2_000;
    /** 队列能力说明（前端直接展示，避免管理员以为「启动就会自己找活干」）。 */
    private static final String QUEUE_HINT =
            "插件只托管 worker 进程，不生产分片队列。队列的写入方在 Voxelith 管线侧，"
                    + "本插件提供共享队列目录供外部工具入队；队列为空时 worker 会在空闲超时后自行退出。";
    /** 日志尾巴上限。 */
    private static final int LOG_TAIL_CHARS = 2_000;

    public static final int DEFAULT_THREADS = 1;
    public static final int DEFAULT_LEASE_MINUTES = 30;
    public static final int DEFAULT_IDLE_MINUTES = 10;

    private final MapProjectRepo projectRepo;
    private final MapArtifactStorage storage;
    private final VoxelithRenderEngine engine;
    private final VoxelithRuntimeConfig config;
    private final PlatformFileMaterializer files;

    private final Map<String, Handle> handles = new ConcurrentHashMap<>();

    public ShardWorkerService(MapProjectRepo projectRepo,
                              MapArtifactStorage storage,
                              VoxelithRenderEngine engine,
                              VoxelithRuntimeConfig config,
                              PlatformFileMaterializer files) {
        this.projectRepo = projectRepo;
        this.storage = storage;
        this.engine = engine;
        this.config = config;
        this.files = files;
    }

    /** 读某地图的 worker 状态；进程已自行退出时归类为 {@code EXITED}。 */
    public ShardWorkerStatusDTO status(String mapId) {
        MapProject project = requireProject(mapId);
        Handle handle = handles.get(mapId);
        if (handle == null) {
            return idle(project);
        }
        handle.refreshExitNote();
        return handle.toDTO(queueDir(), DEFAULT_THREADS);
    }

    /**
     * 启动 worker（进程异步，物料准备同步）。
     *
     * <p>物料准备（解存档 + 落 client jar）是同步的：存档可能有几百 MB，第一次会等一会儿；
     * 之后按 {@code world.source} 记录复用，不再重复解包。</p>
     */
    public ShardWorkerStatusDTO start(String mapId, String workerId, int threads,
                                      int leaseMinutes, int idleMinutes, boolean meshopt) {
        MapProject project = requireProject(mapId);
        String generationId = project.activeGenerationId();
        if (generationId == null) {
            throw new IllegalArgumentException("该地图还没有已发布的渲染产物，请先渲染一次再启动分片 worker");
        }
        Handle existing = handles.get(mapId);
        if (existing != null && existing.isAlive()) {
            throw new IllegalArgumentException("该地图的分片 worker 已在运行（pid " + existing.pid() + "）");
        }
        Path workDir = config.mapWorkDir(mapId);
        Path artifactDir = config.mapPublishDir(mapId).resolve(generationId);
        if (!Files.isDirectory(artifactDir)) {
            throw new IllegalArgumentException("磁盘上找不到该代的产物目录（" + artifactDir
                    + "），说明它是旧版产物或磁盘副本已被清理，请重新渲染一次");
        }
        try {
            Path worldDir = prepareWorld(project, workDir);
            Path clientJar = prepareClientJar(project, workDir);
            // 与渲染同一口径：mod jar 叠在 client jar 之后（packs[0] = 原版），采集缓存键也含 mod
            List<Path> packs = new java.util.ArrayList<>();
            packs.add(clientJar);
            List<Path> modJars = prepareModJars(project, workDir);
            packs.addAll(modJars);
            int safeThreads = threads <= 0 ? DEFAULT_THREADS : Math.min(threads, 32);
            String safeWorkerId = workerId == null || workerId.isBlank()
                    ? "plugin-" + mapId : workerId.trim();
            VoxelithCliArguments.ShardWorkerInvocation invocation = new VoxelithCliArguments.ShardWorkerInvocation(
                    worldDir, project.dimension(), generationId, packs,
                    // 采集产物按「版本 + 资源包 + 模组」分桶，渲染用哪一桶这里就用哪一桶
                    // （口径只有 VoxelithRuntimeConfig 一处）：分片 worker 用错桶会静默退回静态解析
                    workDir, config.mapPublishDir(mapId),
                    config.resolveModelsFile(project.settings().mcVersion(), project.clientJarName(),
                            project.clientJarFileId(),
                            project.modJars().stream().map(ModJarRef::fileId).toList()),
                    config.queueDir(),
                    safeWorkerId, safeThreads,
                    leaseMinutes <= 0 ? DEFAULT_LEASE_MINUTES : leaseMinutes,
                    idleMinutes <= 0 ? DEFAULT_IDLE_MINUTES : idleMinutes,
                    meshopt);
            VoxelithProcessRunner.ActiveProcess process = engine.startShardWorker(
                    invocation, workDir, config.heapMiB(), null);
            Handle handle = new Handle(mapId, generationId, artifactDir, safeWorkerId, safeThreads, process);
            handles.put(mapId, handle);
            return handle.toDTO(queueDir(), DEFAULT_THREADS);
        } catch (IOException e) {
            throw new IllegalStateException("分片 worker 启动失败: " + e.getMessage(), e);
        }
    }

    /**
     * 停止 worker 并把磁盘上被改动的产物回传到对象存储。
     *
     * <p>停止后句柄会保留，让前端还能看到退出说明、回传个数与日志尾巴；再次启动会覆盖它。</p>
     *
     * @return 停止后的状态（{@code syncedTiles} 为回传个数）
     */
    public ShardWorkerStatusDTO stop(String mapId) {
        MapProject project = requireProject(mapId);
        Handle handle = handles.get(mapId);
        if (handle == null) {
            return idle(project);
        }
        int exitCode = handle.process.stop();
        handle.finishedAt = Instant.now();
        handle.exitNote = handle.process.isAlive()
                ? "未能终止进程（pid " + handle.process.pid() + " 仍在运行，请手工清理）"
                : "已停止（退出码 " + exitCode + "）";
        handle.syncedTiles = syncBack(handle);
        return handle.toDTO(queueDir(), DEFAULT_THREADS);
    }

    /**
     * 把 worker 运行期间改动过的产物回传到对象存储。
     *
     * <p>只回传 {@code mtime >= 启动时间} 的文件：worker 写的是增量瓦片与局部失效后的
     * {@code manifest.json}，全量重传既慢又无必要。</p>
     */
    private int syncBack(Handle handle) {
        Path dir = handle.artifactDir;
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        long threshold = handle.startedAt.toEpochMilli() - MTIME_TOLERANCE_MILLIS;
        int synced = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> changed = walk.filter(Files::isRegularFile)
                    .filter(file -> {
                        try {
                            return Files.getLastModifiedTime(file).toMillis() >= threshold;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .sorted(Comparator.naturalOrder())
                    .toList();
            for (Path file : changed) {
                String relative = dir.relativize(file).toString().replace('\\', '/');
                try {
                    storage.saveArtifact(handle.mapId, handle.generationId, relative,
                            Files.readAllBytes(file));
                    synced++;
                } catch (IOException | RuntimeException ignored) {
                    // 单个文件回传失败不该中断整批：worker 的成果能收多少收多少
                }
            }
        } catch (IOException e) {
            handle.error = "产物回传时遍历目录失败: " + e.getMessage();
        }
        return synced;
    }

    /** 解包存档（按 {@code world.source} 记录复用，换存档后才重解）。 */
    private Path prepareWorld(MapProject project, Path workDir) throws IOException {
        Path destination = workDir.resolve("world");
        Path marker = workDir.resolve("world.source");
        String sourceId = project.worldFileId();
        if (Files.isRegularFile(marker) && Files.isDirectory(destination)) {
            String recorded = Files.readString(marker, StandardCharsets.UTF_8).trim();
            if (recorded.equals(sourceId)) {
                return destination;
            }
        }
        Path archive = files.materialize(sourceId, workDir.resolve("world.zip"), "存档文件");
        deleteQuietly(destination);
        Path extracted = WorldArchiveExtractor.extract(archive, destination, project.worldName(),
                project.dimension());
        Files.writeString(marker, sourceId, StandardCharsets.UTF_8);
        return extracted;
    }

    private Path prepareClientJar(MapProject project, Path workDir) throws IOException {
        // 与渲染同一口径：保留上传原名，引擎才能从资源包文件名里读出采集版本
        return files.materialize(project.clientJarFileId(),
                workDir.resolve(MinecraftVersions.jarFileName(project.clientJarName())), "客户端 jar");
    }

    /** 物化绑定的模组 jar（长驻 worker 的持久工作目录里，跨启动复用）。 */
    private List<Path> prepareModJars(MapProject project, Path workDir) throws IOException {
        List<Path> result = new java.util.ArrayList<>();
        List<ModJarRef> modJars = project.modJars();
        for (int index = 0; index < modJars.size(); index++) {
            ModJarRef modJar = modJars.get(index);
            String raw = modJar.name() == null || modJar.name().isBlank()
                    ? "mod" : Path.of(modJar.name()).getFileName().toString();
            String base = raw.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (base.isBlank()) {
                base = "mod";
            }
            if (!base.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                base = base + ".jar";
            }
            result.add(files.materialize(modJar.fileId(),
                    workDir.resolve("mod-" + (index + 1) + "-" + base), "模组 jar"));
        }
        return result;
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 删不掉就留着，不影响后续流程
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    private MapProject requireProject(String mapId) {
        Optional<MapProject> found = projectRepo.findById(mapId);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("地图不存在: " + mapId);
        }
        return found.get();
    }

    private String queueDir() {
        return config.queueDir().toString();
    }

    private ShardWorkerStatusDTO idle(MapProject project) {
        String mapId = project.id();
        String generationId = project.activeGenerationId();
        String artifactDir = generationId == null
                ? config.mapPublishDir(mapId).toString()
                : config.mapPublishDir(mapId).resolve(generationId).toString();
        return ShardWorkerStatusDTO.idle(mapId, queueDir(), QUEUE_HINT, artifactDir, DEFAULT_THREADS);
    }

    /** 卸载时停掉全部 worker：插件退出后留下孤儿 JVM 会一直吃 CPU。 */
    @Override
    public void close() {
        for (Handle handle : handles.values()) {
            try {
                handle.process.stop();
                handle.syncedTiles = syncBack(handle);
            } catch (RuntimeException ignored) {
                // 卸载路径上尽力而为
            }
        }
        handles.clear();
    }

    /** 一个已启动的 worker 进程及其上下文。 */
    private static final class Handle {

        private final String mapId;
        private final String generationId;
        private final Path artifactDir;
        private final String workerId;
        private final int threads;
        private final VoxelithProcessRunner.ActiveProcess process;
        private final Instant startedAt = Instant.now();

        private volatile Instant finishedAt;
        private volatile String exitNote;
        private volatile String error;
        private volatile int syncedTiles;

        private Handle(String mapId, String generationId, Path artifactDir, String workerId,
                       int threads, VoxelithProcessRunner.ActiveProcess process) {
            this.mapId = mapId;
            this.generationId = generationId;
            this.artifactDir = artifactDir;
            this.workerId = workerId;
            this.threads = threads;
            this.process = process;
        }

        private boolean isAlive() {
            return process.isAlive();
        }

        private long pid() {
            return process.pid();
        }

        /** 进程已自行退出但还没写过退出说明时补一条（队列空 / 空闲超时）。 */
        private void refreshExitNote() {
            if (!process.isAlive() && exitNote == null) {
                exitNote = "worker 已自行退出（队列为空或达到空闲超时）";
                finishedAt = Instant.now();
            }
        }

        private ShardWorkerStatusDTO toDTO(String queueDir, int defaultThreads) {
            refreshExitNote();
            boolean alive = process.isAlive();
            String state = alive ? "RUNNING" : "EXITED";
            return new ShardWorkerStatusDTO(mapId, state, alive ? process.pid() : -1,
                    workerId, threads <= 0 ? defaultThreads : threads, alive,
                    queueDir, QUEUE_HINT, artifactDir.toString(), generationId, syncedTiles,
                    startedAt.toString(), exitNote, error, process.logTail(LOG_TAIL_CHARS));
        }
    }
}
