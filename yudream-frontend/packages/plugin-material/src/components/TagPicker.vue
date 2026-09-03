<script setup lang="ts">
import type { TableColumn, YdTablePickerQuery, YdTablePickerResult } from '@yudream/components'
import type { TagView } from '../types'
import { FaButton, FaInput, useFaToast, YdTablePicker } from '@yudream/components'
import { ref } from 'vue'

/**
 * 弹出式标签选择：YdTablePicker 以表格弹窗浏览/搜索已有标签，下方输入行支持新建标签。
 * 选中值即标签名本身（rowKey=name），新建的标签不在标签云里也能正常回显。
 */
const props = withDefaults(defineProps<{
  tags: TagView[]
  max?: number
  maxLength?: number
}>(), { max: 8, maxLength: 20 })

const model = defineModel<string[]>({ default: () => [] })
const toast = useFaToast()
const newTag = ref('')

const columns: TableColumn<TagView>[] = [
  { accessorKey: 'name', header: '标签', minWidth: 180 },
  { accessorKey: 'count', header: '使用次数', width: 90 },
]

/** 标签云由后端按使用次数降序给出（上限 30），弹窗内按关键字过滤 + 前端分页。 */
async function fetcher(query: YdTablePickerQuery): Promise<YdTablePickerResult<TagView>> {
  const keyword = query.keyword.trim().toLowerCase()
  const filtered = keyword
    ? props.tags.filter(tag => tag.name.toLowerCase().includes(keyword))
    : props.tags
  const from = (query.page - 1) * query.size
  return { list: filtered.slice(from, from + query.size), total: filtered.length }
}

/** 弹窗确认时兜底数量上限（choose 时没有 limit 概念，这里裁剪尾部并提示） */
function onChange(keys: string[]) {
  if (keys.length > props.max) {
    model.value = keys.slice(0, props.max)
    toast.warning(`最多选择 ${props.max} 个标签`)
  }
}

function addTag() {
  const name = newTag.value.trim()
  if (!name) {
    return
  }
  if (name.length > props.maxLength) {
    toast.warning(`单个标签不能超过 ${props.maxLength} 字`)
    return
  }
  if (model.value.includes(name)) {
    newTag.value = ''
    return
  }
  if (model.value.length >= props.max) {
    toast.warning(`最多 ${props.max} 个标签`)
    return
  }
  model.value = [...model.value, name]
  newTag.value = ''
}
</script>

<template>
  <div class="flex flex-col gap-2">
    <YdTablePicker
      v-model="model"
      :columns="columns"
      :fetcher="fetcher"
      row-key="name"
      label-key="name"
      title="选择标签"
      :placeholder="`选择已有标签，最多 ${max} 个`"
      search-placeholder="搜索标签名称"
      empty-text="暂无已有标签，可在下方输入新建"
      :page-size="10"
      :page-sizes="[10, 20, 50]"
      @change="onChange"
    />
    <div class="flex gap-2">
      <FaInput
        v-model="newTag"
        placeholder="输入新标签，回车添加"
        :maxlength="maxLength"
        clearable
        @keydown.enter.prevent="addTag"
      />
      <FaButton variant="outline" class="shrink-0" @click="addTag">
        新建标签
      </FaButton>
    </div>
  </div>
</template>
