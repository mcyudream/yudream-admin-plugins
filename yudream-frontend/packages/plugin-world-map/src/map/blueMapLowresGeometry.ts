import * as THREE from 'three'

/**
 * BlueMap stores a one-pixel overlap around each lowres coverage tile. Matching that overlap
 * prevents adjacent height fields from separating at their shared world-space boundary.
 */
export function createBlueMapLowresGeometry(tileSize: number, segments: number): THREE.PlaneGeometry {
  const geometry = new THREE.PlaneGeometry(tileSize + 1, tileSize + 1, segments, segments)
  geometry.rotateX(-Math.PI / 2)
  geometry.translate(tileSize * 0.5 + 1, 0, tileSize * 0.5 + 1)
  return geometry
}
