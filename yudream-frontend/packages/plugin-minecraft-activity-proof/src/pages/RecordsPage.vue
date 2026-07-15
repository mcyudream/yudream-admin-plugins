<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { ActivityProofModel } from '../composables/useActivityProof'
import type { ActivityProofExportRecord } from '../types'
import { FaButton, FaFileUpload, FaIcon, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaTable } from '@yudream/components'
import { ref } from 'vue'

const props = defineProps<{
  model: ActivityProofModel
}>()
const uploadFiles = ref([])

const columns: TableColumn<ActivityProofExportRecord>[] = [
  { id: 'file', header: '文件', width: 260, fixed: 'left' },
  { accessorKey: 'activityName', header: '活动', width: 180 },
  { id: 'participants', header: '参与人数', width: 120, align: 'center' },
  { id: 'pdf', header: '盖章 PDF', width: 220 },
  { id: 'generatedAt', header: '生成时间', width: 180 },
  { id: 'operation', header: '操作', width: 400, align: 'center', fixed: 'right' },
]

async function pageChanged() { await props.model.loadRecords() }
</script>

<template>
  <section class="proof-page">
    <FaPageHeader title="活动证明记录">
      <FaButton variant="outline" :loading="model.loading" @click="model.loadRecords">
        <FaIcon name="i-ri:refresh-line" />刷新
      </FaButton>
    </FaPageHeader>
    <FaPageMain>
      <FaTable row-key="id" table-root-class="max-w-full overflow-x-auto rounded-lg" table-class="min-w-[1200px]" border stripe column-visibility :columns="columns" :data="model.exports">
        <template #toolbar><FaSearchBar class="w-full"><FaButton variant="outline" :loading="model.loading" @click="model.loadRecords"><FaIcon name="i-ri:refresh-line" />刷新</FaButton></FaSearchBar></template>
        <template #cell-file="{ row }"><strong>{{ row.original.outputFilename }}</strong><div>{{ row.original.serverName || row.original.serverId }}</div></template>
        <template #cell-participants="{ row }">{{ row.original.participantCount }} 人<span v-if="row.original.unmatchedCount"> / {{ row.original.unmatchedCount }} 未匹配</span></template>
        <template #cell-pdf="{ row }"><strong>{{ row.original.stampedPdfReady ? row.original.stampedPdfFilename : '未上传' }}</strong><div v-if="row.original.stampedPdfReady">{{ model.formatFileSize(row.original.stampedPdfSize) }}</div></template>
        <template #cell-generatedAt="{ row }">{{ model.formatTime(row.original.generatedAt) }}</template>
        <template #cell-operation="{ row }">
          <div class="flex-center gap-2">
            <FaButton size="sm" variant="outline" @click="model.openDownload(row.original)">下载 Word</FaButton>
            <FaButton v-if="row.original.stampedPdfReady" size="sm" variant="outline" @click="model.openStampedPdf(row.original)">下载 PDF</FaButton>
            <FaFileUpload v-model="uploadFiles" :max="1" :before-upload="file => file.type === 'application/pdf'" :http-request="options => model.uploadStampedPdfFile(row.original, options.file)" description="上传 PDF" />
            <FaButton size="sm" variant="destructive" @click="model.deleteExportRecord(row.original)">删除</FaButton>
          </div>
        </template>
      </FaTable>
      <FaPagination v-model:page="model.exportsPager.page" v-model:size="model.exportsPager.size" :total="model.exportsPager.total" class="mt-3" @page-change="pageChanged" @size-change="pageChanged" />
    </FaPageMain>
  </section>
</template>
