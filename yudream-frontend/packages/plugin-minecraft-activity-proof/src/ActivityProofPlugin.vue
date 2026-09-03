<script setup lang="ts">
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed } from 'vue'
import ActivitiesPage from './pages/ActivitiesPage.vue'
import ActivityAdminDetailPage from './pages/ActivityAdminDetailPage.vue'
import ActivityDetailPage from './pages/ActivityDetailPage.vue'
import ActivityEditPage from './pages/ActivityEditPage.vue'
import MappingsPage from './pages/MappingsPage.vue'
import MinePage from './pages/MinePage.vue'
import MyActivitiesPage from './pages/MyActivitiesPage.vue'
import RecordsPage from './pages/RecordsPage.vue'
import SettingsPage from './pages/SettingsPage.vue'
import SquarePage from './pages/SquarePage.vue'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const componentName = computed(() => (props.route?.meta?.plugin as { component?: string } | undefined)?.component || '')

const page = computed(() => {
  if (componentName.value.endsWith('/ActivityDetail')) return ActivityDetailPage
  if (componentName.value.endsWith('/MyActivities')) return MyActivitiesPage
  if (componentName.value.endsWith('/Mine')) return MinePage
  if (componentName.value.endsWith('/Activities')) return ActivitiesPage
  if (componentName.value.endsWith('/ActivityEdit')) return ActivityEditPage
  if (componentName.value.endsWith('/ActivityAdminDetail')) return ActivityAdminDetailPage
  if (componentName.value.endsWith('/Records')) return RecordsPage
  if (componentName.value.endsWith('/Mappings')) return MappingsPage
  if (componentName.value.endsWith('/Settings')) return SettingsPage
  return SquarePage
})
</script>

<template>
  <div class="minecraft-activity-proof-plugin">
    <component :is="page" :sdk="sdk" :route="route" />
  </div>
</template>
