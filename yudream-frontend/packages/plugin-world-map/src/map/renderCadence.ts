/** Minecraft animation data advances at 20 ticks per second; rendering it faster only wastes GPU time. */
export const BACKGROUND_FRAME_INTERVAL_MS = 50

export interface BackgroundRenderState {
  pendingTiles: number
  animatedMaterials: boolean
  pageVisible: boolean
}

/** Background frames are only useful while tiles settle or visible material animations advance. */
export function needsBackgroundRender(state: BackgroundRenderState): boolean {
  return state.pageVisible && (state.pendingTiles > 0 || state.animatedMaterials)
}
