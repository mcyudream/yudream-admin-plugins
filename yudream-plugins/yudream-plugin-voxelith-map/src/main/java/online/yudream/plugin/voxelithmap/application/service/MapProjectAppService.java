package online.yudream.plugin.voxelithmap.application.service;

import online.yudream.plugin.voxelithmap.application.assembler.VoxelithAppAssembler;
import online.yudream.plugin.voxelithmap.application.cmd.CreateMapCmd;
import online.yudream.plugin.voxelithmap.application.cmd.UpdateMapCmd;
import online.yudream.plugin.voxelithmap.application.dto.EngineStatusDTO;
import online.yudream.plugin.voxelithmap.application.dto.MapAdminDTO;
import online.yudream.plugin.voxelithmap.application.dto.MapSummaryDTO;
import online.yudream.plugin.voxelithmap.application.dto.PreviewDTO;
import online.yudream.plugin.voxelithmap.application.dto.RenderTaskDTO;
import online.yudream.plugin.voxelithmap.domain.aggregate.MapProject;
import online.yudream.plugin.voxelithmap.domain.aggregate.RenderTask;
import online.yudream.plugin.voxelithmap.domain.enumerate.ProjectState;
import online.yudream.plugin.voxelithmap.domain.repo.MapProjectRepo;
import online.yudream.plugin.voxelithmap.domain.value.DefaultView;
import online.yudream.plugin.voxelithmap.domain.value.InstanceDimensionDir;
import online.yudream.plugin.voxelithmap.domain.value.ModJarRef;
import online.yudream.plugin.voxelithmap.domain.value.RegionWindow;
import online.yudream.plugin.voxelithmap.domain.value.RenderSettings;
import online.yudream.plugin.voxelithmap.infrastructure.engine.PlatformFileMaterializer;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithBundledRuntime;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithRuntimeConfig;
import online.yudream.plugin.voxelithmap.infrastructure.image.CoverThumbnailer;
import online.yudream.plugin.voxelithmap.infrastructure.sidecar.VoxelithSidecar;
import online.yudream.plugin.voxelithmap.infrastructure.storage.MapArtifactStorage;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.plugin.voxelithmap.infrastructure.world.WorldArchiveExtractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 地图项目应用服务：CRUD、渲染触发、预览、引擎自检。
 *
 * <p>权限与数据范围由接口层保证（管理端全部要求 {@code plugin:voxelith-map:manage}），
 * 这一层只做业务规则与状态流转。</p>
 */
public final class MapProjectAppService {

    /** 模组包清单一次最多返回多少条明细（超大包只报总数，避免响应撑爆）。 */
    private static final int MAX_MOD_PACK_LISTING = 500;

    /** 地图 id 的最大长度（会进对象 key 与 URL 路径）。 */
    private static final int MAX_ID_LENGTH = 48;

    private final MapProjectRepo projectRepo;
    private final MapArtifactStorage storage;
    private final GenerationPublisher publisher;
    private final RenderOrchestrator orchestrator;
    private final PreviewService previewService;
    private final VoxelithSidecar sidecar;
    private final VoxelithRuntimeConfig config;
    private final PlatformFileMaterializer files;

    public MapProjectAppService(MapProjectRepo projectRepo,
                                MapArtifactStorage storage,
                                GenerationPublisher publisher,
                                RenderOrchestrator orchestrator,
                                PreviewService previewService,
                                VoxelithSidecar sidecar,
                                VoxelithRuntimeConfig config,
                                PlatformFileMaterializer files) {
        this.projectRepo = projectRepo;
        this.storage = storage;
        this.publisher = publisher;
        this.orchestrator = orchestrator;
        this.previewService = previewService;
        this.sidecar = sidecar;
        this.config = config;
        this.files = files;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 公开地图列表：只有真正有产物、且未归档的地图才会出现在查看器里。 */
    public List<MapSummaryDTO> listPublic() {
        List<MapSummaryDTO> result = new ArrayList<>();
        for (MapProject project : projectRepo.findAll()) {
            if (!project.archived() && project.hasPublishedArtifacts()) {
                result.add(VoxelithAppAssembler.toSummary(project));
            }
        }
        result.sort(Comparator.comparing(MapSummaryDTO::name));
        return List.copyOf(result);
    }

    /** 管理端地图列表（不含已归档，归档走 {@link #listArchived()}）。 */
    public List<MapAdminDTO> listAdmin() {
        List<MapProject> projects = new ArrayList<>(projectRepo.findAll());
        projects.sort(Comparator.comparing(MapProject::updatedAt).reversed());
        List<MapAdminDTO> result = new ArrayList<>(projects.size());
        for (MapProject project : projects) {
            if (!project.archived()) {
                result.add(VoxelithAppAssembler.toAdmin(project, coverRevisionOf(project)));
            }
        }
        return List.copyOf(result);
    }

    /** 已归档地图列表（归档页用）。 */
    public List<MapAdminDTO> listArchived() {
        List<MapProject> projects = new ArrayList<>(projectRepo.findAll());
        projects.sort(Comparator.comparing(MapProject::updatedAt).reversed());
        List<MapAdminDTO> result = new ArrayList<>(projects.size());
        for (MapProject project : projects) {
            if (project.archived()) {
                result.add(VoxelithAppAssembler.toAdmin(project, coverRevisionOf(project)));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 同一世界分组里的维度地图（**展示页**用：只给已发布、未归档的）。
     *
     * <p>查看器据此提供「切换维度」：同一个世界的各维度是各自独立的地图（各自渲染、各自瓦片），
     * 所以切换维度就是切到兄弟地图。只列已发布的：没渲染完的维度切过去只会是空白，
     * 列出来等于给访问者一个死链。</p>
     */
    public List<MapSummaryDTO> listPublicDimensions(String mapId) {
        MapProject current = requirePublished(mapId);
        List<MapSummaryDTO> result = new ArrayList<>();
        for (MapProject project : dimensionGroupOf(current)) {
            if (!project.archived() && project.hasPublishedArtifacts()) {
                result.add(VoxelithAppAssembler.toSummary(project));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 同一世界分组里的维度地图（**渲染页**用：含未渲染与已归档的）。
     *
     * <p>渲染页列全部维度是有意的：管理员正是要看到「哪些维度还没渲染」才能逐个切过去渲染。
     * 已归档的也留着，方便切过去恢复或删除（详情页本身能处理归档态的地图）。</p>
     */
    public List<MapAdminDTO> listAdminDimensions(String mapId) {
        MapProject current = require(mapId);
        List<MapAdminDTO> result = new ArrayList<>();
        // 已删除的地图连文档一起没了，分组里自然不会有；这里不再按状态过滤
        for (MapProject project : dimensionGroupOf(current)) {
            result.add(VoxelithAppAssembler.toAdmin(project, coverRevisionOf(project)));
        }
        return List.copyOf(result);
    }

    /**
     * 取同一世界的维度地图，顺序固定为主世界 → 下界 → 末地 → 其它（列表顺序稳定，切换按钮不会跳动）。
     *
     * <p>「同一个世界」按两条关系求连通闭包，任一成立即同组：</p>
     * <ul>
     *   <li><b>世界分组标识相同</b>（{@link MapProject#worldGroup()}）——一次导入建出的多张维度地图，
     *       或同一份存档建出的多张图；</li>
     *   <li><b>来源 MC 面板实例相同</b>——同一个实例的各维度就是一个世界。用户很可能**分几次**
     *       导入同一个实例的不同维度（甚至一次只导出一个维度），那几次的世界分组标识并不相同；
     *       只看分组标识会让这类数据「一个维度都切不了」，正是线上反馈的现象。</li>
     * </ul>
     *
     * <p>闭包（而不是两两比较）是必需的：A 与 B 同实例、B 与 C 同存档时，A 与 C 也必须归一组。</p>
     */
    private List<MapProject> dimensionGroupOf(MapProject current) {
        List<MapProject> all = projectRepo.findAll();
        Set<String> keys = worldKeysOf(current);
        Set<String> visited = new HashSet<>();
        List<MapProject> groupMaps = new ArrayList<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (MapProject project : all) {
                if (visited.contains(project.id()) || Collections.disjoint(worldKeysOf(project), keys)) {
                    continue;
                }
                visited.add(project.id());
                keys.addAll(worldKeysOf(project));
                groupMaps.add(project);
                grew = true;
            }
        }
        // findAll 理论上一定包含当前图；真遇上仓储异常也不能让切换器里没有「当前维度」
        if (!visited.contains(current.id())) {
            groupMaps.add(current);
        }
        groupMaps.sort(Comparator
                .comparingInt((MapProject project) -> InstanceDimensionDir.dimensionRank(project.dimension()))
                .thenComparing(MapProject::updatedAt, Comparator.reverseOrder()));
        return groupMaps;
    }

    /** 一张地图的「同一世界」线索：世界分组标识 + 来源实例（都是非空才计入）。 */
    private static Set<String> worldKeysOf(MapProject project) {
        Set<String> keys = new LinkedHashSet<>();
        String group = project.worldGroup();
        if (group != null && !group.isBlank()) {
            keys.add("group:" + group);
        }
        project.sourceInstanceId()
                .filter(instanceId -> !instanceId.isBlank())
                .ifPresent(instanceId -> keys.add("instance:" + instanceId));
        return keys;
    }

    /** 归档：从管理列表与公开查看器隐藏，数据保留，可随时恢复。 */
    public MapAdminDTO archive(String mapId) {
        MapProject project = require(mapId);
        project.archive();
        projectRepo.save(project);
        return VoxelithAppAssembler.toAdmin(project, coverRevisionOf(project));
    }

    /** 恢复已归档地图。 */
    public MapAdminDTO unarchive(String mapId) {
        MapProject project = require(mapId);
        project.unarchive();
        projectRepo.save(project);
        return VoxelithAppAssembler.toAdmin(project, coverRevisionOf(project));
    }

    /** 管理端地图详情。 */
    public MapAdminDTO detail(String mapId) {
        MapProject project = require(mapId);
        return VoxelithAppAssembler.toAdmin(project, coverRevisionOf(project));
    }

    public MapProject require(String mapId) {
        return projectRepo.findById(mapId)
                .orElseThrow(() -> new IllegalArgumentException("地图不存在: " + mapId));
    }

    /** 公开查看器要求：必须有已发布产物。 */
    public MapProject requirePublished(String mapId) {
        MapProject project = require(mapId);
        if (!project.hasPublishedArtifacts()) {
            throw new IllegalArgumentException("地图 " + mapId + " 还没有可浏览的产物");
        }
        return project;
    }

    public List<RenderTaskDTO> tasks() {
        List<RenderTask> tasks = orchestrator.tasks();
        List<RenderTaskDTO> result = new ArrayList<>(tasks.size());
        for (RenderTask task : tasks) {
            // 作业类任务自带展示主体，不必为它读一次地图文档
            result.add(VoxelithAppAssembler.toTask(task,
                    task.mapId().isBlank() ? null : nameOf(task.mapId())));
        }
        return List.copyOf(result);
    }

    public List<RenderTaskDTO> tasksOf(String mapId) {
        List<RenderTaskDTO> result = new ArrayList<>();
        for (RenderTask task : orchestrator.tasksOf(mapId)) {
            result.add(VoxelithAppAssembler.toTask(task, nameOf(mapId)));
        }
        return List.copyOf(result);
    }

    // ------------------------------------------------------------------
    // 变更
    // ------------------------------------------------------------------

    /** 创建地图项目。 */
    public MapAdminDTO create(CreateMapCmd cmd) {
        if (cmd == null || cmd.worldFileId() == null || cmd.worldFileId().isBlank()) {
            throw new IllegalArgumentException("请先上传世界存档");
        }
        // 「一次上传全部维度」：包内并列的 world / world_nether / world_the_end 各建一张地图，
        // 共享同一份存档与同一个世界分组；只有主世界时与单维度创建等价。
        // 复用实例导入那条已验证的建图循环（命名、分组、模组包引用都在里面）。
        if (Boolean.TRUE.equals(cmd.allDimensions())) {
            Path archive = materializeForScan(cmd.worldFileId());
            List<InstanceDimensionDir> dimensions;
            try {
                dimensions = WorldArchiveExtractor.detectDimensionDirs(archive);
            }
            catch (IOException e) {
                throw new IllegalStateException("读取存档包失败：" + e.getMessage(), e);
            }
            finally {
                deleteQuietly(archive);
            }
            if (dimensions.size() > 1) {
                List<MapAdminDTO> created = importInstanceWorld(cmd.worldFileId(), "", cmd.name(), "",
                        cmd.modsPackFileId(), cmd.modsPackName(),
                        dimensions.stream().map(InstanceDimensionDir::dirName).toList(),
                        cmd.clientJarFileId(), cmd.clientJarName());
                return created.isEmpty()
                        ? throwCreateFailed()
                        : created.get(0);
            }
        }
        // client jar 允许留空：渲染时按存档版本自动下载（1.0.42 起）
        if (cmd.clientJarFileId() != null && !cmd.clientJarFileId().isBlank()
                && !files.exists(cmd.clientJarFileId())) {
            throw new IllegalArgumentException("找不到上传的 client jar");
        }
        if (!files.exists(cmd.worldFileId())) {
            throw new IllegalArgumentException("找不到上传的存档文件");
        }
        List<ModJarRef> modJars = validatedModJars(cmd.modJars());
        validatedModsPack(cmd.modsPackFileId());
        RenderSettings settings = cmd.settings() == null
                ? RenderSettings.defaults()
                : cmd.settings();
        if (cmd.dimension() != null && !cmd.dimension().isBlank()) {
            settings = new RenderSettings(cmd.dimension(), settings.minY(), settings.maxLevel(),
                    settings.sampleChunks(), settings.lodAtlas(), settings.meshopt(), settings.maxChunks(),
                    settings.batchChunks(), settings.skipHarvest(), settings.mcVersion(), settings.loaderVersion());
        }
        String worldName = cmd.worldName() == null || cmd.worldName().isBlank() ? "世界存档" : cmd.worldName();
        String name = cmd.name() == null || cmd.name().isBlank() ? worldName : cmd.name();
        String mapId = uniqueId(slug(name));
        MapProject project = MapProject.create(mapId, name, worldName, cmd.worldFileId(),
                cmd.clientJarFileId(), cmd.clientJarName(), modJars, 0, 64, 0, settings);
        project.setModsPack(cmd.modsPackFileId(), cmd.modsPackName());
        projectRepo.save(project);
        // 存档本体留在宿主平台文件里（平台文件 id 已记在项目上），插件不复制一份：
        // GB 级存档再存一遍对象存储纯属浪费，渲染时按需取流即可
        return VoxelithAppAssembler.toAdmin(project, coverRevisionOf(project));
    }

    /** 把平台文件落到本地临时文件（仅用于只读探测：扫包内维度目录）。 */
    private Path materializeForScan(String fileId) {
        try {
            Path staging = java.nio.file.Files.createTempDirectory("voxelith-scan-");
            return files.materialize(fileId, staging.resolve("world.zip"), "存档文件");
        }
        catch (IOException e) {
            throw new IllegalStateException("读取存档包失败：" + e.getMessage(), e);
        }
    }

    private static MapAdminDTO throwCreateFailed() {
        throw new IllegalStateException("存档包内没有可识别的维度目录，无法建图");
    }

    private static void deleteQuietly(Path path) {
        try {
            java.nio.file.Files.deleteIfExists(path);
        }
        catch (IOException ignored) {
            // 临时目录交给系统清理
        }
    }

    /** 更新项目（改名 / 换存档 / 改参数 / 框选范围）。 */
    public MapAdminDTO update(String mapId, UpdateMapCmd cmd) {
        MapProject project = require(mapId);
        if (cmd == null) {
            return VoxelithAppAssembler.toAdmin(project, coverRevisionOf(project));
        }
        if (cmd.name() != null && !cmd.name().isBlank()) {
            project.rename(cmd.name());
        }
        if (cmd.worldFileId() != null && !cmd.worldFileId().isBlank()) {
            if (!files.exists(cmd.worldFileId())) {
                throw new IllegalArgumentException("找不到上传的存档文件");
            }
            project.setWorldSource(cmd.worldFileId(), project.clientJarFileId(), project.clientJarName(),
                    cmd.worldName() == null ? project.worldName() : cmd.worldName());
        }
        if (cmd.worldName() != null && !cmd.worldName().isBlank()
                && (cmd.worldFileId() == null || cmd.worldFileId().isBlank())) {
            project.setWorldSource(project.worldFileId(), project.clientJarFileId(),
                    project.clientJarName(), cmd.worldName());
        }
        if (cmd.clientJarFileId() != null && !cmd.clientJarFileId().isBlank()) {
            if (!files.exists(cmd.clientJarFileId())) {
                throw new IllegalArgumentException("找不到上传的 client jar");
            }
            project.setWorldSource(project.worldFileId(), cmd.clientJarFileId(), cmd.clientJarName(),
                    project.worldName());
        }
        // modsPackFileId：null = 不改动；空串 = 清除（换成纯原版或改成逐个 jar）
        if (cmd.modsPackFileId() != null) {
            validatedModsPack(cmd.modsPackFileId());
            project.setModsPack(cmd.modsPackFileId(), cmd.modsPackName());
        }
        // modJars：null = 不改动；非 null（含空表）= 整体替换
        if (cmd.modJars() != null) {
            project.setModJars(validatedModJars(cmd.modJars()));
        }
        if (cmd.settings() != null) {
            project.updateSettings(cmd.settings());
        }
        if (cmd.clearRegion()) {
            project.setRegion(null);
        } else if (cmd.hasRegion()) {
            project.setRegion(new RegionWindow(cmd.regionMinX(), cmd.regionMinZ(),
                    cmd.regionMaxX(), cmd.regionMaxZ()));
        }
        projectRepo.save(project);
        return VoxelithAppAssembler.toAdmin(project, coverRevisionOf(project));
    }

    /** 删除地图：产物（全部代）、输入文件、任务记录一起清。 */
    public void delete(String mapId) {
        MapProject project = require(mapId);
        publisher.deleteAll(project);
        // 实例导入的存档与模组 key 不在 maps/{id}/ 前缀下，这里一并清理；
        // 但一次导入可能建出多张维度地图、共享同一份存档包与模组包：仍被别的地图引用时不能删
        deleteImportedKeyUnlessShared(project.worldFileId(), mapId);
        project.modsPackFileId().ifPresent(packId -> deleteImportedKeyUnlessShared(packId, mapId));
        for (ModJarRef modJar : project.modJars()) {
            deleteImportedKeyUnlessShared(modJar.fileId(), mapId);
        }
        projectRepo.delete(mapId);
    }

    /**
     * 从 MC 面板实例导入世界并创建地图。
     *
     * <p>调用方是服务端的导入作业（{@code PluginJobService}）：世界与模组包由作业从面板实例分块
     * 拉回后直接写进本插件文件存储，因此这里的 id 是插件存储 key（{@code imports/…}），
     * 与手动创建时的平台文件 id 并存——解析顺序由 {@link PlatformFileMaterializer} 统一兜底
     * （平台文件优先，插件存储兜底）。</p>
     *
     * <p>版本由作业从实例根目录的 jar 文件名判定后传入；版本为空时按自动判定处理。</p>
     *
     * <p>mods 目录整包 zip 只登记成「模组包」一个引用（{@link MapProject#modsPackFileId()}），
     * 不在导入时解包成一个个 jar 落存储：那会在导入时打出成百上千次写请求、渲染时再逐个读回来，
     * 对象存储连接池正是被这种放大占满。解包挪到渲染准备阶段，在任务临时目录里本地完成。</p>
     *
     * @param worldFileId   存档 zip 的文件 id（插件存储 key 或平台文件 id）
     * @param modsZipFileId 模组包 zip 的文件 id（可空）
     * @param modsPackName  模组包原始文件名（展示用，可空）
     */
    public List<MapAdminDTO> importInstanceWorld(String worldFileId, String instanceId, String name,
                                                 String version, String modsZipFileId, String modsPackName,
                                                 List<String> worldDirs) {
        return importInstanceWorld(worldFileId, instanceId, name, version, modsZipFileId, modsPackName,
                worldDirs, "", "");
    }

    /**
     * 同 {@link #importInstanceWorld}，但可带上调用方已绑定的 client jar（上传建图那条路会带）。
     */
    public List<MapAdminDTO> importInstanceWorld(String worldFileId, String instanceId, String name,
                                                 String version, String modsZipFileId, String modsPackName,
                                                 List<String> worldDirs,
                                                 String clientJarFileId, String clientJarName) {
        // 早失败：明显过小的包不是有效的 world 打包，别等渲染阶段才报（大小未知时跳过该判断）
        long worldSize = files.sizeOf(worldFileId);
        if (worldSize > 0 && worldSize < 1024) {
            throw new IllegalArgumentException("存档内容过小，疑似无效的 world 打包");
        }
        // 存档按维度分目录，每个维度各自对应一张地图：一个导入包（含 world / world_nether / world_the_end，
        // 或老版 world 里的 DIM-1/DIM1）可能一次建出多张地图——它们**共享同一份存档包**，
        // 靠 worldName 记录「这张地图取包里的哪个目录」，渲染时按该目录定位存档根（见 WorldArchiveExtractor）。
        // 浏览器搬运路径传来的是存档根名（world_nether…），服务端作业路径传来的是同一套名字，
        // 因此这里只按名字反推维度；打包路径只影响打包，不参与建图。
        List<InstanceDimensionDir> dimensions = worldDirs == null || worldDirs.isEmpty()
                ? List.of(new InstanceDimensionDir(InstanceDimensionDir.OVERWORLD_DIR,
                        InstanceDimensionDir.OVERWORLD, null))
                : worldDirs.stream()
                        .map(dir -> new InstanceDimensionDir(dir,
                                InstanceDimensionDir.dimensionOf(dir).orElse(InstanceDimensionDir.OVERWORLD),
                                null))
                        .toList();
        boolean hasVersion = version != null && !version.isBlank();
        String baseName = name == null || name.isBlank() ? "实例-" + instanceId : name.trim();
        List<MapAdminDTO> created = new ArrayList<>();
        // 本次导入建出的地图归入同一个「世界分组」，渲染页与展示页据此切换维度。
        // 用主世界那张图的 mapId 作为分组标识：它一定存在（worldDirs 为空时也建主世界），
        // 且不随「同步最新存档」换存档而改变。
        String worldGroupId = null;
        for (InstanceDimensionDir dimension : dimensions) {
            RenderSettings settings = RenderSettings.defaults();
            if (hasVersion) {
                settings = new RenderSettings(dimension.dimension(), settings.minY(), settings.maxLevel(),
                        settings.sampleChunks(), settings.lodAtlas(), settings.meshopt(), settings.maxChunks(),
                        settings.batchChunks(), settings.skipHarvest(), version, settings.loaderVersion());
            }
            boolean overworld = InstanceDimensionDir.OVERWORLD.equals(dimension.dimension());
            CreateMapCmd cmd = new CreateMapCmd(
                    // 主世界用原名，其它维度带维度后缀：列表里一眼分得清
                    overworld ? baseName : baseName + " · " + InstanceDimensionDir.label(dimension.dimension()),
                    worldFileId,
                    dimension.dirName(),
                    clientJarFileId == null ? "" : clientJarFileId,
                    clientJarName != null && !clientJarName.isBlank()
                            ? clientJarName
                            : (hasVersion ? "client-" + version + ".jar" : ""),
                    modsZipFileId,
                    modsPackName,
                    List.of(),
                    dimension.dimension(),
                    settings,
                    // 建单张维度地图（多维度已在入口展开过），不再递归展开
                    Boolean.FALSE);
            MapAdminDTO map = create(cmd);
            // 模组包已随创建写入（见 cmd）。这里补两处项目属性：世界分组与来源实例
            // （实例 id 缺失时不写来源——留下一个不存在的来源只会让同步按钮每次都报错）
            MapProject project = require(map.id());
            if (worldGroupId == null) {
                worldGroupId = project.id();
            }
            project.assignWorldGroup(worldGroupId);
            if (instanceId != null && !instanceId.isBlank()) {
                project.setSourceInstanceId(instanceId);
            }
            projectRepo.save(project);
            created.add(map);
        }
        return List.copyOf(created);
    }

    /**
     * 用已上传的新存档覆盖地图存档（实例同步用）。新存档是宿主平台文件 id；旧存档若在
     * 插件存储的 imports/ 下（旧版本导入留下）一并清理。client jar 与模组引用保持不变。
     */
    public void replaceWorldArchive(String mapId, String worldFileId) {
        MapProject project = require(mapId);
        if (worldFileId == null || worldFileId.isBlank() || !files.exists(worldFileId)) {
            throw new IllegalArgumentException("找不到上传的存档文件");
        }
        String oldKey = project.worldFileId();
        project.setWorldSource(worldFileId, project.clientJarFileId(), project.clientJarName(), project.worldName());
        projectRepo.save(project);
        if (oldKey != null && oldKey.startsWith("imports/")) {
            storage.deleteKey(oldKey);
        }
    }

    /**
     * 解析模组包，列出其中所有模组 jar。
     *
     * <p>只读 zip 中央目录、不解压内容；但宿主 SPI 只提供顺序流，而中央目录在文件末尾，所以必须先把包
     * 物化到本地——这是一次完整的对象读，因此本接口按需触发（管理页点「解析」时），不在页面加载时自动跑。</p>
     *
     * @param fileId 模组包文件 id（平台文件或插件存储 key）
     * @param name   展示名（可空；优先用平台文件的原始文件名）
     */
    public online.yudream.plugin.voxelithmap.application.dto.ModPackListingDTO inspectModPack(String fileId,
                                                                                             String name) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("缺少模组包文件 id");
        }
        Path archive = null;
        try {
            archive = Files.createTempFile("voxelith-mods-listing-", ".zip");
            files.materialize(fileId, archive, "模组包");
            List<online.yudream.plugin.voxelithmap.infrastructure.modpack.ModPackInspector.JarInfo> jars =
                    online.yudream.plugin.voxelithmap.infrastructure.modpack.ModPackInspector
                            .listJars(archive, MAX_MOD_PACK_LISTING);
            long totalBytes = jars.stream()
                    .mapToLong(online.yudream.plugin.voxelithmap.infrastructure.modpack.ModPackInspector.JarInfo::sizeBytes)
                    .sum();
            List<online.yudream.plugin.voxelithmap.application.dto.ModPackListingDTO.JarEntryDTO> entries =
                    jars.stream()
                            .map(jar -> new online.yudream.plugin.voxelithmap.application.dto.ModPackListingDTO
                                    .JarEntryDTO(jar.name(), jar.sizeBytes()))
                            .toList();
            return new online.yudream.plugin.voxelithmap.application.dto.ModPackListingDTO(
                    fileId, name == null || name.isBlank() ? "mods.zip" : name, jars.size(), totalBytes,
                    entries, jars.size() > MAX_MOD_PACK_LISTING);
        }
        catch (IOException e) {
            throw new IllegalArgumentException("模组包解析失败（可能不是 zip，或文件已损坏）：" + e.getMessage(), e);
        }
        finally {
            if (archive != null) {
                try {
                    Files.deleteIfExists(archive);
                }
                catch (IOException ignored) {
                    // 临时文件清理失败不影响结果
                }
            }
        }
    }

    /** 地图当前存档 zip 字节（下载用）；平台文件与插件存储都能取；不存在返回 null。 */
    public byte[] worldArchiveZip(String mapId) throws java.io.IOException {
        MapProject project = require(mapId);
        Optional<PluginStoredFile> file = files.find(project.worldFileId());
        if (file.isEmpty()) {
            return null;
        }
        try (var input = file.get().inputStream()) {
            return input.readAllBytes();
        }
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    /** 触发一次渲染。 */
    public RenderTaskDTO render(String mapId) {
        MapProject project = require(mapId);
        if (project.archived()) {
            throw new IllegalStateException("地图已归档，请先在归档列表中恢复再渲染");
        }
        if (orchestrator.activeCount() >= Math.max(1, config.maxConcurrentTasks())) {
            throw new IllegalStateException("已有 " + orchestrator.activeCount() + " 个任务在执行，请等它跑完");
        }
        RenderTask task = orchestrator.submit(project);
        return VoxelithAppAssembler.toTask(task, project.name());
    }

    /** 取消任务。 */
    public boolean cancelTask(String taskId) {
        return orchestrator.cancel(taskId);
    }

    // ------------------------------------------------------------------
    // 预览
    // ------------------------------------------------------------------

    public PreviewDTO preview(String mapId) {
        return previewService.status(require(mapId));
    }

    public PreviewDTO triggerPreview(String mapId) {
        return previewService.trigger(require(mapId));
    }

    public Optional<byte[]> previewImage(String mapId, Integer step) throws java.io.IOException {
        return previewService.previewPng(require(mapId), step);
    }

    public Optional<byte[]> previewWindow(String mapId, int minX, int minZ, int size, int step)
            throws java.io.IOException {
        return previewService.windowPng(require(mapId), minX, minZ, size, step);
    }

    // ------------------------------------------------------------------
    // 标注
    // ------------------------------------------------------------------

    /** 读取标注（不存在返回空对象，前端据此渲染空图层）。 */
    public String markers(String mapId) {
        require(mapId);
        return storage.markers(mapId)
                .map(file -> {
                    try (var input = file.inputStream()) {
                        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        return "{\"sets\":[]}";
                    }
                })
                .orElse("{\"sets\":[]}");
    }

    /**
     * 保存标注。
     *
     * <p>只做最小形状校验（必须是含 {@code sets} 数组的 JSON 对象）：标注文件会喂给
     * 前端渲染，形状不对会让整个查看器白屏，因此在入口挡住比在渲染时崩要好。</p>
     */
    public void saveMarkers(String mapId, String json) {
        require(mapId);
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("标注内容不能为空");
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (!node.isObject()) {
                throw new IllegalArgumentException("标注必须是 JSON 对象");
            }
            if (node.has("sets") && !node.path("sets").isArray()) {
                throw new IllegalArgumentException("sets 必须是数组");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("标注不是合法 JSON：" + e.getOriginalMessage());
        }
        storage.saveMarkers(mapId, json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 保存管理员设置的默认初始视角，可同时携带一张列表封面截图（PNG，base64 解码后的字节）。
     *
     * <p>封面截图存到地图级存储（与渲染代解耦）：渲染重发布不会动它，
     * 列表页直接用公开 {@code cover.png} 地址展示。</p>
     */
    public void setDefaultView(String mapId, DefaultView view, byte[] coverPng) {
        require(mapId);
        if (view == null) {
            throw new IllegalArgumentException("默认视角不能为空");
        }
        if (coverPng != null && coverPng.length > 0) {
            if (coverPng.length > MAX_COVER_BYTES) {
                throw new IllegalArgumentException("封面截图过大（限 8MB），请调小窗口后重试");
            }
            if (!isPng(coverPng)) {
                throw new IllegalArgumentException("封面截图必须是 PNG 图片");
            }
            storage.saveCover(mapId, coverPng);
            // 顺手出一张小尺寸 JPEG 供列表使用：画廊一张卡片几百像素宽，不该去拉数 MB 的
            // PNG 截屏（地图一多会占满同源连接，把页面其它请求挤住）。生成不了就清掉旧缩略图，
            // 由读取路径退回原图，避免留下与当前封面不一致的旧图。
            Optional<byte[]> thumb = CoverThumbnailer.jpeg(coverPng);
            if (thumb.isPresent()) {
                storage.saveCoverThumb(mapId, thumb.get());
            }
            else {
                storage.deleteKey(MapArtifactStorage.coverThumbKey(mapId));
            }
        }
        MapProject project = require(mapId);
        project.setDefaultView(view);
        if (coverPng != null && coverPng.length > 0) {
            project.setCoverRevision(System.currentTimeMillis());
        }
        projectRepo.save(project);
    }

    /** 清除默认视角（查看页回到内置机位）；已保存的封面保留，可在保存新视角时覆盖。 */
    public void clearDefaultView(String mapId) {
        MapProject project = require(mapId);
        project.setDefaultView(null);
        projectRepo.save(project);
    }


    /** 封面缓存指纹（列表拼 {@code ?v=} 用）：没保存过封面为 0。 */
    public long coverRevisionOf(MapProject project) {
        if (project.coverRevision() != MapProject.UNRESOLVED_COVER_REVISION) {
            return project.coverRevision();
        }
        // 一次性迁移：老记录没有该字段，查一次封面是否存在并固化成 0（无）或时间戳（有）
        long resolved = storage.cover(project.id()).isPresent() ? System.currentTimeMillis() : 0L;
        project.setCoverRevision(resolved);
        projectRepo.save(project);
        return resolved;
    }

    /**
     * 列表封面缩略图字节。
     *
     * <p>命中缓存直接返回；老地图（缩略图机制上线前保存的封面）按需补生成一次并落存储，
     * 之后就走缓存——这样无需用户重新保存封面即可享受小图。生成不了（缺 java.desktop、
     * 图片解不开）返回空，由调用方退回原封面。</p>
     *
     * @return JPEG 字节；无封面或生成失败时为空
     */
    public Optional<byte[]> coverThumbnail(String mapId) {
        require(mapId);
        Optional<PluginStoredFile> cached = storage.coverThumb(mapId);
        if (cached.isPresent()) {
            return readBytes(cached.get());
        }
        Optional<PluginStoredFile> cover = storage.cover(mapId);
        if (cover.isEmpty()) {
            return Optional.empty();
        }
        Optional<byte[]> source = readBytes(cover.get());
        if (source.isEmpty()) {
            return Optional.empty();
        }
        Optional<byte[]> generated = CoverThumbnailer.jpeg(source.get());
        generated.ifPresent(bytes -> storage.saveCoverThumb(mapId, bytes));
        return generated;
    }

    private static Optional<byte[]> readBytes(PluginStoredFile file) {
        try (var input = file.inputStream()) {
            return Optional.of(input.readAllBytes());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static final int MAX_COVER_BYTES = 8 * 1024 * 1024;

    private static boolean isPng(byte[] data) {
        return data.length >= 8
                && (data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47
                && data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A;
    }

    // ------------------------------------------------------------------
    // 自检
    // ------------------------------------------------------------------

    /**
     * 是否已有可用的采集产物。
     *
     * <p>显式配置就只看那一个文件；否则扫分桶根——分桶是按采集版本隔离的，哪一桶先有都不重要，
     * 因为每张地图会按自己的版本去找。</p>
     */
    private static boolean modelsCached(VoxelithRuntimeConfig config) {
        java.nio.file.Path explicit = config.configuredModelsFile();
        if (explicit != null) {
            return Files.isRegularFile(explicit);
        }
        try (var buckets = Files.list(config.modelsDir())) {
            return buckets.anyMatch(bucket -> Files.isRegularFile(bucket.resolve("models.json.gz")));
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** 引擎与边车自检（管理页排障面板）。 */
    public EngineStatusDTO engineStatus() {
        // 采集产物按「采集版本」分桶，所以自检看的是分桶根下有没有任意一份
        java.nio.file.Path models = config.configuredModelsFile() != null
                ? config.configuredModelsFile() : config.modelsDir();
        return new EngineStatusDTO(
                VoxelithBundledRuntime.describeBundledResources().values().stream().allMatch(Boolean.TRUE::equals),
                config.javaExecutable().toString(),
                config.runtimeRoot().toAbsolutePath().toString(),
                config.heapMiB(),
                config.timeoutMinutes(),
                config.maxConcurrentTasks(),
                config.harvestCapable(),
                modelsCached(config),
                models.toAbsolutePath().toString(),
                config.sidecarEnabled(),
                sidecar.state().name(),
                sidecar.port(),
                sidecar.lastError(),
                sidecar.failureCountdown(),
                sidecar.describeLaunchPlan(),
                VoxelithBundledRuntime.describeBundledResources());
    }


    /**
     * 校验模组列表：逐个确认平台文件存在，剔掉空条目。
     *
     * <p>渲染前才物化文件，这里就拦「创建/更新时文件已被删」的情况，免得等到渲染中途才报错。</p>
     */
    /**
     * 回收导入遗留的存储对象；仍被其它地图引用时跳过。
     *
     * <p>一次实例导入可能建出多张维度地图（world / world_nether / world_the_end），它们**共享**同一份
     * 存档包与模组包。若删除其中一张就把共享对象删掉，其余地图的渲染会立刻报「找不到存档」——
     * 所以先查引用：还有别的地图指向同一个 key 就保留。</p>
     */
    private void deleteImportedKeyUnlessShared(String fileId, String deletingMapId) {
        if (fileId == null || !fileId.startsWith("imports/")) {
            return;
        }
        for (MapProject other : projectRepo.findAll()) {
            if (other.id().equals(deletingMapId)) {
                continue;
            }
            if (fileId.equals(other.worldFileId()) || fileId.equals(other.modsPackFileId().orElse(null))) {
                return;
            }
        }
        storage.deleteKey(fileId);
    }

    /** 校验模组包（单个 zip）存在；空串/空值表示没有包，直接放过。 */
    private void validatedModsPack(String modsPackFileId) {
        if (modsPackFileId == null || modsPackFileId.isBlank()) {
            return;
        }
        if (!files.exists(modsPackFileId)) {
            throw new IllegalArgumentException("找不到上传的模组包（文件不存在或已删除）");
        }
    }

    private List<ModJarRef> validatedModJars(List<ModJarRef> modJars) {
        if (modJars == null || modJars.isEmpty()) {
            return List.of();
        }
        List<ModJarRef> result = new ArrayList<>();
        for (ModJarRef modJar : modJars) {
            if (modJar == null || modJar.fileId() == null || modJar.fileId().isBlank()) {
                continue;
            }
            if (!files.exists(modJar.fileId())) {
                throw new IllegalArgumentException("找不到上传的模组 jar：" + modJar.displayName());
            }
            result.add(modJar);
        }
        return List.copyOf(result);
    }

    private String nameOf(String mapId) {
        return projectRepo.findById(mapId).map(MapProject::name).orElse(mapId);
    }

    /** 生成唯一 id：名称 slug + 短随机后缀（允许中文，但不允许路径分隔符等危险字符）。 */
    private String uniqueId(String slug) {
        String base = slug.isBlank() ? "map" : slug;
        for (int attempt = 0; attempt < 20; attempt++) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 5);
            String candidate = truncate(base, MAX_ID_LENGTH - 6) + "-" + suffix;
            if (projectRepo.findById(candidate).isEmpty()) {
                return candidate;
            }
        }
        return "map-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 清掉 URL/路径里会出问题的字符。中文保留（宿主与对象存储都支持 UTF-8 key）。 */
    static String slug(String name) {
        StringBuilder builder = new StringBuilder();
        for (char character : name.trim().toCharArray()) {
            if (Character.isLetterOrDigit(character) || character == '-' || character == '_'
                    || character > 0x7F) {
                builder.append(character);
            } else if (character == ' ' || character == '.') {
                builder.append('-');
            }
        }
        String value = builder.toString();
        while (value.contains("--")) {
            value = value.replace("--", "-");
        }
        return value.replaceAll("^-+|-+$", "").toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 供测试与自检：当前项目数与状态分布。 */
    public String describeInventory() {
        long ready = projectRepo.findAll().stream()
                .filter(project -> project.state() == ProjectState.READY).count();
        return "maps=" + projectRepo.count() + " ready=" + ready + " at=" + Instant.now();
    }
}
