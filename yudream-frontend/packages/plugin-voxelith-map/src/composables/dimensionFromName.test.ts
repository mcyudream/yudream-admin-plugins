import { describe, expect, it } from 'vitest'
import { dimensionFromArchiveName } from './dimensionFromName'

/**
 * 存档名 → 维度。
 *
 * <p>判定错方向的代价是白渲染一次（维度选错等于渲染了另一张图），所以「world_nether 不能被
 * world 规则先匹配走」这类顺序问题必须钉住。</p>
 */
describe('dimensionFromArchiveName', () => {
  it('maps the vanilla world directories', () => {
    expect(dimensionFromArchiveName('world.zip')).toBe('minecraft:overworld')
    expect(dimensionFromArchiveName('world_nether.zip')).toBe('minecraft:nether')
    expect(dimensionFromArchiveName('world_the_end.zip')).toBe('minecraft:the_end')
  })

  it('prefers the specific rule over the generic world rule', () => {
    // world_nether / world_the_end 都含 world，必须命中更具体的规则
    expect(dimensionFromArchiveName('存档-world_nether-2026.zip')).toBe('minecraft:nether')
    expect(dimensionFromArchiveName('D:/backup/world_the_end.zip')).toBe('minecraft:the_end')
  })

  it('accepts legacy Dim folders and english/chinese keywords', () => {
    expect(dimensionFromArchiveName('DIM-1.zip')).toBe('minecraft:nether')
    expect(dimensionFromArchiveName('DIM1.zip')).toBe('minecraft:the_end')
    expect(dimensionFromArchiveName('下界存档.zip')).toBe('minecraft:nether')
    expect(dimensionFromArchiveName('末地岛屿.zip')).toBe('minecraft:the_end')
    expect(dimensionFromArchiveName('主世界备份.zip')).toBe('minecraft:overworld')
  })

  it('ignores paths that only look like dimensions in a parent folder', () => {
    // 只按最后一段判定：父目录叫 world_nether 不代表这个包是下界
    expect(dimensionFromArchiveName('world_nether/spawn.zip')).toBe('')
  })

  it('returns empty when nothing matches (caller keeps the current choice)', () => {
    expect(dimensionFromArchiveName('存档.zip')).toBe('')
    expect(dimensionFromArchiveName('')).toBe('')
    expect(dimensionFromArchiveName(null)).toBe('')
  })
})
