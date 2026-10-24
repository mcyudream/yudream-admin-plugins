<script setup lang="ts">
import type { ActionMenuItem, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { DatasetRecord, DocJob, OfficeDocType, OfficePackage, PrecheckRow, PreviewResult } from '../types'
import { YdAlert, YdButton, YdDrawer, YdIcon, YdInput, YdLabel, YdLoadingOverlay, YdResponsiveEnhancedTable, YdSelect, YdSwitch, YdTablePageLayout, YdTag, YdTextarea, useYdModal, useYdToast } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createOfficeAdminApi, errorMessage, saveBlob } from '../api/office-api'
import { formatTime, jobStatusText } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const toast = useYdToast()
const confirm = useYdModal()
const api = createOfficeAdminApi(props.sdk)

const docTypes = ref<OfficeDocType[]>([])
const packages = ref<OfficePackage[]>([])
const datasetRecords = ref<DatasetRecord[]>([])
const loading = ref(false)
const creating = ref(false)

// 新建生成
const createForm = ref({
  docTypeCode: '',
  sourceType: 'MONTH',
  sourceId: '',
  period: new Date().toISOString().slice(0, 7),
})
const preview = ref<PreviewResult | null>(null)
const previewLoading = ref(false)

const sourceTypeOptions = [
  { label: '活动平台活动', value: 'ACTIVITY' },
  { label: '数据集记录', value: 'RECORD' },
  { label: '周期聚合（月度）', value: 'MONTH' },
]

const docTypeOptions = () => [
  { label: '选择类型', value: '' },
  ...docTypes.value.map(item => ({ label: item.name, value: item.code })),
]

const datasetOptions = () => [
  { label: '选择数据记录', value: '' },
  ...datasetRecords.value.map(record => ({
    label: `${record.schemaCode} / ${String(record.data.title || record.data.name || record.id)}`,
    value: record.id,
  })),
]

// 任务列表
const jobs = ref<DocJob[]>([])
const jobTotal = ref(0)
const jobPage = ref(1)
const jobSize = ref(10)
const jobsLoading = ref(false)

const jobColumns: TableColumn<DocJob>[] = [
  { id: 'docType', header: '文档类型', width: 160, enableSorting: false },
  { id: 'target', header: '目标', minWidth: 200, enableSorting: false },
  { id: 'status', header: '状态', width: 120, enableSorting: false },
  { accessorKey: 'updatedAt', header: '更新时间', width: 170 },
  { id: 'result', header: '产物', minWidth: 180, enableSorting: false },
]

const jobActions: ActionMenuItem<DocJob>[] = [
  { label: '详情', icon: 'morph:eye', onClick: row => openJob(row) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => removeJob(row) },
]

// 任务详情（抽屉）
const jobDrawerOpen = ref(false)
const job = ref<DocJob | null>(null)
const draftEntries = ref<{ key: string, value: string }[]>([])
const jobBusy = ref(false)

// 打包
const packageForm = ref({ code: '', period: new Date().toISOString().slice(0, 7), skipMissing: false })
const precheckRows = ref<PrecheckRow[]>([])
const packageLoading = ref(false)
const exporting = ref(false)

const packageOptions = () => [
  { label: '选择打包定义', value: '' },
  ...packages.value.map(item => ({ label: item.name, value: item.code })),
]

onMounted(load)

async function load() {
  loading.value = true
  try {
    docTypes.value = await api.docTypes()
    packages.value = await api.packages()
    await loadJobs()
  }
  catch (cause) {
    toast.error(errorMessage(cause, '办公数据加载失败'))
  }
  finally {
    loading.value = false
  }
}

async function loadJobs() {
  jobsLoading.value = true
  try {
    const data = await api.jobs({ page: jobPage.value, size: jobSize.value })
    jobs.value = data.records ?? []
    jobTotal.value = data.total ?? 0
  }
  finally {
    jobsLoading.value = false
  }
}

async function loadDatasetOptions() {
  try {
    const data = await api.dataset('', 1, 50)
    datasetRecords.value = data.records ?? []
  }
  catch {
    datasetRecords.value = []
  }
}

function onSourceTypeChange() {
  preview.value = null
  if (createForm.value.sourceType === 'RECORD') {
    void loadDatasetOptions()
  }
}

async function runPreview() {
  if (!createForm.value.docTypeCode) {
    toast.warning('请选择文档类型')
    return
  }
  previewLoading.value = true
  try {
    preview.value = await api.preview({
      docTypeCode: createForm.value.docTypeCode,
      sourceType: createForm.value.sourceType,
      sourceId: createForm.value.sourceId,
      period: createForm.value.period,
    })
  }
  catch (cause) {
    toast.error(errorMessage(cause, '映射预览失败'))
  }
  finally {
    previewLoading.value = false
  }
}

async function createJob() {
  if (!createForm.value.docTypeCode) {
    toast.warning('请选择文档类型')
    return
  }
  creating.value = true
  try {
    await api.createJob({
      docTypeCode: createForm.value.docTypeCode,
      sourceType: createForm.value.sourceType,
      sourceId: createForm.value.sourceId,
      period: createForm.value.period,
    })
    toast.success('任务已创建，可在下方任务列表中 AI 起草')
    await loadJobs()
  }
  catch (cause) {
    toast.error(errorMessage(cause, '创建失败'))
  }
  finally {
    creating.value = false
  }
}

async function openJob(row: DocJob) {
  job.value = await api.job(row.id)
  draftEntries.value = Object.entries(job.value.draft).map(([key, value]) => ({ key, value }))
  jobDrawerOpen.value = true
}

async function runAi() {
  if (!job.value || jobBusy.value) {
    return
  }
  jobBusy.value = true
  try {
    job.value = await api.runJobAi(job.value.id)
    draftEntries.value = Object.entries(job.value.draft).map(([key, value]) => ({ key, value }))
    toast.success('AI 草稿已生成，请编辑确认')
  }
  catch (cause) {
    toast.error(errorMessage(cause, 'AI 起草失败'))
  }
  finally {
    jobBusy.value = false
  }
}

async function saveDraft() {
  if (!job.value || jobBusy.value) {
    return
  }
  jobBusy.value = true
  try {
    const draft = Object.fromEntries(draftEntries.value.map(entry => [entry.key, entry.value]))
    job.value = await api.saveJobDraft(job.value.id, draft)
    toast.success('草稿已保存')
    await loadJobs()
  }
  catch (cause) {
    toast.error(errorMessage(cause, '草稿保存失败'))
  }
  finally {
    jobBusy.value = false
  }
}

async function render() {
  if (!job.value || jobBusy.value) {
    return
  }
  jobBusy.value = true
  try {
    job.value = await api.renderJob(job.value.id)
    toast.success('已渲染定稿')
    await loadJobs()
  }
  catch (cause) {
    toast.error(errorMessage(cause, '渲染失败'))
  }
  finally {
    jobBusy.value = false
  }
}

async function download() {
  if (!job.value?.resultFileKey) {
    return
  }
  const blob = await api.downloadJob(job.value.id)
  saveBlob(blob.data, job.value.resultFilename || `${job.value.id}.docx`)
}

function removeJob(row: DocJob) {
  confirm.confirm({
    title: '删除生成任务',
    content: '确认删除该任务吗？定稿产物将一并清理。',
    onConfirm: async () => {
      try {
        await api.deleteJob(row.id)
        toast.success('已删除')
        await loadJobs()
      }
      catch (cause) {
        toast.error(errorMessage(cause, '删除失败'))
      }
    },
  })
}

async function runPrecheck() {
  if (!packageForm.value.code) {
    toast.warning('请选择打包定义')
    return
  }
  packageLoading.value = true
  try {
    precheckRows.value = await api.packagePrecheck(packageForm.value.code, packageForm.value.period)
  }
  catch (cause) {
    toast.error(errorMessage(cause, '预检失败'))
  }
  finally {
    packageLoading.value = false
  }
}

async function exportZip() {
  if (!packageForm.value.code) {
    toast.warning('请选择打包定义')
    return
  }
  exporting.value = true
  try {
    const blob = await api.exportPackage(packageForm.value.code, packageForm.value.period, packageForm.value.skipMissing)
    saveBlob(blob.data, `ai-office-${packageForm.value.code}-${packageForm.value.period}.zip`)
  }
  catch (cause) {
    toast.error(errorMessage(cause, '打包导出失败'))
  }
  finally {
    exporting.value = false
  }
}
</script>

<template>
  <YdTablePageLayout title="生成与打包" :show-search="false">
    <template #stats>
      <div class="flex flex-col gap-3">
        <div class="flex flex-wrap items-end gap-2">
          <YdLabel label="文档类型">
            <YdSelect v-model="createForm.docTypeCode" class="w-52" :options="docTypeOptions()" />
          </YdLabel>
          <YdLabel label="目标">
            <YdSelect v-model="createForm.sourceType" class="w-44" :options="sourceTypeOptions" @change="onSourceTypeChange" />
          </YdLabel>
          <YdLabel v-if="createForm.sourceType === 'ACTIVITY'" label="活动 ID">
            <YdInput v-model="createForm.sourceId" class="w-56" placeholder="从活动平台复制" />
          </YdLabel>
          <YdLabel v-if="createForm.sourceType === 'RECORD'" label="数据记录">
            <YdSelect v-model="createForm.sourceId" class="w-56" :options="datasetOptions()" />
          </YdLabel>
          <YdLabel label="周期">
            <YdInput v-model="createForm.period" class="w-32" placeholder="yyyy-MM" />
          </YdLabel>
          <YdButton variant="outline" :loading="previewLoading" @click="runPreview">预览映射</YdButton>
          <YdButton :loading="creating" @click="createJob">创建任务</YdButton>
        </div>
        <table v-if="preview" class="office-preview-table">
          <thead>
            <tr><th>变量</th><th>数据源</th><th>解析值</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in preview.rows" :key="row.var">
              <td>{{ row.var }} <YdTag v-if="row.ai" variant="outline">AI</YdTag></td>
              <td>{{ row.source }}</td>
              <td :class="{ 'text-muted-foreground': row.missing }">{{ row.missing ? '（缺失）' : String(row.value) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <template #table>
      <YdAlert v-if="false" variant="destructive" />
      <YdLoadingOverlay :loading="jobsLoading">
        <YdResponsiveEnhancedTable
          :columns="jobColumns"
          :data="jobs"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[980px]"
          border
          stripe
          column-visibility
          :actions="jobActions"
          empty-text="还没有生成任务"
        >
          <template #cell-docType="{ row }">{{ row.original.docTypeCode }}</template>
          <template #cell-target="{ row }">{{ row.original.sourceType }} / {{ row.original.sourceId || row.original.period }}</template>
          <template #cell-status="{ row }">
            <YdTag :variant="row.original.status === 'final' ? 'default' : 'outline'">{{ jobStatusText(row.original.status) }}</YdTag>
            <div v-if="row.original.error" class="max-w-60 truncate text-xs text-muted-foreground" :title="row.original.error">{{ row.original.error }}</div>
          </template>
          <template #cell-updatedAt="{ row }">{{ formatTime(row.original.updatedAt) }}</template>
          <template #cell-result="{ row }">
            <span class="truncate" :title="row.original.resultFilename">{{ row.original.resultFilename || '—' }}</span>
          </template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>

    <template #footer>
      <div class="flex flex-col gap-3">
        <div class="flex flex-wrap items-end gap-2">
          <YdLabel label="打包定义">
            <YdSelect v-model="packageForm.code" class="w-52" :options="packageOptions()" />
          </YdLabel>
          <YdLabel label="周期">
            <YdInput v-model="packageForm.period" class="w-32" placeholder="yyyy-MM" />
          </YdLabel>
          <YdButton variant="outline" :loading="packageLoading" @click="runPrecheck">预检</YdButton>
          <span class="flex items-center gap-1 text-sm">跳过缺失 <YdSwitch v-model="packageForm.skipMissing" size="sm" /></span>
          <YdButton :loading="exporting" @click="exportZip">导出 zip</YdButton>
        </div>
        <table v-if="precheckRows.length" class="office-preview-table">
          <thead>
            <tr><th>文档类型</th><th>目标</th><th>状态</th><th>产物</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in precheckRows" :key="`${row.docTypeCode}-${row.targetId}`">
              <td>{{ row.docTypeName }}</td>
              <td>{{ row.targetLabel }}</td>
              <td>
                <YdTag :variant="row.ready ? 'default' : 'outline'">{{ row.ready ? '已定稿' : (row.missing ? '类型缺失' : '未定稿') }}</YdTag>
              </td>
              <td>{{ row.filename || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </YdTablePageLayout>

  <YdDrawer
    v-model="jobDrawerOpen"
    :title="`任务详情：${job?.docTypeCode || ''}`"
    :show-confirm-button="false"
    :footer="false"
  >
    <div v-if="job" class="flex flex-col gap-3">
      <div class="flex flex-wrap gap-2">
        <YdButton :loading="jobBusy" @click="runAi">
          <YdIcon name="i-ri:magic-line" />AI 起草
        </YdButton>
        <YdButton variant="outline" :loading="jobBusy" @click="saveDraft">
          <YdIcon name="i-ri:save-line" />保存草稿
        </YdButton>
        <YdButton variant="outline" :loading="jobBusy" @click="render">
          <YdIcon name="i-ri:file-word-line" />渲染定稿
        </YdButton>
        <YdButton v-if="job.resultFileKey" variant="outline" @click="download">
          <YdIcon name="i-ri:download-line" />下载 docx
        </YdButton>
      </div>
      <p class="text-sm text-muted-foreground">
        状态：{{ jobStatusText(job.status) }}。AI 产物一律进入草稿，人工编辑保存后才能渲染定稿。
      </p>
      <YdLabel v-for="entry in draftEntries" :key="entry.key" :label="`草稿：${entry.key}`">
        <YdTextarea v-model="entry.value" :rows="6" />
      </YdLabel>
      <p v-if="!draftEntries.length" class="text-sm text-muted-foreground">
        暂无草稿；点击「AI 起草」为 ai: 映射生成正文。
      </p>
    </div>
  </YdDrawer>
</template>
