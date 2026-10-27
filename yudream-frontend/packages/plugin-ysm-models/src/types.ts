export type ReviewStatus = 'APPROVED' | 'PENDING' | 'REJECTED'
export interface ModelSettings { uploadEnabled: boolean; reviewRequired: boolean }
export interface ModelUploadPolicy extends ModelSettings { canUpload: boolean }
export interface ModelRow { id: string; name: string; hash: string; ownerId?: string; enabled: boolean; serverEnabled: boolean; updatedAt: number; reviewStatus?: ReviewStatus; reviewNote?: string }
export interface Page<T> { records: T[]; total: number }
export interface Outfit { modelId: string; texture: string }
export interface Player { uuid: string; name: string; outfit: Outfit }
export type Vector = [number, number, number]
export interface Bone { name: string; parent?: string; pivot?: Vector; rotation?: Vector }
export interface Cube { origin: Vector; size: Vector; uv: [number, number] | Partial<Record<'north' | 'south' | 'east' | 'west' | 'up' | 'down', { uv: [number, number]; uv_size?: [number, number]; uv_rotation?: number }>>; pivot?: Vector; rotation?: Vector; inflate?: number; mirror?: boolean }
export interface RawAnimation { animation_length: number; loop?: boolean | 'hold_on_last_frame'; initializers?: { expr: unknown[] }[]; bones?: Record<string, Partial<Record<'rotation' | 'position' | 'scale', unknown>>> }
export interface Bundle { schema: number; name: string; modelScale?: Vector; previewAnimation?: string; layers?: string[]; compatibility?: string[]; bones: Bone[]; parts: { bone: string; cubes: Cube[] }[]; animations: Record<string, RawAnimation>; textures: { name: string; png: string; width: number; height: number }[] }
