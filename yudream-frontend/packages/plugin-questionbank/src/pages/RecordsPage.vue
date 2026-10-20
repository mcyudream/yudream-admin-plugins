<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { TableColumn, ActionMenuItem } from '@yudream/components'
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { SessionSummary } from '../types'
import { YdButton, YdIcon, YdPagination, YdTag, useYdModal, YdResponsiveEnhancedTable } from '@yudream/components'
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { difficultyLabel, formatTime, questionTypeLabel } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const router = useRouter()
const confirm = useYdModal()

const columns: TableColumn<SessionSummary>[] = [
  { accessorKey: 'createdAt', header: '开始时间', width: 170, fixed: 'left' },
  { id: 'scope', header: '抽题范围', minWidth: 200 },
  { accessorKey: 'totalCount', header: '题数', width: 70 },
  { id: 'score', header: '成绩', width: 110 },
  { id: 'status', header: '状态', width: 90 },
  { accessorKey: 'submittedAt', header: '提交时间', width: 170 },
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

/** 答题记录行操作：继续作答或查看 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: row => ((row as { status: string }).status === 'FINISHED' ? '查看' : '继续作答'), icon: 'morph:play', onClick: row => open(row as never) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => confirmDelete(row as never) },
]

</script>

<template>
  <YdTablePageLayout title="成绩记录" :show-search="false">
    <template #actions>
      <YdButton variant="outline" @click="router.push('/platform/plugins/questionbank/papers')">
      <YdIcon name="i-ri:file-paper-2-line" />题单作答
    </YdButton>
    <YdButton @click="router.push('/platform/plugins/questionbank')"><YdIcon name="i-ri:play-line" />去练习</YdButton>
    </template>
    <template #table>
<YdLoadingOverlay :loading="model.loading"><YdResponsiveEnhancedTable
        :actions="rowActions"

      
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
        <YdTag v-if="row.original.status === 'FINISHED' && row.original.pendingReview" variant="secondary">待审核</YdTag>
        <YdTag v-else :variant="row.original.status === 'FINISHED' ? 'default' : 'outline'">
          {{ row.original.status === 'FINISHED' ? '已完成' : '进行中' }}
        </YdTag>
      </template>
      <template #cell-submittedAt="{ row }">{{ row.original.status === 'FINISHED' ? formatTime(row.original.submittedAt) : '-' }}</template>
</YdResponsiveEnhancedTable></YdLoadingOverlay>
    </template>
    <template #footer>
      <YdPagination
      v-model:page="model.myRecordsPager.page"
      v-model:size="model.myRecordsPager.size"
      :total="model.myRecordsPager.total"
      @page-change="model.loadMyRecords"
      @size-change="model.loadMyRecords"
    />
    </template>
  </YdTablePageLayout>
</template>
