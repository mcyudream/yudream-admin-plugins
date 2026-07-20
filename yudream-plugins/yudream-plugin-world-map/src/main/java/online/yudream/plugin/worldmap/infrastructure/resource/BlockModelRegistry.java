package online.yudream.plugin.worldmap.infrastructure.resource;

import online.yudream.plugin.worldmap.infrastructure.world.BlockState;

/**
 * 方块模型注册表：渲染层按方块状态查询烘焙面片与贴图集。
 * {@link BlockState} 直接复用 infrastructure.world 包的同一类型（契约约定）。
 * 实现保证线程安全（烘焙结果按需缓存）。
 */
public interface BlockModelRegistry {

    /**
     * 查询某方块状态的全部烘焙面片（局部 0..16 坐标系）。
     * 模型缺失时返回品红/黑棋盘纹理的全立方体，便于排查；
     * 空气等合法无面方块返回空数组。
     */
    BakedQuad[] quadsFor(BlockState state);

    /** 渲染用贴图集（含全部已引用纹理）。 */
    TextureAtlas atlas();
}
