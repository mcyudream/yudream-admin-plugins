package online.yudream.plugin.voxelithmap.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.plugin.voxelithmap.application.assembler.VoxelithAppAssembler;
import online.yudream.plugin.voxelithmap.application.cmd.ImportInstanceJobCmd;
import online.yudream.plugin.voxelithmap.application.dto.MapAdminDTO;
import online.yudream.plugin.voxelithmap.application.dto.RenderTaskDTO;
import online.yudream.plugin.voxelithmap.domain.aggregate.MapProject;
import online.yudream.plugin.voxelithmap.domain.aggregate.RenderTask;
import online.yudream.plugin.voxelithmap.domain.enumerate.RenderPhase;
import online.yudream.plugin.voxelithmap.domain.enumerate.TaskKind;
import online.yudream.plugin.voxelithmap.domain.port.InstanceFilePort;
import online.yudream.plugin.voxelithmap.domain.repo.RenderTaskRepo;
import online.yudream.plugin.voxelithmap.domain.value.InstanceDimensionDir;
import online.yudream.plugin.voxelithmap.domain.value.InstanceDirEntry;
import online.yudream.plugin.voxelithmap.infrastructure.engine.InstanceJarVersions;
import online.yudream.plugin.voxelithmap.infrastructure.engine.PlatformFileMaterializer;
import online.yudream.plugin.voxelithmap.infrastructure.modpack.ModPackInspector;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithRuntimeConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 长任务作业服务：把不适合占着请求线程做的操作放后台执行。
 *
 * <p>承载两类作业：<b>删除地图</b>（回收渲染产物、存档与地图记录）与<b>实例导入</b>
 * （从面板实例打包 world/mods 拉回本插件存储并建图）。渲染任务由 {@code RenderOrchestrator}
 * 自己排队，不经过这里；三类任务共用同一套任务聚合与推送（{@link RenderTask} +
 * {@link VoxelithEventStream}），因此任务中心、进度流与日志只有一套。</p>
 *
 * <p>实例导入优先放在服务端：搬运（节点打包 → 分块拉回 → 落存储 → 建图）若由浏览器经面板
 * HTTP 端点完成，刷新或关闭页面会把传输断在半路。服务端路径依赖面板暴露实例文件契约；当前
 * 面板 0.22.6 没有该契约，因此 {@link InstanceFilePort} 报告不可用，作业自动走浏览器搬运的
 * 降级路径。渲染、标注与公开查看器始终不受影响。</p>
 */
public final class PluginJobService {

    /** 并发作业上限：删除与导入都是 IO 密集型（对象存储与面板节点），别把连接池打满。 */
    private static final int MAX_CONCURRENT_JOBS = 2;

    /** 单次向面板请求的分块上限；面板/节点有更小上限时会按实际返回量自适应收敛。 */
    private static final int IMPORT_CHUNK_BYTES = 2 * 1024 * 1024;
    /** 打包产物上限（与前端及面板契约同口径的 2GB 护栏）。 */
    private static final long IMPORT_MAX_BYTES = 2L << 30;
    /** 存档小于此字节数基本不是有效的 world 打包，早点失败别等渲染。 */
    private static final long IMPORT_MIN_WORLD_BYTES = 1024;
    /** 进度落库/推送的最小间隔：分块回调太密，避免把存储写成热点。 */
    private static final long PROGRESS_FLUSH_MILLIS = 700;

    /** 进度区间：world 打包+拉回 [10,45]，mods [50,85]，建图 92。 */
    private static final int WORLD_FROM = 10;
    private static final int WORLD_TO = 45;
    private static final int MODS_FROM = 50;
    private static final int MODS_TO = 85;
    private static final int CREATE_PERCENT = 92;

    private final MapProjectAppService maps;
    private final RenderTaskRepo taskRepo;
    private final VoxelithEventStream events;
    private final ExecutorService executor;
    private final InstanceFilePort instances;
    private final PluginFileStore fileStore;
    private final VoxelithRuntimeConfig config;
    /** 把平台文件（浏览器上传的模组包）物化到本地，供完整性排查读取。 */
    private final PlatformFileMaterializer files;
    /** 模组包完整性排查：导入阶段就指出缺失依赖（见 ModPackInspector）。 */
    private final ModPackInspector modPackInspector;
    /** 打包产物拉回器：自适应分块、完整性校验与取消检查（可单测，见 InstanceArchiveFetcher）。 */
    private final InstanceArchiveFetcher fetcher;
    private final Set<String> cancels = ConcurrentHashMap.newKeySet();

    public PluginJobService(MapProjectAppService maps, RenderTaskRepo taskRepo,
                           VoxelithEventStream events, ExecutorService executor,
                           InstanceFilePort instances, PluginFileStore fileStore,
                           VoxelithRuntimeConfig config, PlatformFileMaterializer files,
                           ModPackInspector modPackInspector) {
        this.maps = maps;
        this.taskRepo = taskRepo;
        this.events = events;
        this.executor = executor;
        this.instances = instances;
        this.fileStore = fileStore;
        this.config = config;
        this.files = files;
        this.modPackInspector = modPackInspector;
        this.fetcher = new InstanceArchiveFetcher(instances, IMPORT_CHUNK_BYTES, IMPORT_MAX_BYTES);
    }

    /** 并发上限，供上层做排队/拒绝判断。 */
    public int maxConcurrent() {
        return MAX_CONCURRENT_JOBS;
    }

    /** 面板实例文件访问是否可用（面板插件已安装并启用）；导入入口据此显隐/降级。 */
    public boolean instanceImportAvailable() {
        return instances.available();
    }

    /**
     * 发起一次删除作业：回收渲染产物、存档与地图记录。
     *
     * <p>删除本身是一次不可中断的存储回收，因此只在排队阶段允许取消；作业记录保留地图名
     * （subject），地图删除后任务中心仍能看到这次删除的结果与日志。</p>
     */
    public RenderTaskDTO startDelete(String mapId) {
        MapProject project = maps.require(mapId);
        RenderTask task = RenderTask.createJob(newTaskId(), TaskKind.DELETE, project.name());
        taskRepo.save(task);
        publish(task);
        executor.submit(() -> runDelete(task.id(), mapId));
        return VoxelithAppAssembler.toTask(task, project.name());
    }

    /**
     * 发起一次实例导入作业：面板侧打包 world（与可选 mods）→ 分块拉回本插件文件存储 → 建图。
     *
     * <p>入参校验在提交前完成（面板插件不可用、实例 id 缺失），这样用户拿到的是即时且明确的
     * 报错，而不是任务中心里一条「为什么失败」的任务。</p>
     */
    public RenderTaskDTO startImport(ImportInstanceJobCmd cmd) {
        if (cmd == null || cmd.instanceId() == null || cmd.instanceId().isBlank()) {
            throw new IllegalArgumentException("请选择要导入的面板实例");
        }
        if (!instanceImportAvailable()) {
            throw new IllegalStateException(
                    "MC 面板插件不可用（未安装/未启用，或不提供实例文件访问契约）：服务端导入不可用，"
                            + "请改用浏览器搬运方式导入");
        }
        String subject = cmd.instanceName() == null || cmd.instanceName().isBlank()
                ? cmd.instanceId()
                : cmd.instanceName();
        RenderTask task = RenderTask.createJob(newTaskId(), TaskKind.IMPORT, subject);
        taskRepo.save(task);
        publish(task);
        executor.submit(() -> runImport(task.id(), cmd));
        return VoxelithAppAssembler.toTask(task, subject);
    }

    /** 取消作业。返回 false 表示该任务不是本服务在跑，或已不可取消。 */
    public boolean cancel(String taskId) {
        RenderTask task = taskRepo.findById(taskId).orElse(null);
        if (task == null || !task.canCancel()) {
            return false;
        }
        // 删除与导入（服务端搬运 / 浏览器搬运）由本服务在跑；渲染任务由编排器取消，同步仍是浏览器驱动
        if (task.kind() != TaskKind.DELETE && task.kind() != TaskKind.IMPORT) {
            return false;
        }
        cancels.add(taskId);
        task.cancel();
        taskRepo.save(task);
        publish(task);
        return true;
    }

    // ------------------------------------------------------------------
    // 实例导入：浏览器搬运的任务化
    // ------------------------------------------------------------------

    /**
     * 发起一次**浏览器搬运**的导入任务。
     *
     * <p>面板插件没有实例文件访问契约（旧版面板）时，数据只能由管理员浏览器经面板既有 HTTP 端点搬运。
     * 即便如此，导入仍然是一条正经的任务：任务先在任务中心登记（进度、日志、取消都在那里），
     * 搬运阶段由页面把进展回报进来，存档上传完成后由服务端接管建图。这样「关掉页面就什么都查不到」
     * 变成了「任务在任务中心留痕、失败原因可查」——面板插件不需要为此做任何升级。</p>
     *
     * @return 任务；前端用它的 id 回报进度、提交结果
     */
    public RenderTaskDTO startBrowserImport(ImportInstanceJobCmd cmd) {
        if (cmd == null || cmd.instanceId() == null || cmd.instanceId().isBlank()) {
            throw new IllegalArgumentException("请选择要导入的面板实例");
        }
        String subject = cmd.instanceName() == null || cmd.instanceName().isBlank()
                ? cmd.instanceId()
                : cmd.instanceName();
        RenderTask task = RenderTask.createJob(newTaskId(), TaskKind.IMPORT, subject);
        task.start();
        task.note("浏览器搬运中：正在打包 world");
        task.advance(RenderPhase.QUEUED, 2);
        task.appendLog("已开始从面板实例导入：" + cmd.instanceId() + "（数据由浏览器经面板 HTTP 端点搬运）");
        taskRepo.save(task);
        publish(task);
        return VoxelithAppAssembler.toTask(task, subject);
    }

    /**
     * 页面回报搬运进度（阶段 / 百分比 / 明细）。
     *
     * <p>只接受仍在进行中的导入任务：已取消或已结束的任务直接忽略，避免页面在途的回报把终态改回去。</p>
     */
    public void reportBrowserImportProgress(String taskId, String stage, int percent, String detail) {
        RenderTask task = browserImport(taskId);
        if (task == null) {
            return;
        }
        String text = stage == null ? "" : stage.trim();
        String suffix = detail == null || detail.isBlank() ? "" : " · " + detail.trim();
        if (!text.isBlank()) {
            // 只有阶段文案变化才记一行日志：分块进度可能每几百毫秒回报一次，逐次落日志会把日志刷爆
            if (task.message() == null || !task.message().startsWith(text)) {
                task.appendLog(text + suffix);
            }
            task.note(text + suffix);
        }
        task.advance(RenderPhase.QUEUED, percent);
        persist(task);
    }

    /**
     * 页面把上传完成的存档（与可选模组包）交给服务端：**从这里开始由服务端接管**。
     *
     * <p>建图放到执行器里跑，任务结局不取决于这次 HTTP 响应是否送达；页面的职责到此为止。</p>
     */
    public void completeBrowserImport(String taskId, ImportInstanceJobCmd cmd,
                                      String worldFileId, String modsFileId, String modsPackName) {
        if (worldFileId == null || worldFileId.isBlank()) {
            throw new IllegalArgumentException("缺少 worldFileId（存档上传后的文件 id）");
        }
        RenderTask task = browserImport(taskId);
        if (task == null) {
            return;
        }
        task.note("存档已上传，正在服务端创建地图");
        task.appendLog("浏览器搬运完成，存档文件 id：" + worldFileId
                + (modsFileId == null || modsFileId.isBlank() ? "" : "，模组包文件 id：" + modsFileId));
        task.advance(RenderPhase.QUEUED, CREATE_PERCENT);
        persist(task);
        executor.submit(() -> finishBrowserImport(taskId, cmd, worldFileId, modsFileId, modsPackName));
    }

    /** 页面报告失败（面板报错、上传失败、用户取消搬运等）：把原因落到任务上，任务中心可见可查。 */
    public void failBrowserImport(String taskId, String message) {
        RenderTask task = browserImport(taskId);
        if (task == null) {
            return;
        }
        String reason = message == null || message.isBlank() ? "浏览器搬运中断" : message.trim();
        task.appendLog("导入失败：" + reason);
        task.fail(reason);
        taskRepo.save(task);
        publish(task);
    }

    /**
     * 服务端收尾：建图。
     *
     * <p>与浏览器搬运解耦：页面关掉也不影响这一步（它已经拿到存档 id 了），失败原因照常写进任务。</p>
     */
    private void finishBrowserImport(String taskId, ImportInstanceJobCmd cmd,
                                     String worldFileId, String modsFileId, String modsPackName) {
        try {
            RenderTask task = taskRepo.findById(taskId).orElse(null);
            if (task == null || !task.isRunning()) {
                return;
            }
            String usableModsFileId = modsFileId;
            if (modsFileId != null && !modsFileId.isBlank() && !inspectUploadedModPack(task, modsFileId)) {
                // 损坏的模组包不登记进地图：否则每次渲染都会在解包阶段失败（Unexpected end of ZLIB input stream）
                usableModsFileId = "";
                task.appendLog("模组包未通过完整性校验，本次仅导入世界存档（可在之后重新导入或补传模组）");
                persist(task);
            }
            List<MapAdminDTO> created = maps.importInstanceWorld(worldFileId, cmd.instanceId(), cmd.name(),
                    cmd.version(), usableModsFileId, usableModsFileId.isBlank() ? "" : modsPackName,
                    cmd.worldDirs());
            for (MapAdminDTO map : created) {
                task.appendLog("已创建地图「" + map.name() + "」（" + map.id() + "，维度 "
                        + InstanceDimensionDir.label(map.dimension()) + "）");
            }
            task.note("导入完成");
            task.succeed();
            taskRepo.save(task);
            publish(task);
        }
        catch (Exception e) {
            taskRepo.findById(taskId).ifPresent(task -> {
                if (task.isRunning()) {
                    task.fail(describe(e));
                    task.appendLog("创建地图失败：" + describe(e));
                    taskRepo.save(task);
                    publish(task);
                }
            });
        }
        finally {
            cancels.remove(taskId);
        }
    }

    /** 取一个仍在进行中的导入任务（浏览器搬运）；不是导入任务或已结束返回 null。 */
    private RenderTask browserImport(String taskId) {
        RenderTask task = taskId == null ? null : taskRepo.findById(taskId).orElse(null);
        if (task == null || task.kind() != TaskKind.IMPORT || !task.isRunning() || cancels.contains(taskId)) {
            return null;
        }
        return task;
    }

    // ------------------------------------------------------------------
    // 删除作业
    // ------------------------------------------------------------------

    /**
     * 删除作业主体。
     *
     * <p>进入实际删除后不再理会取消信号：删除是一次存储回收，中途停下只会留下半删除的地图记录。
     * 因此只在调用前检查一次，之后无论取消与否都按实际结果落状态。</p>
     */
    private void runDelete(String taskId, String mapId) {
        try {
            RenderTask task = taskRepo.findById(taskId).orElse(null);
            if (task == null) {
                return;
            }
            task.start();
            persist(task);
            if (cancels.contains(taskId)) {
                task.cancel();
                taskRepo.save(task);
                publish(task);
                return;
            }
            task.note("回收渲染产物与存储");
            task.appendLog("开始删除地图 " + mapId + "：回收产物、存档与记录");
            persist(task);
            maps.delete(mapId);
            task.advance(RenderPhase.QUEUED, 95);
            task.note("删除地图记录");
            task.appendLog("地图已删除");
            task.succeed();
            taskRepo.save(task);
            publish(task);
        }
        catch (Exception e) {
            taskRepo.findById(taskId).ifPresent(task -> {
                task.fail(describe(e));
                task.appendLog("删除失败: " + describe(e));
                taskRepo.save(task);
                publish(task);
            });
        }
        finally {
            cancels.remove(taskId);
        }
    }

    // ------------------------------------------------------------------
    // 实例导入作业
    // ------------------------------------------------------------------

    /**
     * 导入作业主体。
     *
     * <p>进程内执行、与页面无关；面板侧的临时打包产物在成功、失败、取消三种结局下都会清理，
     * 本插件侧的暂存文件与已落存储的半成品同样随之回收（建图之前取消不留任何记录）。</p>
     */
    private void runImport(String taskId, ImportInstanceJobCmd cmd) {
        String instanceId = cmd.instanceId();
        String panelWorld = "voxelith-import-" + taskId + ".zip";
        String panelMods = "voxelith-mods-" + taskId + ".zip";
        Path staging = null;
        boolean worldPacked = false;
        boolean modsPacked = false;
        // 已写入插件存储的 key：建图之前取消/失败时必须回收，否则会在对象存储里留下孤儿存档
        java.util.List<String> storedKeys = new java.util.ArrayList<>();
        boolean created = false;
        try {
            RenderTask task = taskRepo.findById(taskId).orElse(null);
            if (task == null) {
                return;
            }
            task.start();
            persist(task);
            requireNotCancelled(taskId);

            task.note("读取实例目录");
            task.appendLog("开始从面板实例导入：" + instanceId
                    + (cmd.instanceName() == null || cmd.instanceName().isBlank() ? "" : "（" + cmd.instanceName() + "）"));
            persist(task);

            String version = cmd.version() == null ? "" : cmd.version().trim();
            if (version.isBlank()) {
                version = detectVersion(instanceId, task);
            }

            // ---- world：面板打包 → 分块拉回 → 落本插件文件存储 ----
            task.note("面板节点正在打包 world");
            task.appendLog("打包实例 world 目录 → " + panelWorld);
            persist(task);
            worldPacked = true;
            // 存档按维度分目录：把实例里存在的维度目录一起打包，之后再按维度各建一张地图
            // （共享同一份包，渲染时按 worldName 定位各自维度的存档根）。
            // 老版布局（world/DIM-1、world/DIM1）的打包路径是父目录 world，会与主世界合并成一条，
            // 因此这里按**打包路径**去重，避免同一个目录被打两遍。
            List<InstanceDimensionDir> dimensions = detectWorldDirs(task, instanceId);
            List<String> packPaths = dimensions.isEmpty()
                    ? List.of(InstanceDimensionDir.OVERWORLD_DIR)
                    : dimensions.stream().map(InstanceDimensionDir::packPath).distinct().toList();
            long worldSize = instances.packToZip(instanceId, packPaths, panelWorld);
            task.appendLog("本次将建出 " + (dimensions.isEmpty() ? 1 : dimensions.size()) + " 张维度地图，"
                    + "打包目录：" + String.join("、", packPaths)
                    + (dimensions.isEmpty() ? ""
                    : "；维度：" + dimensions.stream().map(InstanceDimensionDir::dimension).toList()));
            if (worldSize > IMPORT_MAX_BYTES) {
                throw new IllegalStateException("world 打包产物超过 2GB 上限");
            }
            task.appendLog("world 打包完成：" + describeBytes(worldSize));
            persist(task);

            staging = stagingFile(taskId, "world");
            long worldBytes = fetchArchive(task, instanceId, panelWorld, staging, worldSize,
                    "正在拉回 world 打包产物", WORLD_FROM, WORLD_TO);
            if (worldBytes < IMPORT_MIN_WORLD_BYTES) {
                throw new IllegalStateException("存档内容过小（" + worldBytes + " 字节），疑似无效的 world 打包");
            }
            String worldKey = "imports/" + taskId + "/world.zip";
            storeArchive(task, worldKey, staging, worldBytes);
            storedKeys.add(worldKey);
            deleteQuietly(staging);
            staging = null;

            // ---- mods（可选，且非阻断）----
            String modsKey = "";
            String modsPackName = "";
            if (cmd.withMods()) {
                modsPacked = true;
                try {
                    task.note("面板节点正在打包 mods 目录");
                    persist(task);
                    List<String> modPaths = modJarPaths(task, instanceId);
                    task.appendLog("打包实例 mods 目录 → " + panelMods
                            + "（" + modPaths.size() + " 个条目）");
                    persist(task);
                    long modsSize = instances.packToZip(instanceId, modPaths, panelMods);
                    if (modsSize > IMPORT_MAX_BYTES) {
                        throw new IllegalStateException("模组打包产物超过 2GB 上限");
                    }
                    staging = stagingFile(taskId, "mods");
                    long modsBytes = fetchArchive(task, instanceId, panelMods, staging, modsSize,
                            "正在拉回模组打包产物", MODS_FROM, MODS_TO);
                    if (modsBytes > 0) {
                        // 先排查再登记：损坏的模组包一旦写进地图，每次渲染都会在解包阶段失败
                        if (!inspectModPack(task, staging)) {
                            task.appendLog("模组包未通过完整性校验，本次仅导入世界存档（可在之后重新导入或补传模组）");
                            persist(task);
                        }
                        else {
                            String key = "imports/" + taskId + "/mods.zip";
                            storeArchive(task, key, staging, modsBytes);
                            storedKeys.add(key);
                            modsKey = key;
                            modsPackName = "mods.zip";
                            task.appendLog("模组包已写入存储：" + key);
                        }
                    }
                }
                catch (InstanceArchiveFetcher.CancelledException e) {
                    throw e;
                }
                catch (RuntimeException e) {
                    // 模组是可选增强：实例没有 mods 目录、节点拒绝访问等都不该拖垮世界导入
                    task.appendLog("模组导入失败，已跳过：" + describe(e));
                    task.note("模组导入失败，继续完成世界导入");
                    persist(task);
                }
                finally {
                    deleteQuietly(staging);
                    staging = null;
                }
            }

            // ---- 建图 ----
            requireNotCancelled(taskId);
            task.note("正在创建地图");
            task.appendLog("创建地图" + (version.isBlank() ? "（版本交由渲染阶段自动判定）" : "（版本 " + version + "）"));
            task.advance(RenderPhase.QUEUED, CREATE_PERCENT);
            persist(task);
            List<MapAdminDTO> projects = maps.importInstanceWorld(worldKey, instanceId, cmd.name(), version,
                    modsKey, modsPackName, dimensions.stream().map(InstanceDimensionDir::dirName).toList());
            // 地图记录已建立：存档与模组包从此归地图所有，由「删除地图」回收（多张维度地图共享同一份包，
            // 删除时会查引用，仍被引用就不删），不再由本作业清理
            created = true;
            for (MapAdminDTO map : projects) {
                task.appendLog("已创建地图「" + map.name() + "」（" + map.id() + "，维度 "
                        + InstanceDimensionDir.label(map.dimension()) + "）");
            }
            task.note("导入完成");
            task.succeed();
            taskRepo.save(task);
            publish(task);
        }
        catch (InstanceArchiveFetcher.CancelledException cancelled) {
            markCancelled(taskId, "导入已取消：临时数据已清理，未创建地图");
        }
        catch (Exception e) {
            if (cancels.contains(taskId)) {
                markCancelled(taskId, "导入已取消：临时数据已清理，未创建地图");
            }
            else {
                taskRepo.findById(taskId).ifPresent(task -> {
                    task.fail(describe(e));
                    task.appendLog("导入失败：" + describe(e));
                    taskRepo.save(task);
                    publish(task);
                });
            }
        }
        finally {
            deleteQuietly(staging);
            // 未建图就结束（取消/失败）：回收已写入存储的存档与模组包，不留下孤儿对象
            if (!created) {
                for (String key : storedKeys) {
                    deleteStoredQuietly(key);
                }
            }
            // 面板侧临时包清理：失败不影响结果，但必须尝试（否则会在实例根目录留下 GB 级残留）
            if (worldPacked) {
                deletePanelFile(instanceId, panelWorld);
            }
            if (modsPacked) {
                deletePanelFile(instanceId, panelMods);
            }
            cancels.remove(taskId);
        }
    }

    /**
     * 模组包完整性排查（本地暂存文件）。
     *
     * <p>只报告、不改动模组集合：缺依赖是实例自身的问题，插件负责在导入阶段就把「谁缺谁」讲清楚，
     * 用户补齐后重新导入、或在详情页单独补传缺失 jar 即可。</p>
     */
    private boolean inspectModPack(RenderTask task, Path archive) {
        return reportModPack(task, archive);
    }

    /** 模组包完整性排查（平台文件 / 插件存储里的对象）：先物化到本地再读。 */
    private boolean inspectUploadedModPack(RenderTask task, String fileId) {
        Path temp = null;
        try {
            temp = stagingFile(task.id(), "mods-inspect");
            files.materialize(fileId, temp, "模组包");
            return reportModPack(task, temp);
        }
        catch (Exception e) {
            // 连读都读不了（物化失败、包不是 zip）：同样按不可用处理，别把它登记进地图
            task.appendLog("模组包完整性排查未能完成：" + describe(e));
            persist(task);
            return false;
        }
        finally {
            deleteQuietly(temp);
        }
    }

    /** @return 模组包是否可用（解压层面完整）；false 时调用方**不得**把包登记进地图 */
    private boolean reportModPack(RenderTask task, Path archive) {
        if (archive == null || modPackInspector == null) {
            return false;
        }
        try {
            ModPackInspector.Report report = modPackInspector.inspect(archive);
            List<String> lines = ModPackInspector.describe(report);
            lines.forEach(task::appendLog);
            // 诊断块在任务详情页单独展示，缺失依赖/包损坏不该被后面的日志冲掉
            task.recordDiagnostics(lines.stream()
                    .map(line -> ModPackInspector.truncate(line, 400))
                    .toList());
            persist(task);
            return report.usable();
        }
        catch (Exception e) {
            task.appendLog("模组包完整性排查未能完成：：" + describe(e));
            persist(task);
            return false;
        }
    }

    /**
     * 模组包只压参与渲染的 jar。
     *
     * <p>模组目录里常混着缓存、配置、日志与 {@code .jar.disabled} 停用包，整目录打包会把
     * 「节点压缩」这一步拖成整个导入最慢、最容易撞面板/代理超时的一环；渲染只吃 jar，
     * 其余文件带过去也没有用。列目录失败或一个 jar 都没有时退回整目录打包，行为与旧版一致。</p>
     */
    private List<String> modJarPaths(RenderTask task, String instanceId) {
        try {
            List<String> jars = new ArrayList<>();
            for (InstanceDirEntry entry : instances.listDir(instanceId, "mods")) {
                if (entry.directory()) {
                    continue;
                }
                String name = entry.name();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".jar") || lower.endsWith(".jar.disabled")) {
                    continue;
                }
                jars.add("mods/" + name);
            }
            if (!jars.isEmpty()) {
                task.appendLog("已识别 " + jars.size() + " 个模组 jar（跳过缓存与停用包）");
                return List.copyOf(jars);
            }
        }
        catch (RuntimeException | LinkageError e) {
            task.appendLog("列出 mods 目录失败，改为整目录打包：" + describe(e));
        }
        return List.of("mods");
    }

    /**
     * 列出实例里存在的维度目录（新版 {@code world}/{@code world_nether}/{@code world_the_end}，
     * 以及老版 {@code world/DIM-1}、{@code world/DIM1}）。
     *
     * <p>老版存档把下界/末地塞在 {@code world} 里面，且这些子目录没有 level.dat：不额外探一层就会
     * 只认出一个维度，下界与末地永远建不出图（线上反馈的就是这种存档）。两种布局同时存在时以新版为准。</p>
     */
    private List<InstanceDimensionDir> detectWorldDirs(RenderTask task, String instanceId) {
        List<String> rootDirs;
        try {
            rootDirs = new ArrayList<>();
            for (InstanceDirEntry entry : instances.listDir(instanceId, ".")) {
                if (entry.directory()) {
                    rootDirs.add(entry.name());
                }
            }
        }
        catch (RuntimeException | LinkageError e) {
            task.appendLog("读取实例根目录失败，按主世界目录导入：" + describe(e));
            return List.of();
        }
        List<InstanceDimensionDir> found = new ArrayList<>(InstanceDimensionDir.pick(rootDirs));
        boolean hasModernNetherOrEnd = found.stream().anyMatch(dir ->
                InstanceDimensionDir.NETHER_DIR.equals(dir.dirName())
                        || InstanceDimensionDir.END_DIR.equals(dir.dirName()));
        String worldDir = found.stream()
                .filter(dir -> InstanceDimensionDir.OVERWORLD.equals(dir.dimension()))
                .map(InstanceDimensionDir::dirName)
                .findFirst()
                .orElse(null);
        if (!hasModernNetherOrEnd && worldDir != null) {
            try {
                for (InstanceDirEntry entry : instances.listDir(instanceId, worldDir)) {
                    if (entry.directory()) {
                        InstanceDimensionDir.legacyNested(worldDir, entry.name())
                                .ifPresent(found::add);
                    }
                }
            }
            catch (RuntimeException | LinkageError e) {
                task.appendLog("读取 " + worldDir + " 目录失败，未识别老版维度子目录：" + describe(e));
            }
        }
        // 顺序固定（主世界 → 下界 → 末地），并与前端、与老版/新版混合的情形保持一致
        found.sort(java.util.Comparator.comparingInt(
                dir -> InstanceDimensionDir.dimensionRank(dir.dimension())));
        return List.copyOf(found);
    }

    /** 从实例根目录的 jar 文件名判定存档版本；判不出就交给渲染阶段自动判定。 */
    private String detectVersion(String instanceId, RenderTask task) {
        try {
            List<InstanceDirEntry> entries = instances.listDir(instanceId, ".");
            Optional<String> version = InstanceJarVersions.pickVersion(
                    entries.stream().filter(entry -> !entry.directory()).map(InstanceDirEntry::name).toList());
            if (version.isPresent()) {
                task.appendLog("从实例根目录 jar 文件名判定存档版本：" + version.get());
                return version.get();
            }
            task.appendLog("未在实例根目录识别到带版本号的 jar，版本交由渲染阶段自动判定");
        }
        catch (RuntimeException | LinkageError e) {
            task.appendLog("读取实例根目录失败（不影响导入，版本交由渲染阶段判定）：" + describe(e));
        }
        return "";
    }

    /**
     * 分块拉回打包产物到本地暂存文件（见 {@link InstanceArchiveFetcher}：自适应分块、
     * 完整性校验、可取消都在那里，且可单测）。
     *
     * <p>进度落库按时间节流：分块回调太密，逐块写文档会把对象存储写成热点。</p>
     *
     * @return 实际写入的字节数
     */
    private long fetchArchive(RenderTask task, String instanceId, String panelPath, Path target,
                              long total, String label, int fromPercent, int toPercent) throws IOException {
        long[] lastFlush = {0};
        return fetcher.fetch(instanceId, panelPath, target, total,
                () -> cancels.contains(task.id()),
                (received, expected) -> {
                    long now = System.currentTimeMillis();
                    if (now - lastFlush[0] < PROGRESS_FLUSH_MILLIS) {
                        return;
                    }
                    lastFlush[0] = now;
                    reportTransfer(task, received, expected, label, fromPercent, toPercent);
                });
    }

    /** 传输进度：比例可量时给确定百分比，量不出时只更新「已拉回 X MiB」明细。 */
    private void reportTransfer(RenderTask task, long received, long total, String label,
                                int fromPercent, int toPercent) {
        int percent;
        if (total > 0) {
            percent = fromPercent + (int) Math.round((toPercent - fromPercent) * Math.min(1.0, (double) received / total));
        }
        else {
            percent = fromPercent;
        }
        task.advance(RenderPhase.QUEUED, percent);
        task.note(label + " · " + describeBytes(received) + (total > 0 ? " / " + describeBytes(total) : ""));
        persist(task);
    }

    /** 把暂存文件写入插件自有对象存储（流式，不二次读进内存）。 */
    private void storeArchive(RenderTask task, String key, Path source, long length) throws IOException {
        try (var input = Files.newInputStream(source)) {
            String stored = fileStore.put(key, input, length, "application/zip");
            task.appendLog("已写入存储：" + (stored == null || stored.isBlank() ? key : stored)
                    + "（" + describeBytes(length) + "）");
        }
    }

    /** 每个作业一个暂存目录，作业结束即删（与渲染任务临时目录同一父目录）。 */
    private Path stagingFile(String taskId, String what) throws IOException {
        Path root = config.tasksDir().resolve("import-" + taskId);
        Files.createDirectories(root);
        return root.resolve(what + ".zip");
    }

    private void requireNotCancelled(String taskId) {
        if (cancels.contains(taskId)) {
            throw new InstanceArchiveFetcher.CancelledException();
        }
    }

    private void markCancelled(String taskId, String log) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.appendLog(log);
            task.cancel();
            taskRepo.save(task);
            publish(task);
        });
    }

    private void deletePanelFile(String instanceId, String path) {
        try {
            instances.deleteFile(instanceId, path);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 面板不可用/文件已不存在：不影响导入结果，残留由下次同实例导入覆盖
        }
    }

    /** 回收插件存储里的对象；对象不存在或存储异常都不影响作业结局。 */
    private void deleteStoredQuietly(String key) {
        try {
            fileStore.delete(key);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 存储删除失败：地图记录没建，残留由运维按 imports/ 前缀清理
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored) {
            // 暂存清理失败不影响结果
        }
    }

    /** 人类可读的字节数（日志与进度明细用）。 */
    private static String describeBytes(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    }

    private void persist(RenderTask task) {
        taskRepo.save(task);
        publish(task);
    }

    private void publish(RenderTask task) {
        events.publish(VoxelithAppAssembler.toTask(task, null));
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static String newTaskId() {
        return "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
