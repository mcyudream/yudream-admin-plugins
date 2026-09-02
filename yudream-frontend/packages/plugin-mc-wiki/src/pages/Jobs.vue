<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { McWikiPluginModel } from '../composables/useMcWikiPlugin'
import type { WikiJob } from '../types'
import { FaButton, FaIcon, FaPageHeader, FaPageMain, FaPagination, FaProgress, FaResponsiveTable, FaTag, useFaModal } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import JobLogModal from '../components/JobLogModal.vue'
import { jobStatusLabel, jobVersionLabel } from '../types'

const props = defineProps<{ model: McWikiPluginModel }>()
const confirm = useFaModal()

const columns: TableColumn<WikiJob>[] = [
  { accessorKey: 'version', header: '版本', minWidth: 110, fixed: 'left' },
  { accessorKey: 'phase', header: '阶段', width: 150 },
  { id: 'progress', header: '进度', width: 180 },
  { id: 'status', header: '状态', width: 100 },
  { accessorKey: 'updatedAt', header: '更新时间', width: 170 },
  { id: 'operation', header: '操作', width: 170 },
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
  <FaPageHeader title="导入任务" description="查看版本导入进度、诊断日志与历史任务">
    <FaButton variant="outline" :loading="model.loading" @click="model.loadJobs"><FaIcon name="i-ri:refresh-line" />刷新</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaResponsiveTable
      v-loading="model.loading"
      :columns="columns"
      :data="model.jobs"
      row-key="jobId"
      table-root-class="max-w-full overflow-x-auto rounded-lg"
      table-class="min-w-[860px]"
      border stripe column-visibility
      empty-text="暂无导入任务"
    >
      <template #cell-version="{ row }"><strong>{{ jobVersionLabel(row.original.version) }}</strong></template>
      <template #cell-phase="{ row }">{{ row.original.phase }}</template>
      <template #cell-progress="{ row }">
        <div class="flex items-center gap-2">
          <FaProgress class="w-24" :model-value="percent(row.original)" />
          <span class="text-xs text-secondary-foreground/60">{{ row.original.done }} / {{ row.original.total }}</span>
        </div>
      </template>
      <template #cell-status="{ row }"><FaTag :variant="statusVariant(row.original.status)">{{ jobStatusLabel(row.original.status) }}</FaTag></template>
      <template #cell-updatedAt="{ row }">{{ formatTime(row.original.updatedAt) }}</template>
      <template #cell-operation="{ row }">
        <div class="flex-center gap-2">
          <FaButton size="sm" variant="outline" @click="openLog(row.original)">日志</FaButton>
          <FaButton size="sm" variant="destructive" @click="confirmDelete(row.original)">删除</FaButton>
        </div>
      </template>
      <template #card="{ row }">
        <div class="flex flex-col gap-3">
          <div class="flex items-center justify-between gap-2">
            <strong class="text-base">{{ jobVersionLabel(row.version) }}</strong>
            <FaTag :variant="statusVariant(row.status)">{{ jobStatusLabel(row.status) }}</FaTag>
          </div>
          <div class="flex flex-col gap-1 text-sm">
            <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">阶段</span><span>{{ row.phase }}</span></div>
            <div class="flex items-center gap-2"><span class="shrink-0 text-secondary-foreground/60">进度</span><FaProgress class="w-28" :model-value="percent(row)" /><span class="text-xs">{{ row.done }} / {{ row.total }}</span></div>
            <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">更新时间</span><span>{{ formatTime(row.updatedAt) }}</span></div>
          </div>
          <div class="flex flex-wrap gap-2">
            <FaButton size="sm" variant="outline" @click="openLog(row)">日志</FaButton>
            <FaButton size="sm" variant="destructive" @click="confirmDelete(row)">删除</FaButton>
          </div>
        </div>
      </template>
    </FaResponsiveTable>
    <FaPagination v-model:page="model.jobPager.page" v-model:size="model.jobPager.size" :total="model.jobPager.total" class="mt-3" @page-change="model.loadJobs" @size-change="model.loadJobs" />
    <JobLogModal v-model="logOpen" :job="selectedJob" :api="model.api" />
  </FaPageMain>
</template>
