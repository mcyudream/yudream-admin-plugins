<script setup lang="ts">
import type { ShopProductSummary, ShopCurrency, ShopSettlement } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaButton, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaSelect, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createShopApi } from '../api/shop-api'
import ProductCard from './ProductCard.vue'
import { errorMessage, hasPermission } from '../composables/utils'

/**
 * 商品列表共用视图。
 *
 * 「玩家市场」与「积分商城」是两个独立入口（各自的页面组件），这里只承载共用的筛选、分页与卡片网格；
 * 两者的差别只有结算方式与服务端返回的数据，因此用 `settlement` 参数化而不是复制一份列表逻辑。
 */
const props = withDefaults(defineProps<{
  sdk: YuDreamPluginSdk
  /** 结算方式：SELLER=玩家互相买卖（转账给卖家）；BURN=官方消耗（扣买家、无收款方） */
  settlement: ShopSettlement
  title: string
  description?: string
  emptyHint?: string
  emptyIcon?: string
  /** 只有玩家市场提供「上架商品」入口 */
  showPublish?: boolean
}>(), {
  emptyHint: '暂时没有上架中的商品',
  emptyIcon: 'i-ri:store-2-line',
  showPublish: false,
})

const router = useRouter()
const api = createShopApi(props.sdk)
const toast = useFaToast()

const loading = ref(false)
const rows = ref<ShopProductSummary[]>([])
const currencies = ref<ShopCurrency[]>([])
const pager = reactive({ page: 1, size: 12, total: 0 })
const filters = reactive({ keyword: '', assetCode: '' })

const canPublish = computed(() => props.showPublish
  && hasPermission(props.sdk.account?.permissions, 'plugin:shop:publish'))
const currencyOptions = computed(() => [
  { label: '全部货币', value: '' },
  ...currencies.value.map(item => ({
    label: item.name && item.name !== item.code ? `${item.name}（${item.code}）` : item.code,
    value: item.code,
  })),
])

async function load() {
  loading.value = true
  try {
    const page = await api.plazaProducts(filters.keyword, filters.assetCode, pager.page, pager.size, props.settlement)
    rows.value = page.records ?? []
    pager.total = Number(page.total ?? 0)
  }
  catch (error) {
    toast.error(errorMessage(error, `加载${props.title}失败`))
  }
  finally {
    loading.value = false
  }
}

async function loadCurrencies() {
  try {
    currencies.value = await api.plazaCurrencies(props.settlement)
  }
  catch {
    currencies.value = []
  }
}

function applyFilters() {
  pager.page = 1
  void load()
}

function resetFilters() {
  filters.keyword = ''
  filters.assetCode = ''
  applyFilters()
}

function openDetail(product: ShopProductSummary) {
  // 带上来源，商品详情返回时回到对应的入口（玩家市场 / 积分商城），不会串到另一侧列表
  void router.push({
    path: '/platform/plugins/shop/detail',
    query: { id: product.id, from: props.settlement === 'BURN' ? 'exchange' : 'plaza' },
  })
}

function openCreate() {
  void router.push({ path: '/platform/plugins/shop/selling/edit' })
}

onMounted(() => {
  void load()
  void loadCurrencies()
})
</script>

<template>
  <section class="shop-page">
    <FaPageHeader :title="title" :description="description" class="mb-0">
      <FaButton v-if="canPublish" @click="openCreate">
        <FaIcon name="i-ri:add-line" />
        上架商品
      </FaButton>
      <FaButton variant="outline" :loading="loading" @click="load">
        <FaIcon name="i-ri:refresh-line" />
        刷新
      </FaButton>
    </FaPageHeader>
    <FaPageMain>
      <div class="grid gap-4">
        <div class="shop-filter-grid shop-plaza-filters">
          <FaInput
            v-model="filters.keyword"
            placeholder="搜索商品标题或简介，回车查询"
            clearable
            @keyup.enter="applyFilters"
            @clear="applyFilters"
          />
          <FaSelect v-model="filters.assetCode" class="shop-select" :options="currencyOptions" placeholder="全部货币" />
          <div class="flex flex-wrap justify-end gap-2">
            <FaButton variant="outline" @click="resetFilters">重置</FaButton>
            <FaButton :loading="loading" @click="applyFilters">
              <FaIcon name="i-ri:search-line" />
              查询
            </FaButton>
          </div>
        </div>

        <div v-loading="loading" class="shop-plaza">
          <div v-if="!loading && !rows.length" class="shop-plaza-empty">
            <FaIcon :name="emptyIcon" class="text-4xl text-muted-foreground" />
            <p>{{ emptyHint }}</p>
          </div>
          <div v-else class="shop-plaza-grid">
            <ProductCard
              v-for="product in rows"
              :key="product.id"
              :sdk="sdk"
              :product="product"
              @open="openDetail"
            />
          </div>
        </div>

        <FaPagination
          v-model:page="pager.page"
          v-model:size="pager.size"
          :total="pager.total"
          class="mt-3"
          @page-change="load"
          @size-change="applyFilters"
        />
      </div>
    </FaPageMain>
  </section>
</template>
