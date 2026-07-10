<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { MinecraftServer } from '../types'
import type { MinecraftServerPluginModel } from '../composables/useMinecraftServerPlugin'
import { useRouter } from 'vue-router'
import { FaButton, FaIcon, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaTable } from '@yudream/components'
import StatusPill from '../components/StatusPill.vue'

const props = defineProps<{ model: MinecraftServerPluginModel }>()
const router = useRouter()
const columns: TableColumn<MinecraftServer>[] = [
  { accessorKey: 'name', header: '服务器', width: 240, fixed: 'left' },
  { id: 'status', header: '状态', width: 100, align: 'center' },
  { id: 'enabled', header: '发布', width: 100, align: 'center' },
  { accessorKey: 'sort', header: '排序', width: 80, align: 'center' },
  { id: 'operation', header: '操作', width: 320, align: 'center', fixed: 'right' },
]
function go(component: 'Editor' | 'Seasons' | 'Operations' | 'Players', id?: string) {
  return router.push({ path: `/platform/plugins/minecraft-server/admin/${component.toLowerCase()}`, query: id ? { id } : {} })
}
async function reload() { await props.model.load(true) }
</script>

<template>
  <FaPageHeader title="服务器管理" class="mb-0">
    <FaButton @click="go('Editor')"><FaIcon name="i-ri:add-line" />新增服务器</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaTable row-key="id" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[980px]" border stripe column-visibility :columns="columns" :data="model.servers">
      <template #toolbar><FaSearchBar class="w-full"><div class="flex justify-end gap-2"><FaButton variant="outline" :loading="model.loading" @click="reload"><FaIcon name="i-ri:refresh-line" />刷新</FaButton></div></FaSearchBar></template>
      <template #cell-status="{ row }"><StatusPill :status="row.original.status?.status" /></template>
      <template #cell-enabled="{ row }">{{ row.original.enabled ? '启用' : '停用' }}</template>
      <template #cell-operation="{ row }"><div class="flex flex-wrap justify-center gap-2"><FaButton size="sm" variant="outline" @click="go('Editor', row.original.id)">编辑</FaButton><FaButton size="sm" variant="outline" @click="go('Seasons', row.original.id)">周目</FaButton><FaButton size="sm" variant="outline" @click="go('Operations', row.original.id)">操作记录</FaButton><FaButton size="sm" variant="outline" @click="go('Players', row.original.id)">玩家统计</FaButton></div></template>
    </FaTable>
    <FaPagination v-model:page="model.serverPager.page" v-model:size="model.serverPager.size" :total="model.serverPager.total" class="mt-3" @page-change="reload" @size-change="reload" />
  </FaPageMain>
</template>
