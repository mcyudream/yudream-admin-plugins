import { describe, expect, it } from 'vitest'
import { BACKGROUND_FRAME_INTERVAL_MS, needsBackgroundRender } from './renderCadence'

describe('needsBackgroundRender', () => {
  it('keeps tile loads and animated materials responsive at the Minecraft tick cadence', () => {
    expect(BACKGROUND_FRAME_INTERVAL_MS).toBe(50)
    expect(needsBackgroundRender({ pendingTiles: 1, animatedMaterials: false, pageVisible: true })).toBe(true)
    expect(needsBackgroundRender({ pendingTiles: 0, animatedMaterials: true, pageVisible: true })).toBe(true)
  })

  it('does not schedule hidden or fully idle viewers', () => {
    expect(needsBackgroundRender({ pendingTiles: 0, animatedMaterials: false, pageVisible: true })).toBe(false)
    expect(needsBackgroundRender({ pendingTiles: 4, animatedMaterials: true, pageVisible: false })).toBe(false)
  })
})
