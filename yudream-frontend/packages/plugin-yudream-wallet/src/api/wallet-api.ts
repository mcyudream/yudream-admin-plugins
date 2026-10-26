import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { PagedResult, WalletAsset, WalletBalance, WalletRechargeOptions, WalletRechargeResult, WalletRechargeSettings, WalletSummary, WalletTransaction } from '../types'

/** 从宿主注入的 http 客户端异常中提取可展示信息，插件不可假设有全局错误提示 */
export function errorMessage(cause: unknown, fallback: string): string {
  const data = cause as { response?: { data?: { message?: unknown } }, message?: unknown } | null
  const bodyMessage = data?.response?.data?.message
  if (typeof bodyMessage === 'string' && bodyMessage.trim()) {
    return bodyMessage
  }
  if (typeof data?.message === 'string' && data.message.trim()) {
    return data.message
  }
  return fallback
}

export function createWalletApi(sdk: YuDreamPluginSdk) {
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

  return {
    status: () => sdk.http.get<WalletSummary>('/status'),
    assets: async () => (await sdk.http.get<PagedResult<WalletAsset>>('/assets?page=1&size=100')).records,
    adminAssets: (page = 1, size = 10) => sdk.http.get<PagedResult<WalletAsset>>(`/admin/assets${query({ page, size })}`),
    meBalances: () => sdk.http.get<WalletBalance[]>('/me/balances'),
    adminBalances: (assetCode?: string, page = 1, size = 10) => sdk.http.get<PagedResult<WalletBalance>>(`/admin/balances${query({ page, size, assetCode })}`),
    adminTransactions: (filters?: Record<string, string>, page = 1, size = 10) => sdk.http.get<PagedResult<WalletTransaction>>(`/admin/transactions${query({ page, size, ...filters })}`),
    meTransactions: (filters?: Record<string, string>, page = 1, size = 10) => sdk.http.get<PagedResult<WalletTransaction>>(`/me/transactions${query({ page, size, ...filters })}`),
    saveAsset: (data: Record<string, unknown>) => sdk.http.post<WalletAsset>('/admin/assets', data),
    deleteAsset: (assetCode: string) => sdk.http.request(`/admin/assets/${encodeURIComponent(assetCode)}`, { method: 'DELETE' }),
    rechargeOptions: () => sdk.http.get<WalletRechargeOptions>('/me/recharge/options'),
    rechargeSettings: () => sdk.http.get<WalletRechargeSettings>('/admin/recharge/settings'),
    saveRechargeSettings: (data: Record<string, unknown>) => sdk.http.request<WalletRechargeSettings>('/admin/recharge/settings', { method: 'PUT', data }),
    createRecharge: (data: Record<string, unknown>) => sdk.http.post<WalletRechargeResult>('/me/recharges', data),
    credit: (data: Record<string, unknown>) => sdk.http.post<WalletTransaction>('/admin/balances/credit', data),
    debit: (data: Record<string, unknown>) => sdk.http.post<WalletTransaction>('/admin/balances/debit', data),
    transfer: (data: Record<string, unknown>) => sdk.http.post<WalletTransaction>('/me/transfers', data),
  }
}
