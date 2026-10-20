<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { TableColumn, ActionMenuItem } from '@yudream/components'
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { SessionQuestionView, SessionSummary } from '../types'
import { YdButton, YdDrawer, YdIcon, YdInput, YdPagination, YdTag, YdResponsiveEnhancedTable } from '@yudream/components'
import { onMounted, ref } from 'vue'
import MarkdownPreview from '../components/MarkdownPreview.vue'
import { formatTime, optionKey } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model

const columns: TableColumn<SessionSummary>[] = [
  { id: 'user', header: '用户', width: 140, fixed: 'left' },
  { accessorKey: 'paperName', header: '题单', minWidth: 160 },
  { id: 'score', header: '客观题得分', width: 110 },
  { id: 'pending', header: '待审核', width: 90 },
  { accessorKey: 'submittedAt', header: '提交时间', width: 170 },
]

const drawerOpen = ref(false)

function scoreText(row: SessionSummary) {
  return `${row.correctCount}/${row.totalCount}`
}

function pendingCount() {
  if (!model.reviewSession) {
    return 0
  }
  return model.reviewSession.questions.filter(question => question.type === 'SHORT' && question.userAnswer?.correct == null).length
}

async function openReview(row: SessionSummary) {
  drawerOpen.value = true
  await model.loadReviewSession(row.id)
}

async function mark(question: SessionQuestionView, correct: boolean) {
  const sessionId = model.reviewSession?.id
  if (!sessionId) {
    return
  }
  const updated = await model.reviewMark(sessionId, question.questionId, correct)
  if (updated && pendingCount() === 0) {
    // 全部判完后刷新队列，已完成的会话会从待审核列表消失
    void model.loadReviewQueue()
  }
}

function closeDrawer() {
  drawerOpen.value = false
  model.reviewSession = null
  void model.loadReviewQueue()
}

onMounted(() => model.loadReviewQueue())

/** 行操作：进入人工审核 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '审核', icon: 'morph:clipboard-check', onClick: row => openReview(row as never) },
]

</script>

<template>
  <div class="h-full min-h-0">
    <YdTablePageLayout title="简答审核">
      <template #search>
        <div class="yd-filter-grid" style="--yd-filter-columns: 1">
          <YdInput v-model="model.reviewKeyword" class="w-full" placeholder="搜索用户昵称、ID 或题单名称" clearable @keydown.enter="model.applyReviewFilters" @clear="model.applyReviewFilters" />
          <div class="flex items-center justify-end gap-2">
            <YdButton variant="outline" @click="model.applyReviewFilters"><YdIcon name="i-ri:search-line" />查询</YdButton>
          </div>
        </div>
      </template>
    <template #table>
<YdLoadingOverlay :loading="model.loading"><YdResponsiveEnhancedTable
        :actions="rowActions"

      
      :columns="columns"
      :data="model.reviewList"
      row-key="id"
      table-root-class="qb-table-scroll"
      table-class="qb-table-w860"
      border stripe
      empty-text="暂无待审核的作答"
    >
      <template #cell-user="{ row }">{{ row.original.userName || row.original.userId }}</template>
      <template #cell-paperName="{ row }">{{ row.original.paperName || '-' }}</template>
      <template #cell-score="{ row }">{{ scoreText(row.original) }}</template>
      <template #cell-pending="{ row }">
        <YdTag variant="secondary">{{ row.original.pendingReview ? '待审核' : '已完成' }}</YdTag>
      </template>
      <template #cell-submittedAt="{ row }">{{ formatTime(row.original.submittedAt) }}</template>
</YdResponsiveEnhancedTable></YdLoadingOverlay>
    </template>
    <template #footer>
      <YdPagination
      v-model:page="model.reviewPager.page"
      v-model:size="model.reviewPager.size"
      :total="model.reviewPager.total"
      @page-change="model.loadReviewQueue"
      @size-change="model.applyReviewFilters"
    />
    </template>
  </YdTablePageLayout>


    <YdDrawer v-model="drawerOpen" title="简答审核" size="720px" @close="closeDrawer">
      <YdLoadingOverlay :loading="model.reviewSessionLoading"><div  class="flex flex-col gap-4">
        <template v-if="model.reviewSession">
          <div class="qb-result-banner">
            <span class="qb-result-score">{{ model.reviewSession.correctCount }} / {{ model.reviewSession.totalCount }}</span>
            <span class="qb-muted">
              {{ model.reviewSession.userName || model.reviewSession.userId }} · {{ model.reviewSession.paperName || '题单' }} · 还剩 {{ pendingCount() }} 题待判
            </span>
          </div>
          <div
            v-for="(question, index) in model.reviewSession.questions.filter(q => q.type === 'SHORT')"
            :key="question.questionId"
            class="qb-question-card rounded-lg border p-4"
          >
            <div class="qb-question-meta">
              <strong>第 {{ index + 1 }} 题</strong>
              <YdTag variant="secondary">{{ question.typeLabel }}</YdTag>
              <YdTag v-if="question.userAnswer?.correct === true" variant="default">已判对</YdTag>
              <YdTag v-else-if="question.userAnswer?.correct === false" variant="outline">已判错</YdTag>
              <YdTag v-else variant="secondary">待审核</YdTag>
            </div>
            <MarkdownPreview :sdk="model.sdk" :content="question.content" />
            <div class="qb-answer-panel text-sm">
              <div><strong>作答：</strong></div>
              <div style="white-space: pre-wrap">{{ question.userAnswer?.text || '未作答' }}</div>
              <div><strong>参考答案：</strong></div>
              <MarkdownPreview :sdk="model.sdk" :content="question.referenceAnswer ?? ''" />
              <template v-if="question.analysis">
                <div><strong>解析：</strong></div>
                <MarkdownPreview :sdk="model.sdk" :content="question.analysis" />
              </template>
            </div>
            <div class="flex gap-2">
              <YdButton size="sm" :variant="question.userAnswer?.correct === true ? 'default' : 'outline'" @click="mark(question, true)">
                <YdIcon name="i-ri:check-line" />判对
              </YdButton>
              <YdButton size="sm" :variant="question.userAnswer?.correct === false ? 'destructive' : 'outline'" @click="mark(question, false)">
                <YdIcon name="i-ri:close-line" />判错
              </YdButton>
            </div>
          </div>
          <div v-if="!model.reviewSession.questions.some(q => q.type === 'SHORT')" class="qb-muted text-center py-4">
            这份作答没有简答题
          </div>
        </template>
      </div></YdLoadingOverlay>
    </YdDrawer>
  </div>
</template>
