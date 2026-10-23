import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { SkinTexture } from './types'
import { createSkinApi } from './api/skin-api'

export type SkinApi = ReturnType<typeof createSkinApi>

let apiRef: SkinApi | undefined
let canManageRef = false
const cache = new Map<string, SkinTexture>()
let listPromise: Promise<void> | undefined

/** install(sdk) 时绑定材质卡取数通道：管理员走 /admin/textures，普通身份走 /me/textures */
export function bindSkinTexturePeek(sdk: YuDreamPluginSdk) {
  apiRef = createSkinApi(sdk)
  canManageRef = sdk.account.permissions.includes('*') || sdk.account.permissions.includes('plugin:yudream-skin:manage')
  cache.clear()
  listPromise = undefined
}

/** dispose 时解绑并清缓存，避免旧 sdk 泄漏 */
export function resetSkinTexturePeek() {
  apiRef = undefined
  canManageRef = false
  cache.clear()
  listPromise = undefined
}

function loadList(): Promise<void> {
  if (!listPromise) {
    const api = apiRef
    if (!api) {
      return Promise.reject(new Error('皮肤插件材质卡未就绪，请重新进入页面'))
    }
    listPromise = (canManageRef ? api.adminTextures() : api.textures())
      .then((records) => {
        for (const record of records) {
          cache.set(record.hash, record)
        }
      })
      .catch((cause) => {
        // 失败后允许下次打开重试；成功结果按会话缓存
        listPromise = undefined
        throw cause
      })
  }
  return listPromise
}

/** 材质卡按 hash 取详情：清单已在页面加载时全量拉取，这里复用一次请求服务同会话全部打开 */
export async function readSkinTexture(hash: string): Promise<SkinTexture> {
  const cached = cache.get(hash)
  if (cached) {
    return cached
  }
  await loadList()
  const hit = cache.get(hash)
  if (!hit) {
    throw new Error('当前身份看不到该材质，或材质已不存在')
  }
  return hit
}

export function skinTextureApi(): SkinApi {
  if (!apiRef) {
    throw new Error('皮肤插件材质卡未就绪，请重新进入页面')
  }
  return apiRef
}
