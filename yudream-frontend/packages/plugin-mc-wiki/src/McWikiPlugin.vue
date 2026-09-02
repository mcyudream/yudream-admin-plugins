<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed } from 'vue'
import { useMcWikiPlugin } from './composables/useMcWikiPlugin'
import CraftingRecipes from './pages/CraftingRecipes.vue'
import Items from './pages/Items.vue'
import Jobs from './pages/Jobs.vue'
import Versions from './pages/Versions.vue'

const props = defineProps<{ sdk: YuDreamPluginSdk, route?: { meta?: { plugin?: { component?: string } } } }>()
const model = useMcWikiPlugin(props.sdk)
const page = computed(() => {
  const name = props.route?.meta?.plugin?.component?.split('/').pop()
  if (name === 'Jobs') {
    return Jobs
  }
  if (name === 'Versions') {
    return Versions
  }
  if (name === 'Items') {
    return Items
  }
  return CraftingRecipes
})
</script>

<template>
  <div class="mc-wiki-plugin"><component :is="page" :model="model" /></div>
</template>
