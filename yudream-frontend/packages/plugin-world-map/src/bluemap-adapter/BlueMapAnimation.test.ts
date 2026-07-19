import { describe, expect, it } from 'vitest'
import { createBlueMapAnimationState, stepBlueMapAnimation } from './BlueMapAnimation'

describe('BlueMap texture animation', () => {
  it('advances Minecraft-tick frames and keeps interpolation within the current frame', () => {
    const state = createBlueMapAnimationState({ frametime: 2, interpolate: true }, 4)
    expect(stepBlueMapAnimation(state, 100)).toMatchObject({ current: 1, next: 2, interpolation: 0 })
    expect(stepBlueMapAnimation(state, 25)).toMatchObject({ current: 1, next: 2, interpolation: 0.25 })
  })

  it('supports BlueMap explicit frame indexes and durations', () => {
    const state = createBlueMapAnimationState({ frames: [{ index: 3, time: 1 }, { index: 1, time: 3 }] }, 4)
    expect(stepBlueMapAnimation(state, 50)).toMatchObject({ current: 1, next: 3 })
  })
})
