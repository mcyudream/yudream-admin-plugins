<script setup lang="ts">
import type { Component } from 'vue'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdAlert, YdButton, YdLoading, YdPageMain } from '@yudream/components'
import { computed, onMounted, ref, watch } from 'vue'
import { useSkinPlugin } from './composables/useSkinPlugin'
import AdminPlayersPage from './pages/AdminPlayersPage.vue'
import AdminTexturesPage from './pages/AdminTexturesPage.vue'
import AdminClosetPage from './pages/AdminClosetPage.vue'
import ClosetPage from './pages/ClosetPage.vue'
import DashboardPage from './pages/DashboardPage.vue'
import PlayersPage from './pages/PlayersPage.vue'
import SystemPage from './pages/SystemPage.vue'
import TexturesPage from './pages/TexturesPage.vue'
import type { SkinPage } from './types'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  page: SkinPage
  scope: 'user' | 'admin'
}>()

const model = useSkinPlugin(props.sdk)
const loadError = ref('')
const initialLoad = ref(true)
const loadedPage = ref('')
let loadVersion = 0

const pageComponents: Record<SkinPage, Component> = {
  dashboard: DashboardPage,
  settings: SystemPage,
  players: PlayersPage,
  textures: TexturesPage,
  closet: ClosetPage,
  playerManagement: AdminPlayersPage,
  textureManagement: AdminTexturesPage,
  closetManagement: AdminClosetPage,
}

const pageComponent = computed(() => pageComponents[props.page])
const currentPageKey = computed(() => `${props.scope}:${props.page}`)
const hasLoadedCurrentPage = computed(() => loadedPage.value === currentPageKey.value)
const personalPermissionMissing = computed(() => {
  return props.scope === 'user'
    && props.page !== 'textures'
    && !model.canUse
})

async function loadCurrentPage() {
  const currentLoadVersion = ++loadVersion
  if (personalPermissionMissing.value) {
    initialLoad.value = false
    return
  }
  const pageKey = currentPageKey.value
  const scope = props.scope
  const page = props.page
  initialLoad.value = !hasLoadedCurrentPage.value
  loadError.value = ''
  const includeCollections = page !== 'settings'
  try {
    await model.load({
      includeCloset: includeCollections && page !== 'playerManagement' && page !== 'textureManagement',
      includeMigration: page === 'settings',
      includePlayers: includeCollections && page !== 'textureManagement' && page !== 'closetManagement',
      includeTextures: includeCollections,
      scope,
    })
    if (currentLoadVersion === loadVersion) {
      loadedPage.value = pageKey
    }
  }
  catch (error) {
    if (currentLoadVersion === loadVersion) {
      loadError.value = error instanceof Error ? error.message : '皮肤站数据加载失败，请重试。'
    }
  }
  finally {
    if (currentLoadVersion === loadVersion) {
      initialLoad.value = false
    }
  }
}

onMounted(loadCurrentPage)
watch(() => [props.page, props.scope, model.canUse], loadCurrentPage)
</script>

<template>
  <div class="skin-plugin">
    <YdPageMain v-if="personalPermissionMissing">
      <YdAlert
        icon="i-ri:lock-line"
        title="个人皮肤功能需要使用权限"
        description="当前账号可以查看皮肤库。使用仪表盘中的个人角色和衣柜功能，请联系管理员开通「使用皮肤站」权限。"
      />
      <a class="skin-secondary-link mt-4" href="/platform/plugins/yudream-skin/textures">查看皮肤库</a>
    </YdPageMain>
    <YdPageMain v-else-if="!hasLoadedCurrentPage && (initialLoad || model.loading)">
      <YdLoading text="正在加载皮肤与衣柜…" class="py-6" />
    </YdPageMain>
    <YdPageMain v-else-if="!hasLoadedCurrentPage && loadError">
      <YdAlert variant="destructive" title="皮肤站数据加载失败" :description="loadError" />
      <YdButton class="mt-4" :loading="model.loading" @click="loadCurrentPage">重新加载</YdButton>
    </YdPageMain>
    <template v-else>
      <YdPageMain v-if="loadError">
        <YdAlert variant="destructive" title="刷新失败，当前内容已保留">
          <template #description>
            <p>{{ loadError }}</p>
            <YdButton class="mt-3" variant="outline" :loading="model.loading" @click="loadCurrentPage">重试刷新</YdButton>
          </template>
        </YdAlert>
      </YdPageMain>
      <YdLoading v-if="model.loading" text="正在刷新皮肤站数据…" type="flow" :size="24" class="py-3" />
      <component :is="pageComponent" :model="model" />
    </template>
  </div>
</template>
