package online.yudream.plugin.worldmap.infrastructure.world;

/**
 * 世界只读访问入口（渲染层契约，见 CONTRACT.md §7）。
 * 坐标均为世界绝对方块坐标；未生成区块 / 越界坐标返回空气与默认光照
 * （skylight=15、blocklight=0）。
 */
public interface WorldAccess extends AutoCloseable {

    /** 指定坐标的方块状态，未生成或越界时返回 {@link BlockState#AIR}。 */
    BlockState blockState(int x, int y, int z);

    /** 方块光（人工光源）等级 0..15，缺失数据返回 0。 */
    int blockLight(int x, int y, int z);

    /** 天空光等级 0..15，缺失数据返回 15。 */
    int skyLight(int x, int y, int z);

    /** 生物群系命名空间 ID（如 "minecraft:plains"），缺失数据返回 "minecraft:plains"。 */
    String biome(int x, int y, int z);

    /** 该柱最高非空气方块的 y；整柱无方块时返回 {@link #minY()}。 */
    int maxY(int x, int z);

    /** 世界最低建筑高度（如 overworld 为 -64），由已加载区块的 section 范围推导。 */
    int minY();

    /** 世界建筑上限（不含，如 overworld 为 320），由已加载区块的 section 范围推导。 */
    int maxBuildY();

    @Override
    void close();
}
