import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { Page, PublishQualification, ShopCurrency, ShopOrder, ShopProductDetail, ShopProductPayload, ShopProductSummary, ShopProductType, ShopUserOption, AdminShopProductPayload, ShopSettings, ShopSettingsPayload } from '../types'

function query(params: Record<string, string | number | undefined | null>) {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && String(value) !== '') {
      search.set(key, String(value))
    }
  })
  const text = search.toString()
  return text ? `?${text}` : ''
}

export function createShopApi(sdk: YuDreamPluginSdk) {
  return {
    // ---------- 广场（view） ----------
    plazaProducts: (keyword = '', assetCode = '', page = 1, size = 12) =>
      sdk.http.get<Page<ShopProductSummary>>(`/plaza/products${query({ keyword, assetCode, page, size })}`),
    plazaProductDetail: (id: string) =>
      sdk.http.get<ShopProductDetail>(`/plaza/products/${encodeURIComponent(id)}`),
    plazaCurrencies: () =>
      sdk.http.get<ShopCurrency[]>('/plaza/currencies'),

    // ---------- 卖家（publish） ----------
    myCurrencies: () =>
      sdk.http.get<{ walletAvailable: boolean, records: ShopCurrency[] }>('/me/currencies'),
    myProductTypes: () =>
      sdk.http.get<ShopProductType[]>('/me/product-types'),
    publishQualification: () =>
      sdk.http.get<PublishQualification>('/me/publish-qualification'),
    myProducts: (keyword = '', page = 1, size = 10) =>
      sdk.http.get<Page<ShopProductSummary>>(`/me/products${query({ keyword, page, size })}`),
    myProductDetail: (id: string) =>
      sdk.http.get<ShopProductDetail>(`/me/products/${encodeURIComponent(id)}`),
    createProduct: (data: ShopProductPayload) =>
      sdk.http.post<ShopProductDetail>('/me/products', data),
    updateProduct: (id: string, data: ShopProductPayload) =>
      sdk.http.request<ShopProductDetail>(`/me/products/${encodeURIComponent(id)}`, { method: 'PUT', data }),
    setMyProductShelf: (id: string, onShelf: boolean) =>
      sdk.http.post<ShopProductSummary>(`/me/products/${encodeURIComponent(id)}/shelf`, { onShelf }),
    deleteMyProduct: (id: string) =>
      sdk.http.request<{ deleted: boolean }>(`/me/products/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    mySales: (page = 1, size = 10) =>
      sdk.http.get<Page<ShopOrder>>(`/me/sales${query({ page, size })}`),

    // ---------- 买家（use） ----------
    purchase: (productId: string, quantity: number) =>
      sdk.http.post<ShopOrder>('/me/orders', { productId, quantity }),
    myPurchases: (page = 1, size = 10) =>
      sdk.http.get<Page<ShopOrder>>(`/me/purchases${query({ page, size })}`),
    myOrderDetail: (id: string) =>
      sdk.http.get<ShopOrder>(`/me/orders/${encodeURIComponent(id)}`),
    submitVoucher: (id: string, voucher: string) =>
      sdk.http.post<ShopOrder>(`/me/orders/${encodeURIComponent(id)}/voucher`, { voucher }),
    verifyDelivery: (id: string) =>
      sdk.http.post<ShopOrder>(`/me/orders/${encodeURIComponent(id)}/verify`),
    myBalance: (assetCode: string) =>
      sdk.http.get<{ available: boolean, balance: string }>(`/me/wallet/balance${query({ assetCode })}`),

    // ---------- 管理端（manage） ----------
    adminProducts: (keyword = '', ownerId = '', status = '', type = '', page = 1, size = 10) =>
      sdk.http.get<Page<ShopProductSummary>>(`/admin/products${query({ keyword, ownerId, status, type, page, size })}`),
    adminProductDetail: (id: string) =>
      sdk.http.get<ShopProductDetail>(`/admin/products/${encodeURIComponent(id)}`),
    adminCreateProduct: (data: AdminShopProductPayload) =>
      sdk.http.post<ShopProductDetail>('/admin/products', data),
    adminUpdateProduct: (id: string, data: AdminShopProductPayload) =>
      sdk.http.request<ShopProductDetail>(`/admin/products/${encodeURIComponent(id)}`, { method: 'PUT', data }),
    adminProductTypes: () =>
      sdk.http.get<ShopProductType[]>('/admin/product-types'),
    adminUserOptions: (keyword = '', page = 1, size = 10) =>
      sdk.http.get<Page<ShopUserOption>>(`/admin/user-options${query({ keyword, page, size })}`),
    adminSettings: () =>
      sdk.http.get<ShopSettings>('/admin/settings'),
    saveAdminSettings: (data: ShopSettingsPayload) =>
      sdk.http.request<ShopSettings>('/admin/settings', { method: 'PUT', data }),
    adminSetShelf: (id: string, onShelf: boolean) =>
      sdk.http.post<ShopProductSummary>(`/admin/products/${encodeURIComponent(id)}/shelf`, { onShelf }),
    adminDeleteProduct: (id: string) =>
      sdk.http.request<{ deleted: boolean }>(`/admin/products/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    adminOrders: (keyword = '', buyerId = '', sellerId = '', status = '', page = 1, size = 10) =>
      sdk.http.get<Page<ShopOrder>>(`/admin/orders${query({ keyword, buyerId, sellerId, status, page, size })}`),
    adminOrderDetail: (id: string) =>
      sdk.http.get<ShopOrder>(`/admin/orders/${encodeURIComponent(id)}`),
    adminRedeliver: (id: string) =>
      sdk.http.post<ShopOrder>(`/admin/orders/${encodeURIComponent(id)}/redeliver`),
    adminRefund: (id: string) =>
      sdk.http.post<ShopOrder>(`/admin/orders/${encodeURIComponent(id)}/refund`),
  }
}

export type ShopApi = ReturnType<typeof createShopApi>
