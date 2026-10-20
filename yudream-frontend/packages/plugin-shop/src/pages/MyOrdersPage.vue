<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { ShopOrder } from '../types'
import type { TableColumn, ActionMenuItem } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { YdIcon, YdPagination, YdTag, useYdToast, YdResponsiveEnhancedTable } from '@yudream/components'
import { onMounted, reactive, ref } from 'vue'
import { createShopApi } from '../api/shop-api'
import OrderDetailModal from '../components/OrderDetailModal.vue'
import { displayOrderSeller, errorMessage, formatAmount, formatTime, hasDeliveryProof, hasOrderTradeFee, orderStatusTag } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const api = createShopApi(props.sdk)
const toast = useYdToast()

const loading = ref(false)
const rows = ref<ShopOrder[]>([])
const pager = reactive({ page: 1, size: 10, total: 0 })
const detailOpen = ref(false)
const detailLoading = ref(false)
const current = ref<ShopOrder | null>(null)

const columns: TableColumn<ShopOrder>[] = [
  { id: 'product', header: '商品', minWidth: 220, fixed: 'left' },
  { id: 'amount', header: '金额', width: 130 },
  { id: 'seller', header: '卖家', width: 130 },
  { id: 'status', header: '状态', width: 100 },
  { accessorKey: 'createdAt', header: '下单时间', width: 170 },
]

async function load() {
  loading.value = true
  try {
    const page = await api.myPurchases(pager.page, pager.size)
    rows.value = page.records ?? []
    pager.total = Number(page.total ?? 0)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载我的购买失败'))
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

function onVerified(updated: ShopOrder) {
  current.value = updated
  void load()
}

/** 买家取消订单后同步详情与列表 */
function onCancelled(updated: ShopOrder) {
  current.value = updated
  void load()
}

/** 有发货凭证且未核验时才显示核验入口（凭证可能是文本或图片） */
function canVerify(order: ShopOrder) {
  return order.status === 'DELIVERING' && hasDeliveryProof(order) && !order.voucherVerified
}

onMounted(() => { void load() })

/** 订单行操作：核验 / 详情（可取消时进入详情取消） */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '核验', icon: 'morph:scan', visible: row => canVerify(row as never), onClick: row => openDetail(row as never) },
  { label: row => ((row as { cancellable?: boolean }).cancellable ? '详情 / 取消' : '详情'), icon: 'morph:eye', onClick: row => openDetail(row as never) },
]

</script>

<template>
  <div class="h-full min-h-0">
    <YdTablePageLayout title="我的购买" :show-search="false">
    <template #table>
<YdLoadingOverlay :loading="loading"><YdResponsiveEnhancedTable
        :actions="rowActions"

        
        row-key="id"
        table-root-class="rounded-lg overflow-hidden"
        table-class="min-w-[860px]"
        border
        stripe
        column-visibility
        :columns="columns"
        :data="rows"
        empty-text="还没有购买记录，去玩家市场逛逛吧"
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
              <YdIcon name="i-ri:image-line" />
            </div>
            <span class="line-clamp-1 font-medium">{{ row.original.productTitle }}</span>
            <span v-if="row.original.variantName" class="text-xs text-muted-foreground">{{ row.original.variantName }}</span>
          </div>
        </template>
        <template #cell-amount="{ row }">
          {{ formatAmount(row.original.totalAmount) }}
          <span class="text-xs text-muted-foreground">{{ row.original.assetCode }}</span>
          <span class="text-xs text-muted-foreground"> ×{{ row.original.quantity }}</span>
          <div v-if="hasOrderTradeFee(row.original)" class="text-xs text-muted-foreground">
            含交易手续费 {{ formatAmount(row.original.feeAmount) }} {{ row.original.assetCode }}
          </div>
        </template>
        <template #cell-seller="{ row }">
          {{ displayOrderSeller(row.original) }}
        </template>
        <template #cell-status="{ row }">
          <YdTag :variant="orderStatusTag(row.original.status).variant">
            {{ row.original.statusText || orderStatusTag(row.original.status).text }}
          </YdTag>
        </template>
        <template #cell-createdAt="{ row }">
          {{ formatTime(row.original.createdAt) }}
        </template>
</YdResponsiveEnhancedTable></YdLoadingOverlay>
    </template>
    <template #footer>
      <YdPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        @page-change="load"
        @size-change="() => { pager.page = 1; void load() }"
      />
    </template>
  </YdTablePageLayout>




      <OrderDetailModal
        v-model:open="detailOpen"
        :sdk="sdk"
        :order="current"
        :loading="detailLoading"
        :show-content="true"
        :buyer-view="true"
        @verified="onVerified"
        @cancelled="onCancelled"
      />
  </div>
</template>
