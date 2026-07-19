/** BlueMap stops loading PRBM hires tiles for distant camera views. */
export const BLUEMAP_HIRES_DISTANCE = 1000

/** Selects the corresponding BlueMap lowres directory using the map's lodFactor, not base 2. */
export function blueMapLodForDistance(distance: number, minLod: number, maxLod: number, lodFactor: number): number {
  if (maxLod <= minLod || distance <= BLUEMAP_HIRES_DISTANCE) return minLod
  const step = Math.floor(Math.log(distance / BLUEMAP_HIRES_DISTANCE) / Math.log(lodFactor))
  return Math.min(maxLod, Math.max(minLod, minLod + step))
}

export function shouldLoadBlueMapHires(distance: number): boolean {
  return distance < BLUEMAP_HIRES_DISTANCE
}
