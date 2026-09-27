<script setup lang="ts">
import type { PublishQualification, ShopProductSummary } from '../types'
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaAlert, FaButton, FaCard, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSearchBar, FaTag, useFaModal, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createShopApi } from '../api/shop-api'
import { errorMessage, formatAmount, formatTime, hasPermission, perUserLimitText, productStatusTag, productTypeLabel } from '../composables/utils'

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
const filters = reactive({ keyword: '' })
const qualification = ref<PublishQualification | null>(null)
const publishBlocked = computed(() => !!qualification.value && !qualification.value.allowed)
/**
 * 管理账号只能投放官方积分商品，因此「发布商品」入口被禁用；
 * 但历史遗留的玩家商品仍由本人维护，重新上架不应被上架开关/角色一起挡住。
 */
const isManager = computed(() => hasPermission(props.sdk.account?.permissions, 'plugin:shop:manage'))
const shelfBlocked = computed(() => publishBlocked.value && !isManager.value)

const columns: TableColumn<ShopProductSummary>[] = [
  { id: 'title', header: '商品', minWidth: 240, fixed: 'left' },
  { id: 'price', header: '价格', width: 130 },
  { id: 'stock', header: '库存 / 已售', width: 120 },
  { id: 'status', header: '状态', width: 90 },
  { accessorKey: 'updatedAt', header: '更新时间', width: 170 },
  { id: 'operation', header: '操作', width: 210, align: 'center', fixed: 'right' },
]

async function load() {
  loading.value = true
  try {
    const page = await api.myProducts(filters.keyword, pager.page, pager.size)
    rows.value = page.records ?? []
    pager.total = Number(page.total ?? 0)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载我的商品失败'))
  }
  finally {
    loading.value = false
  }
}

function applyFilters() {
  pager.page = 1
  void load()
}

function coverOf(product: ShopProductSummary) {
  return product.coverImage ? props.sdk.files.thumbUrl(product.coverImage, { maxEdge: 160 }) : ''
}

function openCreate() {
  void router.push({ path: '/platform/plugins/shop/selling/edit' })
}

function openEdit(product: ShopProductSummary) {
  void router.push({ path: '/platform/plugins/shop/selling/edit', query: { id: product.id } })
}

function toggleShelf(product: ShopProductSummary) {
  const onShelf = product.status !== 'ON_SHELF'
  actingId.value = product.id
  void api.setMyProductShelf(product.id, onShelf)
    .then(() => {
      toast.success(onShelf ? '已重新上架' : '已下架')
      return load()
    })
    .catch((error: unknown) => toast.error(errorMessage(error, '操作失败')))
    .finally(() => { actingId.value = '' })
}

function confirmDelete(product: ShopProductSummary) {
  confirm.confirm({
    title: '删除商品',
    content: `确认删除「${product.title}」吗？删除后商品不再展示，已产生的订单仍保留快照记录；若有待发货订单将无法删除。`,
    onConfirm: () => removeProduct(product),
  })
}

async function removeProduct(product: ShopProductSummary) {
  actingId.value = product.id
  try {
    await api.deleteMyProduct(product.id)
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

async function loadQualification() {
  try {
    qualification.value = await api.publishQualification()
  }
  catch {
    qualification.value = null
  }
}

onMounted(() => {
  void load()
  void loadQualification()
})
</script>

<template>
  <section class="shop-page">
    <FaPageHeader title="我的商品" class="mb-0">
      <FaButton :disabled="publishBlocked" @click="openCreate">
        <FaIcon name="i-ri:add-line" />
        上架商品
      </FaButton>
    </FaPageHeader>
    <FaPageMain>
      <FaAlert v-if="publishBlocked" title="当前无法上架商品" class="mb-4">
        <template #description>
          {{ qualification?.reason || '管理员已关闭用户上架功能、积分门槛不足，或当前账号是管理员（只能投放官方积分商品）。' }}
        </template>
      </FaAlert>
      <FaResponsiveTable
        v-loading="loading"
        row-key="id"
        table-root-class="rounded-lg overflow-hidden"
        table-class="min-w-[960px]"
        border
        stripe
        column-visibility
        :columns="columns"
        :data="rows"
        :empty-text="publishBlocked ? '还没有商品' : '还没有上架过商品，点击右上角上架第一件商品'"
      >
        <template #toolbar>
          <FaSearchBar class="w-full">
            <div class="grid grid-cols-1 gap-3 md:grid-cols-[minmax(220px,1fr)_auto]">
              <FaInput
                v-model="filters.keyword"
                placeholder="搜索商品标题或简介，回车查询"
                clearable
                @keyup.enter="applyFilters"
                @clear="applyFilters"
              />
              <div class="flex justify-end gap-2">
                <FaButton variant="outline" :loading="loading" @click="applyFilters">查询</FaButton>
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
            <div class="grid gap-0.5">
              <span class="line-clamp-1 font-medium">{{ row.original.title }}</span>
              <span class="line-clamp-1 text-xs text-muted-foreground">
                {{ productTypeLabel(row.original.type, row.original.typeDisplayName) }}
                <template v-if="Number(row.original.perUserLimit) > 0"> · 每人限购 {{ perUserLimitText(row.original.perUserLimit) }}</template>
              </span>
            </div>
          </div>
        </template>
        <template #cell-price="{ row }">
          {{ row.original.assetSymbol || '¥' }}{{ formatAmount(row.original.price) }}
          <span class="text-xs text-muted-foreground">{{ row.original.assetCode }}</span>
        </template>
        <template #cell-stock="{ row }">
          {{ row.original.stock < 0 ? '不限' : row.original.stock }} / {{ Number(row.original.soldCount) || 0 }}
        </template>
        <template #cell-status="{ row }">
          <FaTag :variant="productStatusTag(row.original.status).variant">
            {{ row.original.statusText || productStatusTag(row.original.status).text }}
          </FaTag>
        </template>
        <template #cell-updatedAt="{ row }">
          {{ formatTime(row.original.updatedAt) }}
        </template>
        <template #cell-operation="{ row }">
          <div class="flex-center gap-2">
            <FaButton size="sm" variant="outline" @click="openEdit(row.original)">编辑</FaButton>
            <FaButton
              size="sm"
              variant="outline"
              :disabled="shelfBlocked && row.original.status !== 'ON_SHELF'"
              :loading="actingId === row.original.id"
              @click="toggleShelf(row.original)"
            >
              {{ row.original.status === 'ON_SHELF' ? '下架' : '上架' }}
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
                  <span class="shrink-0 text-secondary-foreground/60">类型</span>
                  <span class="break-all">{{ productTypeLabel(row.type, row.typeDisplayName) }}<template v-if="Number(row.perUserLimit) > 0"> · 每人限购 {{ perUserLimitText(row.perUserLimit) }}</template></span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">更新时间</span>
                  <span class="break-all">{{ formatTime(row.updatedAt) }}</span>
                </div>
              </div>
              <div class="flex flex-wrap gap-2 border-t pt-3">
                <FaButton size="sm" variant="outline" @click="openEdit(row)">编辑</FaButton>
                <FaButton
                  size="sm"
                  variant="outline"
                  :disabled="shelfBlocked && row.status !== 'ON_SHELF'"
                  :loading="actingId === row.id"
                  @click="toggleShelf(row)"
                >
                  {{ row.status === 'ON_SHELF' ? '下架' : '上架' }}
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
    </FaPageMain>
  </section>
</template>
