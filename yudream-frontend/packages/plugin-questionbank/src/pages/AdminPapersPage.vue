<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { TableColumn, ActionMenuItem } from '@yudream/components'
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { PaperView } from '../types'
import { YdButton, YdIcon, YdInput, YdPagination, YdSelect, YdTag, useYdModal, YdResponsiveEnhancedTable } from '@yudream/components'
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { difficultyLabel, formatTime, questionTypeLabel } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const router = useRouter()
const confirm = useYdModal()

const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '草稿', value: 'DRAFT' },
  { label: '已发布', value: 'PUBLISHED' },
  { label: '已归档', value: 'ARCHIVED' },
]

const columns: TableColumn<PaperView>[] = [
  { accessorKey: 'name', header: '题单名称', minWidth: 160, fixed: 'left' },
  { id: 'mode', header: '抽题方式', width: 110 },
  { id: 'scope', header: '抽题范围', minWidth: 200 },
  { accessorKey: 'questionCount', header: '题量', width: 70 },
  { id: 'subjective', header: '简答判分', width: 100 },
  { id: 'status', header: '状态', width: 90 },
  { accessorKey: 'updatedAt', header: '更新时间', width: 170 },
]

function scopeText(row: PaperView) {
  if (row.mode === 'MANUAL') {
    return `手动选题（${row.questionCount} 道）`
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
  return parts.length ? parts.join('；') : '不限范围'
}

function subjectiveText(subjectiveMode?: string) {
  switch (subjectiveMode) {
    case 'REVIEW':
      return '人工审核'
    case 'AI':
      return 'AI 判分'
    default:
      return '用户自评'
  }
}

function statusTag(row: PaperView) {
  switch (row.status) {
    case 'PUBLISHED':
      return { text: '已发布', variant: 'default' as const }
    case 'ARCHIVED':
      return { text: '已归档', variant: 'outline' as const }
    default:
      return { text: '草稿', variant: 'secondary' as const }
  }
}

function openCreate() {
  void router.push({ path: '/platform/plugins/questionbank/admin/papers/edit' })
}

function openEdit(row: PaperView) {
  void router.push({ path: '/platform/plugins/questionbank/admin/papers/edit', query: { id: row.id } })
}

/** 打印页是公开无布局路由（不带系统导航栏），用新标签打开，避免破坏当前页导航状态。 */
function openPrint(row: PaperView) {
  const href = router.resolve({ path: '/platform/plugins/questionbank/admin/papers/print', query: { id: row.id } }).href
  window.open(href, '_blank', 'noopener')
}

function confirmDelete(row: PaperView) {
  confirm.confirm({
    title: '删除题单',
    content: `确认删除题单「${row.name}」吗？删除后不可恢复，已产生的作答记录不受影响。`,
    onConfirm: () => model.removePaper(row),
  })
}

onMounted(() => model.loadAdminPapers())

/** 试卷行操作统一收进操作菜单 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '编辑', icon: 'morph:pencil', onClick: row => openEdit(row as never) },
  { label: '打印', icon: 'morph:printer', onClick: row => openPrint(row as never) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => confirmDelete(row as never) },
]

</script>

<template>
  <YdTablePageLayout title="题单管理">
      <template #search>
        <div class="yd-filter-grid" style="--yd-filter-columns: 2">
          <YdInput v-model="model.adminPapersFilters.keyword" class="w-full" placeholder="搜索题单名称" clearable @keydown.enter="model.applyAdminPapersFilters" @clear="model.applyAdminPapersFilters" />
          <YdSelect v-model="model.adminPapersFilters.status" class="w-full" :options="statusOptions" @change="model.applyAdminPapersFilters" />
          <div class="flex items-center justify-end gap-2">
            <YdButton variant="outline" @click="model.applyAdminPapersFilters"><YdIcon name="i-ri:search-line" />查询</YdButton>
          </div>
        </div>
      </template>
    <template #actions>
      <YdButton @click="openCreate"><YdIcon name="i-ri:add-line" />新建题单</YdButton>
    </template>
    <template #table>
<YdLoadingOverlay :loading="model.loading"><YdResponsiveEnhancedTable
        :actions="rowActions"

      
      :columns="columns"
      :data="model.adminPapers"
      row-key="id"
      table-root-class="qb-table-scroll"
      table-class="qb-table-w1100"
      border stripe
      empty-text="暂无题单，点击右上角新建"
    >
      <template #cell-mode="{ row }">
        <YdTag :variant="row.original.mode === 'RULE' ? 'default' : 'secondary'">
          {{ row.original.mode === 'RULE' ? '随机抽题' : '固定题目' }}
        </YdTag>
      </template>
      <template #cell-scope="{ row }">{{ scopeText(row.original) }}</template>
      <template #cell-subjective="{ row }">{{ subjectiveText(row.original.subjectiveMode) }}</template>
      <template #cell-status="{ row }">
        <YdTag :variant="statusTag(row.original).variant">{{ statusTag(row.original).text }}</YdTag>
      </template>
      <template #cell-updatedAt="{ row }">{{ formatTime(row.original.updatedAt) }}</template>
</YdResponsiveEnhancedTable></YdLoadingOverlay>
    </template>
    <template #footer>
      <YdPagination
      v-model:page="model.adminPapersPager.page"
      v-model:size="model.adminPapersPager.size"
      :total="model.adminPapersPager.total"
      @page-change="model.loadAdminPapers"
      @size-change="model.applyAdminPapersFilters"
    />
    </template>
  </YdTablePageLayout>
</template>
