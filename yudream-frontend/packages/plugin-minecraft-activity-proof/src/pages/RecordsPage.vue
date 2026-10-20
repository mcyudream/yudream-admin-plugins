<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn, ActionMenuItem } from '@yudream/components'
import type { ActivityProofExportRecord } from '../types'
import { YdFileUpload, YdModal, YdPagination, YdResponsiveEnhancedTable } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { useProofRecords } from '../composables/useProofRecords'
import { formatFileSize, formatTime } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
}>()

const model = useProofRecords(props.sdk)
const { loading, acting, records, pager } = model

const uploadFiles = ref<File[]>([])
const uploadVisible = ref(false)
const uploading = ref(false)
const uploadTarget = ref<ActivityProofExportRecord | null>(null)

const columns: TableColumn<ActivityProofExportRecord>[] = [
  { id: 'file', header: '文件', width: 260, fixed: 'left' },
  { accessorKey: 'activityName', header: '活动', width: 180 },
  { id: 'participants', header: '参与人数', width: 120, align: 'center' },
  { id: 'pdf', header: '盖章 PDF', width: 220 },
  { id: 'generatedAt', header: '生成时间', width: 180 },
]

onMounted(model.load)

function openUpload(record: ActivityProofExportRecord) {
  uploadTarget.value = record
  uploadFiles.value = []
  uploadVisible.value = true
}

async function handleUpload(options: { file: File }) {
  if (!uploadTarget.value || uploading.value) return
  uploading.value = true
  try {
    await model.uploadStampedPdf(uploadTarget.value, options.file)
    uploadVisible.value = false
  }
  finally {
    uploading.value = false
  }
}

/** 记录行操作：下载与删除 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '下载 Word', icon: 'morph:download', disabled: () => acting.value, onClick: row => model.download(row as never) },
  { label: '下载 PDF', icon: 'morph:file-text', visible: row => Boolean((row as { stampedPdfReady?: boolean }).stampedPdfReady), disabled: () => acting.value, onClick: row => model.downloadStamped(row as never) },
  { label: '上传 PDF', icon: 'morph:cloud-upload', onClick: row => openUpload(row as never) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => model.remove(row as never) },
]

</script>

<template>
  <YdTablePageLayout title="活动证明记录" :show-search="false">
    <template #table>
<YdLoadingOverlay :loading="loading"><YdResponsiveEnhancedTable
          :actions="rowActions"
        
        
        row-key="id"
        table-root-class="proof-table-scroll"
        table-class="proof-table-w1280"
        border
        stripe
        column-visibility
        :columns="columns"
        :data="records"
      >
        <template #cell-file="{ row }">
          <strong>{{ row.original.outputFilename }}</strong>
          <div>{{ row.original.serverName || row.original.serverId }}</div>
        </template>
        <template #cell-participants="{ row }">
          {{ row.original.participantCount }} 人<span v-if="row.original.unmatchedCount"> / {{ row.original.unmatchedCount }} 未匹配</span>
        </template>
        <template #cell-pdf="{ row }">
          <strong>{{ row.original.stampedPdfReady ? row.original.stampedPdfFilename : '未上传' }}</strong>
          <div v-if="row.original.stampedPdfReady">{{ formatFileSize(row.original.stampedPdfSize) }}</div>
        </template>
        <template #cell-generatedAt="{ row }">{{ formatTime(row.original.generatedAt) }}</template>
</YdResponsiveEnhancedTable></YdLoadingOverlay>
    </template>
    <template #footer>
      <YdPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        @page-change="model.load"
        @size-change="model.load"
      />
    </template>
  </YdTablePageLayout>

  <YdModal
    v-model="uploadVisible"
    title="上传盖章 PDF"
    :description="uploadTarget ? `为「${uploadTarget.outputFilename}」上传盖章后的 PDF 文件。` : '上传盖章后的 PDF 文件。'"
    :show-confirm-button="false"
    show-cancel-button
    cancel-button-text="关闭"
  >
    <YdFileUpload v-model="uploadFiles" :max="1" :before-upload="file => file.type === 'application/pdf'" :http-request="handleUpload" description="拖放或点击选择 PDF 文件" />
  </YdModal>
</template>
