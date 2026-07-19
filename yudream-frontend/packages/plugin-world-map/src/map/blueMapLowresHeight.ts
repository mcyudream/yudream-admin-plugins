import type { LowresImage } from './lowresImageDecode'

export type BlueMapLowresHeightSampler = (localX: number, localZ: number) => number | null

/** Decodes BlueMap's signed 16-bit height packed into metadata green/blue bytes. */
export function blueMapHeightFromMetadata(green: number, blue: number): number {
  const packed = ((green & 0xff) << 8) | (blue & 0xff)
  const height = packed >= 0x8000 ? -(0xffff - packed) : packed
  return height === 0 ? 0 : height
}

/** Matches the lowres vertex shader's vertical displacement, including its anti-z-fighting offset. */
export function blueMapLowresDisplacedHeight(localX: number, localZ: number, green: number, blue: number): number {
  return blueMapHeightFromMetadata(green, blue) + 1 - localX * 0.0001 - localZ * 0.0002
}

/**
 * Reads a single metadata texel only after a direct terrain hit. This keeps lowres terrain picking
 * accurate without retaining a CPU copy of every overview PNG or adding work to the render loop.
 */
export function createBlueMapLowresHeightSampler(image: LowresImage): BlueMapLowresHeightSampler | null {
  if (typeof document === 'undefined') return null
  const width = Number(image.width)
  const height = Number(image.height)
  if (!Number.isFinite(width) || !Number.isFinite(height) || width < 1 || height < 2) return null

  const canvas = document.createElement('canvas')
  canvas.width = 1
  canvas.height = 1
  const context = canvas.getContext('2d', { willReadFrequently: true })
  if (!context) return null
  const metadataTop = Math.floor(height * 0.5)

  return (localX, localZ) => {
    const pixelX = Math.floor(localX)
    const pixelZ = Math.floor(localZ)
    if (pixelX < 0 || pixelX >= width || pixelZ < 0 || pixelZ >= metadataTop) return null
    try {
      context.clearRect(0, 0, 1, 1)
      context.drawImage(image, pixelX, metadataTop + pixelZ, 1, 1, 0, 0, 1, 1)
      const metadata = context.getImageData(0, 0, 1, 1).data
      return blueMapLowresDisplacedHeight(localX, localZ, metadata[1]!, metadata[2]!)
    }
    catch {
      // A tainted canvas or a transient browser decoder failure falls back to the base mesh hit.
      return null
    }
  }
}
