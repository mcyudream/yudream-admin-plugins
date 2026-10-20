<script setup lang="ts">
import { YdLoadingOverlay, type ActionMenuItem, YdTablePageLayout } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { YdAlert, YdPageMain, YdPagination, YdTag, useYdToast, type TableColumn, YdResponsiveEnhancedTable } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { createWebCardApi } from '../api/web-card-api'
import type { DeliveryRecord } from '../types'
import { dateTime, errorText } from '../ui'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createWebCardApi(props.sdk)
const toast = useYdToast()
const rows = ref<DeliveryRecord[]>([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const columns: TableColumn<DeliveryRecord>[] = [{ accessorKey: 'stage', header: '结果', width: 120 }, { accessorKey: 'contentId', header: '内容 ID', minWidth: 220 }, { id: 'mode', header: '触发方式', width: 120 }, { accessorKey: 'attempts', header: '尝试', width: 80 }, { accessorKey: 'error', header: '错误', minWidth: 320 }, { id: 'updatedAt', header: '更新时间', width: 180 }, { id: 'operation', header: '操作', width: 110 }]
async function load() { loading.value = true; error.value = ''; try { const result = await api.deliveries(page.value, size.value); rows.value = result.records; total.value = result.total } catch (cause) { error.value = errorText(cause, '加载运行记录失败') } finally { loading.value = false } }
async function retry(value: DeliveryRecord) { try { await api.retry(value.id); toast.success('定时投递已重新执行'); await load() } catch (cause) { toast.error(errorText(cause, '重试失败')) } }
function variant(stage: string) { return stage === 'DELIVERED' ? 'default' : stage === 'FAILED' ? 'destructive' : 'secondary' }
onMounted(load)

/** 行操作：失败/延迟的定时推送可重试 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '重试', icon: 'morph:refresh', visible: row => Boolean((row as { bindingId?: string }).bindingId) && ['FAILED', 'DELAYED'].includes((row as { stage: string }).stage), onClick: row => retry(row as never) },
]

</script>

<template><section><YdPageMain class="space-y-4"><YdAlert v-if="error" variant="destructive" title="加载失败" :description="error"/>        <YdTablePageLayout title="运行记录" :show-search="false">
            <template #table>
<YdLoadingOverlay :loading="loading"><YdResponsiveEnhancedTable
        :actions="rowActions"
  row-key="id" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[1080px]" border stripe :columns="columns" :data="rows"><template #cell-stage="{row}"><YdTag :variant="variant(row.original.stage)">{{ row.original.stage }}</YdTag></template><template #cell-mode="{row}">{{ row.original.bindingId ? '定时推送' : '即时回复' }}</template><template #cell-updatedAt="{row}">{{ dateTime(row.original.updatedAt) }}</template></YdResponsiveEnhancedTable></YdLoadingOverlay>
            </template>
            <template #footer>
      <YdPagination v-model:page="page" v-model:size="size" :total="total" @page-change="load" @size-change="load"/>
            </template>
          </YdTablePageLayout>
</YdPageMain></section></template>
