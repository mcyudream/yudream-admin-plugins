import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'

/**
 * 宠物动作注册表：挂件上的全部对外动作都经此注册表分发。
 * 本期仅注册本地动作（打开设置页等）；ask/navigate/fill 类型为后续 AI 驱动预留，
 * AI 能力落地时应复用此入口并配合 plugin:mc-pet:ai 权限，详见 docs/mc-pet-ai-evolution.md。
 */
export type PetActionType = 'ask' | 'navigate' | 'fill' | 'local'

export interface PetActionContext {
  sdk: YuDreamPluginSdk
}

export interface PetAction {
  type: PetActionType
  /** 动作标识，如 open-settings。 */
  name: string
  /** 需要的宿主权限码；为空表示登录即可。 */
  permission?: string
  handler: (context: PetActionContext) => void | Promise<void>
}

const registry = new Map<string, PetAction>()

export function registerPetAction(action: PetAction) {
  registry.set(`${action.type}:${action.name}`, action)
}

export function getPetAction(type: PetActionType, name: string) {
  return registry.get(`${type}:${name}`)
}

export function listPetActions(type?: PetActionType) {
  return [...registry.values()].filter(action => !type || action.type === type)
}
