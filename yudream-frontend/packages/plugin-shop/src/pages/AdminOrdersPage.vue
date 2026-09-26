<script setup lang="ts">
import type { ShopOrder } from '../types'
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaButton, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaSelect, FaTable, FaTag, useFaModal, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { createShopApi } from '../api/shop-api'
import OrderDetailModal from '../components/OrderDetailModal.vue'
import OrderVoucherModal from '../components/OrderVoucherModal.vue'
import { displayOrderSeller, displayUserName, errorMessage, formatAmount, formatTime, hasOrderTradeFee, orderSellerAmount, orderStatusTag } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const api = createShopApi(props.sdk)
const toast = useFaToast()
const confirm = useFaModal()

const loading = ref(false)
const actingId = ref('')
const rows = ref<ShopOrder[]>([])
const pager = reactive({ page: 1, size: 10, total: 0 })
const filters = reactive({ keyword: '', buyerId: '', sellerId: '', status: '' })
const detailOpen = ref(false)
const detailLoading = ref(false)
const current = ref<ShopOrder | null>(null)

const voucherOpen = ref(false)
const voucherOrder = ref<ShopOrder | null>(null)
const voucherLoadingId = ref('')

const currentUserId = computed(() => String(props.sdk.account?.userId ?? ''))

/** 管理端只能走 /me/** 操作自己的订单：仅当当前账号就是该订单卖家时才提供发货凭证入口 */
function isOwnSale(order: ShopOrder) {
  return !!currentUserId.value && order.seller?.id === currentUserId.value
}

/** 管理端页面本身受 manage 权限保护：管理员可为任意归属的订单代发货 */
function canSubmitVoucher(order: ShopOrder) {
  return order.status === 'PAID' || order.status === 'DELIVERING'
}

/** 代发货：订单没有自然人卖家（如迁移自积分商城、归属平台的订单）时走管理端发货端点 */
function voucherIsAdmin(order: ShopOrder | null) {
  return !!order && !isOwnSale(order)
}

const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '已支付', value: 'PAID' },
  { label: '发货中', value: 'DELIVERING' },
  { label: '已发货', value: 'DELIVERED' },
  { label: '发货失败', value: 'DELIVERY_FAILED' },
  { label: '已退款', value: 'REFUNDED' },
  { label: '已取消', value: 'CANCELLED' },
]

const columns: TableColumn<ShopOrder>[] = [
  { id: 'id', header: '订单号', width: 110, fixed: 'left' },
  { id: 'product', header: '商品', minWidth: 200 },
  { id: 'amount', header: '金额', width: 130 },
  { id: 'buyer', header: '买家', width: 120 },
  { id: 'seller', header: '卖家', width: 120 },
  { id: 'status', header: '状态', width: 100 },
  { accessorKey: 'createdAt', header: '下单时间', width: 170 },
  { id: 'operation', header: '操作', width: 300, align: 'center', fixed: 'right' },
]

async function load() {
  loading.value = true
  try {
    const page = await api.adminOrders(filters.keyword, filters.buyerId, filters.sellerId, filters.status, pager.page, pager.size)
    rows.value = page.records ?? []
    pager.total = Number(page.total ?? 0)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载订单列表失败'))
  }
  finally {
    loading.value = false
  }
}

function applyFilters() {
  pager.page = 1
  void load()
}

function resetFilters() {
  filters.keyword = ''
  filters.buyerId = ''
  filters.sellerId = ''
  filters.status = ''
  applyFilters()
}

function imageOf(order: ShopOrder) {
  return order.productImage ? props.sdk.files.thumbUrl(order.productImage, { maxEdge: 160 }) : ''
}

async function openDetail(order: ShopOrder) {
  detailOpen.value = true
  detailLoading.value = true
  current.value = null
  try {
    current.value = await api.adminOrderDetail(order.id)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载订单详情失败'))
  }
  finally {
    detailLoading.value = false
  }
}

function confirmRedeliver(order: ShopOrder) {
  confirm.confirm({
    title: '重新发货',
    content: `确认对订单「${order.productTitle}」重新执行发货流程吗？将按当前商品类型处理器重新发货。`,
    onConfirm: () => redeliver(order),
  })
}

async function redeliver(order: ShopOrder) {
  actingId.value = order.id
  try {
    await api.adminRedeliver(order.id)
    toast.success('已重新执行发货')
    await load()
  }
  catch (error) {
    toast.error(errorMessage(error, '重新发货失败'))
  }
  finally {
    actingId.value = ''
  }
}

function confirmRefund(order: ShopOrder) {
  const feeNotice = hasOrderTradeFee(order)
    ? `其中手续费 ${formatAmount(order.feeAmount)} ${order.assetCode} 会从手续费收款方原路收回，`
    : ''
  confirm.confirm({
    title: '订单退款',
    content: `确认将 ${formatAmount(order.totalAmount)} ${order.assetCode} 退回买家吗？${feeNotice}退款后订单标记为已退款，不可撤销。`,
    onConfirm: () => refund(order),
  })
}

async function refund(order: ShopOrder) {
  actingId.value = order.id
  try {
    await api.adminRefund(order.id)
    toast.success('退款成功')
    await load()
  }
  catch (error) {
    toast.error(errorMessage(error, '退款失败'))
  }
  finally {
    actingId.value = ''
  }
}

async function openVoucher(order: ShopOrder) {
  voucherLoadingId.value = order.id
  try {
    // 列表项可能不携带凭证图片，先取管理端详情再提交，避免更新凭证时丢图
    voucherOrder.value = await api.adminOrderDetail(order.id)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载订单详情失败，将按列表数据提交凭证'))
    voucherOrder.value = order
  }
  finally {
    voucherLoadingId.value = ''
  }
  voucherOpen.value = true
}

async function onVoucherSubmitted(updated: ShopOrder) {
  await load()
  if (detailOpen.value && current.value?.id === updated.id) {
    current.value = updated
  }
}

function canRedeliver(order: ShopOrder) {
  return ['PAID', 'DELIVERING', 'DELIVERY_FAILED'].includes(order.status)
}

function canRefund(order: ShopOrder) {
  return !['DELIVERED', 'REFUNDED'].includes(order.status) && !order.refundTransactionId
}

onMounted(() => { void load() })
</script>

<template>
  <section class="shop-page">
    <FaPageHeader title="订单管理" class="mb-0">
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
        table-class="min-w-[1100px]"
        border
        stripe
        column-visibility
        :columns="columns"
        :data="rows"
        empty-text="暂无订单"
      >
        <template #toolbar>
          <FaSearchBar class="w-full">
            <div class="shop-filter-grid shop-order-filters">
              <FaInput
                v-model="filters.keyword"
                placeholder="搜索商品标题或订单号前缀，回车查询"
                clearable
                @keyup.enter="applyFilters"
                @clear="applyFilters"
              />
              <FaInput
                v-model="filters.buyerId"
                placeholder="买家用户 ID"
                clearable
                @keyup.enter="applyFilters"
                @clear="applyFilters"
              />
              <FaInput
                v-model="filters.sellerId"
                placeholder="卖家用户 ID"
                clearable
                @keyup.enter="applyFilters"
                @clear="applyFilters"
              />
              <FaSelect v-model="filters.status" class="shop-select" :options="statusOptions" placeholder="全部状态" />
              <div class="flex justify-end gap-2">
                <FaButton variant="outline" @click="resetFilters">重置</FaButton>
                <FaButton :loading="loading" @click="applyFilters">查询</FaButton>
              </div>
            </div>
          </FaSearchBar>
        </template>
        <template #cell-id="{ row }">
          <span class="font-mono text-xs">{{ row.original.id.slice(0, 8) }}</span>
        </template>
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
          <div v-if="hasOrderTradeFee(row.original)" class="text-xs text-muted-foreground">
            含手续费 {{ formatAmount(row.original.feeAmount) }}，卖家实收 {{ formatAmount(orderSellerAmount(row.original)) }}
          </div>
        </template>
        <template #cell-buyer="{ row }">
          {{ displayUserName(row.original.buyer) }}
        </template>
        <template #cell-seller="{ row }">
          {{ displayOrderSeller(row.original) }}
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
            <FaButton size="sm" variant="outline" @click="openDetail(row.original)">详情</FaButton>
            <FaButton
              v-if="canSubmitVoucher(row.original)"
              size="sm"
              :loading="voucherLoadingId === row.original.id"
              @click="openVoucher(row.original)"
            >
              {{ isOwnSale(row.original) ? '发货凭证' : '代发货' }}
            </FaButton>
            <FaButton
              v-if="canRedeliver(row.original)"
              size="sm"
              variant="outline"
              :loading="actingId === row.original.id"
              @click="confirmRedeliver(row.original)"
            >
              重新发货
            </FaButton>
            <FaButton
              v-if="canRefund(row.original)"
              size="sm"
              variant="destructive"
              :loading="actingId === row.original.id"
              @click="confirmRefund(row.original)"
            >
              退款
            </FaButton>
          </div>
        </template>
      </FaTable>

      <FaPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        class="mt-3"
        @page-change="load"
        @size-change="applyFilters"
      />

      <OrderDetailModal
        v-model:open="detailOpen"
        :sdk="sdk"
        :order="current"
        :loading="detailLoading"
        :show-content="true"
      />

      <OrderVoucherModal
        v-model:open="voucherOpen"
        :sdk="sdk"
        :order="voucherOrder"
        :admin="voucherIsAdmin(voucherOrder)"
        @submitted="onVoucherSubmitted"
      />
    </FaPageMain>
  </section>
</template>
