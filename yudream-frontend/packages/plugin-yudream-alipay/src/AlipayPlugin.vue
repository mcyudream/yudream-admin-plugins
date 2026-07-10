<script setup lang="ts">
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed } from 'vue'
import MyOrdersPage from './pages/MyOrdersPage.vue'
import OrdersPage from './pages/OrdersPage.vue'
import SettingsPage from './pages/SettingsPage.vue'
const props = defineProps<{ sdk: YuDreamPluginSdk, route?: RouteLocationNormalizedLoaded }>()
const page = computed(() => {
  const component = (props.route?.meta?.plugin as { component?: string } | undefined)?.component
  if (component === 'yudream-alipay/MyOrders') return MyOrdersPage
  if (component === 'yudream-alipay/Orders') return OrdersPage
  return SettingsPage
})
</script>
<template><div class="alipay-plugin"><component :is="page" :sdk="sdk" /></div></template>
