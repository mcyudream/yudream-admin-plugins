<script setup lang="ts">
import type { TableColumn, YdTablePickerQuery, YdTablePickerResult } from '@yudream/components'
import type { CategoryView } from '../types'
import { YdTablePicker } from '@yudream/components'
import { computed } from 'vue'

/**
 * 弹出式分类选择：与标签选择一致走 YdTablePicker 表格弹窗，单选。
 * 空值用空字符串表示（未分类 / 全部分类），在弹窗内取消勾选即回到空值。
 */
const props = withDefaults(defineProps<{
  categories: CategoryView[]
  placeholder?: string
}>(), { placeholder: '选择分类（不选为未分类）' })

const model = defineModel<string>({ default: '' })

/** YdTablePicker 是数组模型，这里桥接成单个分类 id */
const keys = computed<string[]>({
  get: () => (model.value ? [model.value] : []),
  set: (value) => {
    model.value = value[0] ?? ''
  },
})

/** 弹窗未打开时 labelCache 为空，触发器靠 initialLabels 显示分类名而不是原始 id */
const initialLabels = computed(() => Object.fromEntries(props.categories.map(category => [category.id, category.name])))

const columns: TableColumn<CategoryView>[] = [
  { accessorKey: 'name', header: '分类', minWidth: 180 },
  { accessorKey: 'materials', header: '物料数', width: 90 },
]

/** 分类全量由后端一次给出，弹窗内按关键字过滤 + 前端分页 */
async function fetcher(query: YdTablePickerQuery): Promise<YdTablePickerResult<CategoryView>> {
  const keyword = query.keyword.trim().toLowerCase()
  const filtered = keyword
    ? props.categories.filter(category => category.name.toLowerCase().includes(keyword))
    : props.categories
  const from = (query.page - 1) * query.size
  return { list: filtered.slice(from, from + query.size), total: filtered.length }
}
</script>

<template>
  <YdTablePicker
    v-model="keys"
    :columns="columns"
    :fetcher="fetcher"
    row-key="id"
    label-key="name"
    :multiple="false"
    :initial-labels="initialLabels"
    title="选择分类"
    :placeholder="placeholder"
    search-placeholder="搜索分类名称"
    empty-text="暂无分类，可在管理页创建"
    :page-size="10"
    :page-sizes="[10, 20, 50]"
  />
</template>
