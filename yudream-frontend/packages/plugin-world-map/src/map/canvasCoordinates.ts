/** Converts a pointer position in a canvas rectangle into Three.js NDC coordinates. */
export function canvasPointerToNdc(
  clientX: number,
  clientY: number,
  bounds: Pick<DOMRect, 'left' | 'top' | 'width' | 'height'>,
) {
  return {
    x: ((clientX - bounds.left) / bounds.width) * 2 - 1,
    y: -((clientY - bounds.top) / bounds.height) * 2 + 1,
  }
}
