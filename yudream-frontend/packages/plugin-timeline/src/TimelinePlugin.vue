<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed } from 'vue'
import { useTimelinePlugin } from './composables/useTimelinePlugin'
import AdminPage from './pages/AdminPage.vue'
import PublicPage from './pages/PublicPage.vue'

const props = defineProps<{ sdk: YuDreamPluginSdk, route?: { meta?: { plugin?: { component?: string } } } }>()
const model = useTimelinePlugin(props.sdk)
const page = computed(() => {
  switch (props.route?.meta?.plugin?.component?.split('/').pop()) {
    case 'Admin':
      return AdminPage
    default:
      return PublicPage
  }
})
</script>

<template>
  <div class="timeline-plugin"><component :is="page" :model="model" :route="route" /></div>
</template>
