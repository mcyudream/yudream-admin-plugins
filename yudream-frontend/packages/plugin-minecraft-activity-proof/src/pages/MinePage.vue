<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { ActivityProofModel } from '../composables/useActivityProof'
import type { ActivityProofExportRecord } from '../types'
import { FaButton, FaIcon, FaPageHeader, FaPageMain, FaPagination, FaTable } from '@yudream/components'

const props = defineProps<{
  model: ActivityProofModel
}>()
const columns: TableColumn<ActivityProofExportRecord>[] = [
  { accessorKey: 'activityName', header: '活动', width: 240 },
  { accessorKey: 'serverName', header: '服务器', width: 180 },
  { id: 'uploadedAt', header: '盖章时间', width: 180 },
  { id: 'operation', header: '操作', width: 140, align: 'center', fixed: 'right' },
]
async function pageChanged() { await props.model.loadMine() }
</script>

<template>
  <section class="proof-page">
    <FaPageHeader title="我的活动证明"><FaButton variant="outline" :loading="model.loading" @click="model.loadMine"><FaIcon name="i-ri:refresh-line" />刷新</FaButton></FaPageHeader>
    <FaPageMain>
      <FaTable row-key="id" table-root-class="rounded-lg overflow-hidden" border stripe :columns="columns" :data="model.myExports">
        <template #cell-uploadedAt="{ row }">{{ model.formatTime(row.original.stampedPdfUploadedAt) }}</template>
        <template #cell-operation="{ row }"><FaButton size="sm" variant="outline" @click="model.openStampedPdf(row.original)">下载 PDF</FaButton></template>
      </FaTable>
      <FaPagination v-model:page="model.myExportsPager.page" v-model:size="model.myExportsPager.size" :total="model.myExportsPager.total" class="mt-3" @page-change="pageChanged" @size-change="pageChanged" />
    </FaPageMain>
  </section>
</template>
