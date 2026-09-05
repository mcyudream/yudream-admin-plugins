<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { ActivityProofExportRecord } from '../types'
import { FaButton, FaCard, FaFileUpload, FaIcon, FaModal, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable } from '@yudream/components'
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
  { id: 'operation', header: '操作', width: 320, align: 'center', fixed: 'right' },
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
</script>

<template>
  <section class="proof-page">
    <FaPageHeader title="活动证明记录">
      <FaButton variant="outline" :loading="loading" @click="model.load">
        <FaIcon name="i-ri:refresh-line" />刷新
      </FaButton>
    </FaPageHeader>
    <FaPageMain>
      <FaResponsiveTable
        v-loading="loading"
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
        <template #cell-operation="{ row }">
          <div class="flex-center gap-2">
            <FaButton size="sm" variant="outline" :loading="acting" @click="model.download(row.original)">下载 Word</FaButton>
            <FaButton v-if="row.original.stampedPdfReady" size="sm" variant="outline" :loading="acting" @click="model.downloadStamped(row.original)">下载 PDF</FaButton>
            <FaButton size="sm" variant="outline" @click="openUpload(row.original)">上传 PDF</FaButton>
            <FaButton size="sm" variant="destructive" @click="model.remove(row.original)">删除</FaButton>
          </div>
        </template>
        <template #card="{ row }">
          <FaCard class="w-full">
            <div class="flex flex-col gap-3">
              <div class="flex items-center justify-between gap-2">
                <span class="min-w-0 break-words text-base font-semibold">{{ row.outputFilename }}</span>
              </div>
              <div class="flex flex-col gap-1 text-sm">
                <div v-if="row.activityName" class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">活动</span>
                  <span class="break-all">{{ row.activityName }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">参与人数</span>
                  <span>{{ row.participantCount }} 人<span v-if="row.unmatchedCount"> / {{ row.unmatchedCount }} 未匹配</span></span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">盖章 PDF</span>
                  <span>{{ row.stampedPdfReady ? row.stampedPdfFilename : '未上传' }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">生成时间</span>
                  <span>{{ formatTime(row.generatedAt) }}</span>
                </div>
              </div>
              <div class="flex flex-wrap gap-2 border-t pt-3">
                <FaButton size="sm" variant="outline" :loading="acting" @click="model.download(row)">下载 Word</FaButton>
                <FaButton v-if="row.stampedPdfReady" size="sm" variant="outline" :loading="acting" @click="model.downloadStamped(row)">下载 PDF</FaButton>
                <FaButton size="sm" variant="outline" @click="openUpload(row)">上传 PDF</FaButton>
                <FaButton size="sm" variant="destructive" @click="model.remove(row)">删除</FaButton>
              </div>
            </div>
          </FaCard>
        </template>
      </FaResponsiveTable>
      <FaPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        class="mt-3"
        @page-change="model.load"
        @size-change="model.load"
      />
      <FaModal
        v-model="uploadVisible"
        title="上传盖章 PDF"
        :description="uploadTarget ? `为「${uploadTarget.outputFilename}」上传盖章后的 PDF 文件。` : '上传盖章后的 PDF 文件。'"
        :show-confirm-button="false"
        show-cancel-button
        cancel-button-text="关闭"
      >
        <FaFileUpload v-model="uploadFiles" :max="1" :before-upload="file => file.type === 'application/pdf'" :http-request="handleUpload" description="拖放或点击选择 PDF 文件" />
      </FaModal>
    </FaPageMain>
  </section>
</template>
