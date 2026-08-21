<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { WordlePluginModel } from '../composables/useWordlePlugin'
import type { WordleGameView } from '../types'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaSelect, FaResponsiveTable, FaTag } from '@yudream/components'

defineProps<{ model: WordlePluginModel }>()
const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '进行中', value: 'PLAYING' },
  { label: '已猜中', value: 'WON' },
  { label: '已结束', value: 'LOST' },
]
const columns: TableColumn<WordleGameView>[] = [
  { id: 'channel', header: '群聊', minWidth: 140, fixed: 'left' },
  { id: 'mode', header: '模式', width: 110 },
  { id: 'answer', header: '答案', width: 140 },
  { id: 'progress', header: '进度', width: 110 },
  { id: 'status', header: '状态', width: 100 },
  { id: 'starter', header: '开局人', width: 130 },
  { id: 'winner', header: '猜中者', width: 130 },
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
      table-class="min-w-[1300px]"
      border stripe column-visibility
      empty-text="暂无对局记录"
    >
      <template #toolbar>
        <FaSearchBar class="w-full">
          <div class="wordle-filter-bar">
            <FaSelect v-model="model.gameStatusFilter" :options="statusOptions" @change="model.applyGameFilter" />
          </div>
        </FaSearchBar>
      </template>
      <template #cell-channel="{ row }">{{ row.original.channelId }}</template>
      <template #cell-mode="{ row }">
        <div class="wordle-chip-list">
          <FaTag variant="secondary">{{ row.original.modeLabel }} · {{ row.original.length }} 字</FaTag>
          <FaTag v-if="row.original.hardMode" variant="secondary">困难</FaTag>
        </div>
      </template>
      <template #cell-answer="{ row }"><strong>{{ row.original.answer }}</strong></template>
      <template #cell-progress="{ row }">{{ row.original.guessCount }} / {{ row.original.maxGuesses }}</template>
      <template #cell-status="{ row }"><FaTag :variant="statusVariant(row.original.status)">{{ model.gameStatusLabel(row.original.status) }}</FaTag></template>
      <template #cell-starter="{ row }">{{ row.original.startedByQq || '-' }}</template>
      <template #cell-winner="{ row }">{{ row.original.winnerQq || '-' }}</template>
      <template #cell-startedAt="{ row }">{{ model.formatTime(row.original.startedAt) }}</template>
      <template #cell-endedAt="{ row }">{{ model.formatTime(row.original.endedAt) }}</template>
      <template #card="{ row }">
        <FaCard class="w-full">
          <div class="flex flex-col gap-3">
            <div class="flex items-center justify-between gap-2">
              <span class="min-w-0 break-words text-base font-semibold">{{ row.answer }}</span>
              <FaTag :variant="statusVariant(row.status)">{{ model.gameStatusLabel(row.status) }}</FaTag>
            </div>
            <div class="flex flex-col gap-1 text-sm">
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">群聊</span><span class="break-all">{{ row.channelId }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">模式</span><span>{{ row.modeLabel }} · {{ row.length }} 字{{ row.hardMode ? ' · 困难' : '' }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">进度</span><span>{{ row.guessCount }} / {{ row.maxGuesses }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">开局人</span><span>{{ row.startedByQq || '-' }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">猜中者</span><span>{{ row.winnerQq || '-' }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">开始时间</span><span>{{ model.formatTime(row.startedAt) }}</span></div>
            </div>
          </div>
        </FaCard>
      </template>
    </FaResponsiveTable>
    <FaPagination v-model:page="model.gamePager.page" v-model:size="model.gamePager.size" :total="model.gamePager.total" class="mt-3" @page-change="model.loadGames" @size-change="model.applyGameFilter" />
  </FaPageMain>
</template>
