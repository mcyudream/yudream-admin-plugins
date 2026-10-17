import { describe, expect, it } from 'vitest'
import { clockTime, describeFormChanges, flattenForm } from './formDiff'

/**
 * 自动保存提示的「改了什么」摘要。
 *
 * <p>提示要么说清具体操作，要么就是噪音：漏报会让人以为没保存（重复操作），误报会让人以为保存了
 * 根本没动的东西。这里把两种错法都钉住。</p>
 */
describe('describeFormChanges', () => {
  const base = {
    name: '生存世界',
    dimension: 'minecraft:overworld',
    settings: { minY: -64, batchChunks: 2048 },
    clearRegion: true,
  }

  it('reports nothing when the form is untouched', () => {
    expect(describeFormChanges(base, structuredClone(base))).toEqual([])
  })

  it('names the changed top-level and nested fields in display order', () => {
    const after = structuredClone(base)
    after.name = '海岛寿司店'
    after.settings.minY = -32

    // 顺序按字段表：名称 在前，最低渲染高度 在后
    expect(describeFormChanges(base, after)).toEqual(['名称', '最低渲染高度'])
  })

  it('reports a single field change only', () => {
    const after = structuredClone(base)
    after.settings.batchChunks = 8192

    expect(describeFormChanges(base, after)).toEqual(['批次大小'])
  })

  it('counts removed fields as changes (clearing the render region)', () => {
    const before = { ...structuredClone(base), regionMinX: 0, regionMaxX: 512 }
    const after = structuredClone(base)

    expect(describeFormChanges(before, after)).toEqual(['范围 X 起', '范围 X 止'])
  })

  it('falls back to the raw path for fields without a display name', () => {
    const after = { ...structuredClone(base), customField: 'x' }

    expect(describeFormChanges(base, after)).toEqual(['customField'])
  })

  it('flattens nested objects into dotted paths', () => {
    expect(flattenForm({ a: 1, b: { c: 'x' } })).toEqual({ a: '1', 'b.c': 'x' })
  })

  it('formats the clock as HH:mm:ss', () => {
    expect(clockTime(new Date(2026, 9, 12, 9, 5, 3))).toBe('09:05:03')
  })
})
