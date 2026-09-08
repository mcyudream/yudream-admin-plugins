import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { MyPet, MyPetSavePayload, PagedResult, PetAdminItem, PetClosetOption, PetDefaults, PetOptions } from '../types'

function query(params: Record<string, string | number | undefined>) {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== '') {
      search.set(key, String(value))
    }
  })
  const value = search.toString()
  return value ? `?${value}` : ''
}

/** 用户端 API 只打 /me/**，管理端只打 /admin/**，两者不共享缓存。 */
export function createMcPetApi(sdk: YuDreamPluginSdk) {
  return {
    me: {
      pet: () => sdk.http.get<MyPet>('/me/pet'),
      savePet: (data: MyPetSavePayload) => sdk.http.request<MyPet>('/me/pet', { method: 'PUT', data }),
      options: () => sdk.http.get<PetOptions>('/me/pet/options'),
      /** 上传皮肤 PNG 到衣柜（需要皮肤插件 ≥ 1.3.0），返回新衣柜条目 */
      uploadSkin: (data: { name?: string, model: 'classic' | 'slim', base64: string }) =>
        sdk.http.post<PetClosetOption>('/me/pet/skin', data),
    },
    admin: {
      defaults: () => sdk.http.get<PetDefaults>('/admin/defaults'),
      saveDefaults: (data: Record<string, unknown>) => sdk.http.request<PetDefaults>('/admin/defaults', { method: 'PUT', data }),
      pets: (page = 1, size = 10) => sdk.http.get<PagedResult<PetAdminItem>>(`/admin/pets${query({ page, size })}`),
      petDetail: (userId: string) => sdk.http.get<PetAdminItem>(`/admin/pets/${encodeURIComponent(userId)}`),
      resetPet: (userId: string) => sdk.http.post<PetAdminItem>(`/admin/pets/${encodeURIComponent(userId)}/reset`),
    },
  }
}

export type McPetApi = ReturnType<typeof createMcPetApi>
