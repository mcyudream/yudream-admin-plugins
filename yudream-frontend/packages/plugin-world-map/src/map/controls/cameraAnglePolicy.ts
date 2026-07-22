/** Keep interactive perspective views terrain-facing instead of allowing horizon/ground inversion. */
export const MIN_ORBIT_POLAR_ANGLE = Math.PI * 0.12
export const MAX_ORBIT_POLAR_ANGLE = Math.PI * 0.4
export const MAX_FLY_PITCH = Math.PI * 0.28

export function clampFlyPitch(value: number): number {
  return Math.min(MAX_FLY_PITCH, Math.max(-MAX_FLY_PITCH, value))
}
