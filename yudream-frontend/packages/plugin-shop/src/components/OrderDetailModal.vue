<script setup lang="ts">
import type { ShopOrder } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaButton, FaIcon, FaModal, FaTag, useFaToast } from '@yudream/components'
import { computed, ref } from 'vue'
import { createShopApi } from '../api/shop-api'
import { displayUserName, formatAmount, formatTime, orderStatusTag } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  open: boolean
  order: ShopOrder | null
  loading?: boolean
  /** 是否展示发货内容（买家与管理员视角） */
  showContent?: boolean
  /** 当前查看者是否为买家本人（可核验发货凭证） */
  verifyable?: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  verified: [order: ShopOrder]
}>()

const modalOpen = computed({
  get: () => props.open,
  set: value => emit('update:open', value),
})

const api = createShopApi(props.sdk)
const toast = useFaToast()

const copied = ref(false)
const copiedVoucher = ref(false)
const verifying = ref(false)

/** 发货中且有凭证的订单出现核验入口；已核验或无凭证不显示 */
const canVerify = computed(() =>
  props.verifyable
  && !!props.order
  && props.order.status === 'DELIVERING'
  && !!props.order.deliveryVoucher
  && !props.order.voucherVerified,
)

const productImageUrl = computed(() =>
  props.order?.productImage ? props.sdk.files.assetUrl(props.order.productImage) : '',
)

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
          <span class="shop-order-detail-label">买家</span>
          <span>{{ displayUserName(order.buyer) }}</span>
          <span class="shop-order-detail-label">卖家</span>
          <span>{{ displayUserName(order.seller) }}</span>
          <span class="shop-order-detail-label">商品类型</span>
          <span>{{ order.productTypeDisplayName || order.productType }}</span>
          <span class="shop-order-detail-label">下单时间</span>
          <span>{{ formatTime(order.createdAt) }}</span>
          <span v-if="order.deliveredAt" class="shop-order-detail-label">发货时间</span>
          <span v-if="order.deliveredAt">{{ formatTime(order.deliveredAt) }}</span>
          <span v-if="order.walletTransactionId" class="shop-order-detail-label">支付流水</span>
          <span v-if="order.walletTransactionId" class="break-all">{{ order.walletTransactionId }}</span>
          <span v-if="order.refundTransactionId" class="shop-order-detail-label">退款流水</span>
          <span v-if="order.refundTransactionId" class="break-all">{{ order.refundTransactionId }}</span>
        </div>
        <div v-if="order.deliveryMessage" class="shop-order-delivery-message">
          <FaIcon name="i-ri:truck-line" />
          <span>{{ order.deliveryMessage }}</span>
        </div>
        <div v-if="order.deliveryVoucher" class="grid gap-2">
          <div class="flex items-center gap-2">
            <span class="text-sm font-semibold">发货凭证</span>
            <FaTag v-if="order.voucherVerified" variant="secondary">买家已核验</FaTag>
            <FaTag v-else-if="order.status === 'DELIVERING'" variant="outline">待买家核验</FaTag>
          </div>
          <pre class="shop-order-delivery-content">{{ order.deliveryVoucher }}</pre>
          <div class="flex flex-wrap gap-2">
            <FaButton
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
      </template>
      <div v-else-if="!loading" class="py-6 text-center text-sm text-muted-foreground">
        订单不存在或无权查看
      </div>
    </div>
  </FaModal>
</template>
