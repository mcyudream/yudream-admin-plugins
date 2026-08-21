<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { McguessPluginModel } from '../composables/useMcguessPlugin'
import type { McguessGameView } from '../types'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaSelect, FaResponsiveTable, FaTag } from '@yudream/components'

defineProps<{ model: McguessPluginModel }>()
const modeOptions = [
  { label: '全部模式', value: '' },
  { label: '猜物', value: 'item' },
  { label: '猜生物', value: 'mob' },
  { label: '猜合成', value: 'recipe' },
  { label: '迷雾', value: 'fog' },
  { label: '快答', value: 'quiz' },
  { label: '宾果', value: 'bingo' },
  { label: '找茬', value: 'spot' },
]
const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '进行中', value: 'PLAYING' },
  { label: '已获胜', value: 'WON' },
  { label: '已揭晓', value: 'LOST' },
]
const columns: TableColumn<McguessGameView>[] = [
  { id: 'channel', header: '群聊', minWidth: 140, fixed: 'left' },
  { id: 'mode', header: '模式', width: 90 },
  { id: 'target', header: '目标 / 进度', width: 150 },
  { id: 'progress', header: '猜测次数', width: 90 },
  { id: 'status', header: '状态', width: 100 },
  { id: 'winner', header: '终结者', width: 130 },
  { id: 'startedAt', header: '开始时间', width: 180 },
  { id: 'endedAt', header: '结束时间', width: 180 },
]

function statusVariant(status: string) { return status === 'WON' ? 'default' : 'secondary' }
</script>

<template>
  <FaPageHeader title="对局记录" class="mb-0">
    <FaButton variant="outline" :loading="model.loading" @click="model.loadGames"><FaIcon name="i-ri:refresh-line" />刷新</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaResponsiveTable
      v-loading="model.loading"
      :columns="columns"
      :data="model.games"
      row-key="id"
      table-root-class="max-w-full overflow-x-auto rounded-lg"
      table-class="min-w-[1100px]"
      border stripe column-visibility
      empty-text="暂无对局记录"
    >
      <template #toolbar>
        <FaSearchBar class="w-full">
          <div class="mcguess-filter-bar">
            <FaSelect v-model="model.gameFilters.mode" :options="modeOptions" @change="model.applyGameFilters" />
            <FaSelect v-model="model.gameFilters.status" :options="statusOptions" @change="model.applyGameFilters" />
          </div>
        </FaSearchBar>
      </template>
      <template #cell-channel="{ row }">{{ row.original.channelId }}</template>
      <template #cell-mode="{ row }">{{ row.original.modeZh }}</template>
      <template #cell-target="{ row }"><strong>{{ row.original.target }}</strong></template>
      <template #cell-progress="{ row }">{{ row.original.guessCount }}</template>
      <template #cell-status="{ row }"><FaTag :variant="statusVariant(row.original.status)">{{ model.gameStatusLabel(row.original.status) }}</FaTag></template>
      <template #cell-winner="{ row }">{{ row.original.winnerQq || '-' }}</template>
      <template #cell-startedAt="{ row }">{{ model.formatTime(row.original.startedAt) }}</template>
      <template #cell-endedAt="{ row }">{{ model.formatTime(row.original.endedAt) }}</template>
      <template #card="{ row }">
        <FaCard class="w-full">
          <div class="flex flex-col gap-3">
            <div class="flex items-center justify-between gap-2">
              <span class="min-w-0 break-words text-base font-semibold">{{ row.target }}</span>
              <FaTag :variant="statusVariant(row.status)">{{ model.gameStatusLabel(row.status) }}</FaTag>
            </div>
            <div class="flex flex-col gap-1 text-sm">
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">群聊</span><span class="break-all">{{ row.channelId }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">模式</span><span>{{ row.modeZh }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">猜测次数</span><span>{{ row.guessCount }} 次</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">终结者</span><span>{{ row.winnerQq || '-' }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">开始时间</span><span>{{ model.formatTime(row.startedAt) }}</span></div>
            </div>
          </div>
        </FaCard>
      </template>
    </FaResponsiveTable>
    <FaPagination v-model:page="model.gamePager.page" v-model:size="model.gamePager.size" :total="model.gamePager.total" class="mt-3" @page-change="model.loadGames" @size-change="model.applyGameFilters" />
  </FaPageMain>
</template>
