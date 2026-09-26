<script setup lang="ts">
import type { Category } from '../api/forum-api'
import { FaSelect } from '@yudream/components'
import { computed } from 'vue'
const props = defineProps<{ categories: Category[] }>()
const model = defineModel<string>({ default: '' })
// FaSelect 当前值匹配不到选项时会回退显示第一个选项的名称，未选中时看起来像已选中；
// 置顶一个禁用占位项承接该回退，未选中时显示"请选择分类"而不是第一个分类
const options = computed(() => [
  { label: '请选择分类', value: '__unselected__', disabled: true },
  ...props.categories.map(c => ({ label: c.name, value: c.id })),
])
</script>
<template><FaSelect v-model="model" class="forum-select" :options="options" placeholder="选择分类" /></template>
