<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { PonyPluginModel } from '../composables/usePonyPlugin'
import type { PonyGameView } from '../types'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaSelect, FaResponsiveTable, FaTag } from '@yudream/components'

defineProps<{ model: PonyPluginModel }>()
const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '进行中', value: 'PLAYING' },
  { label: '已归位', value: 'WON' },
  { label: '已结束', value: 'LOST' },
]
const columns: TableColumn<PonyGameView>[] = [
  { id: 'channel', header: '群聊', minWidth: 140, fixed: 'left' },
  { id: 'size', header: '棋盘', width: 100 },
  { id: 'progress', header: '进度', width: 100 },
  { id: 'lives', header: '生命', width: 90 },
  { id: 'mistakes', header: '失误', width: 90 },
  { id: 'status', header: '状态', width: 100 },
  { id: 'starter', header: '开局人', width: 130 },
  { id: 'winner', header: '收官者', width: 130 },
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
          <div class="pony-filter-bar">
            <FaSelect v-model="model.gameStatusFilter" :options="statusOptions" @change="model.applyGameFilter" />
          </div>
        </FaSearchBar>
      </template>
      <template #cell-channel="{ row }">{{ row.original.channelId }}</template>
      <template #cell-size="{ row }"><FaTag variant="secondary">{{ row.original.size }}×{{ row.original.size }}</FaTag></template>
      <template #cell-progress="{ row }">{{ row.original.horsesPlaced }} / {{ row.original.size }}</template>
      <template #cell-lives="{ row }">{{ row.original.lives }}</template>
      <template #cell-mistakes="{ row }">{{ row.original.mistakes }}</template>
      <template #cell-status="{ row }"><FaTag :variant="statusVariant(row.original.status)">{{ model.gameStatusLabel(row.original.status) }}</FaTag></template>
      <template #cell-starter="{ row }">{{ row.original.startedByQq || '-' }}</template>
      <template #cell-winner="{ row }">{{ row.original.winnerQq || '-' }}</template>
      <template #cell-startedAt="{ row }">{{ model.formatTime(row.original.startedAt) }}</template>
      <template #cell-endedAt="{ row }">{{ model.formatTime(row.original.endedAt) }}</template>
      <template #card="{ row }">
        <FaCard class="w-full">
          <div class="flex flex-col gap-3">
            <div class="flex items-center justify-between gap-2">
              <span class="min-w-0 break-words text-base font-semibold">{{ row.size }}×{{ row.size }} 棋盘</span>
              <FaTag :variant="statusVariant(row.status)">{{ model.gameStatusLabel(row.status) }}</FaTag>
            </div>
            <div class="flex flex-col gap-1 text-sm">
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">群聊</span><span class="break-all">{{ row.channelId }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">进度</span><span>{{ row.horsesPlaced }} / {{ row.size }} 匹</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">生命 / 失误</span><span>{{ row.lives }} / {{ row.mistakes }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">开局人</span><span>{{ row.startedByQq || '-' }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">收官者</span><span>{{ row.winnerQq || '-' }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">开始时间</span><span>{{ model.formatTime(row.startedAt) }}</span></div>
            </div>
          </div>
        </FaCard>
      </template>
    </FaResponsiveTable>
    <FaPagination v-model:page="model.gamePager.page" v-model:size="model.gamePager.size" :total="model.gamePager.total" class="mt-3" @page-change="model.loadGames" @size-change="model.applyGameFilter" />
  </FaPageMain>
</template>
