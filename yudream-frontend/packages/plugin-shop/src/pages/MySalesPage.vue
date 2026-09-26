<script setup lang="ts">
import type { ShopOrder } from '../types'
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaButton, FaIcon, FaModal, FaPageHeader, FaPageMain, FaPagination, FaTable, FaTag, FaTextarea, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { createShopApi } from '../api/shop-api'
import OrderDetailModal from '../components/OrderDetailModal.vue'
import { displayUserName, errorMessage, formatAmount, formatTime, orderStatusTag } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const api = createShopApi(props.sdk)
const toast = useFaToast()

const loading = ref(false)
const rows = ref<ShopOrder[]>([])
const pager = reactive({ page: 1, size: 10, total: 0 })
const detailOpen = ref(false)
const detailLoading = ref(false)
const current = ref<ShopOrder | null>(null)

const voucherOpen = ref(false)
const voucherOrder = ref<ShopOrder | null>(null)
const voucherText = ref('')
const submittingVoucher = ref(false)
const voucherTitle = computed(() =>
  voucherOrder.value?.deliveryVoucher ? '更新发货凭证' : '提交发货凭证',
)

const columns: TableColumn<ShopOrder>[] = [
  { id: 'product', header: '商品', minWidth: 220, fixed: 'left' },
  { id: 'amount', header: '金额', width: 130 },
  { id: 'buyer', header: '买家', width: 130 },
  { id: 'status', header: '状态', width: 100 },
  { accessorKey: 'createdAt', header: '下单时间', width: 170 },
  { id: 'operation', header: '操作', width: 180, align: 'center', fixed: 'right' },
]

async function load() {
  loading.value = true
  try {
    const page = await api.mySales(pager.page, pager.size)
    rows.value = page.records ?? []
    pager.total = Number(page.total ?? 0)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载我的出售失败'))
  }
  finally {
    loading.value = false
  }
}

function imageOf(order: ShopOrder) {
  return order.productImage ? props.sdk.files.thumbUrl(order.productImage, { maxEdge: 160 }) : ''
}

async function openDetail(order: ShopOrder) {
  detailOpen.value = true
  detailLoading.value = true
  current.value = null
  try {
    current.value = await api.myOrderDetail(order.id)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载订单详情失败'))
  }
  finally {
    detailLoading.value = false
  }
}

/** 已支付/发货中的订单可提交或更新发货凭证，提交后等待买家核验 */
function openVoucher(order: ShopOrder) {
  voucherOrder.value = order
  voucherText.value = order.deliveryVoucher || ''
  voucherOpen.value = true
}

async function submitVoucher() {
  if (!voucherOrder.value) {
    return
  }
  if (!voucherText.value.trim()) {
    toast.warning('请填写发货凭证内容（如兑换码、游戏内交易截图说明等）')
    return
  }
  if (submittingVoucher.value) {
    return
  }
  submittingVoucher.value = true
  try {
    await api.submitVoucher(voucherOrder.value.id, voucherText.value.trim())
    toast.success('发货凭证已提交，等待买家核验')
    voucherOpen.value = false
    await load()
    if (detailOpen.value && current.value?.id === voucherOrder.value.id) {
      await openDetail(voucherOrder.value)
    }
  }
  catch (error) {
    toast.error(errorMessage(error, '提交发货凭证失败'))
  }
  finally {
    submittingVoucher.value = false
  }
}

onMounted(() => { void load() })
</script>

<template>
  <section class="shop-page">
    <FaPageHeader title="我的出售" class="mb-0">
      <FaButton variant="outline" :loading="loading" @click="load">
        <FaIcon name="i-ri:refresh-line" />
        刷新
      </FaButton>
    </FaPageHeader>
    <FaPageMain>
      <FaTable
        v-loading="loading"
        row-key="id"
        table-root-class="rounded-lg overflow-hidden"
        table-class="min-w-[860px]"
        border
        stripe
        column-visibility
        :columns="columns"
        :data="rows"
        empty-text="还没有卖出记录"
      >
        <template #cell-product="{ row }">
          <div class="flex items-center gap-2">
            <img
              v-if="imageOf(row.original)"
              :src="imageOf(row.original)"
              :alt="row.original.productTitle"
              class="shop-table-cover"
              loading="lazy"
            >
            <div v-else class="shop-table-cover shop-table-cover-placeholder">
              <FaIcon name="i-ri:image-line" />
            </div>
            <span class="line-clamp-1 font-medium">{{ row.original.productTitle }}</span>
          </div>
        </template>
        <template #cell-amount="{ row }">
          {{ formatAmount(row.original.totalAmount) }}
          <span class="text-xs text-muted-foreground">{{ row.original.assetCode }}</span>
          <span class="text-xs text-muted-foreground"> ×{{ row.original.quantity }}</span>
        </template>
        <template #cell-buyer="{ row }">
          {{ displayUserName(row.original.buyer) }}
        </template>
        <template #cell-status="{ row }">
          <FaTag :variant="orderStatusTag(row.original.status).variant">
            {{ row.original.statusText || orderStatusTag(row.original.status).text }}
          </FaTag>
        </template>
        <template #cell-createdAt="{ row }">
          {{ formatTime(row.original.createdAt) }}
        </template>
        <template #cell-operation="{ row }">
          <div class="flex-center gap-2">
            <FaButton
              v-if="row.original.status === 'PAID' || row.original.status === 'DELIVERING'"
              size="sm"
              @click="openVoucher(row.original)"
            >
              {{ row.original.deliveryVoucher ? '更新凭证' : '发货凭证' }}
            </FaButton>
            <FaButton size="sm" variant="outline" @click="openDetail(row.original)">详情</FaButton>
          </div>
        </template>
      </FaTable>

      <FaPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        class="mt-3"
        @page-change="load"
        @size-change="() => { pager.page = 1; void load() }"
      />

      <OrderDetailModal
        v-model:open="detailOpen"
        :sdk="sdk"
        :order="current"
        :loading="detailLoading"
        :show-content="false"
      />

      <FaModal v-model="voucherOpen" :title="voucherTitle" class="sm:max-w-[520px]" @close="voucherOrder = null">
        <div class="grid gap-3">
          <div v-if="voucherOrder" class="shop-order-delivery-message">
            <FaIcon name="i-ri:shopping-bag-3-line" />
            <span>{{ voucherOrder.productTitle }} ×{{ voucherOrder.quantity }}（买家：{{ displayUserName(voucherOrder.buyer) }}）</span>
          </div>
          <FaTextarea
            v-model="voucherText"
            placeholder="填写发货凭证：如兑换码、卡密、游戏内交易记录说明等（500 字以内）"
            :rows="5"
          />
          <p class="text-xs text-muted-foreground">
            提交后订单进入「待买家核验」状态；买家确认凭证无误并核验后订单完成。有争议时管理员可在此前退款处理。
          </p>
        </div>
        <template #footer>
          <div class="flex justify-end gap-2">
            <FaButton variant="outline" :disabled="submittingVoucher" @click="voucherOpen = false">取消</FaButton>
            <FaButton :loading="submittingVoucher" @click="submitVoucher">提交凭证</FaButton>
          </div>
        </template>
      </FaModal>
    </FaPageMain>
  </section>
</template>
