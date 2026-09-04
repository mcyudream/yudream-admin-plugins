<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { SessionSummary } from '../types'
import { FaButton, FaDrawer, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSearchBar, FaSelect, FaTag, useFaModal } from '@yudream/components'
import { onMounted, ref } from 'vue'
import MarkdownPreview from '../components/MarkdownPreview.vue'
import { formatTime, optionKey } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const confirm = useFaModal()

const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '作答中', value: 'ONGOING' },
  { label: '已提交', value: 'FINISHED' },
]

const columns: TableColumn<SessionSummary>[] = [
  { id: 'user', header: '用户', width: 140, fixed: 'left' },
  { accessorKey: 'paperName', header: '题单', minWidth: 140 },
  { id: 'total', header: '题数', width: 80 },
  { id: 'correct', header: '答对', width: 80 },
  { id: 'accuracy', header: '正确率', width: 90 },
  { id: 'status', header: '状态', width: 90 },
  { accessorKey: 'createdAt', header: '开始时间', width: 170 },
  { id: 'submittedAt', header: '提交时间', width: 170 },
  { id: 'operation', header: '操作', width: 150 },
]

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<import('../types').SessionView | null>(null)

function accuracy(row: SessionSummary) {
  const total = Number(row.totalCount)
  if (!total || row.status !== 'FINISHED') {
    return '-'
  }
  return `${Math.round((Number(row.correctCount) / total) * 100)}%`
}

async function openDetail(row: SessionSummary) {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await model.api.adminRecord(row.id)
  }
  finally {
    detailLoading.value = false
  }
}

function userAnswerText(question: import('../types').SessionQuestionView) {
  const answer = question.userAnswer
  if (!answer) {
    return '未作答'
  }
  if (question.type === 'SINGLE' || question.type === 'TRUE_FALSE') {
    return answer.choice || '未作答'
  }
  if (question.type === 'MULTIPLE') {
    return answer.choices?.length ? [...answer.choices].sort().join('、') : '未作答'
  }
  if (question.type === 'FILL') {
    return answer.blanks?.length ? answer.blanks.map((text, index) => `第${index + 1}空：${text || '（空）'}`).join('；') : '未作答'
  }
  return answer.text || '未作答'
}

function correctAnswerText(question: import('../types').SessionQuestionView) {
  if (question.type === 'SINGLE' || question.type === 'TRUE_FALSE') {
    return question.answer || '-'
  }
  if (question.type === 'MULTIPLE') {
    return question.answers?.join('、') || '-'
  }
  if (question.type === 'FILL') {
    return question.blanks?.map((list, index) => `第${index + 1}空：${list.join(' / ')}`).join('；') || '-'
  }
  return '见参考答案'
}

function correctTagVariant(correct: boolean | undefined) {
  if (correct === true) {
    return 'default' as const
  }
  if (correct === false) {
    return 'outline' as const
  }
  return 'secondary' as const
}

function correctTagText(correct: boolean | undefined) {
  if (correct === true) {
    return '答对'
  }
  if (correct === false) {
    return '答错'
  }
  return '待自评'
}

function confirmDelete(row: SessionSummary) {
  confirm.confirm({
    title: '删除练习记录',
    content: `确认删除用户 ${row.userName || row.userId} 的这条练习记录吗？删除后不可恢复。`,
    onConfirm: () => model.removeAdminRecord(row),
  })
}

onMounted(() => model.loadAdminRecords())
</script>

<template>
  <FaPageHeader title="练习记录" description="跨用户查看全部抽题练习与作答结果" />
  <FaPageMain>
    <FaResponsiveTable
      v-loading="model.loading"
      :columns="columns"
      :data="model.adminRecords"
      row-key="id"
      table-root-class="max-w-full overflow-x-auto rounded-lg"
      table-class="min-w-[960px]"
      border stripe
      empty-text="暂无练习记录"
    >
      <template #toolbar>
        <FaSearchBar class="w-full">
          <div class="qb-toolbar">
            <FaInput v-model="model.adminRecordsFilters.keyword" placeholder="搜索用户昵称或 ID" clearable @keydown.enter="model.applyAdminRecordsFilters" @clear="model.applyAdminRecordsFilters" />
            <FaSelect v-model="model.adminRecordsFilters.status" :options="statusOptions" @change="model.applyAdminRecordsFilters" />
            <FaButton variant="outline" @click="model.applyAdminRecordsFilters"><FaIcon name="i-ri:search-line" />查询</FaButton>
          </div>
        </FaSearchBar>
      </template>
      <template #cell-user="{ row }">{{ row.original.userName || row.original.userId }}</template>
      <template #cell-paperName="{ row }">{{ row.original.paperName || '-' }}</template>
      <template #cell-total="{ row }">{{ row.original.totalCount }}</template>
      <template #cell-correct="{ row }">{{ row.original.status === 'FINISHED' ? row.original.correctCount : '-' }}</template>
      <template #cell-accuracy="{ row }">{{ accuracy(row.original) }}</template>
      <template #cell-status="{ row }">
        <FaTag v-if="row.original.pendingReview" variant="secondary">待审核</FaTag>
        <FaTag v-else :variant="row.original.status === 'FINISHED' ? 'default' : 'outline'">{{ row.original.status === 'FINISHED' ? '已提交' : '作答中' }}</FaTag>
      </template>
      <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
      <template #cell-submittedAt="{ row }">{{ formatTime(row.original.submittedAt) }}</template>
      <template #cell-operation="{ row }">
        <div class="flex-center gap-2">
          <FaButton size="sm" variant="outline" @click="openDetail(row.original)">详情</FaButton>
          <FaButton size="sm" variant="destructive" @click="confirmDelete(row.original)">删除</FaButton>
        </div>
      </template>
      <template #card="{ row }">
        <div class="flex flex-col gap-2 text-sm">
          <div class="flex items-center justify-between">
            <strong class="text-base">{{ row.userName || row.userId }}</strong>
            <FaTag :variant="row.status === 'FINISHED' ? 'default' : 'outline'">{{ row.status === 'FINISHED' ? '已提交' : '作答中' }}</FaTag>
          </div>
          <div class="text-secondary-foreground/60">
            {{ row.totalCount }} 道题<template v-if="row.status === 'FINISHED'"> · 答对 {{ row.correctCount }} · 正确率 {{ accuracy(row) }}</template>
          </div>
          <div class="text-secondary-foreground/60">{{ formatTime(row.createdAt) }}</div>
          <div class="flex gap-2">
            <FaButton size="sm" variant="outline" @click="openDetail(row)">详情</FaButton>
            <FaButton size="sm" variant="destructive" @click="confirmDelete(row)">删除</FaButton>
          </div>
        </div>
      </template>
    </FaResponsiveTable>
    <FaPagination
      v-model:page="model.adminRecordsPager.page"
      v-model:size="model.adminRecordsPager.size"
      :total="model.adminRecordsPager.total"
      class="mt-3"
      @page-change="model.loadAdminRecords"
      @size-change="model.applyAdminRecordsFilters"
    />
    <FaDrawer v-model="detailOpen" title="练习详情" size="720px">
      <div v-loading="detailLoading" class="flex flex-col gap-4">
        <template v-if="detail">
          <div class="qb-result-banner">
            <span class="qb-result-score">{{ detail.correctCount }} / {{ detail.totalCount }}</span>
            <span class="qb-muted">{{ detail.userName || detail.userId }}<template v-if="detail.paperName"> · {{ detail.paperName }}</template> · {{ formatTime(detail.createdAt) }}</span>
          </div>
          <div v-for="(question, index) in detail.questions" :key="question.questionId" class="qb-question-card rounded-lg border p-4">
            <div class="qb-question-meta">
              <strong>第 {{ index + 1 }} 题</strong>
              <FaTag variant="secondary">{{ question.typeLabel }}</FaTag>
              <FaTag :variant="correctTagVariant(question.userAnswer?.correct)">{{ correctTagText(question.userAnswer?.correct) }}</FaTag>
            </div>
            <MarkdownPreview :sdk="model.sdk" :content="question.content" />
            <div v-if="question.options.length" class="flex flex-col gap-1 text-sm">
              <div v-for="(option, optionIndex) in question.options" :key="optionIndex">
                {{ optionKey(optionIndex) }}. {{ option }}
              </div>
            </div>
            <div class="qb-answer-panel text-sm">
              <div><strong>作答：</strong>{{ userAnswerText(question) }}</div>
              <div><strong>答案：</strong>{{ correctAnswerText(question) }}</div>
              <template v-if="question.type === 'SHORT' && question.referenceAnswer">
                <div><strong>参考答案：</strong></div>
                <MarkdownPreview :sdk="model.sdk" :content="question.referenceAnswer" />
              </template>
              <template v-if="question.analysis">
                <div><strong>解析：</strong></div>
                <MarkdownPreview :sdk="model.sdk" :content="question.analysis" />
              </template>
            </div>
          </div>
        </template>
      </div>
    </FaDrawer>
  </FaPageMain>
</template>
