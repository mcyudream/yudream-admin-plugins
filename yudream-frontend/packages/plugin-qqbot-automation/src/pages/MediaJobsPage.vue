<template>
  <section>
    <FaPageHeader title="媒体任务" />
    <FaPageMain>
      <FaTable row-key="id" :data="jobs" :columns="columns" border stripe table-root-class="max-w-full overflow-x-auto rounded-lg">
        <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
        <template #cell-downloadUrl="{ row }"><a v-if="row.original.downloadUrl" :href="row.original.downloadUrl" target="_blank">下载地址</a><span v-else>{{ row.original.error || '-' }}</span></template>
      </FaTable>
      <FaPagination v-model:page="page" v-model:size="size" :total="total" class="mt-3" @page-change="load" @size-change="load" />
    </FaPageMain>
  </section>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { FaPageHeader, FaPageMain, FaPagination, FaTable, type TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { createQqbotAutomationApi } from '../api/qqbot-automation-api'
import type { MediaJob } from '../types'
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createQqbotAutomationApi(props.sdk); const page = ref(1); const size = ref(10); const total = ref(0); const jobs = ref<MediaJob[]>([])
const columns: TableColumn<MediaJob>[] = [{ accessorKey: 'sourceUrl', header: '来源链接', minWidth: 360 }, { accessorKey: 'status', header: '状态', width: 120 }, { id: 'downloadUrl', header: '结果', width: 160 }, { id: 'createdAt', header: '创建时间', width: 180 }]
async function load() { const result = await api.mediaJobs(page.value, size.value); jobs.value = result.records; total.value = result.total }
function formatTime(value: number) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-' }
onMounted(load)
</script>
