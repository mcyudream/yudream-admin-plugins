/**
 * Scores tile requests around the current target. The center remains first, while tiles in the
 * camera's horizontal forward direction win ties within the same radius. A top-down camera has
 * no horizontal direction and intentionally retains the symmetric distance-only order.
 */
export function tileRequestPriority(dx: number, dz: number, forwardX: number, forwardZ: number): number {
  const distanceSquared = dx * dx + dz * dz
  if (distanceSquared === 0) {
    return 0
  }
  const forwardLength = Math.hypot(forwardX, forwardZ)
  if (forwardLength === 0) {
    return distanceSquared
  }
  const alignment = (dx * forwardX + dz * forwardZ) / (Math.sqrt(distanceSquared) * forwardLength)
  // At equal distance, an opposite-direction tile is one radial tier behind a forward tile.
  return distanceSquared * (1 + (1 - alignment) * 0.5)
}
