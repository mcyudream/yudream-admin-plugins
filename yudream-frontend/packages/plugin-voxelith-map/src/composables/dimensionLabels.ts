/**
 * 维度 id ↔ 中文名（纯数据，无任何宿主依赖）。
 *
 * <p>单独一个模块是为了让渲染页、展示页与纯函数测试都能用它：混在 {@code useVoxelithAdmin} 里
 * 会连带拉进 {@code @yudream/plugin-sdk} 的宿主虚拟模块（{@code @yudream/components} → 宿主注入），
 * 使得「只测维度文案」这类纯函数单测在 vitest 里连模块都加载不起来。</p>
 */

/** voxelith 维度（与后端 InstanceDimensionDir / MinecraftDimension 一致，带命名空间前缀）。 */
export const DIMENSION_OPTIONS: { label: string, value: string }[] = [
  { label: '主世界', value: 'minecraft:overworld' },
  { label: '下界', value: 'minecraft:nether' },
  { label: '末地', value: 'minecraft:the_end' },
]

/** 维度中文名；认不出的维度原样回显（不要吞掉信息）。 */
export function dimensionLabel(dimension: string): string {
  return DIMENSION_OPTIONS.find(d => d.value === dimension)?.label ?? dimension
}
