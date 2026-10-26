import type { RawAnimation } from '../types'
// Compatible with the upstream evaluator; the upload compiler accepts data-only numeric tracks.
export type Vec3 = [number, number, number]
type MolangAxes = [((time: number) => number) | null, ((time: number) => number) | null, ((time: number) => number) | null]
export interface Keyframe { time: number; post: Vec3; pre: Vec3; lerp: 'linear' | 'step' | 'catmullrom'; postMolang?: MolangAxes; preMolang?: MolangAxes }
export interface TimelineEvent { time: number; actions: ((time: number) => number)[]; raw: string[] }
export interface BoneTransform { rotation?: Vec3; position?: Vec3; scale?: Vec3 }
export interface AnimationClip { name: string; loop: boolean; length: number; bones: Record<string, Partial<Record<'rotation' | 'position' | 'scale', Keyframe[]>>> }
export const BONE_CHANNELS = ['rotation', 'position', 'scale'] as const
function vector(value: unknown): Vec3 {
  if (typeof value === 'number' && Number.isFinite(value)) return [value, value, value]
  if (Array.isArray(value) && value.length === 3 && value.every(v => typeof v === 'number' && Number.isFinite(v))) return [value[0], value[1], value[2]]
  throw new Error('动画包含不支持的向量')
}
function frame(value: unknown, time: number): Keyframe {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const f = value as { pre?: unknown; post?: unknown; vector?: unknown; lerp_mode?: Keyframe['lerp'] }
    return { time, pre: vector(f.pre ?? f.post ?? f.vector), post: vector(f.post ?? f.vector), lerp: f.lerp_mode ?? 'linear' }
  }
  const v = vector(value)
  return { time, pre: v, post: v, lerp: 'linear' }
}
export function parseAnimation(name: string, raw: RawAnimation): AnimationClip {
  const clip: AnimationClip = { name, loop: raw.loop === true, length: raw.animation_length, bones: {} }
  for (const [bone, channels] of Object.entries(raw.bones ?? {})) {
    const tracks: AnimationClip['bones'][string] = {}
    for (const key of BONE_CHANNELS) {
      const value = channels[key]
      if (value == null) continue
      tracks[key] = value && typeof value === 'object' && !Array.isArray(value)
        ? Object.entries(value).map(([time, f]) => frame(f, Number(time))).sort((a, b) => a.time - b.time)
        : [frame(value, 0)]
    }
    clip.bones[bone] = tracks
  }
  return clip
}
