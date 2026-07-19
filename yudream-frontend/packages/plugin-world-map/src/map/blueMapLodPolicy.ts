/** BlueMap stops loading PRBM hires tiles for distant camera views. */
export const BLUEMAP_HIRES_DISTANCE = 1000
/** Keep the PRBM/overview transition stable while orbit damping crosses the nominal threshold. */
export const BLUEMAP_HIRES_ENTER_DISTANCE = 900
export const BLUEMAP_HIRES_EXIT_DISTANCE = 1100

/** Selects the corresponding BlueMap lowres directory using the map's lodFactor, not base 2. */
export function blueMapLodForDistance(distance: number, minLod: number, maxLod: number, lodFactor: number): number {
  if (maxLod <= minLod || distance <= BLUEMAP_HIRES_DISTANCE) return minLod
  const step = Math.floor(Math.log(distance / BLUEMAP_HIRES_DISTANCE) / Math.log(lodFactor))
  return Math.min(maxLod, Math.max(minLod, minLod + step))
}

export function shouldLoadBlueMapHires(distance: number): boolean {
  return distance < BLUEMAP_HIRES_DISTANCE
}

/**
 * Retains high-resolution tiles through a small hysteresis band. This prevents rapid zooms around
 * one kilometre from repeatedly aborting PRBM requests and falling back to lowres imagery.
 */
export function nextBlueMapHiresEnabled(currentlyEnabled: boolean, distance: number): boolean {
  if (currentlyEnabled) {
    return distance < BLUEMAP_HIRES_EXIT_DISTANCE
  }
  return distance < BLUEMAP_HIRES_ENTER_DISTANCE
}
