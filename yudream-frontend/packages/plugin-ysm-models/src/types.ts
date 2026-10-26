export interface ModelRow { id: string; name: string; hash: string; ownerId?: string; enabled: boolean; serverEnabled: boolean; updatedAt: number }
export interface Page<T> { records: T[]; total: number }
export interface Outfit { modelId: string; texture: string }
export interface Player { uuid: string; name: string; outfit: Outfit }
export type Vector = [number, number, number]
export interface Bone { name: string; parent?: string; pivot?: Vector; rotation?: Vector }
export interface Cube { origin: Vector; size: Vector; uv: [number, number]; pivot?: Vector; rotation?: Vector; inflate?: number; mirror?: boolean }
export interface RawAnimation { animation_length: number; loop?: boolean | 'hold_on_last_frame'; bones?: Record<string, Partial<Record<'rotation' | 'position' | 'scale', unknown>>> }
export interface Bundle { schema: number; name: string; bones: Bone[]; parts: { bone: string; cubes: Cube[] }[]; animations: Record<string, RawAnimation>; textures: { name: string; png: string; width: number; height: number }[] }
