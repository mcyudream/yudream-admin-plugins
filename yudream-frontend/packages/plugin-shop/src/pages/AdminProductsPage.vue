<script setup lang="ts">
import type { ShopProductDetail, ShopProductSummary } from '../types'
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaButton, FaCard, FaIcon, FaInput, FaModal, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSearchBar, FaSelect, FaTag, useFaModal, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createShopApi } from '../api/shop-api'
import MarkdownPreview from '../components/MarkdownPreview.vue'
import { displayProductOwner, errorMessage, formatAmount, formatTime, isPlatformOwned, perUserLimitText, productStatusTag, productTypeLabel } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const api = createShopApi(props.sdk)
const toast = useFaToast()
const confirm = useFaModal()

const loading = ref(false)
const actingId = ref('')
const rows = ref<ShopProductSummary[]>([])
const pager = reactive({ page: 1, size: 10, total: 0 })
const filters = reactive({ keyword: '', ownerId: '', status: '' })
const detailOpen = ref(false)
const detailLoading = ref(false)
const current = ref<ShopProductDetail | null>(null)

const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '上架中', value: 'ON_SHELF' },
  { label: '已下架', value: 'OFF_SHELF' },
]

const columns: TableColumn<ShopProductSummary>[] = [
  { id: 'title', header: '商品', minWidth: 220, fixed: 'left' },
  { id: 'owner', header: '卖家', width: 130 },
  { id: 'price', header: '价格', width: 130 },
  { id: 'stock', header: '库存 / 已售', width: 120 },
  { id: 'type', header: '类型', width: 110 },
  { id: 'sortOrder', header: '排序', width: 80, align: 'center' },
  { id: 'status', header: '状态', width: 90 },
  { accessorKey: 'updatedAt', header: '更新时间', width: 170 },
  { id: 'operation', header: '操作', width: 460, align: 'center', fixed: 'right' },
]

const currentConfigText = computed(() => {
  const config = current.value?.typeConfig
  return config && Object.keys(config).length ? JSON.stringify(config, null, 2) : ''
})

async function load() {
  loading.value = true
  try {
    const page = await api.adminProducts(filters.keyword, filters.ownerId, filters.status, '', pager.page, pager.size)
    rows.value = page.records ?? []
    pager.total = Number(page.total ?? 0)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载商品列表失败'))
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
  filters.ownerId = ''
  filters.status = ''
  applyFilters()
}

function coverOf(product: ShopProductSummary) {
  return product.coverImage ? props.sdk.files.thumbUrl(product.coverImage, { maxEdge: 160 }) : ''
}

function openCreate() {
  void router.push({ path: '/platform/plugins/shop/system/products/edit' })
}

function openEdit(product: ShopProductSummary) {
  void router.push({ path: '/platform/plugins/shop/system/products/edit', query: { id: product.id } })
}

async function openDetail(product: ShopProductSummary) {
  detailOpen.value = true
  detailLoading.value = true
  current.value = null
  try {
    current.value = await api.adminProductDetail(product.id)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载商品详情失败'))
  }
  finally {
    detailLoading.value = false
  }
}

function toggleShelf(product: ShopProductSummary) {
  const onShelf = product.status !== 'ON_SHELF'
  actingId.value = product.id
  void api.adminSetShelf(product.id, onShelf)
    .then(() => {
      toast.success(onShelf ? '已恢复上架' : '已强制下架')
      return load()
    })
    .catch((error: unknown) => toast.error(errorMessage(error, '操作失败')))
    .finally(() => { actingId.value = '' })
}

async function moveProduct(product: ShopProductSummary, direction: 'UP' | 'DOWN' | 'TOP') {
  if (actingId.value) {
    return
  }
  actingId.value = product.id
  try {
    await api.adminMoveProduct(product.id, direction)
    await load()
  }
  catch (error) {
    toast.error(errorMessage(error, '排序失败'))
  }
  finally {
    actingId.value = ''
  }
}

function confirmDelete(product: ShopProductSummary) {
  confirm.confirm({
    title: '删除商品',
    content: `确认删除「${product.title}」（归属：${displayProductOwner(product)}）吗？删除后商品不再展示，已产生的订单仍保留快照记录；若有待发货订单将无法删除。`,
    onConfirm: () => removeProduct(product),
  })
}

async function removeProduct(product: ShopProductSummary) {
  actingId.value = product.id
  try {
    await api.adminDeleteProduct(product.id)
    toast.success('商品已删除')
    if (rows.value.length <= 1 && pager.page > 1) {
      pager.page -= 1
    }
    await load()
  }
  catch (error) {
    toast.error(errorMessage(error, '删除失败'))
  }
  finally {
    actingId.value = ''
  }
}

onMounted(() => { void load() })
</script>

<template>
  <section class="shop-page">
    <FaPageHeader title="商品管理" class="mb-0">
      <div class="flex flex-wrap justify-end gap-2">
        <FaButton @click="openCreate">
          <FaIcon name="i-ri:add-line" />
          新增商品
        </FaButton>
        <FaButton variant="outline" :loading="loading" @click="load">
          <FaIcon name="i-ri:refresh-line" />
          刷新
        </FaButton>
      </div>
    </FaPageHeader>
    <FaPageMain>
      <FaResponsiveTable
        v-loading="loading"
        row-key="id"
        table-root-class="rounded-lg overflow-hidden"
        table-class="min-w-[1100px]"
        border
        stripe
        column-visibility
        :columns="columns"
        :data="rows"
        empty-text="暂无商品"
      >
        <template #toolbar>
          <FaSearchBar class="w-full">
            <div class="shop-filter-grid shop-product-filters">
              <FaInput
                v-model="filters.keyword"
                placeholder="搜索商品标题或简介，回车查询"
                clearable
                @keyup.enter="applyFilters"
                @clear="applyFilters"
              />
              <FaInput
                v-model="filters.ownerId"
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
        <template #cell-title="{ row }">
          <div class="flex items-center gap-2">
            <img
              v-if="coverOf(row.original)"
              :src="coverOf(row.original)"
              :alt="row.original.title"
              class="shop-table-cover"
              loading="lazy"
            >
            <div v-else class="shop-table-cover shop-table-cover-placeholder">
              <FaIcon name="i-ri:image-line" />
            </div>
            <span class="line-clamp-1 font-medium">{{ row.original.title }}</span>
          </div>
        </template>
        <template #cell-owner="{ row }">
          {{ displayProductOwner(row.original) }}
        </template>
        <template #cell-price="{ row }">
          {{ row.original.assetSymbol || '¥' }}{{ formatAmount(row.original.price) }}
          <span class="text-xs text-muted-foreground">{{ row.original.assetCode }}</span>
        </template>
        <template #cell-stock="{ row }">
          {{ row.original.stock < 0 ? '不限' : row.original.stock }} / {{ Number(row.original.soldCount) || 0 }}
        </template>
        <template #cell-type="{ row }">
          <FaTag variant="secondary">{{ productTypeLabel(row.original.type, row.original.typeDisplayName) }}</FaTag>
        </template>
        <template #cell-status="{ row }">
          <FaTag :variant="productStatusTag(row.original.status).variant">
            {{ row.original.statusText || productStatusTag(row.original.status).text }}
          </FaTag>
        </template>
        <template #cell-updatedAt="{ row }">
          {{ formatTime(row.original.updatedAt) }}
        </template>
        <template #cell-sortOrder="{ row }">
          <span class="text-xs text-muted-foreground">{{ Number(row.original.sortOrder) || 0 }}</span>
        </template>
        <template #cell-operation="{ row }">
          <div class="flex-center gap-2">
            <FaButton size="sm" variant="outline" :loading="actingId === row.original.id" @click="moveProduct(row.original, 'TOP')">
              置顶
            </FaButton>
            <FaButton size="sm" variant="outline" :loading="actingId === row.original.id" @click="moveProduct(row.original, 'UP')">
              上移
            </FaButton>
            <FaButton size="sm" variant="outline" :loading="actingId === row.original.id" @click="moveProduct(row.original, 'DOWN')">
              下移
            </FaButton>
            <FaButton size="sm" variant="outline" @click="openDetail(row.original)">详情</FaButton>
            <FaButton size="sm" variant="outline" @click="openEdit(row.original)">编辑</FaButton>
            <FaButton
              size="sm"
              variant="outline"
              :loading="actingId === row.original.id"
              @click="toggleShelf(row.original)"
            >
              {{ row.original.status === 'ON_SHELF' ? '强制下架' : '恢复上架' }}
            </FaButton>
            <FaButton
              size="sm"
              variant="destructive"
              :loading="actingId === row.original.id"
              @click="confirmDelete(row.original)"
            >
              删除
            </FaButton>
          </div>
        </template>
        <template #card="{ row }">
          <FaCard class="w-full">
            <div class="flex flex-col gap-3">
              <div class="flex items-center justify-between gap-2">
                <span class="min-w-0 break-words text-base font-semibold">{{ row.title }}</span>
                <FaTag :variant="productStatusTag(row.status).variant">
                  {{ row.statusText || productStatusTag(row.status).text }}
                </FaTag>
              </div>
              <div class="flex flex-col gap-1 text-sm">
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">价格</span>
                  <span class="break-all">{{ row.assetSymbol || '¥' }}{{ formatAmount(row.price) }} {{ row.assetCode }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">库存 / 已售</span>
                  <span class="break-all">{{ row.stock < 0 ? '不限' : row.stock }} / {{ Number(row.soldCount) || 0 }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">卖家</span>
                  <span class="break-all">{{ displayProductOwner(row) }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">类型 / 排序</span>
                  <span class="break-all">{{ productTypeLabel(row.type, row.typeDisplayName) }} · 排序 {{ Number(row.sortOrder) || 0 }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">更新时间</span>
                  <span class="break-all">{{ formatTime(row.updatedAt) }}</span>
                </div>
              </div>
              <div class="flex flex-wrap gap-2 border-t pt-3">
                <FaButton size="sm" variant="outline" :loading="actingId === row.id" @click="moveProduct(row, 'TOP')">
                  置顶
                </FaButton>
                <FaButton size="sm" variant="outline" :loading="actingId === row.id" @click="moveProduct(row, 'UP')">
                  上移
                </FaButton>
                <FaButton size="sm" variant="outline" :loading="actingId === row.id" @click="moveProduct(row, 'DOWN')">
                  下移
                </FaButton>
                <FaButton size="sm" variant="outline" @click="openDetail(row)">详情</FaButton>
                <FaButton size="sm" variant="outline" @click="openEdit(row)">编辑</FaButton>
                <FaButton
                  size="sm"
                  variant="outline"
                  :loading="actingId === row.id"
                  @click="toggleShelf(row)"
                >
                  {{ row.status === 'ON_SHELF' ? '强制下架' : '恢复上架' }}
                </FaButton>
                <FaButton
                  size="sm"
                  variant="destructive"
                  :loading="actingId === row.id"
                  @click="confirmDelete(row)"
                >
                  删除
                </FaButton>
              </div>
            </div>
          </FaCard>
        </template>
      </FaResponsiveTable>

      <FaPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        class="mt-3"
        @page-change="load"
        @size-change="applyFilters"
      />

      <FaModal v-model="detailOpen" title="商品详情" :show-confirm-button="false">
        <div v-loading="detailLoading" class="grid max-h-[70vh] gap-3 overflow-y-auto">
          <template v-if="current">
            <div class="flex flex-wrap items-center gap-2">
              <span class="text-base font-semibold">{{ current.title }}</span>
              <FaTag :variant="productStatusTag(current.status).variant">
                {{ current.statusText || productStatusTag(current.status).text }}
              </FaTag>
              <FaTag variant="secondary">{{ productTypeLabel(current.type, current.typeDisplayName) }}</FaTag>
            </div>
            <div class="shop-order-detail-grid">
              <span class="shop-order-detail-label">归属</span>
              <span>{{ displayProductOwner(current) }}<template v-if="!isPlatformOwned(current.ownerId, current.settlement)">（{{ current.ownerId }}）</template></span>
              <span class="shop-order-detail-label">价格</span>
              <span>{{ current.assetSymbol || '¥' }}{{ formatAmount(current.price) }} {{ current.assetCode }}</span>
              <span class="shop-order-detail-label">库存 / 已售</span>
              <span>{{ current.stock < 0 ? '不限' : current.stock }} / {{ Number(current.soldCount) || 0 }}</span>
              <span class="shop-order-detail-label">每人限购</span>
              <span>{{ perUserLimitText(current.perUserLimit) }}</span>
              <span class="shop-order-detail-label">创建 / 更新</span>
              <span>{{ formatTime(current.createdAt) }} / {{ formatTime(current.updatedAt) }}</span>
            </div>
            <p v-if="current.summary" class="text-sm text-muted-foreground">{{ current.summary }}</p>
            <div v-if="current.images?.length" class="shop-detail-gallery-thumbs">
              <img
                v-for="url in current.images.map(item => sdk.files.assetUrl(item))"
                :key="url"
                :src="url"
                :alt="current.title"
                loading="lazy"
              >
            </div>
            <div v-if="currentConfigText" class="grid gap-1">
              <span class="text-sm font-semibold">类型配置</span>
              <pre class="shop-order-delivery-content">{{ currentConfigText }}</pre>
            </div>
            <div class="grid gap-1">
              <span class="text-sm font-semibold">商品介绍</span>
              <MarkdownPreview v-if="current.descriptionMd" :sdk="sdk" :content="current.descriptionMd" />
              <p v-else class="text-sm text-muted-foreground">卖家没有填写详细介绍。</p>
            </div>
          </template>
          <div v-else-if="!detailLoading" class="py-6 text-center text-sm text-muted-foreground">
            商品不存在
          </div>
        </div>
      </FaModal>
    </FaPageMain>
  </section>
</template>
