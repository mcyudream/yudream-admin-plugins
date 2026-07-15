<script setup lang="ts">
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import PoliciesPage from './pages/PoliciesPage.vue'
defineProps<{ sdk: YuDreamPluginSdk; route?: RouteLocationNormalizedLoaded }>()
</script>
<template><PoliciesPage :sdk="sdk" /></template>
