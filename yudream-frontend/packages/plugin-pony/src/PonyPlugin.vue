<script setup lang="ts">
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed, onMounted, watch } from 'vue'
import { usePonyPlugin } from './composables/usePonyPlugin'
import GamesPage from './pages/GamesPage.vue'
import MyStatsPage from './pages/MyStatsPage.vue'
import OverviewPage from './pages/OverviewPage.vue'
import PlayersPage from './pages/PlayersPage.vue'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const model = usePonyPlugin(props.sdk)

const pageName = computed(() => {
  const component = (props.route?.meta?.plugin as { component?: string } | undefined)?.component || ''
  if (component.endsWith('/Games')) {
    return 'games'
  }
  if (component.endsWith('/Players')) {
    return 'players'
  }
  if (component.endsWith('/MyStats')) {
    return 'my-stats'
  }
  return 'overview'
})

const page = computed(() => {
  if (pageName.value === 'games') {
    return GamesPage
  }
  if (pageName.value === 'players') {
    return PlayersPage
  }
  if (pageName.value === 'my-stats') {
    return MyStatsPage
  }
  return OverviewPage
})

function loadPage(name: string) {
  if (name === 'games') {
    return model.loadGames()
  }
  if (name === 'players') {
    return model.loadPlayers()
  }
  if (name === 'my-stats') {
    return model.loadMyStats()
  }
  return model.loadOverview()
}

onMounted(() => loadPage(pageName.value))
watch(pageName, value => loadPage(value))
</script>

<template>
  <div class="pony-plugin">
    <component :is="page" :model="model" />
  </div>
</template>
