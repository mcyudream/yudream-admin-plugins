/** Higher viewpoints cover more terrain, so flight speed grows smoothly without affecting ground control. */
export const MAX_FLY_SPEED_SCALE = 4

export function flySpeedScale(altitudeAboveSpawn: number): number {
  const altitude = Number.isFinite(altitudeAboveSpawn) ? Math.max(0, altitudeAboveSpawn) : 0
  return Math.min(MAX_FLY_SPEED_SCALE, Math.max(1, Math.sqrt(Math.max(64, altitude) / 64)))
}
