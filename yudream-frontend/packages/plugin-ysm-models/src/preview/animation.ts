import type { RawAnimation, Bundle } from '../types'
import { compile, context, type Context } from './molang'
import { evaluateClip } from '../vendor/ysm-model-manager/animation-evaluator'
export type Vec3 = [number, number, number]
type MolangAxes = [((time: number) => number) | null, ((time: number) => number) | null, ((time: number) => number) | null]
export interface Keyframe { time: number; post: Vec3; pre: Vec3; lerp: 'linear' | 'step' | 'catmullrom'; postMolang?: MolangAxes; preMolang?: MolangAxes }
export interface TimelineEvent { time: number; actions: ((time: number) => number)[]; raw: string[] }
export interface BoneTransform { rotation?: Vec3; position?: Vec3; scale?: Vec3 }
export interface AnimationClip { name: string; loop: boolean; length: number; bones: Record<string, Partial<Record<'rotation' | 'position' | 'scale', Keyframe[]>>>; state: Context; initializers: ((time: number) => number)[] }
export const BONE_CHANNELS = ['rotation', 'position', 'scale'] as const
function vector(value: unknown, state: Context): { base: Vec3; dynamic: MolangAxes } {
  const values = Array.isArray(value) ? value : [value, value, value]
  if (values.length !== 3) throw new Error('动画包含不支持的向量')
  return { base: values.map(v => typeof v === 'number' ? v : 0) as Vec3,
    dynamic: values.map(v => typeof v === 'number' ? null : compile(v, state)) as MolangAxes }
}
function frame(value: unknown, time: number, state: Context): Keyframe {
  const f = value && typeof value === 'object' && !Array.isArray(value) && !('expr' in value)
    ? value as { pre?: unknown; post?: unknown; vector?: unknown; lerp_mode?: Keyframe['lerp'] } : { post: value }
  const pre = vector(f.pre ?? f.post ?? f.vector, state), post = vector(f.post ?? f.vector, state)
  return { time, pre: pre.base, post: post.base, preMolang: pre.dynamic, postMolang: post.dynamic, lerp: f.lerp_mode ?? 'linear' }
}
export function parseAnimation(name: string, raw: RawAnimation, state = context()): AnimationClip {
  const clip: AnimationClip = { name, loop: raw.loop === true, length: raw.animation_length, bones: {}, state,
    initializers: (raw.initializers ?? []).map(v => compile(v, state)) }
  for (const [bone, channels] of Object.entries(raw.bones ?? {})) {
    const tracks: AnimationClip['bones'][string] = {}
    for (const key of BONE_CHANNELS) {
      const value = channels[key]
      if (value == null) continue
      tracks[key] = value && typeof value === 'object' && !Array.isArray(value) && !('expr' in value)
        ? Object.entries(value).map(([time, f]) => frame(f, Number(time), state)).sort((a, b) => a.time - b.time)
        : [frame(value, 0, state)]
    }
    clip.bones[bone] = tracks
  }
  return clip
}
export function parseAnimations(bundle: Bundle): Map<string, AnimationClip> {
  const state = context()
  return new Map(Object.entries(bundle.animations).map(([name, a]) => [name, parseAnimation(name, a, state)]))
}
export function evaluatePose(bundle: Bundle, clips: Map<string, AnimationClip>, selected: string, time: number): Map<string, BoneTransform> {
  const names = [...(bundle.layers ?? []).filter(n => n.startsWith('pre_') && n !== selected), selected,
    ...(bundle.layers ?? []).filter(n => !n.startsWith('pre_') && n !== selected)]
  const active = names.map(n => clips.get(n)).filter((v): v is AnimationClip => !!v)
  if (active.length) active[0].state.variables.clear()
  for (const clip of active) for (const initial of clip.initializers) initial(time)
  const result = new Map<string, BoneTransform>()
  for (const clip of active) for (const [bone, pose] of evaluateClip(clip, time)) {
    const target = result.get(bone) ?? {}
    for (const key of BONE_CHANNELS) if (pose[key]) {
      const value = target[key] ?? (key === 'scale' ? [1, 1, 1] : [0, 0, 0])
      target[key] = value.map((n, i) => key === 'scale' ? n * pose[key]![i] : n + pose[key]![i]) as Vec3
    }
    result.set(bone, target)
  }
  return result
}
export const previewAnimation = (bundle: Bundle) => bundle.previewAnimation && bundle.animations[bundle.previewAnimation] ? bundle.previewAnimation : bundle.animations.idle ? 'idle' : Object.keys(bundle.animations)[0] ?? ''
