<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { QuizRankEntry } from '../types'
import { FaButton, FaIcon, FaPageHeader, FaPageMain, FaResponsiveTable, FaTag } from '@yudream/components'
import { onMounted } from 'vue'
import { formatTime } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model

const columns: TableColumn<QuizRankEntry>[] = [
  { accessorKey: 'rank', header: '名次', width: 80, fixed: 'left' },
  { id: 'name', header: '成员', minWidth: 200 },
  { id: 'score', header: '累计答对', width: 110 },
  { accessorKey: 'lastAt', header: '最近答对', width: 180 },
]

function rankText(rank: number) {
  return ['🥇', '🥈', '🥉'][rank - 1] ?? String(rank)
}

onMounted(() => model.loadQuizLeaderboard())
</script>

<template>
  <FaPageHeader title="抢答排行榜" description="QQ 群内「抽题」抢答的累计答对榜；绑定系统账号后以昵称上榜，未绑定显示脱敏 QQ 号">
    <FaButton variant="outline" :loading="model.quizLeaderboardLoading" @click="model.loadQuizLeaderboard()">
      <FaIcon name="i-ri:refresh-line" />刷新
    </FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaResponsiveTable
      v-loading="model.quizLeaderboardLoading"
      :columns="columns"
      :data="model.quizLeaderboard"
      row-key="rank"
      table-root-class="qb-table-scroll"
      table-class="qb-table-w560"
      border stripe
      empty-text="还没有抢答成绩，去群里发送「抽题」开始抢答吧"
    >
      <template #cell-rank="{ row }">{{ rankText(row.original.rank) }}</template>
      <template #cell-name="{ row }">
        <div class="flex items-center gap-2">
          <span>{{ row.original.name }}</span>
          <FaTag v-if="row.original.bound" variant="secondary">已绑定</FaTag>
          <FaTag v-else variant="outline">未绑定</FaTag>
        </div>
      </template>
      <template #cell-score="{ row }">{{ row.original.score }} 题</template>
      <template #cell-lastAt="{ row }">{{ formatTime(row.original.lastAt) }}</template>
      <template #card="{ row }">
        <div class="flex flex-col gap-2 text-sm">
          <div class="flex items-center justify-between">
            <strong class="text-base">{{ rankText(row.rank) }} {{ row.name }}</strong>
            <FaTag :variant="row.bound ? 'secondary' : 'outline'">{{ row.bound ? '已绑定' : '未绑定' }}</FaTag>
          </div>
          <div class="text-secondary-foreground/60">累计答对 {{ row.score }} 题 · 最近 {{ formatTime(row.lastAt) }}</div>
        </div>
      </template>
    </FaResponsiveTable>
  </FaPageMain>
</template>
