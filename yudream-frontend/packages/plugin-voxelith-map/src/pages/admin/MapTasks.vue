<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { YdButton, YdCard, YdDrawer, YdIcon, YdPagination, YdProgress, YdSelect, YdTag, useYdToast, YdResponsiveEnhancedTable, type ActionMenuItem, YdTablePageLayout } from '@yudream/components'
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  formatError,
  formatTime,
  renderPhaseLabel,
  TASK_STATE_META,
  useVoxelithAdmin,
} from '../../composables/useVoxelithAdmin'
import { createVoxelithMapAdminApi, downloadTaskLog } from '../../api/voxelith-map-admin-api'
import { renderTaskProgress } from '../../composables/useRenderTaskProgress'
import type { RenderTask, RenderTaskKind, RenderTaskState } from '../../types'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const toast = useYdToast()
const api = createVoxelithMapAdminApi(props.sdk)
const model = useVoxelithAdmin(props.sdk)

// ---------------------------------------------------------------------------
// 任务中心：渲染与导入这类长任务共用一套任务模型（kind 区分），进度与日志同源。
// 长任务都在服务端执行——离开本页、刷新、甚至关掉浏览器都不影响它们继续跑。
// ---------------------------------------------------------------------------

const KIND_META: Record<RenderTaskKind, { label: string, icon: string }> = {
  RENDER: { label: '渲染', icon: 'i-mdi:cube-outline' },
  IMPORT: { label: '实例导入', icon: 'i-mdi:download-box-outline' },
  SYNC: { label: '存档同步', icon: 'i-mdi:sync' },
  DELETE: { label: '删除地图', icon: 'i-mdi:delete-outline' },
}

function kindOf(task: RenderTask): RenderTaskKind {
  return (task.kind ?? 'RENDER') as RenderTaskKind
}

function kindMeta(task: RenderTask): { label: string, icon: string } {
  return KIND_META[kindOf(task)] ?? KIND_META.RENDER
}

/** 状态标签元信息：未知状态兜底，避免模板里对字符串索引联合类型（后者会丢收窄）。 */
function stateMeta(state: string): { label: string, variant: 'default' | 'secondary' | 'destructive' | 'outline' } {
  return TASK_STATE_META[state as RenderTaskState] ?? { label: state, variant: 'secondary' }
}

const kindFilter = ref<'ALL' | RenderTaskKind>('ALL')
const stateFilter = ref<'ALL' | 'ACTIVE' | 'FINISHED'>('ALL')

const kindFilterOptions = [
  { label: '全部类型', value: 'ALL' },
  { label: '渲染', value: 'RENDER' },
  { label: '实例导入', value: 'IMPORT' },
  { label: '存档同步', value: 'SYNC' },
  { label: '删除地图', value: 'DELETE' },
]

const stateFilterOptions = [
  { label: '全部状态', value: 'ALL' },
  { label: '进行中', value: 'ACTIVE' },
  { label: '已结束', value: 'FINISHED' },
]

/** 最近在前：任务列表本身就是「最新发生了什么」的视图。 */
const filteredTasks = computed(() => {
  const tasks = [...model.tasks.value]
  tasks.sort((a, b) => String(b.createdAt ?? '').localeCompare(String(a.createdAt ?? '')))
  return tasks.filter((task) => {
    if (kindFilter.value !== 'ALL' && kindOf(task) !== kindFilter.value) return false
    const active = task.state === 'PENDING' || task.state === 'RUNNING'
    if (stateFilter.value === 'ACTIVE') return active
    if (stateFilter.value === 'FINISHED') return !active
    return true
  })
})

const page = ref(1)
const pageSize = ref(10)
const pagedTasks = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filteredTasks.value.slice(start, start + pageSize.value)
})

function clampPage(): void {
  const lastPage = Math.max(1, Math.ceil(filteredTasks.value.length / pageSize.value))
  if (page.value > lastPage) page.value = lastPage
}

watch(() => filteredTasks.value.length, clampPage)
watch(pageSize, clampPage)
watch([kindFilter, stateFilter], () => {
  page.value = 1
})

const columns = [
  { id: 'kind', header: '类型', width: 110 },
  { id: 'subject', header: '对象', width: 220 },
  { id: 'state', header: '状态', width: 96 },
  { id: 'progress', header: '进度', width: 260 },
  { id: 'message', header: '当前阶段', width: 240 },
  { id: 'createdAt', header: '开始于', width: 160 },
]

// ---------------------------------------------------------------------------
// 日志抽屉：失败原因、诊断与日志尾巴都在这里看（长行在框内横向滚动）。
// 抽屉里只有「日志尾部」（4000 字），完整日志按需下载。
// ---------------------------------------------------------------------------

const logTask = ref<RenderTask | null>(null)
const logDrawerOpen = ref(false)
const cancelling = ref('')
/** 正在拉取完整日志的任务 id（服务端要读文件，大日志稍慢） */
const logDownloading = ref('')

function openLog(task: RenderTask): void {
  logTask.value = task
  logDrawerOpen.value = true
}

/**
 * 下载该任务的**完整日志**。
 *
 * <p>任务文档只保留日志尾部（最近 4000 字，够看失败原因与最后阶段）；子进程的完整输出由服务端
 * 单独保留，直连地址带不上鉴权令牌，因此经 SDK 取回二进制再另存为文件。</p>
 */
async function downloadFullLog(task: RenderTask): Promise<void> {
  if (logDownloading.value) return
  logDownloading.value = task.id
  try {
    await downloadTaskLog(props.sdk, task.id)
  }
  catch (e) {
    toast.error(formatError(e, '完整日志获取失败'))
  }
  finally {
    logDownloading.value = ''
  }
}

async function cancelTask(task: RenderTask): Promise<void> {
  if (cancelling.value) return
  cancelling.value = task.id
  try {
    const res = await api.cancelTask(task.id)
    if (res.canceled) {
      toast.success('已请求取消任务')
    }
    else {
      toast.warning('任务已结束或不可取消')
    }
    await model.loadMaps(true)
  }
  catch (e) {
    toast.error(formatError(e, '取消失败'))
  }
  finally {
    cancelling.value = ''
  }
}

function openMap(task: RenderTask): void {
  if (!task.mapId) return
  void router.push({ path: '/platform/plugins/voxelith-map/map-detail', query: { id: task.mapId } })
}

/** 任务行操作：日志 / 取消 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '查看日志', icon: 'morph:file-text', onClick: row => openLog(row as never) },
  { label: '取消任务', icon: 'morph:close', visible: row => Boolean((row as { canCancel?: boolean }).canCancel), disabled: () => cancelling.value !== '', onClick: row => cancelTask(row as never) },
]

</script>

<template>

  <YdTablePageLayout title="任务中心">
      <template #search>
        <div class="yd-filter-grid" style="--yd-filter-columns: 2">
          <YdSelect v-model="kindFilter" :options="kindFilterOptions" class="w-full" />
          <YdSelect v-model="stateFilter" :options="stateFilterOptions" class="w-full" />
          <div class="flex items-center justify-end text-xs text-muted-foreground">
            共 {{ filteredTasks.length }} 个任务
          </div>
        </div>
      </template>
    <template #table>
      <YdCard class="mb-3 p-3">
        <p class="text-sm text-muted-foreground">
          渲染、实例导入这类长任务都在服务端执行：离开本页、刷新页面甚至关闭浏览器都不会中断它们，
          回到这里即可看到最新进度与日志。
        </p>
      </YdCard>
<YdResponsiveEnhancedTable
      :actions="rowActions"

      row-key="id"
      table-root-class="max-w-full overflow-x-auto rounded-lg"
      table-class="min-w-[1000px]"
      border
      stripe
      :columns="columns"
      :data="pagedTasks"
    >

      <template #cell-kind="{ row }">
        <YdTag variant="secondary">
          <YdIcon :name="kindMeta(row.original).icon" class="mr-1" />
          {{ kindMeta(row.original).label }}
        </YdTag>
      </template>

      <template #cell-subject="{ row }">
        <YdButton
          v-if="row.original.mapId"
          variant="link"
          class="h-auto min-w-0 px-0"
          @click="openMap(row.original)"
        >
          <span class="truncate">{{ row.original.subject || row.original.mapName || row.original.mapId }}</span>
        </YdButton>
        <span v-else class="truncate">{{ row.original.subject || '—' }}</span>
      </template>

      <template #cell-state="{ row }">
        <YdTag :variant="stateMeta(row.original.state).variant">
          {{ stateMeta(row.original.state).label }}
        </YdTag>
      </template>

      <template #cell-progress="{ row }">
        <YdProgress :model-value="renderTaskProgress(row.original)" />
        <span class="mt-1 block text-xs text-muted-foreground">
          {{ renderTaskProgress(row.original) }}%
        </span>
      </template>

      <template #cell-message="{ row }">
        <span class="block truncate" :title="row.original.message || ''">
          {{ row.original.state === 'FAILED'
            ? (row.original.error || '失败')
            : (row.original.message || renderPhaseLabel(row.original.phase) || '—') }}
        </span>
      </template>

      <template #cell-createdAt="{ row }">
        {{ formatTime(row.original.createdAt) }}
      </template>


</YdResponsiveEnhancedTable>
    </template>
    <template #footer>
      <YdPagination
      v-if="filteredTasks.length > pageSize"
      v-model:page="page"
      v-model:size="pageSize"
      :total="filteredTasks.length"
    />
    </template>
  </YdTablePageLayout>




    <!-- 日志抽屉 -->
    <YdDrawer
      v-model="logDrawerOpen"
      :title="logTask ? `${kindMeta(logTask).label} · ${logTask.subject || logTask.mapName || logTask.mapId || '任务'}` : '任务日志'"
      side="right"
      :show-confirm-button="false"
      :footer="false"
      content-class="voxelith-log-drawer"
    >
      <div v-if="logTask" class="grid gap-3 p-4">
        <div class="flex flex-wrap items-center gap-2 text-sm">
          <YdTag :variant="stateMeta(logTask.state).variant">
            {{ stateMeta(logTask.state).label }}
          </YdTag>
          <span class="text-muted-foreground">进度 {{ renderTaskProgress(logTask) }}%</span>
          <span class="text-muted-foreground">开始 {{ formatTime(logTask.startedAt || logTask.createdAt) }}</span>
          <span v-if="logTask.finishedAt" class="text-muted-foreground">结束 {{ formatTime(logTask.finishedAt) }}</span>
        </div>

        <div v-if="logTask.error" class="rounded-md border border-destructive/30 bg-muted/40 p-3">
          <div class="mb-1 text-sm font-medium text-destructive">失败原因</div>
          <pre class="voxelith-log-pre max-h-[30vh] font-mono text-xs leading-5">{{ logTask.error }}</pre>
        </div>

        <div v-if="logTask.diagnostics?.length" class="rounded-md border border-border bg-muted/40 p-3">
          <div class="mb-1 text-sm font-medium">引擎诊断</div>
          <pre class="voxelith-log-pre max-h-[30vh] font-mono text-xs leading-5">{{ logTask.diagnostics.join('\n') }}</pre>
        </div>

        <div class="rounded-md border border-border bg-muted/40 p-3">
          <div class="mb-1 flex flex-wrap items-center justify-between gap-2">
            <div class="text-sm font-medium">任务日志</div>
            <div class="flex items-center gap-2">
              <span class="text-xs text-muted-foreground">此处为日志尾部（最近 4000 字）</span>
              <YdButton
                size="sm"
                variant="outline"
                :loading="logDownloading === logTask.id"
                @click="downloadFullLog(logTask)"
              >
                下载完整日志
              </YdButton>
            </div>
          </div>
          <pre class="voxelith-log-pre max-h-[55vh] font-mono text-xs leading-5">{{ logTask.logTail || '（暂无日志）' }}</pre>
        </div>
      </div>
    </YdDrawer>
</template>
