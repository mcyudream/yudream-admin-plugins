export interface BlueMapAnimationDefinition {
  interpolate?: boolean
  frametime?: number
  frames?: Array<number | { index?: number, time?: number }>
}

export interface BlueMapAnimationState {
  frameCount: number
  frameIndex: number
  elapsedMs: number
  frameDurationMs: number
  definition: BlueMapAnimationDefinition
}

export function createBlueMapAnimationState(definition: BlueMapAnimationDefinition, frameCount: number): BlueMapAnimationState {
  return {
    frameCount: Math.max(1, frameCount),
    frameIndex: 0,
    elapsedMs: 0,
    frameDurationMs: frameDuration(definition, 0),
    definition,
  }
}

export function stepBlueMapAnimation(state: BlueMapAnimationState, deltaMs: number) {
  state.elapsedMs += Math.max(0, deltaMs)
  while (state.elapsedMs >= state.frameDurationMs) {
    state.elapsedMs -= state.frameDurationMs
    state.frameIndex = (state.frameIndex + 1) % effectiveFrameCount(state)
    state.frameDurationMs = frameDuration(state.definition, state.frameIndex)
  }
  const current = frameAt(state, state.frameIndex)
  const next = frameAt(state, (state.frameIndex + 1) % effectiveFrameCount(state))
  return {
    current,
    next,
    interpolation: state.definition.interpolate ? state.elapsedMs / state.frameDurationMs : 0,
  }
}

function effectiveFrameCount(state: BlueMapAnimationState): number {
  return state.definition.frames?.length || state.frameCount
}

function frameAt(state: BlueMapAnimationState, index: number): number {
  const frame = state.definition.frames?.[index]
  return typeof frame === 'number' ? frame : frame?.index ?? index
}

function frameDuration(definition: BlueMapAnimationDefinition, index: number): number {
  const frame = definition.frames?.[index]
  const ticks = typeof frame === 'object' && typeof frame.time === 'number'
    ? frame.time
    : definition.frametime ?? 1
  return Math.max(1, ticks) * 50
}
