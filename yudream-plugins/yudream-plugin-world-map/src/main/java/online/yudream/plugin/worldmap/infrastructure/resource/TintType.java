package online.yudream.plugin.worldmap.infrastructure.resource;

/**
 * 顶点染色类型，对应契约 §4 的 colors 通道取值来源。
 * 渲染层按类型查 colormap / 生物群系色表生成顶点色。
 */
public enum TintType {
    /** 不染色（顶点色 1,1,1）。 */
    NONE,
    /** 草方块类，取 grass colormap。 */
    GRASS,
    /** 树叶类，取 foliage colormap。 */
    FOLIAGE,
    /** 水体（一期水色可按近似色或群系水色处理）。 */
    WATER,
    /** 红石线（一期按 NONE 处理，仅占位）。 */
    REDSTONE
}
