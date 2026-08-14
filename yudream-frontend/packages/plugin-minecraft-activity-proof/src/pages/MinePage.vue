<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { ActivityProofModel } from '../composables/useActivityProof'
import type { ActivityProofExportRecord } from '../types'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable } from '@yudream/components'

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
      <FaResponsiveTable row-key="id" table-root-class="max-w-full overflow-x-auto rounded-lg" border stripe :columns="columns" :data="model.myExports">
        <template #cell-uploadedAt="{ row }">{{ model.formatTime(row.original.stampedPdfUploadedAt) }}</template>
        <template #cell-operation="{ row }"><FaButton size="sm" variant="outline" @click="model.openStampedPdf(row.original)">下载 PDF</FaButton></template>
        <template #card="{ row }">
          <FaCard class="w-full">
            <div class="flex flex-col gap-3">
              <div class="flex items-center justify-between gap-2">
                <span class="min-w-0 break-words text-base font-semibold">{{ row.activityName }}</span>
              </div>
              <div class="flex flex-col gap-1 text-sm">
                <div v-if="row.serverName" class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">服务器</span>
                  <span class="break-all">{{ row.serverName }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">盖章时间</span>
                  <span>{{ model.formatTime(row.stampedPdfUploadedAt) }}</span>
                </div>
              </div>
              <div class="flex flex-wrap gap-2 border-t pt-3">
                <FaButton size="sm" variant="outline" @click="model.openStampedPdf(row)">下载 PDF</FaButton>
              </div>
            </div>
          </FaCard>
        </template>
      </FaResponsiveTable>
      <FaPagination v-model:page="model.myExportsPager.page" v-model:size="model.myExportsPager.size" :total="model.myExportsPager.total" class="mt-3" @page-change="pageChanged" @size-change="pageChanged" />
    </FaPageMain>
  </section>
</template>
