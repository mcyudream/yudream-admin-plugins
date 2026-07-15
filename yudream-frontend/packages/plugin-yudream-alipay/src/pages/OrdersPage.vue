<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AlipayOrder } from '../types'
import { computed, onMounted, reactive, ref } from 'vue'
import { FaButton, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaTable, FaTag } from '@yudream/components'
import { createAlipayApi } from '../api/alipay-api'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createAlipayApi(props.sdk)
const loading = ref(false)
const rows = ref<AlipayOrder[]>([])
const search = ref('')
const pager = reactive({ page: 1, size: 10, total: 0 })
const filteredRows = computed(() => { const key = search.value.trim().toLowerCase(); return key ? rows.value.filter(row => `${row.outTradeNo} ${row.userId} ${row.status}`.toLowerCase().includes(key)) : rows.value })
const columns: TableColumn<AlipayOrder>[] = [
  { accessorKey: 'outTradeNo', header: '订单号', minWidth: 230, fixed: 'left' }, { accessorKey: 'userId', header: '用户', width: 150 },
  { accessorKey: 'assetCode', header: '币种', width: 100 }, { accessorKey: 'amount', header: '支付金额', width: 120 },
  { accessorKey: 'walletAmount', header: '到账金额', width: 120 }, { id: 'status', header: '状态', width: 100 }, { id: 'createdAt', header: '创建时间', width: 180 },
]
function formatTime(value?: number) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-' }
async function load() { loading.value = true; try { const result = await api.adminOrders(pager.page, pager.size); rows.value = result.records; pager.total = result.total } finally { loading.value = false } }
function reset() { search.value = '' }
function onSize() { pager.page = 1; load() }
onMounted(load)
</script>

<template>
  <FaPageHeader title="支付宝订单" class="mb-0" />
  <FaPageMain>
    <FaTable v-loading="loading" row-key="outTradeNo" table-root-class="max-w-full overflow-x-auto rounded-lg" table-class="min-w-[1050px]" border stripe column-visibility :columns="columns" :data="filteredRows" empty-text="暂无订单">
      <template #toolbar><FaSearchBar class="w-full"><div class="alipay-form-grid"><FaInput v-model="search" clearable placeholder="订单号 / 用户 / 状态" /><div class="alipay-actions"><FaButton variant="outline" @click="reset">重置</FaButton><FaButton @click="load"><FaIcon name="i-ri:search-line" />查询</FaButton></div></div></FaSearchBar></template>
      <template #cell-status="{ row }"><FaTag>{{ row.original.status }}</FaTag></template>
      <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
    </FaTable>
    <FaPagination v-model:page="pager.page" v-model:size="pager.size" :total="pager.total" class="mt-3" @page-change="load" @size-change="onSize" />
  </FaPageMain>
</template>
