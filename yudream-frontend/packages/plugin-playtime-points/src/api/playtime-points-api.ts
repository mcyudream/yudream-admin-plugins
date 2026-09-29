import type {
  AdminOptions,
  CheckInRewardRecord,
  LastScanInfo,
  MySummary,
  PageResult,
  SaveSettingsPayload,
  ScanResult,
  SettlementRecord,
} from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'

function query(params: Record<string, unknown>): string {
  const qs = Object.entries(params)
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`)
    .join('&')
  return qs ? `?${qs}` : ''
}

export function createPlaytimePointsApi(sdk: YuDreamPluginSdk) {
  return {
    admin: {
      options: (): Promise<AdminOptions> => sdk.http.get<AdminOptions>('/admin/options'),
      saveSettings: (data: SaveSettingsPayload): Promise<unknown> =>
        sdk.http.request('/admin/settings', { method: 'PUT', data }),
      settlements: (params: { serverId?: string, keyword?: string, page?: number, size?: number }): Promise<PageResult<SettlementRecord>> =>
        sdk.http.get<PageResult<SettlementRecord>>(`/admin/settlements${query(params)}`),
      /** 打卡积分发放流水（跨用户，可按项目 / 用户筛选）。 */
      checkInRewards: (params: { projectId?: string, userId?: string, page?: number, size?: number }): Promise<PageResult<CheckInRewardRecord>> =>
        sdk.http.get<PageResult<CheckInRewardRecord>>(`/admin/check-in-rewards${query(params)}`),
      run: (): Promise<ScanResult> => sdk.http.post<ScanResult>('/admin/run'),
      lastScan: (): Promise<LastScanInfo> => sdk.http.get<LastScanInfo>('/admin/last-scan'),
    },
    me: {
      summary: (): Promise<MySummary> => sdk.http.get<MySummary>('/me/summary'),
      settlements: (page: number, size: number): Promise<PageResult<SettlementRecord>> =>
        sdk.http.get<PageResult<SettlementRecord>>(`/me/settlements${query({ page, size })}`),
    },
  }
}

export type PlaytimePointsApi = ReturnType<typeof createPlaytimePointsApi>
