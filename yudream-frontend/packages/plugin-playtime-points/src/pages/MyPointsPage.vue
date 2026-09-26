<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { SettlementRecord } from '../types'
import { FaCard, FaIcon, FaPageHeader, FaPageMain, FaPagination, FaTable, FaTag } from '@yudream/components'
import { computed, onMounted } from 'vue'
import { createPlaytimePointsApi } from '../api/playtime-points-api'
import { useMyPoints } from '../composables/useMyPoints'
import { formatDuration, formatTime } from '../composables/utils'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createPlaytimePointsApi(props.sdk)
const model = useMyPoints(api)
const { loading, summary, records, pager } = model

const columns: TableColumn<SettlementRecord>[] = [
  { id: 'windowEnd', header: '退出时间', width: 180 },
  { accessorKey: 'serverName', header: '服务器', width: 160 },
  { accessorKey: 'playerName', header: '玩家', width: 140 },
  { id: 'effective', header: '有效时长', width: 140 },
  { id: 'detail', header: '在线 / 挂机', width: 200 },
  { id: 'points', header: '积分', width: 110, align: 'center' },
  { id: 'credited', header: '入账', width: 110, align: 'center' },
]

const statCards = computed(() => {
  const data = summary.value
  return [
    { label: '累计积分', value: data ? data.totalPoints : '-', hint: data?.assetName ?? data?.assetCode ?? '' },
    {
      label: '钱包余额',
      value: data ? (data.balance ?? (data.walletAvailable ? '0' : '-')) : '-',
      hint: data?.walletAvailable ? `${data.assetSymbol || ''}${data.assetCode}` : '钱包插件未安装',
    },
    { label: '有效在线', value: data ? formatDuration(data.totalEffectiveMinutes * 60_000) : '-', hint: `共 ${data?.sessions ?? 0} 次结算` },
  ]
})

function creditedLabel(row: SettlementRecord): string {
  return Number(row.credited) > 0 ? row.credited : '累计中'
}

onMounted(() => {
  void model.load()
})
</script>

<template>
  <FaPageHeader
    title="我的积分"
    description="在 Minecraft 服务器里的有效在线时长会按服务器权重折算成钱包积分，每次退出服务器结算一次。"
  />

  <FaPageMain>
    <div class="ptp-my" v-loading="loading">
      <div class="ptp-stat-grid">
        <FaCard v-for="card in statCards" :key="card.label" class="ptp-stat-card">
          <div class="ptp-stat-label">{{ card.label }}</div>
          <div class="ptp-stat-value">
            <span v-if="card.label === '钱包余额' && summary?.assetSymbol">{{ summary.assetSymbol }}</span>
            {{ card.value }}
          </div>
          <div class="ptp-stat-hint">{{ card.hint }}</div>
        </FaCard>
      </div>

      <FaCard v-if="summary && summary.byServer.length">
        <div class="ptp-section-title">各服务器明细</div>
        <div class="ptp-server-list">
          <div v-for="item in summary.byServer" :key="item.serverId" class="ptp-server-row">
            <span class="ptp-server-name">{{ item.serverName || item.serverId }}</span>
            <span class="ptp-server-meta">{{ item.effectiveMinutes }} 分钟 · {{ item.sessions }} 次结算</span>
            <FaTag variant="secondary">+{{ item.points }}</FaTag>
          </div>
        </div>
      </FaCard>

      <FaCard>
        <div class="ptp-section-title">结算流水</div>
        <FaTable
          v-loading="loading"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="ptp-table-w1000"
          border
          stripe
          :columns="columns"
          :data="records"
        >
          <template #cell-windowEnd="{ row }">{{ formatTime(row.original.windowEnd) }}</template>
          <template #cell-effective="{ row }">{{ row.original.effectiveMinutes }} 分钟</template>
          <template #cell-detail="{ row }">
            {{ formatDuration(row.original.onlineMillis) }} / {{ formatDuration(row.original.afkMillis) }}
          </template>
          <template #cell-points="{ row }">{{ row.original.points }}</template>
          <template #cell-credited="{ row }">
            <FaTag :variant="Number(row.original.credited) > 0 ? 'default' : 'secondary'">
              {{ creditedLabel(row.original) }}
            </FaTag>
          </template>
          <template #empty>
            <div class="ptp-empty">
              <FaIcon name="i-ri:coins-line" />
              <span>还没有结算记录：进入服务器并退出一次后，这里会出现第一笔结算。</span>
            </div>
          </template>
        </FaTable>
        <FaPagination
          v-model:page="pager.page"
          v-model:size="pager.size"
          :total="pager.total"
          class="mt-3"
          @page-change="model.onPageChange"
          @size-change="model.onSizeChange"
        />
      </FaCard>
    </div>
  </FaPageMain>
</template>
