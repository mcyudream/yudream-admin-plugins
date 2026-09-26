<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { LaunchEventView, StatsScopeEntry } from '../types'
import {
  FaButton,
  FaCard,
  FaIcon,
  FaPageHeader,
  FaPageMain,
  FaTable,
  FaTag,
  useFaToast,
} from '@yudream/components'
import type { TableColumn } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { createYmclApi } from '../api/ymcl-api'
import { formatTime } from '../composables/ymcl-protocol'
import type { StatsSummary } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createYmclApi(props.sdk)
const toast = useFaToast()

const TREND_DAYS = 14

const loading = ref(false)
const summary = ref<StatsSummary | null>(null)
const launches = ref<LaunchEventView[]>([])

const metrics = computed(() => {
  const data = summary.value
  return [
    { label: '总启动次数', value: data?.totalLaunches ?? 0, icon: 'i-ri:rocket-2-line' },
    { label: '今日启动', value: data?.todayLaunches ?? 0, icon: 'i-ri:flashlight-line' },
    { label: '启动用户数', value: data?.totalUsers ?? 0, icon: 'i-ri:group-line' },
    { label: '今日活跃用户', value: data?.todayActiveUsers ?? 0, icon: 'i-ri:user-shared-line' },
    { label: '近 7 日活跃', value: data?.weekActiveUsers ?? 0, icon: 'i-ri:calendar-check-line' },
  ]
})

const trendMax = computed(() =>
  Math.max(1, ...(summary.value?.days || []).map(point => point.total)))

const scopeColumns: TableColumn<StatsScopeEntry>[] = [
  { id: 'name', header: '名称', minWidth: 150 },
  { id: 'refId', header: 'ID', minWidth: 130 },
  { id: 'launches', header: '启动次数', width: 100 },
  { id: 'lastLaunchAt', header: '最近启动', width: 160 },
]

const eventColumns: TableColumn<LaunchEventView>[] = [
  { id: 'username', header: '用户', minWidth: 130 },
  { id: 'occurredAt', header: '时间', width: 160 },
  { id: 'target', header: '服务器 / 整合包', minWidth: 220 },
  { id: 'packVersion', header: '版本', width: 110 },
  { id: 'quickPlay', header: '进入方式', width: 100 },
  { id: 'client', header: '客户端', width: 150 },
]

function scopeRef(row: StatsScopeEntry) {
  return row.refId || '（未知）'
}

function userLabel(row: LaunchEventView) {
  return row.username || row.userId || '（未知用户）'
}

function quickPlayLabel(row: LaunchEventView) {
  if (row.quickPlay === 'server') {
    return '直连进服'
  }
  if (row.quickPlay === 'singleplayer') {
    return '单人世界'
  }
  return '普通启动'
}

function clientLabel(row: LaunchEventView) {
  const platform = row.platform || '?'
  const version = row.launcherVersion || '?'
  return `${platform} · ${version}`
}

function eventTarget(row: LaunchEventView) {
  if (row.serverId) {
    return row.packId ? `${row.serverId} / ${row.packId}` : row.serverId
  }
  return row.packId || '-'
}

async function load() {
  loading.value = true
  try {
    const [summaryData, launchesData] = await Promise.all([
      api.statsSummary(TREND_DAYS),
      api.statsLaunches(20),
    ])
    summary.value = summaryData
    launches.value = launchesData.launches
  }
  catch (error) {
    const message = error instanceof Error ? error.message : '请求失败'
    toast.error(message)
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
    <FaPageHeader title="启动统计" description="域整合包实例的启动上报聚合：启动次数、用户活跃与最近动态。数据由启动器在启动成功后上报。">
      <template #actions>
        <FaButton variant="outline" :loading="loading" @click="load">
          <FaIcon name="i-ri:refresh-line" />
          刷新
        </FaButton>
      </template>
    </FaPageHeader>

    <FaPageMain>
      <div class="ymcl-stats-stack">
        <div class="ymcl-stats-metrics">
          <FaCard v-for="metric in metrics" :key="metric.label" content-class="ymcl-stats-metric">
            <div class="ymcl-stats-metric__icon">
              <FaIcon :name="metric.icon" />
            </div>
            <div class="ymcl-stats-metric__body">
              <span class="ymcl-stats-metric__value">{{ metric.value.toLocaleString() }}</span>
              <span class="ymcl-stats-metric__label">{{ metric.label }}</span>
            </div>
          </FaCard>
        </div>

        <FaCard title="近 14 天趋势" content-class="ymcl-card-content">
          <div v-if="summary?.days?.length" class="ymcl-stats-chart" v-loading="loading">
            <div
              v-for="point in summary.days"
              :key="point.day"
              class="ymcl-stats-chart__bar"
              :title="`${point.day}：启动 ${point.total} 次 · 活跃 ${point.users} 人`"
            >
              <span class="ymcl-stats-chart__bar-value">{{ point.total || '' }}</span>
              <div
                class="ymcl-stats-chart__bar-fill"
                :style="{ height: `${Math.max(point.total ? 4 : 0, Math.round((point.total / trendMax) * 120))}px` }"
              />
              <span class="ymcl-stats-chart__bar-day">{{ point.day.slice(5) }}</span>
            </div>
          </div>
          <p v-else class="ymcl-muted">
            暂无数据。启动器在域整合包实例启动成功后会自动上报。
          </p>
        </FaCard>

        <div class="ymcl-stats-grid">
          <FaCard title="Top 服务器" content-class="ymcl-card-content">
            <FaTable
              table-root-class="max-w-full overflow-x-auto rounded-lg overflow-hidden"
              :columns="scopeColumns"
              :data="summary?.topServers || []"
              row-key="refId"
            >
              <template #empty>
                <span class="ymcl-muted">暂无服务器启动记录</span>
              </template>
              <template #cell-name="{ row }">
                <span :class="row.original.name === row.original.refId ? 'ymcl-muted' : ''">
                  {{ row.original.name || scopeRef(row.original) }}
                </span>
              </template>
              <template #cell-refId="{ row }">
                <code class="ymcl-hash">{{ scopeRef(row.original) }}</code>
              </template>
              <template #cell-launches="{ row }">
                {{ row.original.launches.toLocaleString() }}
              </template>
              <template #cell-lastLaunchAt="{ row }">
                {{ formatTime(row.original.lastLaunchAt || null) }}
              </template>
            </FaTable>
          </FaCard>

          <FaCard title="Top 整合包" content-class="ymcl-card-content">
            <FaTable
              table-root-class="max-w-full overflow-x-auto rounded-lg overflow-hidden"
              :columns="scopeColumns"
              :data="summary?.topPacks || []"
              row-key="refId"
            >
              <template #empty>
                <span class="ymcl-muted">暂无整合包启动记录</span>
              </template>
              <template #cell-name="{ row }">
                <span :class="row.original.name === row.original.refId ? 'ymcl-muted' : ''">
                  {{ row.original.name || scopeRef(row.original) }}
                </span>
              </template>
              <template #cell-refId="{ row }">
                <code class="ymcl-hash">{{ scopeRef(row.original) }}</code>
              </template>
              <template #cell-launches="{ row }">
                {{ row.original.launches.toLocaleString() }}
              </template>
              <template #cell-lastLaunchAt="{ row }">
                {{ formatTime(row.original.lastLaunchAt || null) }}
              </template>
            </FaTable>
          </FaCard>
        </div>

        <div class="ymcl-stats-grid">
          <FaCard title="平台分布" content-class="ymcl-card-content">
            <div v-if="summary?.platforms?.length" class="ymcl-stats-tags">
              <FaTag v-for="entry in summary.platforms" :key="entry.value">
                {{ entry.value }} · {{ entry.count.toLocaleString() }}
              </FaTag>
            </div>
            <p v-else class="ymcl-muted">暂无数据</p>
          </FaCard>
          <FaCard title="启动器版本分布" content-class="ymcl-card-content">
            <div v-if="summary?.launcherVersions?.length" class="ymcl-stats-tags">
              <FaTag v-for="entry in summary.launcherVersions" :key="entry.value">
                {{ entry.value }} · {{ entry.count.toLocaleString() }}
              </FaTag>
            </div>
            <p v-else class="ymcl-muted">暂无数据</p>
          </FaCard>
        </div>

        <FaCard title="最近启动动态" content-class="ymcl-card-content">
          <FaTable
            table-root-class="max-w-full overflow-x-auto rounded-lg overflow-hidden"
            :columns="eventColumns"
            :data="launches"
            row-key="id"
          >
            <template #empty>
              <span class="ymcl-muted">暂无启动记录</span>
            </template>
            <template #cell-username="{ row }">
              <span class="ymcl-break">{{ userLabel(row.original) }}</span>
            </template>
            <template #cell-occurredAt="{ row }">
              {{ formatTime(row.original.occurredAt || null) }}
            </template>
            <template #cell-target="{ row }">
              <code class="ymcl-hash ymcl-break">{{ eventTarget(row.original) }}</code>
            </template>
            <template #cell-packVersion="{ row }">
              {{ row.original.packVersion || '-' }}
            </template>
            <template #cell-quickPlay="{ row }">
              <FaTag :variant="row.original.quickPlay === 'server' ? 'default' : 'outline'">
                {{ quickPlayLabel(row.original) }}
              </FaTag>
            </template>
            <template #cell-client="{ row }">
              <span class="ymcl-hash">{{ clientLabel(row.original) }}</span>
            </template>
          </FaTable>
          </FaCard>
      </div>
    </FaPageMain>
  </div>
</template>
