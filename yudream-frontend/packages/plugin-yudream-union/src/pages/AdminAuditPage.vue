<script setup lang="ts">
import type { ActionMenuItem, SearchConfig, SearchFieldConfig, TableColumn, YdTablePageAction } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { UnionAuditLog } from '../types'
import { ref } from 'vue'
import { YdAlert, YdDynamicSearchForm, YdLoadingOverlay, YdPagination, YdResponsiveEnhancedTable, YdTablePageLayout, useYdModal, useYdToast } from '@yudream/components'
import { formatDateTime } from '../composables/useUnionApiShell'
import { useUnionAudit } from '../composables/useUnionAudit'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useUnionAudit(props.sdk)
const confirm = useYdModal()
const toast = useYdToast()

/** ControlType 是 components 内部字符串枚举，桥接层只出类型，这里用字面量收敛一次 */
type SearchFieldInput = Partial<Omit<SearchFieldConfig, 'controlType'>> & Pick<SearchFieldConfig, 'fieldName' | 'label'> & { controlType?: 'TEXT' | 'SELECT' | 'DATE_RANGE' | 'NUMBER_RANGE' | 'CHECKBOX' | 'RADIO' }

function searchField(config: SearchFieldInput): SearchFieldConfig {
  const { controlType = 'TEXT', order = 0, showWhenFolded = false, width = '200px', ...rest } = config
  return { ...rest, fieldName: config.fieldName, label: config.label, controlType: controlType as SearchFieldConfig['controlType'], order, showWhenFolded, width }
}

const searchQuery = ref<Record<string, any>>({ keyword: model.keyword })

const searchConfig: SearchConfig = {
  fields: [
    searchField({ fieldName: 'keyword', label: '关键词', controlType: 'TEXT', order: 1, showWhenFolded: true, width: '260px' }),
  ],
}

function applySearch() {
  model.keyword = String(searchQuery.value.keyword ?? '')
  void model.search()
}

const columns: TableColumn<UnionAuditLog>[] = [
  { accessorKey: 'at', header: '时间', width: 180 },
  { accessorKey: 'action', header: '动作', width: 150 },
  { accessorKey: 'target', header: '目标', minWidth: 150 },
  { id: 'detail', header: '详情', minWidth: 280, enableSorting: false },
  { accessorKey: 'actor', header: '操作者', width: 140, defaultHidden: true },
]

const pageActions: YdTablePageAction[] = [
  { label: '清空审计', icon: 'morph:trash-2', danger: true, onClick: clearAll },
]

const rowActions: ActionMenuItem<UnionAuditLog>[] = [
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: log => deleteOne(log) },
]

function deleteOne(log: UnionAuditLog) {
  confirm.confirm({
    title: '删除审计记录',
    content: `确认删除「${log.action || '未知动作'}」这条审计记录吗？`,
    onConfirm: async () => {
      try {
        await model.deleteOne(log)
      }
      catch (cause) {
        toast.error(cause instanceof Error ? cause.message : '删除失败')
      }
    },
  })
}

function clearAll() {
  confirm.confirm({
    title: '清空审计记录',
    content: '确认清空全部审计记录吗？该操作不可恢复；自动保留清理仍按设置执行。',
    onConfirm: async () => {
      try {
        await model.clear()
      }
      catch (cause) {
        toast.error(cause instanceof Error ? cause.message : '清空失败')
      }
    },
  })
}
</script>

<template>
  <YdTablePageLayout title="审计" :actions="pageActions" stats-title="审计检索与保留">
    <template #search="{ fold }">
      <YdDynamicSearchForm
        v-model="searchQuery"
        :config="searchConfig"
        :fold="fold"
        @search="applySearch"
        @reset="applySearch"
      />
    </template>
    <template #stats>
      <p class="text-sm text-muted-foreground">按时间倒序显示；点击列头可按时间、动作、目标、操作者排序（服务端排序）。支持关键词搜索、单条删除和全量清空；自动保留清理由站点身份页配置。</p>
    </template>
    <template #table>
      <YdAlert v-if="model.error" class="mb-3" variant="destructive" title="加载失败" :description="model.error" />
      <YdLoadingOverlay :loading="model.loading">
        <YdResponsiveEnhancedTable
          :columns="columns"
          :data="model.records"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[760px]"
          border
          stripe
          sortable
          manual-sorting
          :sorting="model.sorting"
          column-visibility
          :actions="rowActions"
          empty-text="暂无审计记录"
          @sorting-change="model.changeSorting"
        >
          <template #cell-at="{ row }">{{ formatDateTime(row.original.at) }}</template>
          <template #cell-action="{ row }">{{ row.original.action }}</template>
          <template #cell-target="{ row }">{{ row.original.target || '-' }}</template>
          <template #cell-detail="{ row }">{{ row.original.detail || '-' }}</template>
          <template #cell-actor="{ row }">{{ row.original.actorType }}/{{ row.original.actor }}</template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>
    <template #footer>
      <YdPagination
        v-model:page="model.page"
        v-model:size="model.size"
        :total="model.total"
        @page-change="page => model.changePage(page)"
        @size-change="size => model.changePage(1, size)"
      />
    </template>
  </YdTablePageLayout>
</template>
