<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { WordlePluginModel } from '../composables/useWordlePlugin'
import type { WordlePlayerView } from '../types'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaTag } from '@yudream/components'

defineProps<{ model: WordlePluginModel }>()
const columns: TableColumn<WordlePlayerView>[] = [
  { id: 'player', header: '玩家', minWidth: 180, fixed: 'left' },
  { id: 'qq', header: 'QQ', width: 130 },
  { id: 'english', header: '英文单词', width: 120 },
  { id: 'idiom', header: '四字成语', width: 120 },
  { id: 'winRate', header: '胜率', width: 90 },
  { id: 'streak', header: '连胜', width: 110 },
  { id: 'distribution', header: '猜中分布', minWidth: 200 },
  { id: 'updatedAt', header: '最近参与', width: 180 },
]

function distributionText(player: WordlePlayerView) {
  const entries = Object.entries(player.winDistribution || {}).sort(([a], [b]) => Number(a) - Number(b))
  return entries.length ? entries.map(([guesses, count]) => `${guesses} 次×${count}`).join('，') : '-'
}
</script>

<template>
  <FaPageHeader title="玩家战绩" class="mb-0">
    <FaButton variant="outline" :loading="model.loading" @click="model.loadPlayers"><FaIcon name="i-ri:refresh-line" />刷新</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaResponsiveTable
      v-loading="model.loading"
      :columns="columns"
      :data="model.players"
      row-key="userId"
      table-root-class="max-w-full overflow-x-auto rounded-lg"
      table-class="min-w-[1200px]"
      border stripe column-visibility
      empty-text="暂无玩家战绩"
    >
      <template #cell-player="{ row }"><strong>{{ model.playerLabel(row.original) }}</strong></template>
      <template #cell-qq="{ row }">{{ row.original.qq || '-' }}</template>
      <template #cell-english="{ row }">{{ row.original.englishWins }} / {{ row.original.englishPlayed }}</template>
      <template #cell-idiom="{ row }">{{ row.original.idiomWins }} / {{ row.original.idiomPlayed }}</template>
      <template #cell-winRate="{ row }">{{ model.playerWinRate(row.original) }}</template>
      <template #cell-streak="{ row }">
        <div class="wordle-chip-list">
          <FaTag variant="secondary">当前 {{ row.original.currentStreak }}</FaTag>
          <FaTag variant="secondary">最佳 {{ row.original.bestStreak }}</FaTag>
        </div>
      </template>
      <template #cell-distribution="{ row }">{{ distributionText(row.original) }}</template>
      <template #cell-updatedAt="{ row }">{{ model.formatTime(row.original.updatedAt) }}</template>
      <template #card="{ row }">
        <FaCard class="w-full">
          <div class="flex flex-col gap-3">
            <div class="flex items-center justify-between gap-2">
              <span class="min-w-0 break-words text-base font-semibold">{{ model.playerLabel(row) }}</span>
              <FaTag variant="secondary">胜率 {{ model.playerWinRate(row) }}</FaTag>
            </div>
            <div class="flex flex-col gap-1 text-sm">
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">QQ</span><span>{{ row.qq || '-' }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">英文单词</span><span>{{ row.englishWins }} / {{ row.englishPlayed }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">四字成语</span><span>{{ row.idiomWins }} / {{ row.idiomPlayed }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">连胜</span><span>当前 {{ row.currentStreak }} · 最佳 {{ row.bestStreak }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">猜中分布</span><span class="break-all">{{ distributionText(row) }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">最近参与</span><span>{{ model.formatTime(row.updatedAt) }}</span></div>
            </div>
          </div>
        </FaCard>
      </template>
    </FaResponsiveTable>
    <FaPagination v-model:page="model.playerPager.page" v-model:size="model.playerPager.size" :total="model.playerPager.total" class="mt-3" @page-change="model.loadPlayers" @size-change="model.loadPlayers" />
  </FaPageMain>
</template>
