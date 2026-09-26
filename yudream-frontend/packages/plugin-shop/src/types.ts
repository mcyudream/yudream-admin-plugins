/** 共享视图模型：Java Long / 雪花 ID 一律 string；金额为 string|number 由页面格式化。 */

export type TimeValue = string | number | number[] | null | undefined

/** 内置商品类型：积分兑换。支付即扣积分（消耗）、需提交发货凭证、买家核验后完成。 */
export const POINTS_REDEEM_TYPE = 'POINTS_REDEEM'

/** 订单结算方式：SELLER 转给卖家 / BURN 消耗（扣买家资产、不产生收款方）。 */
export type ShopSettlement = 'SELLER' | 'BURN'

/** 玩家市场交易手续费的收款方式：BURN 销毁 / PLATFORM 转给指定平台用户。 */
export type ShopTradeFeePayee = 'BURN' | 'PLATFORM'

/** 单笔发货凭证最多可提交的图片数量，与后端 ShopOrder.MAX_DELIVERY_PROOFS 对齐。 */
export const MAX_DELIVERY_PROOFS = 6

/** 发货凭证文本最大长度，与后端 ShopOrder.MAX_VOUCHER_LENGTH 对齐。 */
export const MAX_VOUCHER_LENGTH = 500

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
  /** 每人限购数量，0 表示不限；积分兑换类商品即「每人限兑」 */
  perUserLimit: number
  type: string
  typeDisplayName?: string
  /** 结算方式：SELLER 为玩家商品（归属真实用户）；BURN 为官方消耗商品（归属平台，没有归属用户） */
  settlement?: ShopSettlement
  /** 是否官方投放（平台归属、没有归属用户） */
  platformOwned?: boolean
  /** 官方商品的归属展示名（可在商店设置里改，null 表示不显示归属行） */
  ownerLabel?: string | null
  /** 官方商品的归属头像（商店设置里配置，null 表示用展示名首字占位） */
  ownerAvatar?: string | null
  /** 可选型号：非空时价格与库存以型号为准（price 为最低价、stock 为合计） */
  variants?: ShopVariant[]
  /** 展示排序权重（管理端可调，大的在前；0 表示按创建时间倒序） */
  sortOrder?: number
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
  /** 玩家市场交易手续费（十进制字符串）：买家总支出仍为 totalAmount，没有手续费的订单为 0 */
  feeAmount?: string | number | null
  /** 卖家实收（= totalAmount − feeAmount）；旧订单与消耗类订单等于 totalAmount */
  sellerAmount?: string | number | null
  /** 下单时选中的型号（商品无型号时为 null） */
  variantId?: string | null
  variantName?: string | null
  status: string
  statusText?: string
  /** 结算方式：SELLER 转给卖家 / BURN 消耗；历史订单缺省按 SELLER 处理 */
  settlement: ShopSettlement
  /** 没有卖家账号的消耗式订单的归属展示名，null 表示不显示归属行 */
  sellerLabel?: string | null
  walletTransactionId?: string
  refundTransactionId?: string
  deliveryMessage?: string
  deliveryContent?: string | null
  /** 卖家发货凭证文本；voucherVerified 为买家核验标记 */
  deliveryVoucher?: string | null
  /** 卖家发货凭证图片（已上传文件地址，最多 6 张），与 deliveryVoucher 至少一个非空 */
  deliveryProofs: string[]
  voucherVerified?: boolean
  /** 买家视角：未发货、未终结为 true，可自行取消并原路退款 */
  cancellable: boolean
  createdAt: TimeValue
  paidAt?: TimeValue
  deliveredAt?: TimeValue
}

/** 商品型号：同一商品可选不同型号，各自带价格与库存 */
export interface ShopVariant {
  id: string
  name: string
  price: string | number
  stock: number
  image?: string | null
  soldOut?: boolean
}

/** 型号请求体：id 留空表示新建 */
export interface ShopVariantPayload {
  id?: string
  name: string
  price: number
  stock: number
  image?: string
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
  /** 每人限购数量，0 表示不限 */
  perUserLimit: number
  type: string
  typeConfig: Record<string, unknown>
  /** 可选型号；非空时以型号价格与库存为准 */
  variants: ShopVariantPayload[]
}

export interface AdminShopProductPayload extends ShopProductPayload {
  /** 归属用户 ID，留空归属管理员自己 */
  ownerId?: string
  /** 展示排序权重（管理端可调，0 到 9999，大的在前） */
  sortOrder?: number
}

export interface ShopSettings {
  allowUserPublish: boolean
  publishAssetCode: string | null
  publishMinBalance: string | number
  allowedAssetCodes: string[]
  walletAvailable: boolean
  /** 官方（平台归属）商品的归属展示名，空串表示界面不显示归属行 */
  platformOwnerName: string
  /** 官方归属头像（平台上传文件地址），null 表示不设置 */
  platformOwnerAvatar: string | null
  /** 玩家市场交易手续费开关（官方积分兑换等消耗类商品不收费） */
  tradeFeeEnabled: boolean
  /** 手续费费率（成交额百分比，0~100 的十进制字符串） */
  tradeFeeRate: string | number
  /** 最低手续费（十进制字符串，0 表示不设下限） */
  tradeFeeMinAmount: string | number
  /** 手续费收款方式：销毁（默认，不需要额外账号）或转给指定平台用户 */
  tradeFeePayee: ShopTradeFeePayee
  /** 平台用户 ID（tradeFeePayee 为 PLATFORM 时必填） */
  tradeFeePayeeUserId: string | null
  assetOptions: ShopCurrency[]
}

export interface ShopSettingsPayload {
  allowUserPublish: boolean
  publishAssetCode: string
  publishMinBalance: number
  platformOwnerName: string
  platformOwnerAvatar: string
  allowedAssetCodes: string[]
  tradeFeeEnabled: boolean
  tradeFeeRate: string
  tradeFeeMinAmount: string
  tradeFeePayee: ShopTradeFeePayee
  tradeFeePayeeUserId: string
}

export interface PublishQualification {
  allowed: boolean
  reason?: string | null
  publishAssetCode?: string | null
  publishMinBalance?: string | number | null
  balance?: string | null
  walletAvailable: boolean
}
