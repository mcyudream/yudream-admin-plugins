<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { DatasetRecord, DocJob, OfficeDocType, OfficePackage, PrecheckRow, PreviewResult } from '../types'
import { YdButton, YdIcon, YdInput, YdModal, YdPageHeader, YdPageMain, YdSelect, YdSwitch, YdTag, YdTextarea, useYdModal, useYdToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
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

// 新建生成
const createForm = reactive({
  docTypeCode: '',
  sourceType: 'MONTH',
  sourceId: '',
  period: new Date().toISOString().slice(0, 7),
})
const preview = ref<PreviewResult | null>(null)
const previewLoading = ref(false)
const creating = ref(false)

// 任务列表
const jobs = ref<DocJob[]>([])
const jobPager = reactive({ page: 1, size: 10, total: 0 })
const jobFilter = reactive({ status: '' })

// 任务详情（草稿编辑）
const jobModalVisible = ref(false)
const job = ref<DocJob | null>(null)
const draftEntries = ref<{ key: string, value: string }[]>([])
const jobBusy = ref(false)

// 打包
const packageForm = reactive({ code: '', period: new Date().toISOString().slice(0, 7), skipMissing: false })
const precheckRows = ref<PrecheckRow[]>([])
const packageLoading = ref(false)
const exporting = ref(false)

const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '待起草', value: 'draft' },
  { label: '草稿待定稿', value: 'drafted' },
  { label: '已定稿', value: 'final' },
  { label: '失败', value: 'failed' },
]

const sourceTypeOptions = [
  { label: '活动平台活动', value: 'ACTIVITY' },
  { label: '数据集记录', value: 'RECORD' },
  { label: '周期聚合（月度）', value: 'MONTH' },
]

const datasetOptions = computed(() => [
  { label: '选择数据记录', value: '' },
  ...datasetRecords.value.map(record => ({
    label: `${record.schemaCode} / ${String(record.data.title || record.data.name || record.id)}`,
    value: record.id,
  })),
])

const packageOptions = computed(() => [
  { label: '选择打包定义', value: '' },
  ...packages.value.map(item => ({ label: item.name, value: item.code })),
])

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
  const data = await api.jobs({ status: jobFilter.status || undefined, page: jobPager.page, size: jobPager.size })
  jobs.value = data.records ?? []
  jobPager.total = data.total ?? 0
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
  if (createForm.sourceType === 'RECORD') {
    void loadDatasetOptions()
  }
}

async function runPreview() {
  if (!createForm.docTypeCode) {
    toast.warning('请选择文档类型')
    return
  }
  previewLoading.value = true
  try {
    preview.value = await api.preview({
      docTypeCode: createForm.docTypeCode,
      sourceType: createForm.sourceType,
      sourceId: createForm.sourceId,
      period: createForm.period,
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
  if (!createForm.docTypeCode) {
    toast.warning('请选择文档类型')
    return
  }
  creating.value = true
  try {
    await api.createJob({
      docTypeCode: createForm.docTypeCode,
      sourceType: createForm.sourceType,
      sourceId: createForm.sourceId,
      period: createForm.period,
    })
    toast.success('任务已创建，可进行 AI 起草')
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
  jobModalVisible.value = true
}

async function runAi() {
  if (!job.value) {
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
  if (!job.value) {
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
  if (!job.value) {
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
  if (!packageForm.code) {
    toast.warning('请选择打包定义')
    return
  }
  packageLoading.value = true
  try {
    precheckRows.value = await api.packagePrecheck(packageForm.code, packageForm.period)
  }
  catch (cause) {
    toast.error(errorMessage(cause, '预检失败'))
  }
  finally {
    packageLoading.value = false
  }
}

async function exportZip() {
  if (!packageForm.code) {
    toast.warning('请选择打包定义')
    return
  }
  exporting.value = true
  try {
    const blob = await api.exportPackage(packageForm.code, packageForm.period, packageForm.skipMissing)
    saveBlob(blob.data, `ai-office-${packageForm.code}-${packageForm.period}.zip`)
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
  <section class="office-page">
    <YdPageHeader title="生成与打包" />

    <YdPageMain>
      <h4 class="mb-2 font-medium">生成文档</h4>
      <div class="flex flex-wrap items-end gap-2">
        <label class="flex flex-col gap-1 text-sm">
          <span>文档类型</span>
          <YdSelect
            v-model="createForm.docTypeCode"
            class="w-52"
            :options="[{ label: '选择类型', value: '' }, ...docTypes.map(item => ({ label: item.name, value: item.code }))]"
          />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span>目标</span>
          <YdSelect v-model="createForm.sourceType" class="w-44" :options="sourceTypeOptions" @change="onSourceTypeChange" />
        </label>
        <label v-if="createForm.sourceType === 'ACTIVITY'" class="flex flex-col gap-1 text-sm">
          <span>活动 ID（从活动平台复制）</span>
          <YdInput v-model="createForm.sourceId" class="w-56" placeholder="活动ID" />
        </label>
        <label v-if="createForm.sourceType === 'RECORD'" class="flex flex-col gap-1 text-sm">
          <span>数据记录</span>
          <YdSelect v-model="createForm.sourceId" class="w-56" :options="datasetOptions" />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span>周期</span>
          <YdInput v-model="createForm.period" class="w-32" placeholder="yyyy-MM" />
        </label>
        <YdButton variant="outline" :loading="previewLoading" @click="runPreview">
          <YdIcon name="i-ri:eye-line" />预览映射
        </YdButton>
        <YdButton :loading="creating" @click="createJob">
          <YdIcon name="i-ri:add-line" />创建任务
        </YdButton>
      </div>

      <table v-if="preview" class="office-preview-table mt-3">
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
    </YdPageMain>

    <YdPageMain>
      <div class="mb-2 flex items-center justify-between">
        <h4 class="font-medium">生成任务</h4>
        <YdSelect v-model="jobFilter.status" class="w-36" :options="statusOptions" @change="jobPager.page = 1; loadJobs()" />
      </div>
      <table v-if="jobs.length" class="office-preview-table">
        <thead>
          <tr><th>文档类型</th><th>目标</th><th>状态</th><th>更新时间</th><th>产物</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in jobs" :key="row.id">
            <td>{{ row.docTypeCode }}</td>
            <td>{{ row.sourceType }} / {{ row.sourceId || row.period }}</td>
            <td>
              <YdTag :variant="row.status === 'final' ? 'default' : 'outline'">{{ jobStatusText(row.status) }}</YdTag>
              <div v-if="row.error" class="text-xs text-muted-foreground">{{ row.error }}</div>
            </td>
            <td>{{ formatTime(row.updatedAt) }}</td>
            <td>{{ row.resultFilename || '—' }}</td>
            <td class="flex gap-1">
              <YdButton size="sm" variant="outline" @click="openJob(row)">详情</YdButton>
              <YdButton size="sm" variant="outline" @click="removeJob(row)">删除</YdButton>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="text-sm text-muted-foreground">还没有生成任务</p>
    </YdPageMain>

    <YdPageMain>
      <h4 class="mb-2 font-medium">打包导出</h4>
      <div class="flex flex-wrap items-end gap-2">
        <YdSelect v-model="packageForm.code" class="w-52" :options="packageOptions" />
        <YdInput v-model="packageForm.period" class="w-32" placeholder="yyyy-MM" />
        <YdButton variant="outline" :loading="packageLoading" @click="runPrecheck">
          <YdIcon name="i-ri:check-double-line" />预检
        </YdButton>
        <span class="flex items-center gap-1 text-sm">跳过缺失 <YdSwitch v-model="packageForm.skipMissing" size="sm" /></span>
        <YdButton :loading="exporting" @click="exportZip">
          <YdIcon name="i-ri:folder-zip-line" />导出 zip
        </YdButton>
      </div>
      <table v-if="precheckRows.length" class="office-preview-table mt-3">
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
    </YdPageMain>

    <YdModal
      v-model="jobModalVisible"
      :title="`任务详情：${job?.docTypeCode || ''}`"
      :show-confirm-button="false"
      show-cancel-button
      cancel-button-text="关闭"
      width-class="max-w-3xl"
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
        <label v-for="entry in draftEntries" :key="entry.key" class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">草稿：{{ entry.key }}</span>
          <YdTextarea v-model="entry.value" :rows="6" />
        </label>
        <p v-if="!draftEntries.length" class="text-sm text-muted-foreground">
          暂无草稿；点击「AI 起草」为 ai: 映射生成正文，或在此后手动添加。
        </p>
      </div>
    </YdModal>
  </section>
</template>
