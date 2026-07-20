/** A loaded PRBM tile remains useful after a small camera move when it is still in the desired disk. */
export function shouldRetainHiresTile(viewerDisposed: boolean, stillDesired: boolean): boolean {
  return !viewerDisposed && stillDesired
}
