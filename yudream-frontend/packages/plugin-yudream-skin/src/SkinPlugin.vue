<script setup lang="ts">
import type { Component } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed, onMounted, watch } from 'vue'
import { useSkinPlugin } from './composables/useSkinPlugin'
import AdminPlayersPage from './pages/AdminPlayersPage.vue'
import AdminTexturesPage from './pages/AdminTexturesPage.vue'
import AdminClosetPage from './pages/AdminClosetPage.vue'
import ClosetPage from './pages/ClosetPage.vue'
import DashboardPage from './pages/DashboardPage.vue'
import PlayersPage from './pages/PlayersPage.vue'
import MigrationPage from './pages/MigrationPage.vue'
import SystemPage from './pages/SystemPage.vue'
import TexturesPage from './pages/TexturesPage.vue'
import type { SkinPage } from './types'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const model = useSkinPlugin(props.sdk)

const currentPage = computed<SkinPage>(() => {
  const path = props.route?.path || ''
  if (path.endsWith('/admin/players')) {
    return 'playerManagement'
  }
  if (path.endsWith('/admin/textures')) {
    return 'textureManagement'
  }
  if (path.endsWith('/admin/closet')) {
    return 'closetManagement'
  }
  if (path.endsWith('/admin/settings') || path.endsWith('/admin')) {
    return 'settings'
  }
  if (path.endsWith('/admin/migration')) {
    return 'migration'
  }
  if (path.endsWith('/players')) {
    return 'players'
  }
  if (path.endsWith('/textures')) {
    return 'textures'
  }
  if (path.endsWith('/closet')) {
    return 'closet'
  }
  if (path.endsWith('/dashboard')) {
    return 'dashboard'
  }
  const component = String((props.route?.meta?.plugin as any)?.component || '').toLowerCase()
  if (component.includes('playermanagement')) {
    return 'playerManagement'
  }
  if (component.includes('texturemanagement')) {
    return 'textureManagement'
  }
  if (component.includes('closetmanagement')) {
    return 'closetManagement'
  }
  if (component.includes('dashboard') || component.includes('home')) {
    return 'dashboard'
  }
  if (component.includes('players')) {
    return 'players'
  }
  if (component.includes('textures')) {
    return 'textures'
  }
  if (component.includes('closet')) {
    return 'closet'
  }
  return 'settings'
})

const pageComponents: Record<SkinPage, Component> = {
  dashboard: DashboardPage,
  settings: SystemPage,
  migration: MigrationPage,
  players: PlayersPage,
  textures: TexturesPage,
  closet: ClosetPage,
  playerManagement: AdminPlayersPage,
  textureManagement: AdminTexturesPage,
  closetManagement: AdminClosetPage,
}

const pageComponent = computed(() => pageComponents[currentPage.value])
const currentScope = computed(() => {
  return currentPage.value === 'settings' || currentPage.value === 'migration' || currentPage.value === 'playerManagement' || currentPage.value === 'textureManagement' || currentPage.value === 'closetManagement'
    ? 'admin'
    : 'user'
})

function loadCurrentPage() {
  const includeCollections = currentPage.value !== 'settings'
  return model.load({
    includeCloset: includeCollections && currentPage.value !== 'playerManagement' && currentPage.value !== 'textureManagement',
    includeMigration: currentPage.value === 'migration',
    includePlayers: includeCollections && currentPage.value !== 'textureManagement' && currentPage.value !== 'closetManagement',
    includeTextures: includeCollections,
    scope: currentScope.value,
  })
}

onMounted(loadCurrentPage)
watch(currentPage, loadCurrentPage)
</script>

<template>
  <div class="skin-plugin">
    <component :is="pageComponent" :model="model" />
  </div>
</template>
