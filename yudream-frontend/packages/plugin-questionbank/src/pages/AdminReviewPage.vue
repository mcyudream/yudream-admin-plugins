<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { SessionQuestionView, SessionSummary } from '../types'
import { FaButton, FaDrawer, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSearchBar, FaTag } from '@yudream/components'
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
  { id: 'operation', header: '操作', width: 120 },
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
</script>

<template>
  <FaPageHeader title="简答审核" description="人工审核题单的简答题作答，判定对错后计入成绩" />
  <FaPageMain>
    <FaResponsiveTable
      v-loading="model.loading"
      :columns="columns"
      :data="model.reviewList"
      row-key="id"
      table-root-class="max-w-full overflow-x-auto rounded-lg"
      table-class="min-w-[860px]"
      border stripe
      empty-text="暂无待审核的作答"
    >
      <template #toolbar>
        <FaSearchBar class="w-full">
          <div class="qb-toolbar">
            <FaInput v-model="model.reviewKeyword" placeholder="搜索用户昵称、ID 或题单名称" clearable @keydown.enter="model.applyReviewFilters" @clear="model.applyReviewFilters" />
            <FaButton variant="outline" @click="model.applyReviewFilters"><FaIcon name="i-ri:search-line" />查询</FaButton>
          </div>
        </FaSearchBar>
      </template>
      <template #cell-user="{ row }">{{ row.original.userName || row.original.userId }}</template>
      <template #cell-paperName="{ row }">{{ row.original.paperName || '-' }}</template>
      <template #cell-score="{ row }">{{ scoreText(row.original) }}</template>
      <template #cell-pending="{ row }">
        <FaTag variant="secondary">{{ row.original.pendingReview ? '待审核' : '已完成' }}</FaTag>
      </template>
      <template #cell-submittedAt="{ row }">{{ formatTime(row.original.submittedAt) }}</template>
      <template #cell-operation="{ row }">
        <FaButton size="sm" variant="outline" @click="openReview(row.original)">审核</FaButton>
      </template>
      <template #card="{ row }">
        <div class="flex flex-col gap-2 text-sm">
          <div class="flex items-center justify-between">
            <strong>{{ row.userName || row.userId }}</strong>
            <FaTag variant="secondary">{{ row.pendingReview ? '待审核' : '已完成' }}</FaTag>
          </div>
          <div class="text-secondary-foreground/60">{{ row.paperName || '-' }} · 当前得分 {{ scoreText(row) }}</div>
          <div class="text-secondary-foreground/60">{{ formatTime(row.submittedAt) }}</div>
          <div class="flex gap-2">
            <FaButton size="sm" variant="outline" @click="openReview(row)">审核</FaButton>
          </div>
        </div>
      </template>
    </FaResponsiveTable>
    <FaPagination
      v-model:page="model.reviewPager.page"
      v-model:size="model.reviewPager.size"
      :total="model.reviewPager.total"
      class="mt-3"
      @page-change="model.loadReviewQueue"
      @size-change="model.applyReviewFilters"
    />
    <FaDrawer v-model="drawerOpen" title="简答审核" size="720px" @close="closeDrawer">
      <div v-loading="model.reviewSessionLoading" class="flex flex-col gap-4">
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
              <FaTag variant="secondary">{{ question.typeLabel }}</FaTag>
              <FaTag v-if="question.userAnswer?.correct === true" variant="default">已判对</FaTag>
              <FaTag v-else-if="question.userAnswer?.correct === false" variant="outline">已判错</FaTag>
              <FaTag v-else variant="secondary">待审核</FaTag>
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
              <FaButton size="sm" :variant="question.userAnswer?.correct === true ? 'default' : 'outline'" @click="mark(question, true)">
                <FaIcon name="i-ri:check-line" />判对
              </FaButton>
              <FaButton size="sm" :variant="question.userAnswer?.correct === false ? 'destructive' : 'outline'" @click="mark(question, false)">
                <FaIcon name="i-ri:close-line" />判错
              </FaButton>
            </div>
          </div>
          <div v-if="!model.reviewSession.questions.some(q => q.type === 'SHORT')" class="qb-muted text-center py-4">
            这份作答没有简答题
          </div>
        </template>
      </div>
    </FaDrawer>
  </FaPageMain>
</template>
