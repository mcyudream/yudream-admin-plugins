<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { PaperView } from '../types'
import { FaButton, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSearchBar, FaSelect, FaTag, useFaModal } from '@yudream/components'
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { difficultyLabel, formatTime, questionTypeLabel } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const router = useRouter()
const confirm = useFaModal()

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
  { id: 'operation', header: '操作', width: 240 },
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
</script>

<template>
  <FaPageHeader title="题单管理" description="按规则随机抽题或手动固定选题，发布给用户作答，并可打印纸质试卷">
    <FaButton @click="openCreate"><FaIcon name="i-ri:add-line" />新建题单</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaResponsiveTable
      v-loading="model.loading"
      :columns="columns"
      :data="model.adminPapers"
      row-key="id"
      table-root-class="qb-table-scroll"
      table-class="qb-table-w1100"
      border stripe
      empty-text="暂无题单，点击右上角新建"
    >
      <template #toolbar>
        <FaSearchBar class="w-full">
          <div class="qb-toolbar">
            <FaInput v-model="model.adminPapersFilters.keyword" placeholder="搜索题单名称" clearable @keydown.enter="model.applyAdminPapersFilters" @clear="model.applyAdminPapersFilters" />
            <FaSelect v-model="model.adminPapersFilters.status" :options="statusOptions" @change="model.applyAdminPapersFilters" />
            <FaButton variant="outline" @click="model.applyAdminPapersFilters"><FaIcon name="i-ri:search-line" />查询</FaButton>
          </div>
        </FaSearchBar>
      </template>
      <template #cell-mode="{ row }">
        <FaTag :variant="row.original.mode === 'RULE' ? 'default' : 'secondary'">
          {{ row.original.mode === 'RULE' ? '随机抽题' : '固定题目' }}
        </FaTag>
      </template>
      <template #cell-scope="{ row }">{{ scopeText(row.original) }}</template>
      <template #cell-subjective="{ row }">{{ subjectiveText(row.original.subjectiveMode) }}</template>
      <template #cell-status="{ row }">
        <FaTag :variant="statusTag(row.original).variant">{{ statusTag(row.original).text }}</FaTag>
      </template>
      <template #cell-updatedAt="{ row }">{{ formatTime(row.original.updatedAt) }}</template>
      <template #cell-operation="{ row }">
        <div class="flex-center gap-2">
          <FaButton size="sm" variant="outline" @click="openEdit(row.original)">编辑</FaButton>
          <FaButton size="sm" variant="outline" @click="openPrint(row.original)">打印</FaButton>
          <FaButton size="sm" variant="destructive" @click="confirmDelete(row.original)">删除</FaButton>
        </div>
      </template>
      <template #card="{ row }">
        <div class="flex flex-col gap-2 text-sm">
          <div class="flex items-center justify-between gap-2">
            <strong class="text-base">{{ row.name }}</strong>
            <FaTag :variant="statusTag(row).variant">{{ statusTag(row).text }}</FaTag>
          </div>
          <div class="text-secondary-foreground/60">
            {{ row.mode === 'RULE' ? '随机抽题' : '固定题目' }} · {{ row.questionCount }} 题 · {{ scopeText(row) }}
          </div>
          <div class="flex gap-2">
            <FaButton size="sm" variant="outline" @click="openEdit(row)">编辑</FaButton>
            <FaButton size="sm" variant="outline" @click="openPrint(row)">打印</FaButton>
            <FaButton size="sm" variant="destructive" @click="confirmDelete(row)">删除</FaButton>
          </div>
        </div>
      </template>
    </FaResponsiveTable>
    <FaPagination
      v-model:page="model.adminPapersPager.page"
      v-model:size="model.adminPapersPager.size"
      :total="model.adminPapersPager.total"
      class="mt-3"
      @page-change="model.loadAdminPapers"
      @size-change="model.applyAdminPapersFilters"
    />
  </FaPageMain>
</template>
