<script setup lang="ts">
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed, watch } from 'vue'
import { useMinecraftServerPlugin } from './composables/useMinecraftServerPlugin'
import AdminPage from './pages/AdminPage.vue'
import DetailPage from './pages/DetailPage.vue'
import ListPage from './pages/ListPage.vue'
import OperationsPage from './pages/OperationsPage.vue'
import PlayersAdminPage from './pages/PlayersAdminPage.vue'
import SeasonsPage from './pages/SeasonsPage.vue'
import ServerEditorPage from './pages/ServerEditorPage.vue'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const model = useMinecraftServerPlugin(props.sdk)

const page = computed(() => {
  const plugin = props.route?.meta?.plugin as { component?: string } | undefined
  if (plugin?.component === 'minecraft-server/Admin') {
    return AdminPage
  }
  if (plugin?.component === 'minecraft-server/Editor') return ServerEditorPage
  if (plugin?.component === 'minecraft-server/Seasons') return SeasonsPage
  if (plugin?.component === 'minecraft-server/Operations') return OperationsPage
  if (plugin?.component === 'minecraft-server/Players') return PlayersAdminPage
  if (plugin?.component === 'minecraft-server/Detail') {
    return DetailPage
  }
  return ListPage
})
const isAdminPage = computed(() => [AdminPage, ServerEditorPage, SeasonsPage, OperationsPage, PlayersAdminPage].includes(page.value))

async function loadCurrentPage() {
  const id = String(props.route?.query?.id || '')
  if (id) {
    model.selectedId = id
  }
  await model.load(isAdminPage.value)
  if (page.value === ServerEditorPage) {
    if (id) {
      const selected = model.servers.find(item => item.id === id)
      if (selected) model.editServer(selected)
    }
    else model.newServer()
  }
}

watch([page, () => props.route?.query?.id], loadCurrentPage, { immediate: true })
</script>

<template>
  <div class="mc-plugin">
    <component :is="page" :model="model" />
  </div>
</template>
