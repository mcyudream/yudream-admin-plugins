<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import { FaAlert, FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain, FaProgress, FaTable, FaTag } from '@yudream/components'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createMcPanelExtra } from '../../api/api-extra'
import { errorMessage, formatDateTime } from '../../composables/utils'
import MetricTrendChart from '../../components/MetricTrendChart.vue'

/** 数据监控总览（对标 MCSM PanelOverview / NodeOverview / StatusBlock）。 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const extra = createMcPanelExtra(props.sdk)
const router = useRouter()

const loading = ref(false)
const error = ref('')
const data = ref<Record<string, unknown> | null>(null)

const nodes = computed(() => (data.value?.nodes as Record<string, number>) ?? {})
const instances = computed(() => (data.value?.instances as Record<string, number>) ?? {})
const nodeDetails = computed(() => (data.value?.nodeDetails as Array<Record<string, unknown>>) ?? [])
const recentInstances = computed(() => (data.value?.recentInstances as Array<Record<string, unknown>>) ?? [])

const STATE_LABEL: Record<string, string> = {
  running: '运行中',
  exited: '已退出',
  created: '已创建',
  installing: '安装中',
  unknown: '未知',
}

const instanceColumns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'name', header: '实例', minWidth: 160 },
  { id: 'state', header: '状态', width: 90, align: 'center' },
  { id: 'cpu', header: 'CPU', width: 80, align: 'center' },
  { id: 'mem', header: '内存', width: 110, align: 'center' },
  { accessorKey: 'kind', header: '类型', width: 100, align: 'center' },
  { accessorKey: 'mcVersion', header: '版本', width: 100, align: 'center' },
  { id: 'updatedAt', header: '更新时间', width: 160 },
]

async function load(silent = false) {
  if (!silent) {
    loading.value = true
  }
  error.value = ''
  try {
    data.value = await extra.overview() as Record<string, unknown>
  }
  catch (e) {
    // 轮询失败不打断首屏数据，仅首屏或无数据时展示错误
    if (!silent || !data.value) {
      error.value = errorMessage(e, '加载监控数据失败')
    }
  }
  finally {
    if (!silent) {
      loading.value = false
    }
  }
}

/** 后端 overview 每次拉取都会追加点采样；前端 10s 静默轮询让趋势图持续生长。 */
let pollTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  void load()
  pollTimer = setInterval(() => {
    if (!document.hidden) {
      void load(true)
    }
  }, 10_000)
})

onBeforeUnmount(() => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})

function stateTagVariant(state: string): 'default' | 'secondary' | 'outline' | 'destructive' {
  switch (state) {
    case 'running':
      return 'default'
    case 'installing':
      return 'secondary'
    case 'exited':
      return 'destructive'
    default:
      return 'outline'
  }
}

function cpuPercent(row: Record<string, unknown>) {
  return Math.min(100, Math.max(0, Math.round(Number(row.cpuPercent ?? 0))))
}

function memPercent(row: Record<string, unknown>) {
  const used = Number(row.memUsedMb ?? 0)
  const total = Number(row.memTotalMb ?? 0)
  if (!total) {
    return 0
  }
  return Math.min(100, Math.max(0, Math.round((used / total) * 100)))
}

function historyOf(row: Record<string, unknown>) {
  return (row.history as Array<{ cpuPercent?: number, memUsedMb?: number, memTotalMb?: number }> | undefined) ?? []
}

function historyCpuSeries(row: Record<string, unknown>): number[] {
  return historyOf(row).map(sample => Math.min(100, Math.max(0, Number(sample.cpuPercent ?? 0))))
}

function historyMemSeries(row: Record<string, unknown>): number[] {
  return historyOf(row).map((sample) => {
    const total = Number(sample.memTotalMb ?? 0)
    if (!total) {
      return 0
    }
    return Math.min(100, Math.max(0, (Number(sample.memUsedMb ?? 0) / total) * 100))
  })
}

function openNode(row: Record<string, unknown>) {
  router.push(`/platform/plugins/mcpanel/admin/nodes/${encodeURIComponent(String(row.id))}`)
}

function openInstance(item: Record<string, unknown>) {
  router.push(`/platform/plugins/mcpanel/admin/instances/${encodeURIComponent(String(item.id))}`)
}

onMounted(() => void load())
</script>

<template>
  <FaPageHeader title="数据监控" description="节点在线、实例运行状态与资源总览">
    <FaButton variant="outline" @click="load">
      <FaIcon name="i-ri:refresh-line" />
      刷新
    </FaButton>
  </FaPageHeader>
  <FaPageMain v-loading="loading">
    <div class="space-y-6">
      <FaAlert v-if="error" variant="destructive" title="无法加载">
  <template #description>
        {{ error }}
  </template>
      </FaAlert>

      <div class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <FaCard title="节点在线数">
          <div class="mt-1 text-3xl font-semibold">
            {{ nodes.online ?? 0 }}
            <span class="text-base font-normal text-muted-foreground">/ {{ nodes.total ?? 0 }}</span>
          </div>
          <div class="mt-1 text-xs text-muted-foreground">
            在线 / 全部节点
          </div>
        </FaCard>
        <FaCard title="实例运行状态">
          <div class="mt-1 text-3xl font-semibold">
            {{ instances.running ?? 0 }}
            <span class="text-base font-normal text-muted-foreground">/ {{ instances.total ?? 0 }}</span>
          </div>
          <div class="mt-1 text-xs text-muted-foreground">
            运行中 / 全部 · 退出 {{ instances.exited ?? 0 }} · 其他 {{ instances.other ?? 0 }}
          </div>
        </FaCard>
        <FaCard title="面板版本">
          <div class="mt-1 text-lg font-semibold">
            mcpanel {{ data?.pluginVersion || '-' }}
          </div>
          <div class="mt-1 text-xs text-muted-foreground">
            生成时间 {{ formatDateTime(data?.generatedAt as number) }}
          </div>
        </FaCard>
        <FaCard title="最近活跃实例">
          <div class="mt-1 truncate text-lg font-semibold" :title="String(recentInstances[0]?.name ?? '')">
            {{ recentInstances[0]?.name || '—' }}
          </div>
          <div class="mt-1 text-xs text-muted-foreground">
            {{ recentInstances[0] ? `${STATE_LABEL[String(recentInstances[0].state)] || recentInstances[0].state} · ${formatDateTime(recentInstances[0].updatedAt as number)}` : '暂无' }}
          </div>
        </FaCard>
      </div>

      <section>
        <div class="mb-3 flex items-center justify-between">
          <h2 class="text-base font-semibold">
            节点状态总览
          </h2>
          <FaButton variant="ghost" size="sm" @click="router.push('/platform/plugins/mcpanel/admin/nodes')">
            全部节点
            <FaIcon name="i-ri:arrow-right-s-line" />
          </FaButton>
        </div>
        <div v-if="!nodeDetails.length" class="rounded-lg border border-dashed py-10 text-center text-sm text-muted-foreground">
          暂无节点，请到「节点管理」接入第一台运行 Docker Agent 的机器
        </div>
        <div v-else class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          <div
            v-for="row in nodeDetails"
            :key="String(row.id)"
            class="space-y-3 rounded-lg border p-4 transition-colors hover:border-primary/50"
          >
            <div class="flex items-start justify-between gap-2">
              <div class="min-w-0">
                <button type="button" class="max-w-full truncate text-left font-medium text-primary hover:underline" @click="openNode(row)">
                  {{ row.name }}
                </button>
                <div class="mt-0.5 truncate text-xs text-muted-foreground" :title="String(row.endpoint ?? '')">
                  {{ row.endpoint || '未配置控制信道' }}
                </div>
              </div>
              <FaTag :variant="row.status === 'online' ? 'default' : 'secondary'">
                {{ row.status === 'online' ? '在线' : row.status === 'offline' ? '离线' : row.status || '-' }}
              </FaTag>
            </div>
            <div class="text-xs text-muted-foreground">
              Agent {{ row.agentVersion || '-' }} · Docker {{ row.dockerVersion || '-' }} · 容器 {{ row.containers ?? 0 }}
            </div>
            <div>
              <div class="mb-1 flex items-center justify-between text-xs">
                <span class="text-muted-foreground">CPU</span>
                <span>{{ cpuPercent(row) }}%</span>
              </div>
              <FaProgress :model-value="cpuPercent(row)" />
            </div>
            <div>
              <div class="mb-1 flex items-center justify-between text-xs">
                <span class="text-muted-foreground">内存</span>
                <span>{{ memPercent(row) }}% · {{ row.memUsedMb ?? '-' }} / {{ row.memTotalMb ?? '-' }} MB</span>
              </div>
              <FaProgress :model-value="memPercent(row)" />
            </div>
            <div v-if="historyOf(row).length" class="mcp-card-metrics">
              <div class="mcp-metric">
                <div class="mcp-metric-head">
                  <span>CPU 近 {{ historyOf(row).length }} 次采样</span>
                  <strong>{{ cpuPercent(row) }}%</strong>
                </div>
                <MetricTrendChart :values="historyCpuSeries(row)" :height="40" :max="100" name="CPU" />
              </div>
              <div class="mcp-metric">
                <div class="mcp-metric-head">
                  <span>内存</span>
                  <strong>{{ memPercent(row) }}%</strong>
                </div>
                <MetricTrendChart :values="historyMemSeries(row)" :height="40" :max="100" name="内存" />
              </div>
            </div>
          </div>
        </div>
      </section>

      <section>
        <div class="mb-3 flex items-center justify-between">
          <h2 class="text-base font-semibold">
            最近实例
          </h2>
          <FaButton variant="ghost" size="sm" @click="router.push('/platform/plugins/mcpanel/admin/instances')">
            全部实例
            <FaIcon name="i-ri:arrow-right-s-line" />
          </FaButton>
        </div>
        <FaTable
          :columns="instanceColumns"
          :data="recentInstances"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[720px]"
          border
          stripe
          empty-text="暂无实例"
        >
          <template #cell-name="{ row }">
            <button type="button" class="text-left font-medium text-primary hover:underline" @click="openInstance(row.original)">
              {{ row.original.name }}
            </button>
          </template>
          <template #cell-state="{ row }">
            <FaTag :variant="stateTagVariant(String(row.original.state ?? ''))">
              {{ STATE_LABEL[String(row.original.state)] || row.original.state || '-' }}
            </FaTag>
          </template>
          <template #cell-cpu="{ row }">
            {{ row.original.cpuPercent == null ? '-' : `${cpuPercent(row.original)}%` }}
          </template>
          <template #cell-mem="{ row }">
            {{ row.original.memUsedMb == null ? '-' : `${Math.round(Number(row.original.memUsedMb))} MB` }}
          </template>
          <template #cell-updatedAt="{ row }">
            {{ formatDateTime(row.original.updatedAt as number) }}
          </template>
        </FaTable>
      </section>
    </div>
  </FaPageMain>
</template>
