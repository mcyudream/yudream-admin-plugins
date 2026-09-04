<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { SessionSummary } from '../types'
import { FaButton, FaIcon, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaTag, useFaModal } from '@yudream/components'
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { difficultyLabel, formatTime, questionTypeLabel } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const router = useRouter()
const confirm = useFaModal()

const columns: TableColumn<SessionSummary>[] = [
  { accessorKey: 'createdAt', header: '开始时间', width: 170, fixed: 'left' },
  { id: 'scope', header: '抽题范围', minWidth: 200 },
  { accessorKey: 'totalCount', header: '题数', width: 70 },
  { id: 'score', header: '成绩', width: 110 },
  { id: 'status', header: '状态', width: 90 },
  { accessorKey: 'submittedAt', header: '提交时间', width: 170 },
  { id: 'operation', header: '操作', width: 170 },
]

function scopeText(row: SessionSummary) {
  if (row.paperName) {
    return `题单：${row.paperName}`
  }
  const parts: string[] = []
  if (row.types?.length) {
    parts.push(row.types.map(questionTypeLabel).join('/'))
  }
  if (row.tags?.length) {
    parts.push(`标签：${row.tags.join('、')}`)
  }
  if (row.difficulties?.length) {
    parts.push(`难度：${row.difficulties.map(difficultyLabel).join(' ')}`)
  }
  return parts.length ? parts.join('；') : '全部题目'
}

function accuracy(row: SessionSummary) {
  if (row.status !== 'FINISHED' || !row.totalCount) {
    return '-'
  }
  return `${row.correctCount}/${row.totalCount}（${Math.round((row.correctCount / row.totalCount) * 100)}%）`
}

function open(row: SessionSummary) {
  router.push({ path: '/platform/plugins/questionbank/session', query: { id: row.id } })
}

function confirmDelete(row: SessionSummary) {
  confirm.confirm({
    title: '删除练习记录',
    content: `确认删除 ${formatTime(row.createdAt)} 的这次练习记录吗？`,
    onConfirm: () => model.removeMyRecord(row),
  })
}

onMounted(() => model.loadMyRecords())
</script>

<template>
  <FaPageHeader title="成绩记录" description="我的练习历史，进行中的练习可以继续作答">
    <FaButton variant="outline" @click="router.push('/platform/plugins/questionbank/papers')">
      <FaIcon name="i-ri:file-paper-2-line" />题单作答
    </FaButton>
    <FaButton @click="router.push('/platform/plugins/questionbank')"><FaIcon name="i-ri:play-line" />去练习</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaResponsiveTable
      v-loading="model.loading"
      :columns="columns"
      :data="model.myRecords"
      row-key="id"
      table-root-class="qb-table-scroll"
      table-class="qb-table-w960"
      border stripe
      empty-text="还没有练习记录，去抽一组题吧"
    >
      <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
      <template #cell-scope="{ row }">{{ scopeText(row.original) }}</template>
      <template #cell-score="{ row }">{{ accuracy(row.original) }}</template>
      <template #cell-status="{ row }">
        <FaTag v-if="row.original.status === 'FINISHED' && row.original.pendingReview" variant="secondary">待审核</FaTag>
        <FaTag v-else :variant="row.original.status === 'FINISHED' ? 'default' : 'outline'">
          {{ row.original.status === 'FINISHED' ? '已完成' : '进行中' }}
        </FaTag>
      </template>
      <template #cell-submittedAt="{ row }">{{ row.original.status === 'FINISHED' ? formatTime(row.original.submittedAt) : '-' }}</template>
      <template #cell-operation="{ row }">
        <div class="flex-center gap-2">
          <FaButton size="sm" variant="outline" @click="open(row.original)">
            {{ row.original.status === 'FINISHED' ? '查看' : '继续作答' }}
          </FaButton>
          <FaButton size="sm" variant="destructive" @click="confirmDelete(row.original)">删除</FaButton>
        </div>
      </template>
      <template #card="{ row }">
        <div class="flex flex-col gap-2 text-sm">
          <div class="flex items-center justify-between">
            <strong>{{ formatTime(row.createdAt) }}</strong>
            <FaTag :variant="row.status === 'FINISHED' ? 'default' : 'outline'">
              {{ row.status === 'FINISHED' ? '已完成' : '进行中' }}
            </FaTag>
          </div>
          <div class="text-secondary-foreground/60">{{ scopeText(row) }}</div>
          <div>{{ row.totalCount }} 题 · 成绩 {{ accuracy(row) }}</div>
          <div class="flex gap-2">
            <FaButton size="sm" variant="outline" @click="open(row)">
              {{ row.status === 'FINISHED' ? '查看' : '继续作答' }}
            </FaButton>
            <FaButton size="sm" variant="destructive" @click="confirmDelete(row)">删除</FaButton>
          </div>
        </div>
      </template>
    </FaResponsiveTable>
    <FaPagination
      v-model:page="model.myRecordsPager.page"
      v-model:size="model.myRecordsPager.size"
      :total="model.myRecordsPager.total"
      class="mt-3"
      @page-change="model.loadMyRecords"
      @size-change="model.loadMyRecords"
    />
  </FaPageMain>
</template>
