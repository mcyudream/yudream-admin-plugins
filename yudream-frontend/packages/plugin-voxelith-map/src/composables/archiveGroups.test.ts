import { describe, expect, it } from 'vitest'
import { archiveKeyOf, dimensionRank, groupMapsByArchive } from './archiveGroups'

function map(over: Partial<Parameters<typeof groupMapsByArchive>[0][number]> & { id: string }) {
  return {
    name: over.id,
    dimension: 'minecraft:overworld',
    worldFileId: 'w1',
    ...over,
  }
}

describe('dimensionRank', () => {
  it('主世界 → 下界 → 末地 → 其它 固定顺序', () => {
    expect(dimensionRank('minecraft:overworld')).toBe(0)
    expect(dimensionRank('minecraft:the_nether')).toBe(1)
    expect(dimensionRank('minecraft:the_end')).toBe(2)
    expect(dimensionRank('custom:dim')).toBe(3)
  })
})

describe('archiveKeyOf', () => {
  it('优先世界分组键', () => {
    expect(archiveKeyOf(map({ id: 'a', worldGroup: 'g1' }))).toBe('g1')
  })

  it('没有分组键时按存档文件收敛（同一次上传的各维度共享同一个 fileId）', () => {
    expect(archiveKeyOf(map({ id: 'a', worldFileId: 'w9' }))).toBe('archive:w9')
  })

  it('没有存档文件时按来源实例，最后退回自身', () => {
    expect(archiveKeyOf(map({ id: 'a', worldFileId: '', sourceInstanceId: 'inst-1' }))).toBe('instance:inst-1')
    expect(archiveKeyOf(map({ id: 'a', worldFileId: '', sourceInstanceId: null }))).toBe('map:a')
  })
})

describe('groupMapsByArchive', () => {
  it('一次导入的三个维度收敛成一组，主世界排第一', () => {
    const groups = groupMapsByArchive([
      map({ id: 'end', dimension: 'minecraft:the_end', worldGroup: 'g1', worldName: 'world_the_end' }),
      map({ id: 'nether', dimension: 'minecraft:the_nether', worldGroup: 'g1', worldName: 'world_nether' }),
      map({ id: 'over', worldGroup: 'g1', worldName: 'world' }),
    ])
    expect(groups).toHaveLength(1)
    expect(groups[0]?.key).toBe('g1')
    expect(groups[0]?.archiveName).toBe('world')
    expect(groups[0]?.dimensions.map(item => item.id)).toEqual(['over', 'nether', 'end'])
  })

  it('没有分组字段的老数据按存档相同收敛', () => {
    const groups = groupMapsByArchive([
      map({ id: 'over', worldFileId: 'w7', worldName: 'world' }),
      map({ id: 'nether', worldFileId: 'w7', dimension: 'minecraft:the_nether' }),
      map({ id: 'other', worldFileId: 'w8' }),
    ])
    expect(groups).toHaveLength(2)
    expect(groups[0]?.dimensions).toHaveLength(2)
    expect(groups[1]?.dimensions).toHaveLength(1)
  })

  it('单张地图自成一组，存档名退回展示名', () => {
    const groups = groupMapsByArchive([map({ id: 'solo', name: '孤岛', worldName: '' })])
    expect(groups[0]?.archiveName).toBe('孤岛')
    expect(groups[0]?.dimensions).toHaveLength(1)
  })
})
