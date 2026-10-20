<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { ActionMenuItem, StatusColumn, TableColumn } from '@yudream/components'
import type { McWikiPluginModel } from '../composables/useMcWikiPlugin'
import type { WikiJob, WikiVersionRow } from '../types'
import { YdButton, YdCard, YdIcon, YdInput, YdPagination, YdResponsiveEnhancedTable, YdSelect, YdTag, useYdModal, useYdToast } from '@yudream/components'
import { onMounted, ref, watch } from 'vue'
import JobLogModal from '../components/JobLogModal.vue'

const props = defineProps<{ model: McWikiPluginModel }>()
const confirm = useYdModal()
const toast = useYdToast()

const typeOptions = [
  { label: '全部类型', value: '' },
  { label: '正式版', value: 'release' },
  { label: '快照', value: 'snapshot' },
  { label: '旧测试版', value: 'old_beta' },
  { label: '旧阿尔法版', value: 'old_alpha' },
]

const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '已导入', value: 'imported' },
  { label: '未导入', value: 'unimported' },
  { label: '已发布', value: 'published' },
]

const columns: TableColumn<WikiVersionRow>[] = [
  { accessorKey: 'id', header: '版本', minWidth: 130, fixed: 'left' },
  { id: 'type', header: '类型', width: 90 },
  { id: 'imported', header: '导入状态', width: 100 },
  { id: 'counts', header: '物品 / 配方', width: 120 },
  { id: 'published', header: '发布状态', width: 100 },
  { accessorKey: 'releaseTime', header: '发布时间', width: 170 },
]

/** 导入状态：语义色胶囊 */
const importStatusColumn: StatusColumn<WikiVersionRow> = {
  statusKey: 'imported',
  header: '导入状态',
  width: 100,
  statusMap: {
    'true': { label: '已导入', variant: 'default', tone: 'success' },
    'false': { label: '未导入', variant: 'secondary', tone: 'neutral' },
  },
}

/** 发布状态列 */
const publishStatusColumn: StatusColumn<WikiVersionRow> = {
  statusKey: 'published',
  header: '发布状态',
  width: 100,
  statusMap: {
    'true': { label: '已发布', variant: 'default', tone: 'info' },
    'false': { label: '-', variant: 'secondary', tone: 'neutral' },
  },
}

/** 行操作统一收进操作菜单；发布/删除按导入状态禁用并保持确认流程 */
const rowActions: ActionMenuItem<WikiVersionRow>[] = [
  { label: row => (row.imported ? '重新导入' : '导入'), icon: 'morph:download', onClick: row => importVersion(row.id) },
  { label: '发布', icon: 'morph:upload', visible: row => !row.published, disabled: row => !row.imported, onClick: row => props.model.publishVersion(row.id) },
  { label: '取消发布', icon: 'morph:undo', visible: row => row.published, onClick: row => confirmUnpublish(row) },
  { label: '删除数据', icon: 'morph:trash-2', danger: true, disabled: row => !row.imported, onClick: row => confirmDeleteData(row) },
]

const selectedJob = ref<WikiJob | null>(null)
const logOpen = ref(false)

function typeLabel(type: string) {
  return typeOptions.find(option => option.value === type)?.label ?? type
}

function formatTime(value?: string | null) {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function formatTimestamp(value?: number | string) {
  const time = Number(value)
  if (!value || !Number.isFinite(time) || time <= 0) {
    return '-'
  }
  return new Date(time).toLocaleString('zh-CN', { hour12: false })
}

async function openJob(started: { jobId: string, streamId: string }) {
  const job = await props.model.api.job(started.jobId)
  selectedJob.value = { ...job, streamId: started.streamId }
  logOpen.value = true
}

async function importVersion(version: string) {
  try {
    await openJob(await props.model.api.importVersion(version))
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '导入任务创建失败')
  }
}

async function updateRenders() {
  try {
    await openJob(await props.model.api.updateRenders())
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '渲染资产更新任务创建失败')
  }
}

function confirmDeleteData(row: WikiVersionRow) {
  confirm.confirm({
    title: '删除版本数据',
    content: `确认删除 ${row.id} 已导入的 ${row.items} 个物品、${row.recipes} 个配方与遗留贴图吗？${row.published ? '该版本当前已发布，删除后会同时取消发布。' : ''}`,
    onConfirm: () => props.model.deleteVersionData(row.id),
  })
}

function confirmUnpublish(row: WikiVersionRow) {
  confirm.confirm({
    title: '取消发布',
    content: `确认将 ${row.id} 从公开百科取消发布吗？公开页将无法再查询该版本。`,
    onConfirm: () => props.model.unpublishVersion(row.id),
  })
}

watch(logOpen, (open) => {
  if (!open) {
    void props.model.loadVersions()
    void props.model.loadRenders()
  }
})

onMounted(() => {
  void props.model.loadVersions()
  void props.model.loadMeta()
  void props.model.loadRenders()
})
</script>

<template>
  <YdTablePageLayout title="百科版本">
    <template #actions>
      <YdButton variant="outline" :loading="model.loading" @click="model.refreshVersions"><YdIcon name="i-ri:refresh-line" />刷新清单</YdButton>
    </template>
    <template #search>
      <div class="yd-filter-grid" style="--yd-filter-columns: 3">
        <YdInput v-model="model.versionFilters.keyword" placeholder="搜索版本号，如 1.12.2" clearable class="w-full" @keydown.enter="model.applyVersionFilters" @clear="model.applyVersionFilters" />
        <YdSelect v-model="model.versionFilters.type" :options="typeOptions" class="w-full" @change="model.applyVersionFilters" />
        <YdSelect v-model="model.versionFilters.status" :options="statusOptions" class="w-full" @change="model.applyVersionFilters" />
        <div class="flex items-center justify-end gap-2">
          <YdButton variant="outline" @click="model.applyVersionFilters">查询</YdButton>
        </div>
      </div>
    </template>
    <template #table>
      <div class="flex h-full min-h-0 flex-col gap-3">
        <YdCard class="flex-none">
          <div class="flex flex-wrap items-center gap-3">
            <YdIcon name="i-ri:image-2-line" class="text-2xl text-secondary-foreground/60" />
            <div class="flex min-w-0 flex-1 flex-col gap-1">
              <strong class="text-sm">共享渲染资产</strong>
              <span v-if="model.renderMeta?.updated" class="text-xs text-secondary-foreground/60">
                来源 Owen1212055/mc-assets（{{ model.renderMeta.commit }}，Minecraft {{ model.renderMeta.gameVersion || '未知' }}）·
                物品渲染 {{ model.renderMeta.items }} · 生物渲染 {{ model.renderMeta.entitiesIsometric }} · 更新于 {{ formatTimestamp(model.renderMeta.updatedAt) }}
              </span>
              <span v-else class="text-xs text-secondary-foreground/60">尚未更新渲染资产，公开页图标将显示回退占位。所有版本共用同一份渲染图，更新一次即可。</span>
            </div>
            <YdButton variant="outline" @click="updateRenders"><YdIcon name="i-ri:download-cloud-2-line" />一键更新渲染资产</YdButton>
          </div>
        </YdCard>
<YdLoadingOverlay :loading="model.loading" class="min-h-0 flex-1"><YdResponsiveEnhancedTable
      :actions="rowActions"
      :status-column="importStatusColumn"
      :columns="columns"
      :data="model.versions"
      row-key="id"
      table-root-class="rounded-lg overflow-hidden"
      table-class="min-w-[1020px]"
      border stripe column-visibility
      empty-text="暂无版本数据，请先刷新官方版本清单"
    >
      <template #cell-id="{ row }"><strong>{{ row.original.id }}</strong><YdTag v-if="row.original.latest" class="ml-2" variant="secondary">最新</YdTag></template>
      <template #cell-type="{ row }">{{ typeLabel(row.original.type) }}</template>
      <template #cell-counts="{ row }"><span v-if="row.original.imported">{{ row.original.items }} / {{ row.original.recipes }}</span><span v-else>-</span></template>
      <template #cell-published="{ row }"><span class="yd-status-tone" :data-tone="row.original.published ? 'info' : 'neutral'">{{ row.original.published ? '已发布' : '-' }}</span></template>
      <template #cell-releaseTime="{ row }">{{ formatTime(row.original.releaseTime) }}</template>
    </YdResponsiveEnhancedTable></YdLoadingOverlay>
      </div>
    </template>
    <template #footer>
      <YdPagination v-model:page="model.versionPager.page" v-model:size="model.versionPager.size" :total="model.versionPager.total" @page-change="model.loadVersions" @size-change="model.applyVersionFilters" />
    </template>
  </YdTablePageLayout>

  <JobLogModal v-model="logOpen" :job="selectedJob" :api="model.api" />
</template>
