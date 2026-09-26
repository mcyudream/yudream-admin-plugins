<script setup lang="ts">
import type { ShopProductSummary, ShopCurrency } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaButton, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaSelect, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createShopApi } from '../api/shop-api'
import ProductCard from '../components/ProductCard.vue'
import { errorMessage, hasPermission } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const api = createShopApi(props.sdk)
const toast = useFaToast()

const loading = ref(false)
const rows = ref<ShopProductSummary[]>([])
const currencies = ref<ShopCurrency[]>([])
const pager = reactive({ page: 1, size: 12, total: 0 })
const filters = reactive({ keyword: '', assetCode: '' })

const canPublish = computed(() => hasPermission(props.sdk.account?.permissions, 'plugin:shop:publish'))
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
    const page = await api.plazaProducts(filters.keyword, filters.assetCode, pager.page, pager.size)
    rows.value = page.records ?? []
    pager.total = Number(page.total ?? 0)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载商品广场失败'))
  }
  finally {
    loading.value = false
  }
}

async function loadCurrencies() {
  try {
    currencies.value = await api.plazaCurrencies()
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
  void router.push({ path: '/platform/plugins/shop/detail', query: { id: product.id } })
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
    <FaPageHeader title="商品广场" class="mb-0">
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
            <FaIcon name="i-ri:store-2-line" class="text-4xl text-muted-foreground" />
            <p>暂时没有上架中的商品</p>
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
