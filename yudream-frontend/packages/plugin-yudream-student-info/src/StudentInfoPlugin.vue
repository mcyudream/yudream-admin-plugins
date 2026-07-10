<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { computed } from 'vue'
import AdminProfilesPage from './pages/AdminProfilesPage.vue'
import ProfilePage from './pages/ProfilePage.vue'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const page = computed(() => {
  const plugin = props.route?.meta?.plugin as { component?: string } | undefined
  if (plugin?.component === 'yudream-student-info/AdminProfiles') {
    return AdminProfilesPage
  }
  return ProfilePage
})

</script>

<template>
  <div class="student-info-plugin">
    <component :is="page" :sdk="sdk" />
  </div>
</template>
