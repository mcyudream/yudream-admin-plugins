package online.yudream.plugin.worldmap.infrastructure.render;

import online.yudream.plugin.worldmap.infrastructure.world.BlockState;
import online.yudream.plugin.worldmap.infrastructure.world.WorldAccess;

/**
 * 渲染视角的世界包装：应用 nether 规则。
 *
 * <ul>
 *   <li>nether 维度天空光强制为 0（下界无天空光）。</li>
 *   <li>剥离基岩顶时，y≥120 的方块视为空气（不参与渲染/剔除/AO），
 *       maxY 相应向下扫描至首个非空气方块。</li>
 * </ul>
 */
final class RenderWorldView {

    /** nether 顶剥离阈值：y≥120 视为基岩顶层。 */
    static final int NETHER_CEILING_Y = 120;

    private final WorldAccess world;
    private final boolean nether;
    private final boolean stripCeiling;

    RenderWorldView(WorldAccess world, boolean nether, boolean stripCeiling) {
        this.world = world;
        this.nether = nether;
        this.stripCeiling = stripCeiling;
    }

    BlockState blockState(int x, int y, int z) {
        if (stripCeiling && y >= NETHER_CEILING_Y) {
            return BlockState.AIR;
        }
        return world.blockState(x, y, z);
    }

    int blockLight(int x, int y, int z) {
        return world.blockLight(x, y, z);
    }

    int skyLight(int x, int y, int z) {
        return nether ? 0 : world.skyLight(x, y, z);
    }

    String biome(int x, int y, int z) {
        return world.biome(x, y, z);
    }

    int minY() {
        return world.minY();
    }

    /** 最高非空气方块 y；剥离顶时从阈值下方向下扫描。 */
    int maxY(int x, int z) {
        int y = world.maxY(x, z);
        if (!stripCeiling || y < NETHER_CEILING_Y) {
            return y;
        }
        for (y = NETHER_CEILING_Y - 1; y > world.minY(); y--) {
            if (!world.blockState(x, y, z).isAir()) {
                return y;
            }
        }
        return world.minY();
    }
}
