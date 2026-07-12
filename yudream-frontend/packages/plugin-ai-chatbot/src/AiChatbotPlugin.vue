<script setup lang="ts">
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import SettingsPage from './pages/SettingsPage.vue'
defineProps<{ sdk: YuDreamPluginSdk; route?: RouteLocationNormalizedLoaded }>()
</script>
<template><SettingsPage :sdk="sdk" /></template>
