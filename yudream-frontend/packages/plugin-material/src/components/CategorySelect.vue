<script setup lang="ts">
import type { CategoryView } from '../types'
import { Select as ASelect } from '@arco-design/web-vue'
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  categories: CategoryView[]
  /** 空值选项文案（分类变多时可搜索）；传空串则不提供空值选项 */
  emptyLabel?: string
  placeholder?: string
}>(), { emptyLabel: '未分类', placeholder: '搜索并选择分类' })

const model = defineModel<string>({ default: '' })

const options = computed(() => {
  const list = props.categories.map(category => ({ label: category.name, value: category.id }))
  return props.emptyLabel ? [{ label: props.emptyLabel, value: '' }, ...list] : list
})
</script>

<template>
  <ASelect v-model="model" :options="options" :placeholder="placeholder" allow-search />
</template>
