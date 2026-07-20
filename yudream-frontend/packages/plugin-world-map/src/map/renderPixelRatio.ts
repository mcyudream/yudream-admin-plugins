/**
 * Keeps high-DPI terrain clear without letting an ultrawide Retina canvas consume an unbounded
 * number of fragments per frame. Eight million pixels preserves a near-2x image at 1080p while
 * reducing fill-rate pressure on 1440p and wider displays.
 */
export const MAX_RENDER_PIXELS = 8_000_000
export const MAX_RENDER_PIXEL_RATIO = 2

export function renderPixelRatio(devicePixelRatio: number, width: number, height: number): number {
  const safeRatio = Number.isFinite(devicePixelRatio) && devicePixelRatio > 0 ? devicePixelRatio : 1
  const safePixels = Math.max(1, width) * Math.max(1, height)
  const budgetRatio = Math.sqrt(MAX_RENDER_PIXELS / safePixels)
  return Math.min(safeRatio, MAX_RENDER_PIXEL_RATIO, budgetRatio)
}
