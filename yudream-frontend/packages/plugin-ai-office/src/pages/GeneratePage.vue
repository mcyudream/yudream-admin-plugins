<script setup lang="ts">
import type { ActionMenuItem, SearchConfig, SearchFieldConfig, SearchOptionsSource, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { DatasetRecord, DocJob, OfficeDocType, PreviewResult } from '../types'
import { YdButton, YdDrawer, YdDynamicSearchForm, YdIcon, YdInput, YdLoadingOverlay, YdModal, YdResponsiveEnhancedTable, YdSelect, YdTablePageLayout, YdTag, YdTextarea, useYdModal, useYdToast } from '@yudream/components'
import { onMounted, reactive, ref } from 'vue'
import { createOfficeAdminApi, errorMessage, saveBlob } from '../api/office-api'
import { formatTime, jobStatusText } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const toast = useYdToast()
const confirm = useYdModal()
const api = createOfficeAdminApi(props.sdk)

const docTypes = ref<OfficeDocType[]>([])
const datasetRecords = ref<DatasetRecord[]>([])
const loading = ref(false)

const sourceTypeOptions = [
  { label: '活动平台活动', value: 'ACTIVITY' },
  { label: '数据对象记录', value: 'RECORD' },
  { label: '周期聚合', value: 'MONTH' },
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

// 任务列表 + 筛选
const jobs = ref<DocJob[]>([])
const jobTotal = ref(0)
const jobPage = ref(1)
const jobSize = ref(10)
const jobsLoading = ref(false)
const jobFilter = reactive({ docTypeCode: '', status: '' })
const searchQuery = ref<Record<string, any>>({ docTypeCode: '', status: '' })

type SearchFieldInput = Partial<Omit<SearchFieldConfig, 'controlType'>> & Pick<SearchFieldConfig, 'fieldName' | 'label'> & { controlType?: 'TEXT' | 'SELECT' }

function searchField(config: SearchFieldInput): SearchFieldConfig {
  const { controlType = 'TEXT', order = 0, showWhenFolded = false, width = '200px', ...rest } = config
  return { ...rest, fieldName: config.fieldName, label: config.label, controlType: controlType as SearchFieldConfig['controlType'], order, showWhenFolded, width }
}

const jobStatusOptions = () => [
  { label: '待起草', value: 'draft' },
  { label: '草稿待定稿', value: 'drafted' },
  { label: '已定稿', value: 'final' },
  { label: '失败', value: 'failed' },
]

const docTypeFilterOptions = () => docTypes.value.map(item => ({ label: item.name, value: item.code }))

const jobSearchConfig: SearchConfig = {
  fields: [
    searchField({ fieldName: 'docTypeCode', label: '文档类型', controlType: 'SELECT', order: 1, width: '220px' }),
    searchField({ fieldName: 'status', label: '状态', controlType: 'SELECT', order: 2, width: '180px' }),
  ],
}

const jobOptionsSources: SearchOptionsSource[] = [
  { id: 'docTypeCodeOptions', loader: docTypeFilterOptions },
  { id: 'statusOptions', loader: jobStatusOptions },
]

function applyJobSearch() {
  jobFilter.docTypeCode = String(searchQuery.value.docTypeCode ?? '')
  jobFilter.status = String(searchQuery.value.status ?? '')
  jobPage.value = 1
  loadJobs()
}

function resetJobSearch() {
  jobFilter.docTypeCode = ''
  jobFilter.status = ''
  searchQuery.value = { docTypeCode: '', status: '' }
  jobPage.value = 1
  loadJobs()
}

const jobColumns: TableColumn<DocJob>[] = [
  { id: 'docType', header: '文档类型', width: 160 },
  { id: 'target', header: '目标', minWidth: 200 },
  { id: 'status', header: '状态', width: 120 },
  { accessorKey: 'updatedAt', header: '更新时间', width: 170 },
  { id: 'result', header: '产物', minWidth: 180 },
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

// 新建任务（弹窗内完成：表单 → 预览 → 创建）
const createVisible = ref(false)
const creating = ref(false)
const previewLoading = ref(false)
const preview = ref<PreviewResult | null>(null)
const createForm = ref({
  docTypeCode: '',
  sourceType: 'MONTH',
  sourceId: '',
  period: new Date().toISOString().slice(0, 7),
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    docTypes.value = await api.docTypes()
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
    const data = await api.jobs({
      docTypeCode: jobFilter.docTypeCode || undefined,
      status: jobFilter.status || undefined,
      page: jobPage.value,
      size: jobSize.value,
    })
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

function openCreate() {
  createForm.value = {
    docTypeCode: '',
    sourceType: 'MONTH',
    sourceId: '',
    period: new Date().toISOString().slice(0, 7),
  }
  preview.value = null
  createVisible.value = true
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

async function submitCreate() {
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
    toast.success('任务已创建：到任务列表点「详情」进行 AI 起草与定稿')
    createVisible.value = false
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
</script>

<template>
  <YdTablePageLayout title="生成任务" :show-search="false" stats-title="生成流程">
    <template #stats>
      <div class="text-sm text-muted-foreground">
        流程：新建任务 → AI 起草（或手写草稿）→ 编辑确认 → 渲染定稿 → 到「打包导出」按周期组装 zip（缺料自动附清单）。周期支持 <code>yyyy-MM</code> 或时间范围 <code>yyyy-MM-dd/yyyy-MM-dd</code>。
      </div>
    </template>

    <template #actions>
      <YdButton @click="openCreate">
        <YdIcon name="i-ri:add-line" />新建任务
      </YdButton>
    </template>

    <template #search="{ fold }">
      <YdDynamicSearchForm
        v-model="searchQuery"
        :config="jobSearchConfig"
        :fold="fold"
        :options-sources="jobOptionsSources"
        @search="applyJobSearch"
        @reset="resetJobSearch"
      />
    </template>

    <template #table>
      <YdLoadingOverlay :loading="loading || jobsLoading">
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
          empty-text="还没有生成任务，点右上角「新建任务」开始"
        >
          <template #cell-docType="{ row }">{{ row.original.docTypeCode }}</template>
          <template #cell-target="{ row }">
            <span class="truncate" :title="`${row.original.sourceType} / ${row.original.sourceId || row.original.period}`">
              {{ row.original.sourceType }} / {{ String(row.original.sourceId || row.original.period).slice(0, 8) }}
            </span>
          </template>
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
      <YdPagination
        v-model:page="jobPage"
        v-model:size="jobSize"
        :total="jobTotal"
        @page-change="loadJobs"
        @size-change="loadJobs"
      />
    </template>
  </YdTablePageLayout>

  <YdModal
    v-model="createVisible"
    title="新建生成任务"
    confirm-button-text="创建任务"
    :confirm-button-loading="creating"
    :show-cancel-button="!creating"
    cancel-button-text="取消"
    width-class="max-w-3xl"
    @confirm="submitCreate"
  >
    <div class="flex flex-col gap-3">
      <div class="grid grid-cols-[repeat(2,minmax(0,1fr))] gap-3">
        <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">文档类型</span>
          <YdSelect v-model="createForm.docTypeCode" :options="docTypeOptions()" />
        </label>
        <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">目标</span>
          <YdSelect v-model="createForm.sourceType" :options="sourceTypeOptions" @change="onSourceTypeChange" />
        </label>
      </div>
      <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">活动 ID（从活动平台复制）</span>
        <YdInput v-model="createForm.sourceId" placeholder="活动ID" />
      </label>
      <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">数据记录</span>
        <YdSelect v-model="createForm.sourceId" :options="datasetOptions()" />
      </label>
      <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">周期（yyyy-MM 或 yyyy-MM-dd/yyyy-MM-dd）</span>
        <YdInput v-model="createForm.period" placeholder="2026-10" />
      </label>
      <div>
        <YdButton size="sm" variant="outline" :loading="previewLoading" @click="runPreview">
          <YdIcon name="i-ri:eye-line" />预览映射
        </YdButton>
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
  </YdModal>

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
      <label v-for="entry in draftEntries" :key="entry.key" class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">草稿：{{ entry.key }}</span>
        <YdTextarea v-model="entry.value" :rows="6" />
      </label>
      <p v-if="!draftEntries.length" class="text-sm text-muted-foreground">
        暂无草稿；点击「AI 起草」为 ai: 映射生成正文。
      </p>
    </div>
  </YdDrawer>
</template>
