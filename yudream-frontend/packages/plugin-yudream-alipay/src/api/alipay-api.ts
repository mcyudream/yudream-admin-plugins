import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AlipayConfig, AlipayOrder, PagedResult } from '../types'

export function createAlipayApi(sdk: YuDreamPluginSdk) {
  return {
    adminConfig: () => sdk.http.get<AlipayConfig>('/admin/config'),
    saveAdminConfig: (data: AlipayConfig) => sdk.http.request<AlipayConfig>('/admin/config', { method: 'PUT', data }),
    adminOrders: (page = 1, size = 10) => sdk.http.get<PagedResult<AlipayOrder>>(`/admin/orders?page=${page}&size=${size}`),
    meOrders: (page = 1, size = 10) => sdk.http.get<PagedResult<AlipayOrder>>(`/me/orders?page=${page}&size=${size}`),
    meOrder: (outTradeNo: string) => sdk.http.get<AlipayOrder>(`/me/orders/${encodeURIComponent(outTradeNo)}`),
  }
}
