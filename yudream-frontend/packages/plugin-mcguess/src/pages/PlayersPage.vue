<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { McguessPluginModel } from '../composables/useMcguessPlugin'
import type { McguessPlayerView } from '../types'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaTag } from '@yudream/components'

defineProps<{ model: McguessPluginModel }>()
const columns: TableColumn<McguessPlayerView>[] = [
  { id: 'player', header: '玩家', minWidth: 180, fixed: 'left' },
  { id: 'qq', header: 'QQ', width: 130 },
  { id: 'item', header: '猜物 胜/参', width: 110 },
  { id: 'mob', header: '猜生物 胜/参', width: 110 },
  { id: 'recipe', header: '猜合成 胜/参', width: 110 },
  { id: 'fog', header: '迷雾 胜/参', width: 100 },
  { id: 'quiz', header: '快答 胜/参', width: 100 },
  { id: 'bingo', header: '宾果 胜/参', width: 100 },
  { id: 'spot', header: '找茬 胜/参', width: 100 },
  { id: 'collection', header: '图鉴', width: 80 },
  { id: 'hol', header: '比大小最佳', width: 100 },
  { id: 'wins', header: '总胜场', width: 90 },
  { id: 'winRate', header: '胜率', width: 90 },
  { id: 'guesses', header: '总猜测', width: 100 },
  { id: 'updatedAt', header: '最近参与', width: 180 },
]
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
      table-class="min-w-[1780px]"
      border stripe column-visibility
      empty-text="暂无玩家战绩"
    >
      <template #cell-player="{ row }"><strong>{{ model.playerLabel(row.original) }}</strong></template>
      <template #cell-qq="{ row }">{{ row.original.qq || '-' }}</template>
      <template #cell-item="{ row }">{{ row.original.itemWins }} / {{ row.original.itemPlayed }}</template>
      <template #cell-mob="{ row }">{{ row.original.mobWins }} / {{ row.original.mobPlayed }}</template>
      <template #cell-recipe="{ row }">{{ row.original.recipeWins }} / {{ row.original.recipePlayed }}</template>
      <template #cell-fog="{ row }">{{ row.original.fogWins }} / {{ row.original.fogPlayed }}</template>
      <template #cell-quiz="{ row }">{{ row.original.quizWins }} / {{ row.original.quizPlayed }}</template>
      <template #cell-bingo="{ row }">{{ row.original.bingoWins }} / {{ row.original.bingoPlayed }}</template>
      <template #cell-spot="{ row }">{{ row.original.spotWins }} / {{ row.original.spotPlayed }}</template>
      <template #cell-collection="{ row }"><FaTag variant="secondary">{{ row.original.collectionCount }}</FaTag></template>
      <template #cell-hol="{ row }">{{ row.original.holBest > 0 ? `${row.original.holBest} 连胜` : '-' }}</template>
      <template #cell-wins="{ row }"><FaTag variant="secondary">{{ model.totalWins(row.original) }}</FaTag></template>
      <template #cell-winRate="{ row }">{{ model.winRateOf(row.original) }}</template>
      <template #cell-guesses="{ row }">{{ row.original.totalGuesses }}</template>
      <template #cell-updatedAt="{ row }">{{ model.formatTime(row.original.updatedAt) }}</template>
      <template #card="{ row }">
        <FaCard class="w-full">
          <div class="flex flex-col gap-3">
            <div class="flex items-center justify-between gap-2">
              <span class="min-w-0 break-words text-base font-semibold">{{ model.playerLabel(row) }}</span>
              <FaTag variant="secondary">胜率 {{ model.winRateOf(row) }}</FaTag>
            </div>
            <div class="flex flex-col gap-1 text-sm">
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">QQ</span><span>{{ row.qq || '-' }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">猜物</span><span>胜 {{ row.itemWins }} / 参与 {{ row.itemPlayed }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">猜生物</span><span>胜 {{ row.mobWins }} / 参与 {{ row.mobPlayed }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">猜合成</span><span>胜 {{ row.recipeWins }} / 参与 {{ row.recipePlayed }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">迷雾 / 快答</span><span>胜 {{ row.fogWins }}/{{ row.fogPlayed }} · {{ row.quizWins }}/{{ row.quizPlayed }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">宾果 / 找茬</span><span>胜 {{ row.bingoWins }}/{{ row.bingoPlayed }} · {{ row.spotWins }}/{{ row.spotPlayed }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">图鉴</span><span>已收集 {{ row.collectionCount }} 件</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">比大小最佳</span><span>{{ row.holBest > 0 ? `${row.holBest} 连胜` : '-' }}</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">总猜测</span><span>{{ row.totalGuesses }} 次</span></div>
              <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">最近参与</span><span>{{ model.formatTime(row.updatedAt) }}</span></div>
            </div>
          </div>
        </FaCard>
      </template>
    </FaResponsiveTable>
    <FaPagination v-model:page="model.playerPager.page" v-model:size="model.playerPager.size" :total="model.playerPager.total" class="mt-3" @page-change="model.loadPlayers" @size-change="model.loadPlayers" />
  </FaPageMain>
</template>
