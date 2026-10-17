package online.yudream.plugin.voxelithmap.application.service;

import online.yudream.plugin.voxelithmap.application.assembler.VoxelithAppAssembler;
import online.yudream.plugin.voxelithmap.application.dto.RenderTaskDTO;
import online.yudream.plugin.voxelithmap.domain.aggregate.MapGeneration;
import online.yudream.plugin.voxelithmap.domain.aggregate.MapProject;
import online.yudream.plugin.voxelithmap.domain.aggregate.RenderTask;
import online.yudream.plugin.voxelithmap.domain.enumerate.RenderPhase;
import online.yudream.plugin.voxelithmap.domain.enumerate.TaskKind;
import online.yudream.plugin.voxelithmap.domain.repo.MapProjectRepo;
import online.yudream.plugin.voxelithmap.domain.repo.RenderTaskRepo;
import online.yudream.plugin.voxelithmap.domain.value.ModJarRef;
import online.yudream.plugin.voxelithmap.domain.value.RenderSettings;
import online.yudream.plugin.voxelithmap.infrastructure.engine.MinecraftVersions;
import online.yudream.plugin.voxelithmap.infrastructure.engine.PlatformFileMaterializer;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithArtifactImporter;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithCliArguments;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithDiagnostics;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithProcessRunner;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithRenderEngine;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VanillaClientJarDownloader;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithRuntimeConfig;
import online.yudream.plugin.voxelithmap.infrastructure.storage.MapArtifactStorage;
import online.yudream.plugin.voxelithmap.application.service.ClientJarLibrary;
import online.yudream.plugin.voxelithmap.infrastructure.world.LevelDatReader;
import online.yudream.plugin.voxelithmap.infrastructure.world.WorldArchiveExtractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 渲染编排：排队、执行、进度上报、失败降级、原子发布。
 *
 * <p>一个渲染任务从头到尾只有一条主线：</p>
 * <pre>
 * 入队 → 物料准备（存档解包 / client jar / models 缓存）→ 拉 RenderMapCli 子进程
 *      → 逐行解析阶段进度 → 导入产物（清单驱动）→ 校验完整性 → 切换发布指针 → 回收旧代
 * </pre>
 *
 * <p>三条硬规则：</p>
 * <ol>
 *   <li><b>失败不夺走线上内容</b>：产物写在隔离代目录里，只有导入完整才切换指针，
 *       失败/取消一律 {@code discard} 该代；</li>
 *   <li><b>重启后能自愈</b>：上一次进程被杀时留下的 PENDING/RUNNING 任务在启动时标记失败，
 *       并把它们遗留的代目录清掉（代 key 清单已落盘，所以垃圾可回收）；</li>
 *   <li><b>进度单调</b>：阶段与百分比只前进，子进程重复打印日志不会让进度条回跳。</li>
 * </ol>
 */
public final class RenderOrchestrator implements AutoCloseable {

    /** 进度落库节流：渲染会打印成千上万行，逐行写库会把宿主 DB 打爆。 */
    private static final long PERSIST_INTERVAL_MILLIS = 750;
    /** SSE 推送节流。 */
    private static final long EVENT_INTERVAL_MILLIS = 250;
    /** 采集超时（采集要联网拉 MC 客户端，给宽一点）。 */
    private static final Duration HARVEST_TIMEOUT = Duration.ofMinutes(30);

    private final MapProjectRepo projectRepo;
    private final RenderTaskRepo taskRepo;
    private final MapArtifactStorage storage;
    private final GenerationPublisher publisher;
    private final VoxelithRenderEngine engine;
    private final VoxelithRuntimeConfig config;
    private final PlatformFileMaterializer files;
    /** 客户端 jar 库（可空：测试装配）；自动下载前优先命中已预上传的版本。 */
    private final ClientJarLibrary clientJars;
    /** 完整日志落盘：任务目录随任务消失，完整输出要单独保留 */
    private final online.yudream.plugin.voxelithmap.infrastructure.storage.TaskLogStore taskLogs;
    private final VoxelithEventStream eventStream;
    private final VoxelithArtifactImporter importer;
    /** 读引擎产物（烘焙报告等）用；解析失败一律当作「没有报告」，不因为体检本身把渲染判失败。 */
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private final ExecutorService workers;
    private final Map<String, Running> running = new ConcurrentHashMap<>();

    public RenderOrchestrator(MapProjectRepo projectRepo,
                              RenderTaskRepo taskRepo,
                              MapArtifactStorage storage,
                              GenerationPublisher publisher,
                              VoxelithRenderEngine engine,
                              VoxelithRuntimeConfig config,
                              PlatformFileMaterializer files,
                              VoxelithEventStream eventStream,
                              com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                ClientJarLibrary clientJars,
                                online.yudream.plugin.voxelithmap.infrastructure.storage.TaskLogStore taskLogs) {
        this.projectRepo = projectRepo;
        this.taskRepo = taskRepo;
        this.storage = storage;
        this.publisher = publisher;
        this.engine = engine;
        this.config = config;
        this.files = files;
        this.clientJars = clientJars;
        this.taskLogs = taskLogs;
        this.eventStream = eventStream;
        this.importer = new VoxelithArtifactImporter(storage, objectMapper);
        this.objectMapper = objectMapper;
        int concurrency = Math.max(1, config.maxConcurrentTasks());
        this.workers = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "voxelith-map-render");
            // 守护线程：宿主停机时不阻塞 JVM，进程内没有未完成的任务可留（渲染在子进程里）
            thread.setDaemon(true);
            return thread;
        });
    }

    // ------------------------------------------------------------------
    // 提交与取消
    // ------------------------------------------------------------------

    /**
     * 提交一次渲染。
     *
     * @throws IllegalArgumentException 地图不存在或缺少必要输入
     */
    public RenderTask submit(MapProject project) {
        if (project.worldFileId() == null || project.worldFileId().isBlank()) {
            throw new IllegalArgumentException("该地图还没绑定存档");
        }
        MapGeneration generation = publisher.stage(project.id());
        RenderTask task = RenderTask.create(newTaskId(), generation);
        taskRepo.save(task);
        project.markRendering();
        projectRepo.save(project);
        publishEvent(task, project.name());

        Running handle = new Running(task.id());
        running.put(task.id(), handle);
        Future<?> future = workers.submit(() -> execute(task, project, handle));
        handle.attach(future);
        return task;
    }

    /** 取消任务（排队中的与运行中的都能取消）。 */
    public boolean cancel(String taskId) {
        Running handle = running.get(taskId);
        if (handle == null) {
            return false;
        }
        handle.cancel();
        return true;
    }

    public Optional<RenderTask> task(String taskId) {
        return taskRepo.findById(taskId);
    }

    public List<RenderTask> tasks() {
        return taskRepo.findAll();
    }

    public List<RenderTask> tasksOf(String mapId) {
        return taskRepo.findByMapId(mapId);
    }

    /**
     * 重启自愈：把上次进程留下的未完成任务标记失败并回收它们的代目录。
     *
     * @return 被标记失败的任务数
     */
    public int recoverStaleTasks() {
        int recovered = 0;
        for (RenderTask task : taskRepo.findAll()) {
            if (!task.canCancel()) {
                continue;
            }
            // 只有渲染任务有「代」。导入（服务端搬运或浏览器搬运）与删除这类任务在重启后同样是残留，
            // 也要标记失败，但没有产物可回收——此前这里无条件取 generation()，一个残留的导入任务
            // 就会让整轮自愈抛错中断，后续渲染任务反而漏收。
            boolean rendered = task.kind() == TaskKind.RENDER;
            MapGeneration generation = rendered ? task.generation() : null;
            task.fail("插件重启前任务未完成，已标记失败");
            taskRepo.save(task);
            if (rendered) {
                // 失败代的产物（可能只导入了一半）按代清单回收，避免长期占空间
                publisher.discard(generation);
                projectRepo.findById(task.mapId()).ifPresent(project -> {
                    project.markFailed(task.error());
                    projectRepo.save(project);
                });
            }
            recovered++;
        }
        return recovered;
    }

    /** 当前正在执行的任务数。 */
    public int activeCount() {
        return (int) running.values().stream().filter(Running::isActive).count();
    }

    @Override
    public void close() {
        for (Running handle : running.values()) {
            handle.cancel();
        }
        workers.shutdownNow();
        try {
            workers.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    private void execute(RenderTask task, MapProject project, Running handle) {
        Path taskDir = null;
        /** 子进程完整日志（收尾时搬到持久目录，任务目录随后删除） */
        Path retainedLogFile = null;
        MapGeneration generation = task.generation();
        try {
            // 排队期间被取消（或提交与执行的竞态窗口）：直接走取消收尾，不再准备物料。
            // 检查的是取消标志而不是 Future 状态——取消不再 interrupt 线程（见 Running.cancel）
            if (handle.isCanceled()) {
                cancelTask(task, project, generation);
                return;
            }
            Path tasksRoot = config.tasksDir();
            Files.createDirectories(tasksRoot);
            taskDir = Files.createDirectories(tasksRoot.resolve("render-" + task.id()));

            RenderExecution execution = new RenderExecution(task, project, taskDir);
            task.start();
            persistForce(task, project);

            if (!prepare(execution)) {
                return;
            }
            // 物料准备（解存档、落 jar）可能耗时数十秒，用户可能在这期间点了取消：
            // 别再把渲染子进程拉起来
            if (handle.isCanceled()) {
                cancelTask(task, project, generation);
                return;
            }
            Path result = runCli(execution, handle);
            // runCli 内部就把子进程日志路径记在 execution 上：这里落一份引用给 finally 用
            retainedLogFile = execution.lastLogFile();
            if (result == null) {
                return;
            }
            // 空地图体检：烘焙出 0 个方块说明存档里没有可渲染的地形（空世界，或框选范围落在没有方块
            // 的地方）。此时发布只会得到一张空白地图，宁可在这里失败并说清原因。
            if (bakeProducedNothing(project)) {
                failTask(task, project, generation,
                        "存档里没有可渲染的方块（地图为空，或框选范围内没有地形）："
                                + "请确认存档内容与框选范围后重试");
                return;
            }
            importAndPublish(execution, result, handle);
        } catch (Exception e) {
            task.appendLog("[voxelith-map] 渲染异常终止: " + e);
            failTask(task, project, generation, "渲染异常: " + e.getMessage());
        } finally {
            running.remove(task.id());
            // 完整日志先搬到持久目录，再删任务目录：否则「日志只剩尾部」是必然结果
            if (taskLogs != null && retainedLogFile != null) {
                taskLogs.keep(task.id(), retainedLogFile);
            }
            deleteQuietly(taskDir);
        }
    }

    /** 物料准备：解存档、落 client jar、准备 models 缓存。返回 false 表示已失败返回。 */
    private boolean prepare(RenderExecution execution) {
        RenderTask task = execution.task();
        MapProject project = execution.project();
        Path dir = execution.taskDir();
        try {
            Path archive = files.materialize(project.worldFileId(), dir.resolve("world.zip"), "存档文件");
            Path worldDir = WorldArchiveExtractor.extract(archive, dir.resolve("world"), project.worldName(),
                    project.dimension());
            execution.worldDir = worldDir;
            execution.log.accept("[voxelith-map] 存档解包完成: " + worldDir.getFileName());
            // 存档版本与引擎判定同源（level.dat 的 Data.Name），供自动下载与采集使用
            execution.levelDatVersion = LevelDatReader.readVersionName(
                    worldDir.resolve("level.dat"));
            if (execution.levelDatVersion != null) {
                execution.log.accept("[voxelith-map] 存档版本（level.dat）: " + execution.levelDatVersion);
            }

            if (project.clientJarFileId() == null || project.clientJarFileId().isBlank()) {
                // 未绑定 client jar：按创建时的承诺从镜像自动下载（packs[0] 缺失会让引擎退回静态解析）
                Path downloaded = ensureVanillaClientJar(execution);
                if (downloaded != null) {
                    execution.packs.add(downloaded);
                }
            }
            else {
                // 保留上传时的原始文件名：引擎按「资源包文件名 → 存档版本」判定采集版本，
                // 落成中性的 client.jar 会把最可靠的那条判据丢掉（连带版本体检也会变哑）
                Path clientJar = files.materialize(project.clientJarFileId(),
                        dir.resolve(MinecraftVersions.jarFileName(project.clientJarName())), "客户端 jar");
                execution.packs.add(clientJar);
                execution.log.accept("[voxelith-map] 客户端 jar 就绪: " + clientJar.getFileName() + "，开始渲染");
            }

            // 模组 jar：必须叠在 client jar 之后（上游约定 packs[0] = 原版，其余 = mod），
            // 采集与资源解析都按这个顺序消费；顺序即优先级，mod 覆盖原版、先绑的先叠
            List<ModJarRef> modJars = project.modJars();
            for (int index = 0; index < modJars.size(); index++) {
                ModJarRef modJar = modJars.get(index);
                Path path = files.materialize(modJar.fileId(),
                        dir.resolve(safeModJarFileName(modJar, index)), "模组 jar");
                execution.mods.add(path);
                execution.packs.add(path);
                execution.log.accept("[voxelith-map] 模组 jar 就绪(" + (index + 1) + "/"
                        + modJars.size() + "): " + modJar.displayName());
            }
            if (!modJars.isEmpty()) {
                execution.log.accept("[voxelith-map] 共 " + modJars.size()
                        + " 个模组 jar 已叠入资源包栈（模组方块随本次渲染一并采集与解析）");
            }

            // 模组包（整包 zip）：只从存储取一次，解包在任务临时目录里本地做。
            // 之前逐个 jar 落在对象存储里，导入要写 N 次、每次渲染再读 N 次，连接池扛不住
            Optional<String> packId = project.modsPackFileId();
            if (packId.isPresent()) {
                Path packZip = files.materialize(packId.get(), dir.resolve("mods-pack.zip"), "模组包");
                Path modsDir = dir.resolve("mods");
                Files.createDirectories(modsDir);
                List<Path> extracted = new ArrayList<>();
                WorldArchiveExtractor.streamJarEntries(packZip, (fileName, content) -> {
                    Path target = modsDir.resolve(safePackJarName(fileName, extracted.size()));
                    Files.write(target, content);
                    extracted.add(target);
                });
                for (Path jar : extracted) {
                    execution.mods.add(jar);
                    execution.packs.add(jar);
                }
                execution.log.accept("[voxelith-map] 模组包已解包: " + project.modsPackName()
                        + "，含 " + extracted.size() + " 个模组 jar（zip 只取一次，解包在本地完成）");
            }

            if (execution.packs.isEmpty()) {
                throw new IOException("未绑定 client jar 且自动下载失败，无法渲染（详见上方日志）");
            }
            execution.modelsFile = ensureModels(execution);
            return true;
        } catch (IOException e) {
            failTask(task, project, execution.generation(), e.getMessage());
            return false;
        }
    }

    /**
     * 准备 {@code models.json.gz} 缓存。
     *
     * <p>采集是按「一次采集、长期复用」设计的：产物落在运行根下的固定位置，之后所有地图都命中
     * 同一份。只有缓存缺失且引擎具备采集能力时才真的去采集（约 1 分钟，且需要联网）；
     * 采集失败不致命——退回静态解析，但必须在任务日志里说清楚为什么。</p>
     *
     * @return 可用的 models 文件路径；返回 {@code null} 表示走静态解析
     */
    private Path ensureModels(RenderExecution execution) {
        Path explicit = config.configuredModelsFile();
        if (explicit != null) {
            if (Files.isRegularFile(explicit)) {
                execution.log.accept("[voxelith-map] 使用显式配置的采集产物: " + explicit);
                return explicit;
            }
            execution.log.accept("[voxelith-map] 显式配置的采集产物不存在，按默认规则处理: " + explicit);
        }
        String version = mcVersionHint(execution);
        List<String> modJarFileIds = new ArrayList<>(execution.project().modJars().stream()
                .map(ModJarRef::fileId).toList());
        // 模组包也要进分桶键：换了包却不换桶的话，新模组的方块会静默缺几何
        execution.project().modsPackFileId().ifPresent(modJarFileIds::add);
        if (version == null) {
            // 判不出采集版本：交给引擎自己按「资源包文件名 → 存档版本 → 兜底」判定并就地采集。
            // 采集 CLI 没有这套判定（默认写死 1.20.1），插件替它猜版本正是品红方块/空洞的成因
            execution.log.accept("[voxelith-map] 客户端 jar 文件名里没有版本号 → 采集交给引擎"
                    + "按存档版本自动判定（本张地图首次渲染会采集一次；"
                    + "给 jar 起个带版本号的名字可让各图共用采集产物）");
            return null;
        }
        Path cache = config.modelsCacheFile(
                VoxelithRuntimeConfig.modelsCacheKey(version, execution.project().clientJarFileId(),
                        modJarFileIds));
        if (Files.isRegularFile(cache)) {
            execution.log.accept("[voxelith-map] 命中模型采集缓存（mc=" + version + "）: " + cache);
            return cache;
        }
        if (config.externalServerJar() != null && config.externalWorkerClasspath().isEmpty()) {
            // 外部引擎 + 没给 worker classpath：补不了普通 jar，直接静态解析
            execution.log.accept("[voxelith-map] 外部引擎模式下未配置 worker classpath，退回静态模型解析");
            return null;
        }
        if (execution.project().settings().skipHarvest()) {
            execution.log.accept("[voxelith-map] 已按设置跳过模型采集，使用静态模型解析");
            return null;
        }
        Path harvestDir = cache.getParent() == null ? config.runtimeRoot() : cache.getParent();
        execution.log.accept("[voxelith-map] 首次以 mc=" + version + " 渲染，开始采集模型几何"
                + "（需要联网下载原版资源，约 1 分钟；模组 " + execution.mods.size() + " 个一并采集）");
        VoxelithProcessRunner.RunResult result = engine.harvestModels(
                harvestDir, execution.packs.get(0), execution.mods, version,
                execution.project().settings().loaderVersion(), config.provisionCacheDir(),
                HARVEST_TIMEOUT, execution.log);
        if (!result.succeeded() || !Files.isRegularFile(cache)) {
            String reason = result.succeeded() ? "未产出 models.json.gz" : result.failureReason();
            if (!execution.mods.isEmpty()) {
                // 带模组采集常因「模组之间依赖不全」直接失败（加载器拒绝启动，例如缺 cristellib）。
                // 这里再用**原版（不带模组）**采一次：拿到 models.json.gz 才有意义——引擎的**多批次渲染**
                // 以预烘焙模型为前提（见 RenderMapCli：batchChunks>0 && 区块数>batchChunks && 有预烘焙模型
                // 才走 renderInBatches），没有它就单遍烘焙，大窗口的峰值内存与超时都随窗口线性上涨。
                execution.log.accept("[voxelith-map] 模组几何采集失败（" + reason + "），改用原版模型重试一次"
                        + "（模组方块将缺少几何，但多批次渲染因此可用）");
                VoxelithProcessRunner.RunResult vanilla = engine.harvestModels(
                        harvestDir, execution.packs.get(0), java.util.List.of(), version,
                        execution.project().settings().loaderVersion(), config.provisionCacheDir(),
                        HARVEST_TIMEOUT, execution.log);
                if (vanilla.succeeded() && Files.isRegularFile(cache)) {
                    execution.log.accept("[voxelith-map] 原版模型采集完成，继续渲染（渲染诊断里会标注模组方块缺几何）");
                    return cache;
                }
                execution.log.accept("[voxelith-map] 原版模型采集同样失败，退回静态模型解析："
                        + (vanilla.succeeded() ? "未产出 models.json.gz" : vanilla.failureReason()));
                return null;
            }
            execution.log.accept("[voxelith-map] 模型采集失败，退回静态模型解析（uvlock/元素旋转可能与原版有偏差）："
                    + reason);
            return null;
        }
        return cache;
    }

    /**
     * 未绑定 client jar 时自动下载原版客户端（packs[0]），按存档版本取官方源/BMCLAPI 镜像。
     *
     * @return jar 路径；版本判不出或下载失败返回 {@code null}（引擎将退回静态解析，日志已说明）
     */
    private Path ensureVanillaClientJar(RenderExecution execution) {
        RenderSettings settings = execution.effectiveSettings();
        String version = !settings.usesAutoVersion()
                ? settings.mcVersion()
                : execution.levelDatVersion;
        if (version == null || version.isBlank()) {
            execution.log.accept("[voxelith-map] 未绑定客户端 jar 且无法从 level.dat 读出存档版本，"
                    + "无法自动下载原版客户端，引擎将退回静态模型解析");
            return null;
        }
        // 1) 管理员预上传的 jar 库（按版本命中）：库里的 jar 经平台文件存储提供
        if (clientJars != null) {
            try {
                Optional<ClientJarLibrary.Entry> libraryEntry = clientJars.find(version);
                if (libraryEntry.isPresent()) {
                    Path cacheDir = config.provisionCacheDir();
                    Files.createDirectories(cacheDir);
                    Path cached = cacheDir.resolve("client-" + version + ".jar");
                    files.materialize(libraryEntry.get().fileId(), cached, "客户端 jar（jar 库 " + version + "）");
                    execution.log.accept("[voxelith-map] 命中客户端 jar 库（" + version + "）: " + cached);
                    return cached;
                }
            } catch (IOException error) {
                execution.log.accept("[voxelith-map] 读取客户端 jar 库失败，回退自动下载: "
                        + error.getMessage());
            }
        }
        // 2) 本地 provision 缓存 / 官方源与镜像下载（下载后写入缓存，跨地图复用）
        Path cacheDir = config.provisionCacheDir();
        try {
            Path jar = new VanillaClientJarDownloader().download(version, cacheDir, execution.log::accept);
            execution.log.accept("[voxelith-map] 自动下载的原版客户端 jar（按存档版本 "
                    + version + "）已作为 packs[0] 加入资源包栈");
            return jar;
        } catch (Exception e) {
            execution.log.accept("[voxelith-map] 自动下载原版客户端 jar 失败，引擎将退回静态模型解析："
                    + e.getMessage());
            return null;
        }
    }

    /**
     * 本次渲染要用的采集版本；判不出来返回 {@code null}（= 交给引擎按存档版本判定）。
     *
     * <p>优先级与引擎的 {@code McVersionResolver} 一致：显式设置 → 资源包文件名 →
     * level.dat 的存档版本（{@link LevelDatReader}，与引擎判定同源，不是另起炉灶猜版本）。
     * 以前插件读不到 level.dat 只能认前两级；现在补上第三级后，客户端 jar 缺失或
     * 文件名不带版本号的存档也能走插件侧采集（命中缓存、诊断清晰）。</p>
     */
    private String mcVersionHint(RenderExecution execution) {
        RenderSettings settings = execution.effectiveSettings();
        if (!settings.usesAutoVersion()) {
            return settings.mcVersion();
        }
        String fromJar = MinecraftVersions.versionOf(execution.project().clientJarName());
        if (fromJar != null) {
            return fromJar;
        }
        return execution.levelDatVersion;
    }

    /** 拉子进程渲染。返回产物根目录；返回 {@code null} 表示已失败/取消。 */
    private VoxelithCliArguments.RenderInvocation buildRenderInvocation(RenderExecution execution) {
        VoxelithCliArguments.RenderInvocation invocation = new VoxelithCliArguments.RenderInvocation(
                execution.worldDir,
                execution.dimension(),
                execution.generation().id(),
                execution.project().name(),
                execution.packs,
                // 持久工作目录：renderMap 在此留下 surface/heightfield，全景图与分片 worker 要靠它
                config.mapWorkDir(execution.project().id()),
                // 持久发布目录：worker 会把增量瓦片直接写进 <publishDir>/<generationId>/
                config.mapPublishDir(execution.project().id()),
                execution.modelsFile,
                execution.effectiveSettings(),
                execution.project().region().orElse(null),
                engine.renderWorkerClasspath());
        // 引擎可就地采集时把 worker classpath 下发；否则明确告知将静态解析，不留黑盒
        if (execution.modelsFile == null) {
            if (invocation.workerClasspath().isEmpty()) {
                execution.log.accept("[voxelith-map] 采集 worker classpath 不可用：本次渲染将使用静态模型解析");
            }
            else {
                execution.log.accept("[voxelith-map] 已随渲染下发采集 worker：引擎将在渲染内就地采集模型"
                        + "（需要联网，一次性）");
            }
        }
        return invocation;
    }

    private Path runCli(RenderExecution execution, Running handle) {
        RenderTask task = execution.task();
        MapProject project = execution.project();
        VoxelithCliArguments.RenderInvocation invocation = buildRenderInvocation(execution);
        // 按窗口规模预配堆：避免「默认 4096m 必然 OOM → 重试从头重烘」的浪费
        // （bake 结果只在进程内存里，引擎无断点续跑能力，重试必然重算）
        int heapMiB = presetHeapMiB(execution);
        long chunks = windowChunkEstimate(execution);
        int timeoutMinutes = online.yudream.plugin.voxelithmap.infrastructure.engine.RenderBudget
                .timeoutMinutes(config.timeoutMinutes(), chunks);
        execution.recordTimeoutMinutes(timeoutMinutes);
        if (chunks > 0 && execution.project().settings().batchChunks() > 0
                && chunks > execution.project().settings().batchChunks() && execution.modelsFile == null) {
            // 引擎的多批次渲染以「预烘焙模型」为前提：没有它只能单遍烘焙，峰值内存与时长都随整个窗口走。
            // 这条链路很隐蔽（采集失败 → 无 models.json.gz → 悄悄变成单遍），必须显式说出来。
            execution.log.accept("[voxelith-map] 注意：本次没有预烘焙模型（models.json.gz），引擎将单遍烘焙——"
                    + "多批次渲染已失效，峰值内存与耗时随整个窗口（约 " + chunks + " 区块）上涨；"
                    + "修复模型采集（检查模组依赖是否齐全，或勾选「跳过模型采集」以外的方式）后再渲染可获得分批效果");
        }
        if (chunks > 0 && timeoutMinutes > config.timeoutMinutes()) {
            // 超时与堆同源自适应：大型窗口既吃堆也吃时间，只放宽堆而不放宽时间，就会在烘焙末段被超时打断
            execution.log.accept("[voxelith-map] 窗口较大（约 " + chunks + " 区块），渲染超时放宽为 "
                    + timeoutMinutes + " 分钟（配置值 " + config.timeoutMinutes() + " 分钟）");
        }
        if (heapMiB > config.heapMiB()) {
            execution.log.accept("[voxelith-map] 窗口较大（约 "
                    + chunks + " 区块），渲染堆预配为 -Xmx" + heapMiB
                    + "m（配置值 " + config.heapMiB() + "m）");
        }
        VoxelithRenderEngine.RenderOutcome outcome = engine.render(
                invocation, execution.taskDir(), handle::isCanceled, execution.progress(),
                execution.log, heapMiB, java.time.Duration.ofMinutes(timeoutMinutes));
        // 子进程的完整输出写在任务临时目录里，收尾时要把这份搬到持久目录（任务目录随后即删）
        execution.recordLogFile(outcome.result().logFile());
        // 诊断（版本体检警告、模型来源…）都出现在输出开头，只靠日志尾巴会丢；这里在分支收敛前
        // 同步一次，成功/失败/取消三条路都能带上
        execution.flushDiagnostics();
        // OOM 自动重试：堆不够时以双倍堆重跑一次（烘焙需要重算，但免去改宿主配置/缩范围）
        if (!outcome.succeeded() && !outcome.result().canceled()
                && outcome.result().exitCode() == 4 && config.oomAutoRetry()) {
            outcome = retryWithLargerHeap(execution, handle, outcome);
        }
        if (outcome.succeeded()) {
            return outcome.publishRoot();
        }
        if (outcome.result().canceled()) {
            cancelTask(task, project, execution.generation());
            return null;
        }
        failTask(task, project, execution.generation(), describeFailure(execution, outcome.result()));
        return null;
    }

    /**
     * 失败原因（超时单独说清楚上限与调法）。
     *
     * <p>超时被杀时只说「渲染超时被终止」会让人不知道上限是多少、也不知道大型窗口会自动放宽——
     * 排查第一步就卡住。这里补上本次上限与宿主设置键。</p>
     */
    private String describeFailure(RenderExecution execution, VoxelithProcessRunner.RunResult result) {
        if (!result.timedOut()) {
            return result.failureReason();
        }
        Integer minutes = execution.timeoutMinutes();
        return "渲染超时被终止（本次上限 " + (minutes == null ? "?" : minutes) + " 分钟）"
                + "：大型地图会按窗口区块数自动放宽，也可调高宿主设置 yudream.voxelith-map.timeout-minutes，"
                + "或缩小渲染范围后重试";
    }

    /**
     * 取消收尾：丢弃隔离代、状态置 CANCELED、落库、广播事件。
     *
     * <p>失败与取消都走「discard 隔离代」，线上内容不动；区别只在聚合终态——
     * 取消必须落在 CANCELED 而不是 FAILED，前端才能显示「已取消」而不是「渲染失败」。</p>
     */
    /** 窗口区块总数估算（region 框选时）；未框选返回 -1。 */
    private long windowChunkEstimate(RenderExecution execution) {
        return execution.project().region().map(region -> {
            long chunksX = (long) Math.ceil((region.maxX() - region.minX() + 1) / 16.0);
            long chunksZ = (long) Math.ceil((region.maxZ() - region.minZ() + 1) / 16.0);
            return chunksX * chunksZ;
        }).orElse(-1L);
    }

    /**
     * 按窗口规模预配渲染堆（MiB）：每区块预留 0.35MiB（覆盖烘焙 + 网格数据转换两个峰值），
     * 256MiB 对齐、上限 32GiB；低于配置值时不预配（尊重显式配置），未框选（整世界）也无法估算。
     */
    private int presetHeapMiB(RenderExecution execution) {
        if (config.heapMiB() <= 0) {
            return 0;
        }
        long total = windowChunkEstimate(execution);
        if (total <= 0) {
            return config.heapMiB();
        }
        long estimate = Math.min((long) Math.ceil(total * 0.35 / 256.0) * 256, 32768);
        return (int) Math.max(config.heapMiB(), estimate);
    }

    /**
     * OOM 自动重试：以双倍堆（上限 32GiB）重跑一次渲染。
     * 失败结果原样返回时调用方走正常失败路径；重试本身的取舍都会写进任务日志。
     */
    private VoxelithRenderEngine.RenderOutcome retryWithLargerHeap(
            RenderExecution execution, Running handle, VoxelithRenderEngine.RenderOutcome failed) {
        int originalHeap = parseHeapMiB(failed.result().heapLabel());
        if (originalHeap <= 0) {
            originalHeap = config.heapMiB();
        }
        if (originalHeap <= 0) {
            originalHeap = VoxelithRuntimeConfig.DEFAULT_HEAP_MIB;
        }
        int retryHeap = Math.min(originalHeap * 2, 32768);
        if (retryHeap <= originalHeap) {
            execution.log.accept("[voxelith-map] 渲染子进程堆不足，且已达自动重试堆上限（"
                    + originalHeap + " MiB），不再重试");
            return failed;
        }
        execution.log.accept("[voxelith-map] 渲染子进程堆不足（-Xmx" + originalHeap
                + "m），自动以 -Xmx" + retryHeap + "m 重试一次（烘焙需重新计算；"
                + "如仍不足可在宿主设置继续调大 yudream.voxelith-map.heap-mib 后重渲）");
        VoxelithRenderEngine.RenderOutcome outcome = engine.render(
                buildRenderInvocation(execution), execution.taskDir(), handle::isCanceled,
                execution.progress(), execution.log, retryHeap);
        execution.flushDiagnostics();
        return outcome;
    }

    /** 从 -Xmx 标签（如 4096m）解析堆 MiB；解析不出返回 -1。 */
    private static int parseHeapMiB(String heapLabel) {
        if (heapLabel == null || heapLabel.isBlank()) {
            return -1;
        }
        String digits = heapLabel.trim().replaceAll("(?i)[a-z]+$", "");
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void cancelTask(RenderTask task, MapProject project, MapGeneration generation) {
        publisher.discard(generation);
        task.cancel();
        project.markCanceled();
        taskRepo.save(task);
        projectRepo.save(project);
        publishEvent(task, project.name());
    }

    /** 导入产物 → 校验完整性 → 切换发布指针 → 回收旧代。 */
    private void importAndPublish(RenderExecution execution, Path publishRoot, Running handle) {
        RenderTask task = execution.task();
        MapProject project = execution.project();
        MapGeneration generation = execution.generation();
        // 渲染成功后用户点了取消：尊重取消请求，不把这次产物发布出去（导入可能耗时数秒）
        if (handle.isCanceled()) {
            cancelTask(task, project, generation);
            return;
        }
        try {
            task.advance(RenderPhase.IMPORT, 99);
            execution.persistIfDue(true);
            VoxelithArtifactImporter.ImportSummary summary =
                    importer.importPublished(project.id(), generation.id(), publishRoot);
            if (!summary.complete()) {
                failTask(task, project, generation, summary.missingDescription());
                return;
            }
            // 地表栅格（1px=1方块）随本代导入：2D 地图用它做逐方块精度的平面图。
            // 文件在持久 work 目录（渲染日志中的「地表栅格已落盘」），缺失不影响发布。
            try {
                if (importer.importSurface(config.mapWorkDir(project.id()), project.id(), generation.id())) {
                    task.appendLog("[voxelith-map] 已导入地表栅格 surface.png（1px=1方块）供 2D 地图使用");
                }
            } catch (IOException e) {
                task.appendLog("[voxelith-map] 导入 surface.png 失败（不影响渲染产物）：" + e.getMessage());
            }
            publisher.requirePublishable(generation);
            project.publish(generation, summary.summary());
            projectRepo.save(project);

            List<String> recycled = publisher.cleanupOldGenerations(project);
            if (!recycled.isEmpty()) {
                project.forgetGenerations(recycled);
                projectRepo.save(project);
            }
            pruneStaleDiskGenerations(project);
            task.succeed();
            task.appendLog("[voxelith-map] 发布完成，共 " + summary.importedKeys().size() + " 个产物");
            taskRepo.save(task);
            publishEvent(task, project.name());
        } catch (Exception e) {
            failTask(task, project, generation, "导入/发布失败: " + e.getMessage());
        }
    }

    /**
     * 「空地图」判定：读引擎落在渲染工作目录里的烘焙报告，方块数为 0 即视为空。
     *
     * <p>没有报告（老引擎）或读不懂时一律返回 false —— 体检本身不该成为新的失败来源。</p>
     */
    private boolean bakeProducedNothing(MapProject project) {
        Path report = config.mapWorkDir(project.id()).resolve("bake-report.json");
        if (!Files.isRegularFile(report)) {
            return false;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    objectMapper.readTree(Files.readAllBytes(report));
            com.fasterxml.jackson.databind.JsonNode blocks = root.get("blocksBaked");
            return blocks != null && blocks.isNumber() && blocks.asLong() == 0L;
        }
        catch (Exception e) {
            return false;
        }
    }

    private void failTask(RenderTask task, MapProject project, MapGeneration generation, String reason) {
        try {
            publisher.discard(generation);
        } catch (RuntimeException e) {
            // 回收失败不改变任务结局：遗留的垃圾由重启自愈或下次发布时再收一次
            task.appendLog("[voxelith-map] 回收失败代产物时出错: " + e);
        }
        task.fail(reason);
        taskRepo.save(task);
        project.markFailed(reason);
        projectRepo.save(project);
        publishEvent(task, project.name());
    }

    /**
     * 清理磁盘发布目录里已不被引用的代目录（{@code <publish>/<mapId>/<generationId>}）。
     *
     * <p>渲染把成品写进持久发布目录（按代隔离），发布指针切换后旧代目录就从「分片 worker 可增量
     * 更新的工作副本」退化成纯占磁盘的残留；对象存储那边由 {@code cleanupOldGenerations} 按代清单
     * 回收，磁盘这边没有清单可依，直接按「目录名不在已发布代集合里」判定。</p>
     */
    private void pruneStaleDiskGenerations(MapProject project) {
        Path mapPublishDir = config.mapPublishDir(project.id());
        if (!Files.isDirectory(mapPublishDir)) {
            return;
        }
        Set<String> keep = new HashSet<>(project.publishedGenerationIds());
        try (Stream<Path> entries = Files.list(mapPublishDir)) {
            for (Path entry : entries.toList()) {
                if (Files.isDirectory(entry) && !keep.contains(entry.getFileName().toString())) {
                    deleteQuietly(entry);
                }
            }
        } catch (IOException | RuntimeException e) {
            // 清不掉只占磁盘，不影响正确性；发布已成功，别让磁盘清扫失败把结局翻车，
            // 也没有任务日志可挂（任务此刻已收尾），静默忽略即可
        }
    }

    private void persistForce(RenderTask task, MapProject project) {
        taskRepo.save(task);
        publishEvent(task, project.name());
    }

    private void publishEvent(RenderTask task, String mapName) {
        eventStream.publish(VoxelithAppAssembler.toTask(task, mapName));
    }

    private static String newTaskId() {
        return "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 模组包内条目的落地文件名：去掉目录部分并拦掉非法名（zip 内容不可信）。 */
    private static String safePackJarName(String fileName, int index) {
        String base = fileName.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        String cleaned = base.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (cleaned.isBlank() || !cleaned.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            cleaned = "mod-" + index + ".jar";
        }
        // 前缀序号：包内同名条目不会互相覆盖，且保持包内顺序（顺序即模组叠加优先级）
        return index + "-" + cleaned;
    }

    private static String safeModJarFileName(ModJarRef modJar, int index) {
        String raw = modJar.name();
        String base = raw == null || raw.isBlank() ? "mod" : Path.of(raw).getFileName().toString();
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (base.isBlank()) {
            base = "mod";
        }
        if (!base.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            base = base + ".jar";
        }
        return "mod-" + (index + 1) + "-" + base;
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 残留临时目录不致命：下一次任务用新目录
                }
            });
        } catch (IOException ignored) {
            // 遍历失败同理：任务已经结束，清理只是尽力而为
        }
    }

    /** 运行中任务的句柄。 */
    private static final class Running {

        private final String taskId;
        private final AtomicBoolean canceled = new AtomicBoolean();
        private volatile Future<?> future;

        Running(String taskId) {
            this.taskId = taskId;
        }

        void attach(Future<?> future) {
            this.future = future;
            // 提交前后被取消的场景不在这里 interrupt：Future.cancel(true) 会中断 worker 线程，
            // execute 开头的取消检查会把任务收尾到 CANCELED（见 cancel() 的说明）
        }

        /**
         * 请求取消：只置标志，绝不中断 worker 线程。
         *
         * <p>此前这里调 {@code future.cancel(true)}，中断会让 {@code VoxelithProcessRunner} 的
         * {@code waitFor} 抛 {@code InterruptedException} → 编排层把它当渲染异常 → 任务被标成
         * FAILED（错误文案「渲染线程被中断」），用户点「停止」看到的却是「失败」。现在运行中的
         * 任务由引擎的取消探针（200ms 轮询）感知并优雅终止子进程，走
         * {@code RunResult.canceled()} → {@code cancelTask()} 的正路；排队中的任务由
         * {@code execute()} 开头的检查兜住。</p>
         */
        void cancel() {
            canceled.set(true);
        }

        boolean isCanceled() {
            return canceled.get();
        }

        boolean isActive() {
            Future<?> current = future;
            return current != null && !current.isDone();
        }
    }

    /**
     * 单次执行的上下文：把「任务 + 项目 + 临时目录 + 节流状态」绑在一起，
     * 免得方法签名里传七八个参数。
     */
    private final class RenderExecution {

        private final RenderTask task;
        private final MapProject project;
        private final Path taskDir;
        private final List<Path> packs = new ArrayList<>();
        /** 本次渲染物化出来的模组 jar（与 packs[1..] 一一对应；采集时原样下传）。 */
        private final List<Path> mods = new ArrayList<>();
        private Path worldDir;
        private Path modelsFile;
        /** 最后一次子进程运行的完整日志文件（多遍/OOM 重试时取最后一次） */
        private Path lastLogFile;
        /** 本次渲染的超时上限（分钟，按窗口规模自适应；失败文案要用） */
        private Integer timeoutMinutes;
        /** 从 level.dat 读出的存档版本（与引擎判定同源）；读不出为 null。 */
        private String levelDatVersion;
        private final VoxelithDiagnostics diagnostics = new VoxelithDiagnostics();
        private final VoxelithProcessRunner.LineConsumer log;
        private final VoxelithRenderEngine.ProgressSink progress;
        private long lastPersistAt;
        private long lastEventAt;
        private RenderPhase lastPhase;

        RenderExecution(RenderTask task, MapProject project, Path taskDir) {
            this.task = task;
            this.project = project;
            this.taskDir = taskDir;
            this.lastPhase = task.phase();
            this.log = line -> {
                diagnostics.accept(line);
                task.appendLog(line);
                persistIfDue(false);
            };
            this.progress = (phase, percent, message, tilesDone, tilesTotal) -> {
                boolean phaseChanged = phase != null && phase != lastPhase;
                if (phaseChanged) {
                    lastPhase = phase;
                    task.note(message);
                }
                // 计数行（tile/lod 完成 n/m）带上细粒度瓦片计数：此前只拼进 message，
                // reportTiles 无人可调，前端进度永远显示 0/0
                if (tilesDone != null && tilesTotal != null) {
                    task.reportTiles(tilesDone, tilesTotal);
                }
                task.advance(phase, percent);
                persistIfDue(phaseChanged);
            };
        }

        RenderTask task() {
            return task;
        }

        MapProject project() {
            return project;
        }

        Path taskDir() {
            return taskDir;
        }

        void recordLogFile(Path logFile) {
            this.lastLogFile = logFile;
        }

        void recordTimeoutMinutes(int minutes) {
            this.timeoutMinutes = minutes;
        }

        Integer timeoutMinutes() {
            return timeoutMinutes;
        }

        Path lastLogFile() {
            return lastLogFile;
        }

        MapGeneration generation() {
            return task.generation();
        }

        String dimension() {
            return project.settings().dimension();
        }

        /**
         * 本次渲染实际生效的参数。
         *
         * <p>留空 {@code mcVersion} 不等于「无版本」：它是「交给引擎按资源包文件名/存档版本自动判定」，
         * 由 {@code VoxelithCliArguments} 决定要不要下发 {@code --mc-version}。</p>
         */
        RenderSettings effectiveSettings() {
            return project.settings();
        }

        VoxelithProcessRunner.LineConsumer log() {
            return log;
        }

        VoxelithRenderEngine.ProgressSink progress() {
            return progress;
        }

        /** 把当前诊断快照写进聚合（引擎输出里同一行可能重打，聚合内部会去重）。 */
        void flushDiagnostics() {
            task.recordDiagnostics(diagnostics.lines());
        }

        /** 节流落库与推送；{@code force} 用于阶段切换等关键时刻。 */
        void persistIfDue(boolean force) {
            long now = Instant.now().toEpochMilli();
            if (force || now - lastPersistAt >= PERSIST_INTERVAL_MILLIS) {
                lastPersistAt = now;
                flushDiagnostics();
                taskRepo.save(task);
            }
            if (force || now - lastEventAt >= EVENT_INTERVAL_MILLIS) {
                lastEventAt = now;
                publishEvent(task, project.name());
            }
        }
    }

    /** 便于在无 Spring 的纯测试里构造（与 {@link RenderTaskDTO} 一致的任务视图）。 */
    public Optional<RenderTaskDTO> taskView(String taskId) {
        return task(taskId).map(task -> VoxelithAppAssembler.toTask(
                task, projectRepo.findById(task.mapId()).map(MapProject::name).orElse(task.mapId())));
    }

    /** 供测试与自检：当前排队/运行的任务 id。 */
    public List<String> activeTaskIds() {
        return running.values().stream().filter(Running::isActive).map(handle -> handle.taskId).toList();
    }
}
