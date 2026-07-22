/** A decoded PRBM tile remains useful until the viewer disposes or the bounded resident cache evicts it. */
export function shouldRetainHiresTile(viewerDisposed: boolean): boolean {
  return !viewerDisposed
}
