package online.yudream.plugin.worldmap.infrastructure.render;

/**
 * 渲染统计。
 *
 * @param hiresTiles    实际产出的 hires tile 数（空 tile 不计）
 * @param lowresTiles   实际产出的 lowres tile 数（含各 lod，全透明不计）
 * @param atlasBytes    贴图集 PNG 字节数
 * @param elapsedMillis 渲染总耗时
 */
public record RenderSummary(int hiresTiles, int lowresTiles, int atlasBytes, long elapsedMillis) {
}
