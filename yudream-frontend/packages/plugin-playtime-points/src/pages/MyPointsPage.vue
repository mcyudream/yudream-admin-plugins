<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { CheckInRewardRecord, SettlementRecord } from '../types'
import { FaCard, FaIcon, FaPageHeader, FaPageMain, FaPagination, FaTable, FaTag } from '@yudream/components'
import { computed, onMounted } from 'vue'
import { createPlaytimePointsApi } from '../api/playtime-points-api'
import { useMyPoints } from '../composables/useMyPoints'
import { checkInRewardLabel, formatDuration, formatTime, sourceLabel, subDetails } from '../composables/utils'

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
  { id: 'subServers', header: '子服拆分', width: 240 },
  { id: 'points', header: '积分', width: 110, align: 'center' },
  { id: 'credited', header: '入账', width: 110, align: 'center' },
]

const checkInColumns: TableColumn<CheckInRewardRecord>[] = [
  { id: 'acceptedAt', header: '验收通过时间', width: 180 },
  { accessorKey: 'projectName', header: '项目', width: 160 },
  { accessorKey: 'detailTitle', header: '工作细节', width: 180 },
  { id: 'points', header: '积分', width: 100, align: 'center' },
  { id: 'calc', header: '折算方式', width: 300 },
  { id: 'source', header: '来源', width: 110, align: 'center' },
]

const subServerSummary = computed(() => summary.value?.bySubServer ?? [])
const checkInSummary = computed(() => summary.value?.checkInRewards ?? null)

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
    {
      label: '打卡积分',
      value: data ? (data.checkInRewards?.totalPoints ?? '0') : '-',
      hint: data?.checkInRewards?.enabled
        ? `共 ${data.checkInRewards.count} 次发放`
        : '未启用打卡积分',
    },
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
    description="在 Minecraft 服务器里的有效在线时长会按服务器（群组服下按子服）权重折算成钱包积分，每次退出服务器结算一次；项目打卡验收通过也会计分：每次固定积分，或按打卡的有效在线时长与时薪折算（没有时长的打卡按设置的固定积分发放）。"
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

      <FaCard v-if="subServerSummary.length">
        <div class="ptp-section-title">各子服明细</div>
        <p class="ptp-hint">
          群组服下同一段在线时长会按子服拆分计分，下表是各子服累计的有效时长与积分（不含被设置为「不参与结算」的子服）。
        </p>
        <div class="ptp-server-list">
          <div v-for="item in subServerSummary" :key="`${item.serverId}::${item.subServer}`" class="ptp-server-row">
            <span class="ptp-server-name">{{ item.subServer }}</span>
            <span class="ptp-server-meta">
              {{ item.serverName || item.serverId }} · {{ item.effectiveMinutes }} 分钟 · {{ item.sessions }} 次结算
            </span>
            <FaTag variant="secondary">+{{ item.points }}</FaTag>
          </div>
        </div>
      </FaCard>

      <FaCard v-if="checkInSummary && checkInSummary.enabled">
        <div class="ptp-section-title">最近打卡积分</div>
        <p class="ptp-hint">
          项目工作细节验收通过后，该细节下的每条打卡各计一次积分：固定金额模式按每次金额发放；
          按时薪折算模式下，有有效在线时长的打卡按「有效在线时长 × 时薪」发放，没有时长的打卡
          （图片/文件/定位等非 MC 打卡）按「非时长打卡每次积分」发放，并在这里展示折算过程；
          「来源」表示这笔是由定时拉取还是验收实时回调发放的。没有发放的打卡也会列出并写明原因。
        </p>
        <FaTable
          row-key="checkInId"
          table-root-class="rounded-lg overflow-hidden"
          table-class="ptp-table-w1030"
          border
          stripe
          :columns="checkInColumns"
          :data="checkInSummary.recent"
        >
          <template #cell-acceptedAt="{ row }">{{ formatTime(row.original.acceptedAt) }}</template>
          <template #cell-points="{ row }">
            <FaTag :variant="Number(row.original.credit) > 0 ? 'secondary' : 'outline'">
              {{ Number(row.original.credit) > 0 ? `+${row.original.credit}` : '未发放' }}
            </FaTag>
          </template>
          <template #cell-calc="{ row }">
            <span class="ptp-calc-text" :class="{ 'ptp-calc-skipped': !(Number(row.original.credit) > 0) }">
              {{ checkInRewardLabel(row.original) }}
            </span>
          </template>
          <template #cell-source="{ row }">
            <FaTag :variant="row.original.source === 'REALTIME' ? 'default' : 'outline'">
              {{ sourceLabel(row.original.source) }}
            </FaTag>
          </template>
          <template #empty>
            <div class="ptp-empty">
              <FaIcon name="i-ri:map-pin-time-line" />
              <span>还没有打卡积分：项目工作细节验收通过后会自动计入。</span>
            </div>
          </template>
        </FaTable>
      </FaCard>

      <FaCard>
        <div class="ptp-section-title">结算流水</div>
        <FaTable
          v-loading="loading"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="ptp-table-w1240"
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
          <template #cell-subServers="{ row }">
            <div v-if="!subDetails(row.original).length" class="ptp-sub-empty">整服结算</div>
            <div v-else class="ptp-sub-list">
              <div v-for="sub in subDetails(row.original)" :key="sub.subServer" class="ptp-sub-item">
                <span class="ptp-sub-name">{{ sub.subServer }}</span>
                <span class="ptp-sub-meta">{{ sub.effectiveMinutes }} 分钟 × {{ sub.weight }}</span>
                <span class="ptp-sub-points">{{ sub.enabled ? `+${sub.points}` : '停算' }}</span>
              </div>
            </div>
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
