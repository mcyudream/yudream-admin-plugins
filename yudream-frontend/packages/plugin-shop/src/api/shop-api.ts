import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { Page, PublishQualification, ShopCurrency, ShopOrder, ShopProductDetail, ShopProductPayload, ShopProductSummary, ShopProductType, ShopSettlement, ShopUserOption, AdminShopProductPayload, ShopSettings, ShopSettingsPayload } from '../types'
import { MAX_DELIVERY_PROOFS } from '../types'

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

/** 发货凭证请求体：空值不下发，图片按后端上限截断（对齐 ShopOrder.MAX_DELIVERY_PROOFS）。 */
function deliveryPayload(data: { voucher?: string, proofs?: string[] }) {
  const payload: { voucher?: string, proofs?: string[] } = {}
  const voucher = (data.voucher || '').trim()
  if (voucher) {
    payload.voucher = voucher
  }
  const proofs = (data.proofs || []).filter(Boolean).slice(0, MAX_DELIVERY_PROOFS)
  if (proofs.length) {
    payload.proofs = proofs
  }
  return payload
}

export function createShopApi(sdk: YuDreamPluginSdk) {
  return {
    // ---------- 广场（view）：settlement 区分「玩家市场」(SELLER) 与「积分商城」(BURN) ----------
    plazaProducts: (keyword = '', assetCode = '', page = 1, size = 12, settlement: ShopSettlement = 'SELLER') =>
      sdk.http.get<Page<ShopProductSummary>>(`/plaza/products${query({ keyword, assetCode, page, size, settlement })}`),
    plazaProductDetail: (id: string) =>
      sdk.http.get<ShopProductDetail>(`/plaza/products/${encodeURIComponent(id)}`),
    plazaCurrencies: (settlement: ShopSettlement = 'SELLER') =>
      sdk.http.get<ShopCurrency[]>(`/plaza/currencies${query({ settlement })}`),

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
    /** 购买：商品有型号时必须传所选型号 id（价格与库存以该型号为准） */
    purchase: (productId: string, quantity: number, variantId?: string) =>
      sdk.http.post<ShopOrder>('/me/orders', variantId ? { productId, variantId, quantity } : { productId, quantity }),
    myPurchases: (page = 1, size = 10) =>
      sdk.http.get<Page<ShopOrder>>(`/me/purchases${query({ page, size })}`),
    myOrderDetail: (id: string) =>
      sdk.http.get<ShopOrder>(`/me/orders/${encodeURIComponent(id)}`),
    /**
     * 提交/更新发货凭证：文本 voucher 与图片 proofs（已上传文件地址）至少一个非空，
     * proofs 最多 6 张（后端同样按 ShopOrder.MAX_DELIVERY_PROOFS 截断）。
     */
    submitVoucher: (id: string, data: { voucher?: string, proofs?: string[] }) =>
      sdk.http.post<ShopOrder>(`/me/orders/${encodeURIComponent(id)}/voucher`, deliveryPayload(data)),
    /**
     * 管理员代发货：可为任意归属的订单提交凭证（manage 权限）。
     *
     * 覆盖两类订单：归属平台的官方订单（迁移自积分商城的存量兑换、积分兑换商品，没有自然人卖家），
     * 以及管理员代他人上架、需要管理员代为发货的订单。
     */
    adminSubmitDelivery: (id: string, data: { voucher?: string, proofs?: string[] }) =>
      sdk.http.post<ShopOrder>(`/admin/orders/${encodeURIComponent(id)}/delivery`, deliveryPayload(data)),
    verifyDelivery: (id: string) =>
      sdk.http.post<ShopOrder>(`/me/orders/${encodeURIComponent(id)}/verify`),
    /** 买家取消订单：仅未发货、未终结的订单可取消，已支付金额原路退回 */
    cancelOrder: (id: string, reason?: string) => {
      const text = (reason || '').trim()
      return sdk.http.post<ShopOrder>(
        `/me/orders/${encodeURIComponent(id)}/cancel`,
        text ? { reason: text } : {},
      )
    },
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
    /** 管理端排序：direction 取 UP / DOWN / TOP，只在同一结算方式（玩家侧或官方侧）内生效 */
    adminMoveProduct: (id: string, direction: 'UP' | 'DOWN' | 'TOP') =>
      sdk.http.post<ShopProductSummary>(`/admin/products/${encodeURIComponent(id)}/sort`, { direction }),
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
