package online.yudream.plugin.voxelithmap.domain.aggregate;

import online.yudream.plugin.voxelithmap.domain.enumerate.ProjectState;
import online.yudream.plugin.voxelithmap.domain.value.DefaultView;
import online.yudream.plugin.voxelithmap.domain.value.MapManifestSummary;
import online.yudream.plugin.voxelithmap.domain.value.ModJarRef;
import online.yudream.plugin.voxelithmap.domain.value.RegionWindow;
import online.yudream.plugin.voxelithmap.domain.value.RenderSettings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 地图项目聚合根。
 *
 * <p>一个项目 = 一份存档 + 一个维度 + 一套渲染参数 + 若干发布代。世界存档与客户端 jar
 * 都以宿主平台文件的 id 形式引用（插件不持有平台文件的字节，读的时候现取），
 * 因此这里只保存 id 与展示用的元信息。</p>
 *
 * <p>{@code clientJarName} 是「元信息」里唯一有功能性的一条：引擎按<b>资源包文件名</b>判定
 * 采集版本（{@code client-1.21.1.jar → 1.21.1}），而宿主平台文件只给 id 不给原名，
 * 插件不记下来就再也拿不到——落盘中性的 {@code client.jar} 会把这条最可靠的判据丢掉。</p>
 *
 * <p>不变量：</p>
 * <ul>
 *   <li>{@code activeGenerationId} 非空时必在 {@code publishedGenerationIds} 中；</li>
 *   <li>渲染任务失败不改变 {@code activeGenerationId}（失败不夺走线上内容）；</li>
 *   <li>已发布代按发布时间保留最近 {@link #MAX_PUBLISHED_GENERATIONS} 代，更早的可回收。</li>
 * </ul>
 */
public final class MapProject {

    /** 保留的已发布代数（含当前代）。旧代供回滚与缓存命中，超过即允许回收。 */
    public static final int MAX_PUBLISHED_GENERATIONS = 3;

    private final String id;
    private String name;
    private String worldName;
    private String worldFileId;
    private String clientJarFileId;
    private String clientJarName;
    /** 绑定的模组 jar（渲染时叠在 client jar 之上；按绑定顺序生效）。 */
    private String modsPackFileId;
    private String modsPackName = "";
    private final List<ModJarRef> modJars = new ArrayList<>();
    private String dimension;
    private double spawnX;
    private double spawnY;
    private double spawnZ;
    private RenderSettings settings;
    private RegionWindow region;
    /** 管理员设置的默认初始视角（查看页进入时的机位；未设置为 null 走内置默认机位）。 */
    private DefaultView defaultView;
    /** 已归档：管理列表/公开查看器默认隐藏，可在归档页恢复或彻底删除。 */
    private boolean archived;
    /** 来源 MC 面板实例 id（经「从实例导入」创建时记录，用于同步最新存档；非导入为 null）。 */
    private String sourceInstanceId;
    /**
     * 世界分组：同一个世界的一组维度地图共享该值（渲染页与展示页据此切换维度）。
     *
     * <p>不按存档（{@code worldFileId}）分组的原因：那个字段会被「同步最新存档」换掉，
     * 换过之后同一世界的其它维度就认不出同伴了。分组标识只在创建时确定一次，之后不变。</p>
     *
     * <p>单独建的一张地图自成一组（等于自身 id）；老文档没有该字段时按 {@code worldFileId}
     * 兜底（见 {@link #restore}），因此 2.1.21 导入的多维度地图升级后依然能互相切换。</p>
     */
    private String worldGroupId;
    /**
     * 封面版本号：列表用它做缓存指纹（只有封面真的换了才换 URL）。
     * 0 = 没有封面；>0 = 保存封面时的毫秒时间戳；-1 = 老记录尚未解析（首次读到时就地迁移）。
     *
     * <p>落进项目文档而不是每次去对象存储查：列表要为每张地图判「有没有封面」，
     * 单独查一次等于把列表的对象存储读放大数倍（渲染中还会每 2.5 秒轮询一次列表）。</p>
     */
    private long coverRevision = UNRESOLVED_COVER_REVISION;
    private ProjectState state;
    private String activeGenerationId;
    private final Set<String> publishedGenerationIds = new LinkedHashSet<>();
    private MapManifestSummary summary;
    private String lastError;
    private final Instant createdAt;
    private Instant updatedAt;

    private MapProject(String id, String name, String worldName, String worldFileId, String clientJarFileId,
                       String clientJarName, String modsPackFileId, String modsPackName,
                       List<ModJarRef> modJars, String dimension,
                       double spawnX, double spawnY, double spawnZ,
                       RenderSettings settings,
                       RegionWindow region, DefaultView defaultView, ProjectState state, String activeGenerationId,
                       List<String> publishedGenerationIds, MapManifestSummary summary, String lastError,
                       boolean archived, String sourceInstanceId, long coverRevision,
                       String worldGroupId, Instant createdAt, Instant updatedAt) {
        this.id = requireText(id, "mapId");
        this.name = requireText(name, "地图名称");
        this.worldName = worldName;
        this.worldFileId = worldFileId;
        this.clientJarFileId = clientJarFileId;
        this.clientJarName = clientJarName == null ? "" : clientJarName;
        this.modsPackFileId = modsPackFileId;
        this.modsPackName = modsPackName == null ? "" : modsPackName;
        setModJars(modJars);
        this.dimension = dimension;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
        this.settings = settings;
        this.region = region;
        this.defaultView = defaultView;
        this.state = state;
        this.activeGenerationId = activeGenerationId;
        if (publishedGenerationIds != null) {
            for (String generationId : publishedGenerationIds) {
                if (generationId != null && !generationId.isBlank()) {
                    this.publishedGenerationIds.add(generationId);
                }
            }
        }
        // 惰性迁移：老记录只有 activeGenerationId 时补录进发布代集合
        if (this.activeGenerationId != null && !this.activeGenerationId.isBlank()) {
            this.publishedGenerationIds.add(this.activeGenerationId);
        }
        this.summary = summary == null ? MapManifestSummary.empty() : summary;
        this.lastError = lastError;
        this.archived = archived;
        this.sourceInstanceId = sourceInstanceId;
        this.coverRevision = coverRevision;
        // 没显式给分组就按存档派生（见 groupKeyOf）：同一份存档建出的多张图本来就是一个世界的不同维度
        this.worldGroupId = worldGroupId == null || worldGroupId.isBlank()
                ? groupKeyOf(this.id, this.worldFileId)
                : worldGroupId;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
    }

    public static MapProject create(String id, String name, String worldName, String worldFileId,
                                    String clientJarFileId, String clientJarName, List<ModJarRef> modJars,
                                    double spawnX, double spawnY,
                                    double spawnZ, RenderSettings settings) {
        return new MapProject(id, name, worldName, worldFileId, clientJarFileId, clientJarName,
                null, null, modJars,
                settings == null ? RenderSettings.defaults().dimension() : settings.dimension(),
                spawnX, spawnY, spawnZ,
                settings == null ? RenderSettings.defaults() : settings,
                null, null, ProjectState.EMPTY, null, List.of(), MapManifestSummary.empty(), null,
                false, null, 0L, null, Instant.now(), Instant.now());
    }

    /** 仓储重建入口（字段全部显式给出，不做业务校验以外的推导）。 */
    public static MapProject restore(String id, String name, String worldName, String worldFileId,
                                     String clientJarFileId, String clientJarName,
                                     String modsPackFileId, String modsPackName, List<ModJarRef> modJars,
                                     String dimension,
                                     double spawnX, double spawnY, double spawnZ, RenderSettings settings,
                                     RegionWindow region, DefaultView defaultView, ProjectState state, String activeGenerationId,
                                     List<String> publishedGenerationIds, MapManifestSummary summary,
                                     String lastError, boolean archived, String sourceInstanceId,
                                     long coverRevision, String worldGroupId,
                                     Instant createdAt, Instant updatedAt) {
        return new MapProject(id, name, worldName, worldFileId, clientJarFileId, clientJarName,
                modsPackFileId, modsPackName, modJars,
                dimension, spawnX, spawnY,
                spawnZ, settings, region, defaultView, state, activeGenerationId, publishedGenerationIds, summary, lastError,
                archived, sourceInstanceId, coverRevision, worldGroupId, createdAt, updatedAt);
    }

    /**
     * 未显式指定分组时的分组标识：**同一份存档**建出的地图归为一组。
     *
     * <p>手动上传时管理员可以拿同一个多维度的存档包分别建三张图（各自指定维度），它们本就是同一个
     * 世界的三个维度，理应能互相切换；一次实例导入建出的多张图也共享同一个存档。都没有存档时退回
     * 自身 id（自成一组，只是没有别的维度可切）。</p>
     *
     * <p>派生只在**创建那一刻**发生，之后随文档一起持久化：因此「同步最新存档」换掉 worldFileId
     * 不会把这张图从组里踢出去。</p>
     */
    private static String groupKeyOf(String mapId, String worldFileId) {
        return worldFileId == null || worldFileId.isBlank() ? mapId : "archive:" + worldFileId;
    }

    /**
     * 世界分组标识（同一次导入 / 同一份存档的多个维度共享）。
     *
     * <p>永远有值：新建时按存档派生或等于自身 id，老文档按同一规则兜底，调用方不必处理空值。</p>
     */
    public String worldGroup() {
        return worldGroupId == null || worldGroupId.isBlank() ? groupKeyOf(id, worldFileId) : worldGroupId;
    }

    /**
     * 把本图并入一个世界分组。
     *
     * <p>实例导入一次建出多张维度地图时用：先建出的那张（主世界）的 mapId 就是这组的标识。</p>
     */
    public void assignWorldGroup(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return;
        }
        this.worldGroupId = groupId;
        touch();
    }

    /** 老记录（本字段引入前写入的文档）的标记：首次读到时查一次存储并就地固化。 */
    public static final long UNRESOLVED_COVER_REVISION = -1L;

    /** 封面缓存指纹：0 表示没有封面，>0 为封面版本。 */
    public long coverRevision() {
        return coverRevision;
    }

    /**
     * 模组包（一个 zip，渲染前解包成 jar）。
     *
     * <p>整包只占一个存储对象：逐个 jar 落存储会在导入时打出成百上千次写请求，渲染时又要
     * 逐个读回来——对象存储连接池正是被这种放大占满的。</p>
     */
    public Optional<String> modsPackFileId() {
        return Optional.ofNullable(modsPackFileId);
    }

    public String modsPackName() {
        return modsPackName;
    }

    /** 设置/替换模组包（fileId 为空即清除）。 */
    public void setModsPack(String fileId, String name) {
        this.modsPackFileId = fileId == null || fileId.isBlank() ? null : fileId;
        this.modsPackName = name == null ? "" : name;
        touch();
    }

    /** 记录一次封面保存（时间是天然单调的版本号）。 */
    public void setCoverRevision(long revision) {
        this.coverRevision = revision;
        touch();
    }

    // ---------- 查询 ----------

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String worldName() {
        return worldName;
    }

    public String worldFileId() {
        return worldFileId;
    }

    public String clientJarFileId() {
        return clientJarFileId;
    }

    /** 客户端 jar 的原始文件名（可能为空：老项目没记过）。 */
    public String clientJarName() {
        return clientJarName;
    }

    /** 绑定的模组 jar（只读视图，按绑定顺序排列）。 */
    public List<ModJarRef> modJars() {
        return List.copyOf(modJars);
    }

    public String dimension() {
        return dimension;
    }

    public double spawnX() {
        return spawnX;
    }

    public double spawnY() {
        return spawnY;
    }

    public double spawnZ() {
        return spawnZ;
    }

    public RenderSettings settings() {
        return settings;
    }

    public Optional<RegionWindow> region() {
        return Optional.ofNullable(region);
    }

    /** 管理员设置的默认初始视角（未设置为空）。 */
    public Optional<DefaultView> defaultView() {
        return Optional.ofNullable(defaultView);
    }

    /** 是否已归档（归档后管理列表与公开查看器默认隐藏）。 */
    public boolean archived() {
        return archived;
    }

    /** 来源 MC 面板实例 id（经「从实例导入」创建时记录）。 */
    public Optional<String> sourceInstanceId() {
        return Optional.ofNullable(sourceInstanceId);
    }

    /** 记录/更新来源实例 id（同步最新存档后刷新）。 */
    public void setSourceInstanceId(String value) {
        this.sourceInstanceId = value;
        touch();
    }

    public ProjectState state() {
        return state;
    }

    public String activeGenerationId() {
        return activeGenerationId;
    }

    public List<String> publishedGenerationIds() {
        return List.copyOf(publishedGenerationIds);
    }

    public MapManifestSummary summary() {
        return summary;
    }

    public String lastError() {
        return lastError;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** 是否有可公开访问的产物。 */
    public boolean hasPublishedArtifacts() {
        return activeGenerationId != null && !activeGenerationId.isBlank();
    }

    /** 当前发布代。 */
    public MapGeneration activeGeneration() {
        if (!hasPublishedArtifacts()) {
            throw new IllegalStateException("地图 " + id + " 尚无已发布产物");
        }
        return new MapGeneration(id, activeGenerationId);
    }

    // ---------- 变更 ----------

    public void rename(String value) {
        this.name = requireText(value, "地图名称");
        touch();
    }

    public void setRegion(RegionWindow value) {
        this.region = value;
        touch();
    }

    /** 设置默认初始视角；{@code null} 表示清除，回到内置机位。 */
    public void setDefaultView(DefaultView value) {
        this.defaultView = value;
        touch();
    }

    /** 归档：从管理列表与公开查看器隐藏，数据保留。 */
    public void archive() {
        this.archived = true;
        touch();
    }

    /** 取消归档（恢复可见）。 */
    public void unarchive() {
        this.archived = false;
        touch();
    }

    public void updateSettings(RenderSettings value) {
        if (value == null) {
            throw new IllegalArgumentException("渲染参数不能为空");
        }
        this.settings = value;
        this.dimension = value.dimension();
        touch();
    }

    public void setWorldSource(String worldFileId, String clientJarFileId, String clientJarName,
                               String worldName) {
        if (worldFileId == null || worldFileId.isBlank()) {
            throw new IllegalArgumentException("存档文件不能为空");
        }
        this.worldFileId = worldFileId;
        this.clientJarFileId = clientJarFileId;
        if (clientJarName != null) {
            this.clientJarName = clientJarName;
        }
        this.worldName = worldName;
        touch();
    }

    /**
     * 整体替换绑定的模组 jar 列表。
     *
     * <p>传 {@code null} 或空列表 = 清空全部模组（恢复纯原版渲染）。注意：改模组后必须重新渲染，
     * 已发布代不会因此失效——旧代是旧资源栈烘出来的，依旧可看。</p>
     */
    public void setModJars(List<ModJarRef> value) {
        this.modJars.clear();
        if (value != null) {
            for (ModJarRef modJar : value) {
                if (modJar != null) {
                    this.modJars.add(modJar);
                }
            }
        }
        touch();
    }

    /** 渲染开始：项目进入 RENDERING，但保留当前可见代。 */
    public void markRendering() {
        this.state = ProjectState.RENDERING;
        this.lastError = null;
        touch();
    }

    /**
     * 发布成功：切换可见代并记录摘要。
     *
     * <p>调用方必须已完成产物导入（{@code GenerationPublisher}），这里只做指针切换，
     * 因此「发布」这个动作在聚合上是原子的——要么整代可用，要么仍是老一代。</p>
     */
    public void publish(MapGeneration generation, MapManifestSummary newSummary) {
        if (!generation.mapId().equals(id)) {
            throw new IllegalArgumentException("发布代不属于该地图");
        }
        publishedGenerationIds.add(generation.id());
        this.activeGenerationId = generation.id();
        this.summary = newSummary == null ? MapManifestSummary.empty() : newSummary;
        this.state = ProjectState.READY;
        this.lastError = null;
        touch();
    }

    /** 渲染失败：保留可见代；没有任何历史产物时进入 FAILED。 */
    public void markFailed(String message) {
        this.lastError = message;
        this.state = hasPublishedArtifacts() ? ProjectState.READY : ProjectState.FAILED;
        touch();
    }

    /** 任务被取消后回落到渲染前的状态。 */
    public void markCanceled() {
        this.state = hasPublishedArtifacts() ? ProjectState.READY : ProjectState.EMPTY;
        touch();
    }

    /** 需要回收的旧代（保留最近 {@link #MAX_PUBLISHED_GENERATIONS} 代 + 当前代）。 */
    public List<String> generationsEligibleForCleanup() {
        if (publishedGenerationIds.size() <= MAX_PUBLISHED_GENERATIONS) {
            return List.of();
        }
        List<String> ordered = new ArrayList<>(publishedGenerationIds);
        // 新代在后（LinkedHashSet 插入序），从最老的开始回收，且永不回收当前可见代
        List<String> result = new ArrayList<>();
        int keep = MAX_PUBLISHED_GENERATIONS;
        for (int index = 0; index < ordered.size() - keep; index++) {
            String candidate = ordered.get(index);
            if (!candidate.equals(activeGenerationId)) {
                result.add(candidate);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** 回收完成后从集合里摘除（产物已删，记录同步收敛）。 */
    public void forgetGenerations(List<String> generationIds) {
        if (generationIds == null || generationIds.isEmpty()) {
            return;
        }
        generationIds.stream()
                .filter(candidate -> !candidate.equals(activeGenerationId))
                .forEach(publishedGenerationIds::remove);
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.trim();
    }
}
