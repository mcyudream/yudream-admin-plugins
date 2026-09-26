<script setup lang="ts">
import type { ShopOrder } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { MAX_DELIVERY_PROOFS, MAX_VOUCHER_LENGTH } from '../types'
import { FaButton, FaIcon, FaImageUpload, FaModal, FaTextarea, useFaToast } from '@yudream/components'
import { computed, ref, watch } from 'vue'
import { createShopApi } from '../api/shop-api'
import { displayUserName, errorMessage, normalizeFileUrl } from '../composables/utils'

/**
 * 卖家/管理员提交（或更新）发货凭证：文本说明 + 最多 6 张凭证图片，
 * 两者至少一个非空；提交成功后订单进入「待买家核验」。
 */
const props = defineProps<{
  sdk: YuDreamPluginSdk
  open: boolean
  order: ShopOrder | null
  /** 管理员代发货：走 /admin/orders/{id}/delivery，可对非本人名下的订单（含平台归属的迁移订单）提交凭证 */
  admin?: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  submitted: [order: ShopOrder]
}>()

const modalOpen = computed({
  get: () => props.open,
  set: value => emit('update:open', value),
})

const api = createShopApi(props.sdk)
const toast = useFaToast()

const voucherText = ref('')
const uploading = ref(false)
const submitting = ref(false)
const hasExistingVoucher = ref(false)

// FaImageUpload 通过 push/splice 原地改数组，不触发 update:modelValue；
// 用独立 ref 承接展示地址，再 watch 同步回落库相对路径（与商品图片同一套处理）
const proofList = ref<string[]>([])
const proofs = ref<string[]>([])

watch(proofList, (list) => {
  proofs.value = list.map(item => normalizeFileUrl(item)).filter(Boolean).slice(0, MAX_DELIVERY_PROOFS)
}, { deep: true })

const title = computed(() => (hasExistingVoucher.value
  ? '更新发货凭证'
  : (props.admin ? '代发货：提交发货凭证' : '提交发货凭证')))
const canSubmit = computed(() => !!voucherText.value.trim() || proofs.value.length > 0)

watch(() => props.open, (open) => {
  if (open) {
    const voucher = props.order?.deliveryVoucher || ''
    const existingProofs = props.order?.deliveryProofs ?? []
    voucherText.value = voucher
    hasExistingVoucher.value = !!voucher || existingProofs.length > 0
    proofList.value = existingProofs.map(url => props.sdk.files.assetUrl(url))
    proofs.value = existingProofs.map(url => normalizeFileUrl(url)).filter(Boolean)
  }
})

async function uploadRequest(options: { file: File }) {
  uploading.value = true
  try {
    return await props.sdk.files.uploadImage(options.file, { module: 'shop', publicAccess: true })
  }
  catch (error) {
    toast.warning(errorMessage(error, '凭证图片上传失败'))
    throw error
  }
  finally {
    uploading.value = false
  }
}

function afterUpload(uploaded: { assetUrl?: string, url?: string }) {
  // 返回展示地址；落库相对路径由 proofList 的 watch 同步
  return uploaded.assetUrl || uploaded.url || ''
}

async function submit() {
  const voucher = voucherText.value.trim()
  if (!voucher && !proofs.value.length) {
    toast.warning('请填写发货凭证内容，或至少上传 1 张凭证图片')
    return
  }
  if (voucher.length > MAX_VOUCHER_LENGTH) {
    toast.warning(`发货凭证内容不能超过 ${MAX_VOUCHER_LENGTH} 个字符`)
    return
  }
  // 后端只接受平台上传的文件地址（/api/files/**），提前拦截外链，避免提交后才报错
  if (proofs.value.some(item => !item.startsWith('/api/files/'))) {
    toast.warning('凭证图片必须是平台上传的文件，请移除后重新上传')
    return
  }
  if (!props.order) {
    return
  }
  if (submitting.value) {
    return
  }
  submitting.value = true
  try {
    const payload = { voucher, proofs: proofs.value.slice(0, MAX_DELIVERY_PROOFS) }
    const updated = props.admin
      ? await api.adminSubmitDelivery(props.order.id, payload)
      : await api.submitVoucher(props.order.id, payload)
    toast.success('发货凭证已提交，等待买家核验')
    modalOpen.value = false
    emit('submitted', updated)
  }
  catch (error) {
    toast.error(errorMessage(error, '提交发货凭证失败'))
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <FaModal v-model="modalOpen" :title="title" class="sm:max-w-[560px]">
    <div class="grid gap-3">
      <div v-if="order" class="shop-order-delivery-message">
        <FaIcon name="i-ri:shopping-bag-3-line" />
        <span>
          {{ order.productTitle }} ×{{ order.quantity }}
          <template v-if="order.buyer">（买家：{{ displayUserName(order.buyer) }}）</template>
        </span>
      </div>
      <label class="grid gap-2">
        <span class="text-sm">凭证文本（可选）</span>
        <FaTextarea
          v-model="voucherText"
          :maxlength="MAX_VOUCHER_LENGTH"
          placeholder="填写发货凭证：如兑换码、卡密、游戏内交易记录说明等（500 字以内）"
          :rows="4"
        />
      </label>
      <div class="grid gap-2">
        <span class="text-sm">凭证图片（可选，最多 {{ MAX_DELIVERY_PROOFS }} 张）</span>
        <FaImageUpload
          :model-value="proofList"
          :max="MAX_DELIVERY_PROOFS"
          :width="96"
          :height="96"
          :multiple="true"
          :disabled="uploading"
          :http-request="uploadRequest"
          :after-upload="afterUpload"
        />
      </div>
      <p class="text-xs text-muted-foreground">
        凭证文本与图片至少填写一项；提交后订单进入「待买家核验」状态，买家确认无误并核验后订单完成。
        积分兑换类商品必须提交可核验的发货凭证，买家在卖家提交凭证前可自行取消并原路退回积分。
      </p>
    </div>
    <template #footer>
      <div class="flex justify-end gap-2">
        <FaButton variant="outline" :disabled="submitting" @click="modalOpen = false">取消</FaButton>
        <FaButton :loading="submitting" :disabled="!canSubmit || uploading" @click="submit">
          提交凭证
        </FaButton>
      </div>
    </template>
  </FaModal>
</template>
