package online.yudream.plugin.voxelithmap.application.dto;

import java.util.List;

/**
 * 管理端地图详情。
 *
 * @param id                  地图 id
 * @param name                展示名
 * @param worldName           存档名（level.dat 里的 LevelName）
 * @param worldFileId         平台文件 id（存档 zip）
 * @param clientJarFileId     平台文件 id（原版 client jar）
 * @param clientJarName       客户端 jar 的原始文件名（引擎据此判定采集版本）
 * @param hasClientJar        是否已指定 client jar
 * @param modsPackFileId      模组包 zip 的平台文件 id（可空；渲染前解包成 jar）
 * @param modsPackName        模组包原始文件名（展示用）
 * @param modJars             绑定的模组 jar 列表（可空）
 * @param dimension           维度 id
 * @param spawn               出生点 [x,y,z]
 * @param state               项目状态
 * @param activeGenerationId  当前发布代
 * @param publishedGenerations 已发布的代数
 * @param region              框选范围（未框选为 null）
 * @param settings            渲染参数
 * @param scaleDescription    规模描述（一句话）
 * @param boundsMin           包围盒最小角
 * @param boundsMax           包围盒最大角
 * @param lastError           最近一次失败原因
 * @param createdAt           创建时间
 * @param updatedAt           更新时间
 * @param coverRevision       封面版本号：0 表示没有封面，>0 为保存封面时的时间戳。
 *                            列表用它拼 {@code ?v=} 做缓存指纹（用 updatedAt 会让改名/渲染都重下封面），
 *                            同时取代了原来的 hasCover 布尔量——都在项目文档里，列表无需查对象存储
 * @param archived            是否已归档（归档后管理列表/公开查看器默认隐藏）
 * @param sourceInstanceId    来源 MC 面板实例 id（经「从实例导入」创建时记录，可空）
 */
public record MapAdminDTO(
        String id,
        String name,
        String worldName,
        String worldFileId,
        String clientJarFileId,
        String clientJarName,
        boolean hasClientJar,
        String modsPackFileId,
        String modsPackName,
        List<ModJarDTO> modJars,
        String dimension,
        List<Double> spawn,
        String state,
        String activeGenerationId,
        List<String> publishedGenerations,
        RegionDTO region,
        RenderSettingsDTO settings,
        String scaleDescription,
        List<Double> boundsMin,
        List<Double> boundsMax,
        String lastError,
        String createdAt,
        String updatedAt,
        long coverRevision,
        boolean archived,
        String sourceInstanceId,
        /**
         * 世界分组键：同一次导入/同一次上传产生的各维度地图共用一个值。
         *
         * <p>管理端据此把「一个存档」收敛成一行、维度收纳在行内切换。老项目（分组字段引入前
         * 创建）读出来是存档文件 id 派生的键，语义一致，前端不需要区分。</p>
         */
        String worldGroup) {

    /**
     * 绑定的模组 jar。
     *
     * @param fileId 平台文件 id
     * @param name   上传时的原始文件名（可为空）
     */
    public record ModJarDTO(String fileId, String name) {
    }

    /**
     * 框选范围。
     *
     * @param minX 最小 X（含）
     * @param minZ 最小 Z（含）
     * @param maxX 最大 X（含）
     * @param maxZ 最大 Z（含）
     * @param widthBlocks 宽度（方块）
     * @param depthBlocks 深度（方块）
     * @param chunkCount  覆盖区块数（估算）
     */
    public record RegionDTO(int minX, int minZ, int maxX, int maxZ, int widthBlocks, int depthBlocks,
                            long chunkCount) {
    }

    /**
     * 渲染参数。
     *
     * @param minY          最低渲染高度
     * @param maxLevel      LOD 最高层级（0 = 自动）
     * @param lodAtlas      是否生成 LOD 分层图集页
     * @param meshopt       是否启用几何熵编码
     * @param maxChunks     单次渲染区块上限（0 = 不限）
     * @param batchChunks   多遍渲染批次区块数（0 = 不分遍）
     * @param skipHarvest   是否跳过模型采集
     * @param mcVersion     采集 MC 版本（空 = 自动判定）
     * @param loaderVersion 采集 loader 版本
     */
    public record RenderSettingsDTO(int minY, int maxLevel, boolean lodAtlas, boolean meshopt, int maxChunks,
                                    int batchChunks, boolean skipHarvest, String mcVersion, String loaderVersion) {
    }
}
