import { describe, expect, it } from 'vitest'
import { buildDimensionChoices, needsDimensionSwitch } from './dimensionSwitch'

/**
 * 维度切换项的整理规则。
 *
 * <p>这份清单直接决定渲染页/展示页的维度按钮：顺序跳动会让按钮位置乱跳，去重写错会出现
 * 「按钮里没有你正在看的那张图」，都很容易被当成页面 bug 报上来。</p>
 */
describe('buildDimensionChoices', () => {
  const overworld = { id: 'm1', name: '生存世界', dimension: 'minecraft:overworld', ready: true, state: 'READY' }
  const nether = { id: 'm2', name: '生存世界 · 下界', dimension: 'minecraft:nether', ready: true, state: 'READY' }
  const end = { id: 'm3', name: '生存世界 · 末地', dimension: 'minecraft:the_end', ready: false, state: 'EMPTY' }

  it('keeps the server order and marks the current one', () => {
    const choices = buildDimensionChoices([overworld, nether, end], 'm2')
    expect(choices.map(c => c.id)).toEqual(['m1', 'm2', 'm3'])
    expect(choices.map(c => c.label)).toEqual(['主世界', '下界', '末地'])
    expect(choices.map(c => c.current)).toEqual([false, true, false])
  })

  it('carries ready/state through (render page shows which dimensions are not rendered yet)', () => {
    const choices = buildDimensionChoices([overworld, nether, end], 'm1')
    expect(choices.map(c => c.ready)).toEqual([true, true, false])
    expect(choices.map(c => c.state)).toEqual(['READY', 'READY', 'EMPTY'])
  })

  it('defaults ready to true when the public endpoint omits it', () => {
    const choices = buildDimensionChoices([{ id: 'm1', name: 'a', dimension: 'minecraft:overworld' }], 'm1')
    expect(choices[0].ready).toBe(true)
    expect(choices[0].state).toBe('')
  })

  it('derives ready from the admin state (the admin DTO has no ready field)', () => {
    const choices = buildDimensionChoices([
      { id: 'm1', name: 'a', dimension: 'minecraft:overworld', state: 'READY' },
      { id: 'm2', name: 'b', dimension: 'minecraft:nether', state: 'EMPTY' },
      { id: 'm3', name: 'c', dimension: 'minecraft:the_end', state: 'RENDERING' },
    ], 'm1')
    expect(choices.map(c => c.ready)).toEqual([true, false, false])
    expect(choices.map(c => c.state)).toEqual(['READY', 'EMPTY', 'RENDERING'])
  })

  it('deduplicates by dimension, preferring the current map', () => {
    const duplicate = { id: 'm9', name: '旧的生存世界', dimension: 'minecraft:overworld', ready: true, state: 'READY' }
    const choices = buildDimensionChoices([duplicate, overworld, nether], 'm1')
    expect(choices.map(c => c.id)).toEqual(['m1', 'm2'])
  })

  it('keeps the first entry when neither duplicate is current', () => {
    const duplicate = { id: 'm9', name: '旧的生存世界', dimension: 'minecraft:overworld' }
    const choices = buildDimensionChoices([overworld, duplicate, nether], 'm2')
    expect(choices.map(c => c.id)).toEqual(['m1', 'm2'])
  })

  it('tolerates empty and malformed input', () => {
    expect(buildDimensionChoices([], 'm1')).toEqual([])
    expect(buildDimensionChoices([{ id: '', name: 'x', dimension: 'minecraft:overworld' }], 'm1')).toEqual([])
  })
})

describe('needsDimensionSwitch', () => {
  it('is only true when there is another dimension to switch to', () => {
    expect(needsDimensionSwitch([])).toBe(false)
    expect(needsDimensionSwitch(buildDimensionChoices(
      [{ id: 'm1', name: 'a', dimension: 'minecraft:overworld' }], 'm1'))).toBe(false)
    expect(needsDimensionSwitch(buildDimensionChoices([
      { id: 'm1', name: 'a', dimension: 'minecraft:overworld' },
      { id: 'm2', name: 'b', dimension: 'minecraft:nether' },
    ], 'm1'))).toBe(true)
  })
})
