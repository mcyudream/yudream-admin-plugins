/**
 * 从存档包的文件名/目录名判定维度。
 *
 * <p>存档各维度是分开的目录（{@code world} / {@code world_nether} / {@code world_the_end}），
 * 每个维度都应各自对应一张地图；上传时再让管理员手工选一遍维度既重复又容易选错，
 * 因此按名字自动判定：认得出来就回填选择器，认不出来交给管理员自己选。</p>
 */
export type MinecraftDimension = 'minecraft:overworld' | 'minecraft:nether' | 'minecraft:the_end'

/** 名字 → 维度：按「越具体越优先」排列（world_nether 必须排在 world 之前）。 */
const RULES: Array<{ dimension: MinecraftDimension, patterns: RegExp[] }> = [
  {
    dimension: 'minecraft:the_end',
    patterns: [/world_the_end/i, /(^|[^a-z])the[_-]?end($|[^a-z])/i, /(^|[^a-z])end($|[^a-z])/i,
      /dim1/i, /末地|末影/],
  },
  {
    dimension: 'minecraft:nether',
    patterns: [/world_nether/i, /(^|[^a-z])nether($|[^a-z])/i, /dim-?1/i, /下界|地狱/],
  },
  {
    dimension: 'minecraft:overworld',
    patterns: [/world(?!_nether|_the_end)/i, /(^|[^a-z])overworld($|[^a-z])/i, /主世界|地表/],
  },
]

/**
 * 判定存档名对应的维度。
 *
 * @param name 文件名或目录名（可带路径、可带 .zip 后缀）
 * @return 维度；无法判定返回空串（调用方保留管理员当前选择）
 */
export function dimensionFromArchiveName(name: string | null | undefined): MinecraftDimension | '' {
  const text = (name ?? '').trim()
  if (!text) return ''
  // 只按最后一段判断：路径里的目录名（如 d:/存档/world_nether.zip）才算数
  const base = text.replace(/\\/g, '/').split('/').pop() ?? text
  for (const rule of RULES) {
    if (rule.patterns.some(pattern => pattern.test(base))) {
      return rule.dimension
    }
  }
  return ''
}
