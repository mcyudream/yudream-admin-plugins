import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { CreateMapPayload, MapAdmin, RenderTask } from '../types'

/** 契约 §2 管理接口薄封装（permission = plugin:world-map:manage） */
export function createWorldMapAdminApi(sdk: YuDreamPluginSdk) {
  return {
    maps: () => sdk.http.get<{ maps: MapAdmin[] }>('/admin/maps'),
    createMap: (data: CreateMapPayload) => sdk.http.post<MapAdmin>('/admin/maps', data),
    render: (mapId: string) => sdk.http.post<RenderTask>(`/admin/maps/${encodeURIComponent(mapId)}/render`),
    cancelTask: (taskId: string) => sdk.http.post<{ canceled: boolean }>(`/admin/tasks/${encodeURIComponent(taskId)}/cancel`),
    deleteMap: (mapId: string) => sdk.http.request(`/admin/maps/${encodeURIComponent(mapId)}`, { method: 'DELETE' }),
    tasks: () => sdk.http.get<{ tasks: RenderTask[] }>('/admin/tasks'),
  }
}
