<script setup lang="ts">
import type { ShopOrder } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaButton, FaIcon, FaModal, FaTag, FaTextarea, useFaImagePreview, useFaToast } from '@yudream/components'
import { computed, ref, watch } from 'vue'
import { createShopApi } from '../api/shop-api'
import { displayOrderSeller, displayUserName, formatAmount, formatTime, hasDeliveryProof, hasOrderTradeFee, orderSellerAmount, orderStatusTag, settlementDescription, settlementTag } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  open: boolean
  order: ShopOrder | null
  loading?: boolean
  /** 是否展示发货内容（买家与管理员视角） */
  showContent?: boolean
  /** 当前查看者是否为买家本人（可核验发货凭证、可取消未发货订单） */
  buyerView?: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  verified: [order: ShopOrder]
  cancelled: [order: ShopOrder]
}>()

const modalOpen = computed({
  get: () => props.open,
  set: value => emit('update:open', value),
})

const api = createShopApi(props.sdk)
const toast = useFaToast()
const imagePreview = useFaImagePreview()

const copied = ref(false)
const copiedVoucher = ref(false)
const verifying = ref(false)
const canceling = ref(false)
const cancelConfirming = ref(false)
const cancelReason = ref('')

/** 发货凭证图片（落库相对路径 → 当前环境可访问地址） */
const proofFullUrls = computed(() => (props.order?.deliveryProofs ?? []).map(url => props.sdk.files.assetUrl(url)))
const proofThumbUrls = computed(() => (props.order?.deliveryProofs ?? []).map(url => props.sdk.files.thumbUrl(url, { maxEdge: 240 })))
const hasVoucher = computed(() => hasDeliveryProof(props.order))

/** 只有真正收过交易手续费的订单（玩家市场）才显示手续费与卖家实收两行 */
const tradeFeeApplied = computed(() => hasOrderTradeFee(props.order))
const sellerReceived = computed(() => orderSellerAmount(props.order))

/** 发货中且已有凭证（文本或图片）的订单出现核验入口；已核验或无凭证不显示 */
const canVerify = computed(() =>
  props.buyerView
  && !!props.order
  && props.order.status === 'DELIVERING'
  && hasVoucher.value
  && !props.order.voucherVerified,
)

/** 买家视角：后端 cancellable 为 true（未发货、未终结）时允许取消并原路退款 */
const canCancel = computed(() => !!props.buyerView && props.order?.cancellable === true)

/** 取消订单的 deliveryMessage 记录的是取消原因 */
const cancelNotice = computed(() => {
  const reason = props.order?.deliveryMessage?.trim()
  return reason && reason !== '买家取消订单'
    ? `买家已取消该订单，支付金额已原路退回。取消原因：${reason}`
    : '买家已取消该订单，支付金额已原路退回。'
})

const productImageUrl = computed(() =>
  props.order?.productImage ? props.sdk.files.assetUrl(props.order.productImage) : '',
)

watch(() => props.open, (open) => {
  if (!open) {
    cancelConfirming.value = false
    cancelReason.value = ''
    copied.value = false
    copiedVoucher.value = false
  }
})

function openProof(index: number) {
  imagePreview.open(proofFullUrls.value, index)
}

async function copyText(text: string, target: 'content' | 'voucher') {
  try {
    await navigator.clipboard.writeText(text)
    if (target === 'content') {
      copied.value = true
      setTimeout(() => { copied.value = false }, 1500)
    }
    else {
      copiedVoucher.value = true
      setTimeout(() => { copiedVoucher.value = false }, 1500)
    }
  }
  catch {
    // 剪贴板不可用时静默，内容本身已在弹窗中可见
  }
}

async function verifyReceipt() {
  if (!props.order || verifying.value) {
    return
  }
  verifying.value = true
  try {
    const updated = await api.verifyDelivery(props.order.id)
    toast.success('已核验收货，交易完成')
    emit('verified', updated)
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '核验失败')
  }
  finally {
    verifying.value = false
  }
}

async function submitCancel() {
  if (!props.order || canceling.value) {
    return
  }
  canceling.value = true
  try {
    const updated = await api.cancelOrder(props.order.id, cancelReason.value)
    toast.success('订单已取消，支付金额已原路退回')
    cancelConfirming.value = false
    cancelReason.value = ''
    emit('cancelled', updated)
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '取消订单失败')
  }
  finally {
    canceling.value = false
  }
}
</script>

<template>
  <FaModal v-model="modalOpen" title="订单详情" :show-confirm-button="false">
    <div v-loading="loading" class="grid gap-3">
      <template v-if="order">
        <div class="shop-order-detail-head">
          <img
            v-if="productImageUrl"
            :src="productImageUrl"
            :alt="order.productTitle"
            class="shop-order-detail-cover"
            @error="($event.target as HTMLImageElement).style.display = 'none'"
          >
          <div class="grid gap-1">
            <div class="flex flex-wrap items-center gap-2">
              <span class="text-base font-semibold">{{ order.productTitle }}</span>
              <FaTag :variant="orderStatusTag(order.status).variant">
                {{ order.statusText || orderStatusTag(order.status).text }}
              </FaTag>
            </div>
            <span class="text-xs text-muted-foreground">订单号 {{ order.id }}</span>
          </div>
        </div>
        <div class="shop-order-detail-grid">
          <span class="shop-order-detail-label">金额</span>
          <span>{{ formatAmount(order.totalAmount) }} {{ order.assetCode }}（单价 {{ formatAmount(order.price) }} × {{ order.quantity }}）</span>
          <template v-if="tradeFeeApplied">
            <span class="shop-order-detail-label">交易手续费</span>
            <span>
              {{ formatAmount(order.feeAmount) }} {{ order.assetCode }}
              <span class="text-xs text-muted-foreground">（卖家实收 {{ formatAmount(sellerReceived) }} {{ order.assetCode }}，退款时手续费一并退回买家）</span>
            </span>
          </template>
          <span class="shop-order-detail-label">结算方式</span>
          <span class="flex flex-wrap items-center gap-2">
            <FaTag :variant="settlementTag(order.settlement).variant">
              {{ settlementTag(order.settlement).text }}
            </FaTag>
            <span class="text-xs text-muted-foreground">{{ settlementDescription(order.settlement) }}</span>
          </span>
          <span class="shop-order-detail-label">买家</span>
          <span>{{ displayUserName(order.buyer) }}</span>
          <span class="shop-order-detail-label">卖家</span>
          <span>{{ displayOrderSeller(order) }}</span>
          <span class="shop-order-detail-label">商品类型</span>
          <span>{{ order.productTypeDisplayName || order.productType }}</span>
          <template v-if="order.variantName">
            <span class="shop-order-detail-label">型号</span>
            <span>{{ order.variantName }}</span>
          </template>
          <span class="shop-order-detail-label">下单时间</span>
          <span>{{ formatTime(order.createdAt) }}</span>
          <span v-if="order.deliveredAt" class="shop-order-detail-label">发货时间</span>
          <span v-if="order.deliveredAt">{{ formatTime(order.deliveredAt) }}</span>
          <span v-if="order.walletTransactionId" class="shop-order-detail-label">支付流水</span>
          <span v-if="order.walletTransactionId" class="break-all">{{ order.walletTransactionId }}</span>
          <span v-if="order.refundTransactionId" class="shop-order-detail-label">退款流水</span>
          <span v-if="order.refundTransactionId" class="break-all">{{ order.refundTransactionId }}</span>
        </div>
        <div v-if="order.deliveryMessage && order.status !== 'CANCELLED'" class="shop-order-delivery-message">
          <FaIcon name="i-ri:truck-line" />
          <span>{{ order.deliveryMessage }}</span>
        </div>
        <div v-if="order.status === 'CANCELLED'" class="shop-order-delivery-message">
          <FaIcon name="i-ri:close-circle-line" />
          <span>{{ cancelNotice }}</span>
        </div>
        <div v-if="hasVoucher" class="grid gap-2">
          <div class="flex items-center gap-2">
            <span class="text-sm font-semibold">发货凭证</span>
            <FaTag v-if="order.voucherVerified" variant="secondary">买家已核验</FaTag>
            <FaTag v-else-if="order.status === 'DELIVERING'" variant="outline">待买家核验</FaTag>
          </div>
          <pre v-if="order.deliveryVoucher" class="shop-order-delivery-content">{{ order.deliveryVoucher }}</pre>
          <div v-if="proofFullUrls.length" class="shop-proof-grid">
            <button
              v-for="(url, index) in proofFullUrls"
              :key="url"
              type="button"
              class="shop-proof-item"
              :title="`查看第 ${index + 1} 张发货凭证`"
              @click="openProof(index)"
            >
              <img
                :src="proofThumbUrls[index]"
                :alt="`发货凭证 ${index + 1}`"
                loading="lazy"
                @error="($event.target as HTMLImageElement).src = url"
              >
            </button>
          </div>
          <div class="flex flex-wrap gap-2">
            <FaButton
              v-if="order.deliveryVoucher"
              size="sm"
              variant="outline"
              @click="copyText(order.deliveryVoucher!, 'voucher')"
            >
              <FaIcon :name="copiedVoucher ? 'i-ri:check-line' : 'i-ri:file-copy-line'" />
              {{ copiedVoucher ? '已复制' : '复制凭证' }}
            </FaButton>
            <FaButton v-if="canVerify" size="sm" :loading="verifying" @click="verifyReceipt">
              <FaIcon name="i-ri:shield-check-line" />
              核验收货
            </FaButton>
          </div>
        </div>
        <div v-if="showContent && order.deliveryContent" class="grid gap-2">
          <span class="text-sm font-semibold">发货内容</span>
          <pre class="shop-order-delivery-content">{{ order.deliveryContent }}</pre>
          <div>
            <FaButton size="sm" variant="outline" @click="copyText(order.deliveryContent!, 'content')">
              <FaIcon :name="copied ? 'i-ri:check-line' : 'i-ri:file-copy-line'" />
              {{ copied ? '已复制' : '复制内容' }}
            </FaButton>
          </div>
        </div>
        <div v-if="canCancel" class="shop-order-cancel">
          <div class="flex flex-wrap items-center gap-2">
            <FaButton size="sm" variant="destructive" :disabled="canceling" @click="cancelConfirming = true">
              <FaIcon name="i-ri:close-circle-line" />
              取消订单
            </FaButton>
            <span class="text-xs text-muted-foreground">
              卖家提交发货凭证前可取消，已支付金额原路退回。
            </span>
          </div>
          <div v-if="cancelConfirming" class="shop-order-cancel-confirm">
            <span class="text-sm font-medium">确认取消该订单吗？</span>
            <span class="text-xs text-muted-foreground">取消后已支付金额将原路退回，操作不可撤销。</span>
            <FaTextarea
              v-model="cancelReason"
              placeholder="取消原因（可选，便于卖家与管理员了解情况）"
              :rows="2"
            />
            <div class="flex justify-end gap-2">
              <FaButton size="sm" variant="outline" :disabled="canceling" @click="cancelConfirming = false">
                再想想
              </FaButton>
              <FaButton size="sm" variant="destructive" :loading="canceling" @click="submitCancel">
                确认取消
              </FaButton>
            </div>
          </div>
        </div>
      </template>
      <div v-else-if="!loading" class="py-6 text-center text-sm text-muted-foreground">
        订单不存在或无权查看
      </div>
    </div>
  </FaModal>
</template>
