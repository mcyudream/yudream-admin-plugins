<script setup lang="ts">
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed, watch } from 'vue'
import { useWalletPlugin } from './composables/useWalletPlugin'
import BalancesPage from './pages/BalancesPage.vue'
import HomePage from './pages/HomePage.vue'
import RechargePage from './pages/RechargePage.vue'
import RechargeSettingsPage from './pages/RechargeSettingsPage.vue'
import SettingsPage from './pages/SettingsPage.vue'
import TransactionsPage from './pages/TransactionsPage.vue'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const model = useWalletPlugin(props.sdk)
const surface = computed(() => {
  const component = (props.route?.meta?.plugin as { component?: string } | undefined)?.component
  if (component === 'yudream-wallet/Balances') return 'balances'
  if (component === 'yudream-wallet/Transactions') return 'transactions'
  if (component === 'yudream-wallet/Settings' || component === 'yudream-wallet/System') return 'settings'
  if (component === 'yudream-wallet/RechargeSettings') return 'recharge-settings'
  if (component === 'yudream-wallet/Recharge') return 'recharge'
  return 'user'
})
const page = computed(() => {
  const plugin = props.route?.meta?.plugin as { component?: string } | undefined
  if (plugin?.component === 'yudream-wallet/Settings' || plugin?.component === 'yudream-wallet/System') {
    return SettingsPage
  }
  if (plugin?.component === 'yudream-wallet/RechargeSettings') {
    return RechargeSettingsPage
  }
  if (plugin?.component === 'yudream-wallet/Balances') {
    return BalancesPage
  }
  if (plugin?.component === 'yudream-wallet/Recharge') {
    return RechargePage
  }
  if (plugin?.component === 'yudream-wallet/Transactions') {
    return TransactionsPage
  }
  return HomePage
})

watch(surface, value => model.load(value), { immediate: true })
</script>

<template>
  <div class="wallet-plugin">
    <component :is="page" :model="model" />
  </div>
</template>
