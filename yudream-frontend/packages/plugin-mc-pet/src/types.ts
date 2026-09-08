/** 与后端 DTO 对齐；长整型 ID 一律 string。 */

export type PetSource = 'builtin' | 'texture'
export type PetAnimation = 'idle' | 'walk' | 'run' | 'fly' | 'wave' | 'crouch' | 'hit'
export type PetPreferenceMode = 'default' | 'player' | 'closet'
export type PetDefaultsMode = 'builtin' | 'player' | 'texture'
export type PetClickAction = 'menu' | 'greet' | 'none'
export type PetCorner = 'bottom-right' | 'bottom-left' | 'top-right' | 'top-left'

/** 挂件最终生效配置（GET /me/pet 返回）。 */
export interface MyPet {
  source: PetSource
  /** source=builtin 时为空，前端使用插件内置 Steve 皮肤 */
  skinUrl?: string
  model: 'classic' | 'slim'
  size: number
  corner: PetCorner
  /** 用户拖拽后的视口比例坐标（0-1），空表示使用停靠角 */
  positionX?: number
  positionY?: number
  hidden: boolean
  animation: boolean
  clickAction: PetClickAction
  skinAvailable: boolean
  preferenceMode: PetPreferenceMode
  /** 当前偏好中的角色名/衣柜条目（对应模式下有效），供设置页回填 */
  playerName?: string
  closetItemId?: string
}

export interface PetPlayerOption {
  name: string
  uuid: string
  textureHash?: string
  model?: 'classic' | 'slim'
}

export interface PetClosetOption {
  id: string
  textureHash: string
  itemName: string
  createdAt?: string
}

/** 「我的宠物」选择器数据（GET /me/pet/options 返回）。 */
export interface PetOptions {
  skinAvailable: boolean
  players: PetPlayerOption[]
  closet: PetClosetOption[]
}

export interface PetDefaults {
  mode: PetDefaultsMode
  playerName?: string
  /** mode=texture 时生效的皮肤纹理 hash（来自管理员自己的衣柜上传） */
  textureHash?: string
  model?: 'classic' | 'slim'
  animation: boolean
  clickAction: PetClickAction
  size: number
  corner: PetCorner
}

export interface PetPreference {
  userId: string
  mode: PetPreferenceMode
  playerName?: string
  closetItemId?: string
  size?: number
  corner?: PetCorner
  positionX?: number
  positionY?: number
  hidden: boolean
  updatedAt?: string
}

export interface PetAdminItem {
  userId: string
  preference: PetPreference
  effective: MyPet
}

export interface PagedResult<T> {
  records: T[]
  total: number | string
}

export interface MyPetSavePayload {
  mode?: PetPreferenceMode
  playerName?: string
  closetItemId?: string
  size?: number
  corner?: PetCorner
  positionX?: number
  positionY?: number
  hidden?: boolean
  /** 显式清除拖拽位置，回到停靠角 */
  clearPosition?: boolean
}
