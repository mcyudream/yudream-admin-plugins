/** 共享视图模型：Java Long / 雪花 ID 一律 string；金额为 string|number 由页面格式化。 */

export type TimeValue = string | number | number[] | null | undefined

export interface Page<T> {
  records: T[]
  total: number | string
}

export interface ShopUserBrief {
  id: string
  username?: string
  nickname?: string
  avatar?: string
}

export interface ShopProductSummary {
  id: string
  ownerId: string
  owner?: ShopUserBrief | null
  title: string
  summary?: string
  coverImage?: string
  assetCode: string
  assetSymbol?: string
  price: string | number
  stock: number
  soldCount: number | string
  type: string
  typeDisplayName?: string
  status: string
  statusText?: string
  createdAt: TimeValue
  updatedAt: TimeValue
}

export interface ShopProductDetail extends ShopProductSummary {
  images: string[]
  descriptionMd?: string
  typeConfig?: Record<string, unknown> | null
}

export interface ShopOrder {
  id: string
  productId: string
  productTitle: string
  productImage?: string
  productType: string
  productTypeDisplayName?: string
  buyer?: ShopUserBrief | null
  seller?: ShopUserBrief | null
  assetCode: string
  price: string | number
  quantity: number
  totalAmount: string | number
  status: string
  statusText?: string
  walletTransactionId?: string
  refundTransactionId?: string
  deliveryMessage?: string
  deliveryContent?: string | null
  /** 卖家发货凭证；voucherVerified 为买家核验标记 */
  deliveryVoucher?: string | null
  voucherVerified?: boolean
  createdAt: TimeValue
  paidAt?: TimeValue
  deliveredAt?: TimeValue
}

export interface ShopCurrency {
  code: string
  name?: string
  symbol?: string
  scale?: number
}

export interface ShopProductType {
  type: string
  displayName: string
  description?: string
  builtin: boolean
}

/** 管理端用户选项（归属用户选择器）：label 为选择器展示文本 */
export interface ShopUserOption {
  id: string
  username?: string
  nickname?: string
  avatar?: string
  label: string
}

export interface ShopProductPayload {
  title: string
  summary?: string
  descriptionMd?: string
  images: string[]
  assetCode: string
  price: number
  stock: number
  type: string
  typeConfig: Record<string, unknown>
}

export interface AdminShopProductPayload extends ShopProductPayload {
  /** 归属用户 ID，留空归属管理员自己 */
  ownerId?: string
}

export interface ShopSettings {
  allowUserPublish: boolean
  publishAssetCode: string | null
  publishMinBalance: string | number
  allowedAssetCodes: string[]
  walletAvailable: boolean
  assetOptions: ShopCurrency[]
}

export interface ShopSettingsPayload {
  allowUserPublish: boolean
  publishAssetCode: string
  publishMinBalance: number
  allowedAssetCodes: string[]
}

export interface PublishQualification {
  allowed: boolean
  reason?: string | null
  publishAssetCode?: string | null
  publishMinBalance?: string | number | null
  balance?: string | null
  walletAvailable: boolean
}
