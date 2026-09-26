import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TimeValue } from '../types'
import { POINTS_REDEEM_TYPE } from '../types'

export function errorMessage(error: unknown, fallback = '操作失败') {
  if (error && typeof error === 'object') {
    const data = error as { response?: { data?: { message?: string } }, message?: string }
    return data.response?.data?.message || data.message || fallback
  }
  return fallback
}

export function normalizeTime(value: TimeValue) {
  if (value == null || value === '') {
    return 0
  }
  if (Array.isArray(value)) {
    const [year, month, day, hour = 0, minute = 0, second = 0, nano = 0] = value
    return new Date(year, month - 1, day, hour, minute, second, Math.floor(nano / 1000000)).getTime()
  }
  const numeric = Number(value)
  if (Number.isFinite(numeric)) {
    return numeric < 10000000000 ? numeric * 1000 : numeric
  }
  return Number.isFinite(Date.parse(String(value))) ? Date.parse(String(value)) : 0
}

export function formatTime(value: TimeValue) {
  const timestamp = normalizeTime(value)
  return timestamp ? new Date(timestamp).toLocaleString('zh-CN', { hour12: false }) : '-'
}

export function formatAmount(value: string | number | undefined | null) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) {
    return '-'
  }
  return numeric.toFixed(2).replace(/\.?0+$/, '')
}

// 上传结果在开发环境会带 /proxy 前缀或绝对 origin，落库前统一还原成 /api/files/... 相对路径
export function normalizeFileUrl(value: string | null | undefined) {
  const raw = (value || '').trim()
  if (!raw) {
    return ''
  }
  return raw
    .replace(/^https?:\/\/[^/]+(?=\/api\/files\/)/i, '')
    .replace(/^\/proxy(?=\/api\/files\/)/i, '')
}

// 渲染前把内容里的文件引用解析成当前环境可访问的地址
export function resolveMarkdownFileUrls(sdk: YuDreamPluginSdk, text: string | null | undefined) {
  const raw = (text || '')
    .replace(/https?:\/\/[^/\s)"]+(?=\/api\/files\/)/gi, '')
    .replace(/\/proxy(?=\/api\/files\/)/gi, '')
  if (!raw) {
    return ''
  }
  return raw.replace(
    /(\]\(|src=")(\/api\/files\/[^)\s"]+)(\)|")/gi,
    (_match, prefix: string, url: string, suffix: string) => `${prefix}${sdk.files.assetUrl(url)}${suffix}`,
  )
}

/** Markdown 编辑器图片上传：落库为 /api/files/... 相对路径。 */
export async function uploadMarkdownImage(sdk: YuDreamPluginSdk, file: File) {
  if (!file.type.startsWith('image/')) {
    throw new Error('请选择图片文件')
  }
  const uploaded = await sdk.files.uploadImage(file, { module: 'shop', publicAccess: true })
  return normalizeFileUrl(uploaded.assetUrl || uploaded.url)
}

export function displayUserName(user?: { nickname?: string, username?: string, id?: string } | null) {
  return user?.nickname || user?.username || (user?.id ? `用户 ${user.id}` : '未知用户')
}

/** 平台归属标记：官方（消耗式结算）商品与订单没有归属用户，统一记在该值下 */
export const PLATFORM_OWNER_ID = 'system'

/**
 * 是否为官方投放：消耗式结算（BURN，如积分兑换）或归属平台。
 * 这类商品/订单没有归属用户，展示为后台配置的官方展示名，也不会出现在任何人的「我的商品」「我的出售」里。
 */
export function isPlatformOwned(ownerId?: string | null, settlement?: string | null) {
  return settlement === 'BURN' || !ownerId || ownerId === PLATFORM_OWNER_ID
}

type OwnerSource = {
  platformOwned?: boolean | null
  ownerLabel?: string | null
  sellerLabel?: string | null
  owner?: { nickname?: string, username?: string, id?: string } | null
  seller?: { nickname?: string, username?: string, id?: string } | null
  ownerId?: string | null
  settlement?: string | null
}

/**
 * 归属展示名：官方投放显示商店设置里的官方展示名（后台可改成任意名称，留空表示不显示该行，
 * 调用方据此隐藏整行）；真实归属用户显示用户名。
 */
export function displayOwnerLabel(source?: OwnerSource | null) {
  if (!source) {
    return ''
  }
  const platform = source.platformOwned ?? isPlatformOwned(source.ownerId ?? source.seller?.id ?? null,
    source.settlement)
  if (!platform) {
    return displayUserName(source.owner ?? source.seller)
  }
  return (source.ownerLabel ?? source.sellerLabel ?? '').trim()
}

/** 商品归属展示 */
export function displayProductOwner(product?: OwnerSource | null) {
  return displayOwnerLabel(product)
}

/** 订单卖家展示：消耗式订单没有卖家账号，显示配置的官方展示名 */
export function displayOrderSeller(order?: OwnerSource | null) {
  return displayOwnerLabel(order)
}

/** 权限判断与宿主 SPI 对齐：超管角色下发的是字面量 "*"，不展开具体权限码 */
export function hasPermission(permissions: string[] | undefined | null, code: string) {
  if (!permissions?.length) {
    return false
  }
  return permissions.includes('*') || permissions.includes(code)
}

export function productStatusTag(status: string) {
  return status === 'ON_SHELF'
    ? { variant: 'default' as const, text: '上架中' }
    : { variant: 'secondary' as const, text: '已下架' }
}

export function orderStatusTag(status: string): { variant: 'default' | 'secondary' | 'destructive' | 'outline', text: string } {
  switch (status) {
    case 'DELIVERED':
      return { variant: 'default', text: '已发货' }
    case 'DELIVERING':
      return { variant: 'outline', text: '发货中' }
    case 'PAID':
      return { variant: 'outline', text: '已支付' }
    case 'DELIVERY_FAILED':
      return { variant: 'destructive', text: '发货失败' }
    case 'REFUNDED':
      return { variant: 'secondary', text: '已退款' }
    case 'CANCELLED':
      return { variant: 'secondary', text: '已取消' }
    default:
      return { variant: 'secondary', text: status || '未知' }
  }
}

/** 订单结算方式标签：SELLER 转给卖家 / BURN 消耗（扣积分、不给卖家） */
export function settlementTag(settlement?: string | null): { variant: 'default' | 'secondary' | 'destructive' | 'outline', text: string } {
  return settlement === 'BURN'
    ? { variant: 'outline', text: '消耗' }
    : { variant: 'secondary', text: '转给卖家' }
}

/** 结算方式说明，用于结算标签的悬浮提示 */
export function settlementDescription(settlement?: string | null) {
  return settlement === 'BURN'
    ? '消耗式结算：支付时直接扣减买家资产，不转给卖家；取消或退款时原路退回买家。'
    : '转账式结算：买家支付后货款经钱包转给卖家。'
}

/** 商品类型展示名：内置积分兑换类型即使缺少 typeDisplayName 也显示「积分兑换」 */
export function productTypeLabel(type?: string | null, typeDisplayName?: string | null) {
  if (typeDisplayName) {
    return typeDisplayName
  }
  if (type === POINTS_REDEEM_TYPE) {
    return '积分兑换'
  }
  return type || '未知类型'
}

export function isPointsRedeem(type?: string | null) {
  return type === POINTS_REDEEM_TYPE
}

/** 每人限购文案：0 表示不限 */
export function perUserLimitText(limit: number | string | null | undefined) {
  const value = Number(limit)
  return Number.isFinite(value) && value > 0 ? String(value) : '不限'
}

/** 订单是否已有发货凭证：文本与图片至少一项非空 */
export function hasDeliveryProof(order?: { deliveryVoucher?: string | null, deliveryProofs?: string[] | null } | null) {
  if (!order) {
    return false
  }
  return !!order.deliveryVoucher?.trim() || (order.deliveryProofs?.length ?? 0) > 0
}
