<script setup lang="ts">
import type { ShopOrder } from '../types'
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaButton, FaIcon, FaPageHeader, FaPageMain, FaPagination, FaTable, FaTag, useFaToast } from '@yudream/components'
import { onMounted, reactive, ref } from 'vue'
import { createShopApi } from '../api/shop-api'
import OrderDetailModal from '../components/OrderDetailModal.vue'
import OrderVoucherModal from '../components/OrderVoucherModal.vue'
import { displayUserName, errorMessage, formatAmount, formatTime, hasDeliveryProof, orderStatusTag } from '../composables/utils'

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
  // /me/sales 列表项即为完整订单视图（含 deliveryVoucher 与 deliveryProofs），可直接用于回填
  voucherOrder.value = order
  voucherOpen.value = true
}

/** 凭证提交成功后刷新列表，并在详情弹窗打开时同步刷新详情 */
async function onVoucherSubmitted(updated: ShopOrder) {
  await load()
  if (detailOpen.value && current.value?.id === updated.id) {
    current.value = updated
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
            <span v-if="row.original.variantName" class="text-xs text-muted-foreground">{{ row.original.variantName }}</span>
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
              {{ hasDeliveryProof(row.original) ? '更新凭证' : '发货凭证' }}
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

      <OrderVoucherModal
        v-model:open="voucherOpen"
        :sdk="sdk"
        :order="voucherOrder"
        @submitted="onVoucherSubmitted"
      />
    </FaPageMain>
  </section>
</template>
