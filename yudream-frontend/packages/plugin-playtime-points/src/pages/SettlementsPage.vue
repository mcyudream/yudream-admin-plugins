<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { SettlementRecord } from '../types'
import { FaButton, FaCard, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSearchBar, FaSelect, FaTag } from '@yudream/components'
import { onMounted } from 'vue'
import { createPlaytimePointsApi } from '../api/playtime-points-api'
import { useSettlements } from '../composables/useSettlements'
import { formatDuration, formatTime } from '../composables/utils'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createPlaytimePointsApi(props.sdk)
const model = useSettlements(api)
const { loading, running, records, serverOptions, pager, filters } = model

const columns: TableColumn<SettlementRecord>[] = [
  { id: 'windowEnd', header: '退出时间', width: 180 },
  { accessorKey: 'serverName', header: '服务器', width: 160 },
  { accessorKey: 'playerName', header: '玩家', width: 140 },
  { id: 'effective', header: '有效时长', width: 150 },
  { id: 'detail', header: '在线 / 挂机', width: 200 },
  { id: 'weight', header: '权重', width: 90, align: 'center' },
  { id: 'points', header: '积分', width: 110, align: 'center' },
  { id: 'credited', header: '入账', width: 110, align: 'center' },
  { accessorKey: 'assetCode', header: '货币', width: 100, align: 'center' },
]

function creditedLabel(row: SettlementRecord): string {
  return Number(row.credited) > 0 ? row.credited : '累计中'
}

onMounted(() => {
  void model.load()
  void model.loadServerOptions()
})
</script>

<template>
  <FaPageHeader title="结算记录">
    <FaButton :loading="running" @click="model.runNow()">
      <FaIcon name="i-ri:play-circle-line" />
      立即结算
    </FaButton>
  </FaPageHeader>

  <FaPageMain>
    <FaResponsiveTable
      v-loading="loading"
      row-key="id"
      table-root-class="rounded-lg overflow-hidden"
      table-class="ptp-table-w1240"
      border
      stripe
      column-visibility
      :columns="columns"
      :data="records"
      empty-text="暂无结算记录：玩家退出服务器并达到 1 积分后这里会出现流水。"
    >
      <template #toolbar>
        <FaSearchBar class="w-full">
          <div class="ptp-filters">
            <FaSelect
              v-model="filters.serverId"
              :options="serverOptions"
              placeholder="全部服务器"
              clearable
              class="ptp-filter-control"
              @change="model.search()"
            />
            <FaInput
              v-model="filters.keyword"
              placeholder="按玩家名搜索"
              clearable
              class="ptp-filter-control"
              @keyup.enter="model.search()"
            />
            <div class="flex items-center gap-2">
              <FaButton size="sm" @click="model.search()">
                <FaIcon name="i-ri:search-line" />
                查询
              </FaButton>
              <FaButton size="sm" variant="outline" @click="model.resetFilters()">
                重置
              </FaButton>
            </div>
          </div>
        </FaSearchBar>
      </template>
      <template #cell-windowEnd="{ row }">{{ formatTime(row.original.windowEnd) }}</template>
      <template #cell-effective="{ row }">{{ row.original.effectiveMinutes }} 分钟</template>
      <template #cell-detail="{ row }">
        {{ formatDuration(row.original.onlineMillis) }} / {{ formatDuration(row.original.afkMillis) }}
      </template>
      <template #cell-weight="{ row }">×{{ row.original.weight }}</template>
      <template #cell-points="{ row }">{{ row.original.points }}</template>
      <template #cell-credited="{ row }">
        <FaTag :variant="Number(row.original.credited) > 0 ? 'default' : 'secondary'">
          {{ creditedLabel(row.original) }}
        </FaTag>
      </template>
      <template #card="{ row }">
        <FaCard class="w-full">
          <div class="flex flex-col gap-3">
            <div class="flex items-center justify-between gap-2">
              <span class="min-w-0 break-words text-base font-semibold">{{ row.playerName }}</span>
              <FaTag :variant="Number(row.credited) > 0 ? 'default' : 'secondary'">
                {{ creditedLabel(row) }}
              </FaTag>
            </div>
            <div class="flex flex-col gap-1 text-sm">
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">退出时间</span>
                <span class="break-all">{{ formatTime(row.windowEnd) }}</span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">服务器</span>
                <span class="break-all">{{ row.serverName }}</span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">有效时长</span>
                <span class="break-all">{{ row.effectiveMinutes }} 分钟</span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">在线 / 挂机</span>
                <span class="break-all">{{ formatDuration(row.onlineMillis) }} / {{ formatDuration(row.afkMillis) }}</span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">权重</span>
                <span class="break-all">×{{ row.weight }}</span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">积分</span>
                <span class="break-all">{{ row.points }}</span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">货币</span>
                <span class="break-all">{{ row.assetCode }}</span>
              </div>
            </div>
          </div>
        </FaCard>
      </template>
      <template #empty>
        <div class="ptp-empty">
          <FaIcon name="i-ri:file-list-3-line" />
          <span>暂无结算记录：玩家退出服务器并达到 1 积分后这里会出现流水。</span>
        </div>
      </template>
    </FaResponsiveTable>

    <FaPagination
      v-model:page="pager.page"
      v-model:size="pager.size"
      :total="pager.total"
      class="mt-3"
      @page-change="model.onPageChange"
      @size-change="model.onSizeChange"
    />
  </FaPageMain>
</template>
