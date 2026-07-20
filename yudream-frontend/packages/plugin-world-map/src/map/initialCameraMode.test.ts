import { describe, expect, it } from 'vitest'
import { INITIAL_CAMERA_MODE } from '../composables/useWorldMapViewer'

describe('world map initial camera mode', () => {
  it('opens with a terrain-facing orbit camera instead of free flight', () => {
    expect(INITIAL_CAMERA_MODE).toBe('orbit')
  })
})
