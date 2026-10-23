<script setup lang="ts">
import { YdLoadingOverlay } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { DownloadEventView, DownloadPlayerView, DownloadScopeEntry } from '../types'
import { YdCard, YdIcon, YdPageMain, YdTag, useYdToast, YdResponsiveEnhancedTable } from '@yudream/components'
import type { TableColumn } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { createYmclApi } from '../api/ymcl-api'
import { formatTime, shortId } from '../composables/ymcl-protocol'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createYmclApi(props.sdk)
const toast = useYdToast()

const TREND_DAYS = 14

const loading = ref(false)
const summary = ref<import('../types').DownloadStatsSummary | null>(null)
const events = ref<DownloadEventView[]>([])
const players = ref<DownloadPlayerView[]>([])

function formatBytes(bytes: number | null | undefined) {
  const value = bytes || 0
  if (value >= 1024 ** 3) {
    return `${(value / 1024 ** 3).toFixed(2)} GB`
  }
  if (value >= 1024 ** 2) {
    return `${(value / 1024 ** 2).toFixed(1)} MB`
  }
  if (value >= 1024) {
    return `${(value / 1024).toFixed(1)} KB`
  }
  return `${value} B`
}

const CATEGORY_LABELS: Record<string, string> = {
  minecraft_metadata: '原版元数据',
  minecraft_assets: '基础资源',
  minecraft_files: '基础资源',
  loaders: '加载器',
  modrinth: 'Modrinth',
  curseforge: 'CurseForge',
  other: '其他',
}

function categoryLabel(value: string | null | undefined) {
  if (!value) {
    return '—'
  }
  return CATEGORY_LABELS[value] || value
}

const metrics = computed(() => {
  const data = summary.value
  return [
    { label: '总请求', value: (data?.totalRequests ?? 0).toLocaleString(), icon: 'i-ri:download-cloud-2-line' },
    { label: '完成下载', value: (data?.totalDownloads ?? 0).toLocaleString(), icon: 'i-ri:check-double-line' },
    { label: '累计流量', value: formatBytes(data?.totalBytes), icon: 'i-ri:hard-drive-3-line' },
    { label: '下载玩家', value: (data?.totalPlayers ?? 0).toLocaleString(), icon: 'i-ri:group-line' },
    { label: '今日请求', value: (data?.todayRequests ?? 0).toLocaleString(), icon: 'i-ri:flashlight-line' },
    { label: '今日流量', value: formatBytes(data?.todayBytes), icon: 'i-ri:calendar-todo-line' },
  ]
})

const trendMax = computed(() =>
  Math.max(1, ...(summary.value?.days || []).map(point => point.requests)))

const scopeColumns: TableColumn<DownloadScopeEntry>[] = [
  { id: 'refId', header: '名称', minWidth: 140 },
  { id: 'requests', header: '请求', width: 90 },
  { id: 'downloads', header: '完成', width: 90 },
  { id: 'bytes', header: '流量', width: 110 },
  { id: 'lastDownloadAt', header: '最近下载', width: 150 },
]

const eventColumns: TableColumn<DownloadEventView>[] = [
  { id: 'username', header: '玩家', minWidth: 120 },
  { id: 'occurredAt', header: '时间', width: 160 },
  { id: 'category', header: '类别', width: 120 },
  { id: 'source', header: '来源', width: 120 },
  { id: 'path', header: '资源', minWidth: 260 },
  { id: 'bytes', header: '大小', width: 100 },
  { id: 'status', header: '状态', width: 80 },
]

const playerColumns: TableColumn<DownloadPlayerView>[] = [
  { id: 'displayName', header: '玩家', minWidth: 130 },
  { id: 'requests', header: '请求', width: 90 },
  { id: 'downloads', header: '完成', width: 90 },
  { id: 'bytes', header: '流量', width: 110 },
  { id: 'lastCategory', header: '最近类别', width: 120 },
  { id: 'lastSeenAt', header: '最近活跃', width: 160 },
]

function scopeName(row: DownloadScopeEntry) {
  return categoryLabel(row.refId)
}

function playerLabel(row: DownloadPlayerView) {
  return row.displayName || row.nickname || row.username || row.userId
}

function eventPath(row: DownloadEventView) {
  return row.path || row.host || '—'
}

async function load() {
  loading.value = true
  try {
    const [summaryData, eventsData, playersData] = await Promise.all([
      api.downloadStatsSummary(TREND_DAYS),
      api.downloadStatsEvents(20),
      api.downloadStatsPlayers(20),
    ])
    summary.value = summaryData
    events.value = eventsData.events
    players.value = playersData.players
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '请求失败')
  }
  finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="ymcl-page">

    <YdPageMain>
      <div class="ymcl-stats-stack">
        <div class="ymcl-stats-metrics">
          <YdCard v-for="metric in metrics" :key="metric.label" content-class="ymcl-stats-metric">
            <div class="ymcl-stats-metric__icon">
              <YdIcon :name="metric.icon" />
            </div>
            <div class="ymcl-stats-metric__body">
              <span class="ymcl-stats-metric__value">{{ metric.value }}</span>
              <span class="ymcl-stats-metric__label">{{ metric.label }}</span>
            </div>
          </YdCard>
        </div>

        <YdCard title="近 14 天趋势" content-class="ymcl-card-content">
          <YdLoadingOverlay :loading="loading" v-if="summary?.days?.length">
            <div class="ymcl-stats-chart">
              <div
                v-for="point in summary.days"
                :key="point.day"
                class="ymcl-stats-chart__bar"
                :title="`${point.day}：请求 ${point.requests} · 完成 ${point.downloads} · ${formatBytes(point.bytes)}`"
              >
                <span class="ymcl-stats-chart__bar-value">{{ point.requests || '' }}</span>
                <div
                  class="ymcl-stats-chart__bar-fill"
                  :style="{ height: `${Math.max(point.requests ? 4 : 0, Math.round((point.requests / trendMax) * 120))}px` }"
                />
                <span class="ymcl-stats-chart__bar-day">{{ point.day.slice(5) }}</span>
              </div>
            </div>
          </YdLoadingOverlay>
          <p v-else class="ymcl-muted">
            暂无数据。镜像网关在玩家经下载令牌鉴权下载后会回传事件。
          </p>
        </YdCard>

        <div class="ymcl-stats-grid">
          <YdCard title="类别分布" content-class="ymcl-card-content">
            <YdResponsiveEnhancedTable
              table-root-class="max-w-full rounded-lg" table-class="min-w-[520px]"
              :columns="scopeColumns"
              :data="summary?.topCategories || []"
              row-key="refId"
            >
              <template #empty>
                <span class="ymcl-muted">暂无下载记录</span>
              </template>
              <template #cell-refId="{ row }">
                {{ scopeName(row.original) }}
              </template>
              <template #cell-bytes="{ row }">
                {{ formatBytes(row.original.bytes) }}
              </template>
              <template #cell-lastDownloadAt="{ row }">
                {{ formatTime(row.original.lastDownloadAt || null) }}
              </template>
            </YdResponsiveEnhancedTable>
          </YdCard>

          <YdCard title="来源分布" content-class="ymcl-card-content">
            <YdResponsiveEnhancedTable
              table-root-class="max-w-full rounded-lg" table-class="min-w-[520px]"
              :columns="scopeColumns"
              :data="summary?.topSources || []"
              row-key="refId"
            >
              <template #empty>
                <span class="ymcl-muted">暂无下载记录</span>
              </template>
              <template #cell-refId="{ row }">
                <code class="ymcl-hash">{{ shortId(row.original.refId) }}</code>
              </template>
              <template #cell-bytes="{ row }">
                {{ formatBytes(row.original.bytes) }}
              </template>
              <template #cell-lastDownloadAt="{ row }">
                {{ formatTime(row.original.lastDownloadAt || null) }}
              </template>
            </YdResponsiveEnhancedTable>
          </YdCard>
        </div>

        <YdCard title="最近下载动态" content-class="ymcl-card-content">
          <YdResponsiveEnhancedTable
            table-root-class="max-w-full rounded-lg" table-class="min-w-[980px]"
            :columns="eventColumns"
            :data="events"
            row-key="id"
          >
            <template #empty>
              <span class="ymcl-muted">暂无下载记录</span>
            </template>
            <template #cell-username="{ row }">
              <span class="ymcl-break">{{ row.original.username || row.original.userId }}</span>
            </template>
            <template #cell-occurredAt="{ row }">
              {{ formatTime(row.original.occurredAt || null) }}
            </template>
            <template #cell-category="{ row }">
              <YdTag>{{ categoryLabel(row.original.category) }}</YdTag>
            </template>
            <template #cell-source="{ row }">
              {{ row.original.source || '—' }}
            </template>
            <template #cell-path="{ row }">
              <code class="ymcl-hash ymcl-break" :title="eventPath(row.original)">{{ eventPath(row.original) }}</code>
            </template>
            <template #cell-bytes="{ row }">
              {{ formatBytes(row.original.bytes) }}
            </template>
            <template #cell-status="{ row }">
              <YdTag :variant="row.original.status >= 200 && row.original.status < 300 ? 'default' : 'outline'">
                {{ row.original.status }}
              </YdTag>
            </template>
          </YdResponsiveEnhancedTable>
        </YdCard>

        <YdCard title="玩家下载档案" content-class="ymcl-card-content">
          <YdResponsiveEnhancedTable
            table-root-class="max-w-full rounded-lg" table-class="min-w-[720px]"
            :columns="playerColumns"
            :data="players"
            row-key="userId"
          >
            <template #empty>
              <span class="ymcl-muted">暂无玩家下载记录</span>
            </template>
            <template #cell-displayName="{ row }">
              {{ playerLabel(row.original) }}
            </template>
            <template #cell-bytes="{ row }">
              {{ formatBytes(row.original.bytes) }}
            </template>
            <template #cell-lastCategory="{ row }">
              {{ categoryLabel(row.original.lastCategory) }}
            </template>
            <template #cell-lastSeenAt="{ row }">
              {{ formatTime(row.original.lastSeenAt || null) }}
            </template>
          </YdResponsiveEnhancedTable>
        </YdCard>
      </div>
    </YdPageMain>
  </div>
</template>
