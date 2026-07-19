/** A decoded image must not be retained after its tile record or viewer has been evicted. */
export function shouldRetainLowresTexture(viewerDisposed: boolean, recordDisposed: boolean): boolean {
  return !viewerDisposed && !recordDisposed
}
