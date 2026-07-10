<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AlipayOrder } from '../types'
import { onMounted, reactive, ref } from 'vue'
import { FaPageHeader, FaPageMain, FaPagination, FaTable, FaTag } from '@yudream/components'
import { createAlipayApi } from '../api/alipay-api'
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createAlipayApi(props.sdk)
const rows = ref<AlipayOrder[]>([]); const loading = ref(false); const pager = reactive({ page: 1, size: 10, total: 0 })
const columns: TableColumn<AlipayOrder>[] = [{ accessorKey: 'outTradeNo', header: '订单号', minWidth: 230 }, { accessorKey: 'assetCode', header: '币种', width: 100 }, { accessorKey: 'amount', header: '支付金额', width: 120 }, { accessorKey: 'walletAmount', header: '到账金额', width: 120 }, { id: 'status', header: '状态', width: 100 }, { id: 'createdAt', header: '创建时间', width: 180 }]
function formatTime(value?: number) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-' }
async function load() { loading.value = true; try { const result = await api.meOrders(pager.page, pager.size); rows.value = result.records; pager.total = result.total } finally { loading.value = false } }
function onSize() { pager.page = 1; load() }
onMounted(load)
</script>
<template><FaPageHeader title="我的支付宝订单" class="mb-0" /><FaPageMain><FaTable v-loading="loading" row-key="outTradeNo" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[900px]" border stripe :columns="columns" :data="rows"><template #cell-status="{ row }"><FaTag>{{ row.original.status }}</FaTag></template><template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template></FaTable><FaPagination v-model:page="pager.page" v-model:size="pager.size" :total="pager.total" class="mt-3" @page-change="load" @size-change="onSize" /></FaPageMain></template>
