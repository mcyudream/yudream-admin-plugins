import { describe, expect, it } from 'vitest'
import { renderTaskProgress } from './renderTaskProgress'

describe('render task progress', () => {
  it('uses the server phase progress before falling back to tile counts', () => {
    expect(renderTaskProgress({ state: 'RUNNING', progressPercent: 42, doneTiles: 1, totalTiles: 2 } as never)).toBe(42)
    expect(renderTaskProgress({ state: 'RUNNING', doneTiles: 1, totalTiles: 4 } as never)).toBe(25)
  })
})
