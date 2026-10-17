package online.yudream.plugin.voxelithmap.domain.value;

/**
 * 一次渲染的参数快照。
 *
 * <p>默认值与 Voxelith 的 {@code RenderMapOptions} 保持一致，避免「插件不传就变行为」。
 * 这里全部是方块/层级语义，不含路径——路径由 {@code VoxelithRenderConfigurationResolver}
 * 在运行时按插件运行根与任务临时目录推导。</p>
 *
 * @param dimension     维度 id（{@code minecraft:overworld} / {@code the_nether} / {@code the_end}）
 * @param minY          最低渲染高度（含）；低于它的方块不参与网格化
 * @param maxLevel      LOD 最高层级；{@code 0} = 自动（聚合到全图 ≤2×2 瓦片）
 * @param sampleChunks  bake 落盘样本区块数（排障用）
 * @param lodAtlas      是否生成 LOD 分层图集页
 * @param meshopt       是否对瓦片几何做 EXT_meshopt_compression 熵编码
 * @param maxChunks     单次渲染允许的最大非空区块数；{@code 0} = 不限制
 * @param batchChunks   多遍渲染的批次区块数；{@code 0} = 不分遍
 * @param skipHarvest   是否跳过模型采集（跳过则走静态解析，uvlock/旋转与原版有偏差）
 * @param mcVersion     采集用 MC 版本；<b>留空 = 自动</b>（引擎按「资源包文件名 → 存档版本 →
 *                      兜底」判定，见上游 {@code McVersionResolver}）
 * @param loaderVersion 采集用 Fabric loader 版本
 */
public record RenderSettings(
        String dimension,
        int minY,
        int maxLevel,
        int sampleChunks,
        boolean lodAtlas,
        boolean meshopt,
        int maxChunks,
        int batchChunks,
        boolean skipHarvest,
        String mcVersion,
        String loaderVersion) {

    /**
     * 引擎侧的兜底采集版本（与 {@code RenderMapOptions.DEFAULT_MC_VERSION} 对齐）。
     *
     * <p>只有「插件没给、资源包文件名里也没有、存档 level.dat 也读不到」时才用得上——
     * 插件自己不再拿它当默认值往下发：写死版本会让所有非该版本的存档踩贴图改名的坑
     * （1.20.3 起 {@code grass → short_grass}，表现为整片品红）。</p>
     */
    public static final String DEFAULT_MC_VERSION = "1.20.1";
    /** Voxelith 的默认 loader 版本（与 {@code RenderMapOptions.DEFAULT_LOADER_VERSION} 对齐）。 */
    public static final String DEFAULT_LOADER_VERSION = "0.16.14";

    /**
     * 默认渲染参数。
     *
     * <p>{@code minY} 用 -64：1.18+ 世界的地表远高于此，且 Voxelith 的二维预览会给出
     * 更准确的建议值（{@code minSurfaceY - 16}）由前端回填，这里只是兜底。</p>
     */
    public static RenderSettings defaults() {
        return new RenderSettings(
                "minecraft:overworld", -64, 0, 0, true, true, 0, 2048, false,
                "", DEFAULT_LOADER_VERSION);
    }

    public RenderSettings {
        if (dimension == null || dimension.isBlank()) {
            dimension = "minecraft:overworld";
        }
        // 留空必须原样保留：空白就是「自动」，填上默认值等于把自动判定关掉
        mcVersion = mcVersion == null ? "" : mcVersion.trim();
        if (loaderVersion == null || loaderVersion.isBlank()) {
            loaderVersion = DEFAULT_LOADER_VERSION;
        }
        if (maxLevel < 0) {
            throw new IllegalArgumentException("LOD 层级不能为负");
        }
        if (batchChunks < 0 || maxChunks < 0 || sampleChunks < 0) {
            throw new IllegalArgumentException("区块数量参数不能为负");
        }
    }

    /** 采集版本是否交给引擎自动判定（留空即自动）。 */
    public boolean usesAutoVersion() {
        return mcVersion.isBlank();
    }

    /** 覆盖 minY（框选流程按预览给出的建议值回填时用）。 */
    public RenderSettings withMinY(int value) {
        return new RenderSettings(dimension, value, maxLevel, sampleChunks, lodAtlas, meshopt,
                maxChunks, batchChunks, skipHarvest, mcVersion, loaderVersion);
    }

    /** 覆盖采集开关。 */
    public RenderSettings withSkipHarvest(boolean value) {
        return new RenderSettings(dimension, minY, maxLevel, sampleChunks, lodAtlas, meshopt,
                maxChunks, batchChunks, value, mcVersion, loaderVersion);
    }
}
