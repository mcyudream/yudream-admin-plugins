<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { ActionMenuItem, StatusColumn, TableColumn } from '@yudream/components'
import type { McWikiPluginModel } from '../composables/useMcWikiPlugin'
import type { WikiJob } from '../types'
import { YdPageHeader, YdPageMain, YdPagination, YdProgress, YdResponsiveEnhancedTable, useYdModal } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import JobLogModal from '../components/JobLogModal.vue'
import { jobStatusLabel, jobVersionLabel } from '../types'

const props = defineProps<{ model: McWikiPluginModel }>()
const confirm = useYdModal()

const columns: TableColumn<WikiJob>[] = [
  { accessorKey: 'version', header: '版本', minWidth: 110, fixed: 'left' },
  { accessorKey: 'phase', header: '阶段', width: 150 },
  { id: 'progress', header: '进度', width: 180 },
  { id: 'status', header: '状态', width: 100 },
  { accessorKey: 'updatedAt', header: '更新时间', width: 170 },
]

/** 任务状态：语义色胶囊 */
const jobStatusColumn: StatusColumn<WikiJob> = {
  statusKey: 'status',
  header: '状态',
  width: 110,
  statusMap: {
    DONE: { label: jobStatusLabel('DONE'), variant: 'default', tone: 'success' },
    FAILED: { label: jobStatusLabel('FAILED'), variant: 'destructive', tone: 'danger' },
    CANCELLED: { label: jobStatusLabel('CANCELLED'), variant: 'secondary', tone: 'neutral' },
    RUNNING: { label: jobStatusLabel('RUNNING'), variant: 'default', tone: 'info' },
    PENDING: { label: jobStatusLabel('PENDING'), variant: 'default', tone: 'warning' },
  },
}

/** 行操作统一收进操作菜单，删除保持确认流程 */
const rowActions: ActionMenuItem<WikiJob>[] = [
  { label: '日志', icon: 'morph:file-text', onClick: row => openLog(row) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => confirmDelete(row) },
]

const selectedJob = ref<WikiJob | null>(null)
const logOpen = ref(false)

const percent = computed(() => (job: WikiJob) => job.total > 0 ? Math.min(100, Math.round((job.done / job.total) * 100)) : 0)

function statusVariant(status: string) {
  if (status === 'DONE') {
    return 'default'
  }
  if (status === 'FAILED' || status === 'CANCELLED') {
    return 'destructive'
  }
  return 'secondary'
}

function formatTime(value?: number | null) {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function openLog(job: WikiJob) {
  selectedJob.value = job
  logOpen.value = true
}

function confirmDelete(job: WikiJob) {
  confirm.confirm({ title: '删除导入任务', content: `确认删除「${jobVersionLabel(job.version)}」的任务记录吗？已导入的版本数据不受影响。`, onConfirm: () => props.model.deleteJob(job) })
}

onMounted(() => props.model.loadJobs())
</script>

<template>
  <YdPageMain>
    <YdTablePageLayout title="导入任务" :show-search="false">
    <template #table>
<YdLoadingOverlay :loading="model.loading"><YdResponsiveEnhancedTable
      :columns="columns"
      :data="model.jobs"
      row-key="jobId"
      table-root-class="rounded-lg overflow-hidden"
      table-class="min-w-[760px]"
      border stripe column-visibility
      :status-column="jobStatusColumn"
      :actions="rowActions"
      empty-text="暂无导入任务"
    >
      <template #cell-version="{ row }"><strong>{{ jobVersionLabel(row.original.version) }}</strong></template>
      <template #cell-phase="{ row }">{{ row.original.phase }}</template>
      <template #cell-progress="{ row }">
        <div class="flex items-center gap-2">
          <YdProgress class="w-24" :model-value="percent(row.original)" />
          <span class="text-xs text-secondary-foreground/60">{{ row.original.done }} / {{ row.original.total }}</span>
        </div>
      </template>
      <template #cell-updatedAt="{ row }">{{ formatTime(row.original.updatedAt) }}</template>
    </YdResponsiveEnhancedTable></YdLoadingOverlay>
    </template>
    <template #footer>
      <YdPagination v-model:page="model.jobPager.page" v-model:size="model.jobPager.size" :total="model.jobPager.total" @page-change="model.loadJobs" @size-change="model.loadJobs" />
    </template>
  </YdTablePageLayout>


    <JobLogModal v-model="logOpen" :job="selectedJob" :api="model.api" />
  </YdPageMain>
</template>
