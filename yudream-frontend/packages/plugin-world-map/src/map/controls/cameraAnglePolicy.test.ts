import { describe, expect, it } from 'vitest'
import { clampFlyPitch, MAX_FLY_PITCH, MAX_ORBIT_POLAR_ANGLE, MIN_ORBIT_POLAR_ANGLE } from './cameraAnglePolicy'

describe('camera angle policy', () => {
  it('keeps orbit above the terrain-facing elevation floor', () => {
    expect(MIN_ORBIT_POLAR_ANGLE).toBeGreaterThan(0)
    expect(MAX_ORBIT_POLAR_ANGLE).toBeLessThan(Math.PI / 2)
  })

  it('prevents flight from looking straight up or down', () => {
    expect(clampFlyPitch(Math.PI)).toBe(MAX_FLY_PITCH)
    expect(clampFlyPitch(-Math.PI)).toBe(-MAX_FLY_PITCH)
    expect(clampFlyPitch(0.2)).toBe(0.2)
  })
})
