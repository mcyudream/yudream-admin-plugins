/** A decoded image must not be retained after its tile record or viewer has been evicted. */
export function shouldRetainLowresTexture(viewerDisposed: boolean, recordDisposed: boolean): boolean {
  return !viewerDisposed && !recordDisposed
}

/** An aborted request is expected during view changes and must not become an error tile. */
export function shouldMarkLowresLoadFailed(recordDisposed: boolean, aborted: boolean): boolean {
  return !recordDisposed && !aborted
}
