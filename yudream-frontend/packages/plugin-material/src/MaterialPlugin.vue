<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed } from 'vue'
import { useMaterialPlugin } from './composables/useMaterialPlugin'
import AdminPage from './pages/AdminPage.vue'
import CategoriesPage from './pages/CategoriesPage.vue'
import DetailPage from './pages/DetailPage.vue'
import LibraryPage from './pages/LibraryPage.vue'

const props = defineProps<{ sdk: YuDreamPluginSdk, route?: { meta?: { plugin?: { component?: string } } } }>()
const model = useMaterialPlugin(props.sdk)
const page = computed(() => {
  const name = props.route?.meta?.plugin?.component?.split('/').pop()
  if (name === 'Detail') {
    return DetailPage
  }
  if (name === 'Admin') {
    return AdminPage
  }
  if (name === 'Categories') {
    return CategoriesPage
  }
  return LibraryPage
})
</script>

<template>
  <div class="material-plugin"><component :is="page" :model="model" /></div>
</template>
