<script setup lang="ts">
import type { Category } from '../types'
import { FaSelect } from '@yudream/components'
defineProps<{ categories: Category[] }>()
const model = defineModel<string>({ default: '' })
</script>
<template><FaSelect v-model="model" class="forum-select" :options="categories.map(c => ({ label: c.name, value: c.id }))" placeholder="选择分类" /></template>
