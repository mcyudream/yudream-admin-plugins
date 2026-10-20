<script setup lang="ts">
import type { ActionMenuItem, TableColumn, YdTablePageAction } from '@yudream/components'
import type { MinecraftServer } from '../types'
import type { MinecraftServerPluginModel } from '../composables/useMinecraftServerPlugin'
import { useRouter } from 'vue-router'
import { YdPagination, YdResponsiveEnhancedTable, YdTablePageLayout, useYdModal } from '@yudream/components'

const props = defineProps<{ model: MinecraftServerPluginModel }>()
const router = useRouter()
const modal = useYdModal()

const columns: TableColumn<MinecraftServer>[] = [
  { accessorKey: 'name', header: '服务器', width: 240 },
  { id: 'status', header: '状态', width: 110 },
  { accessorKey: 'sort', header: '排序', width: 80 },
]

function statusTone(server: MinecraftServer): string {
  return server.status?.status === 'ONLINE' ? 'success' : 'neutral'
}

function statusText(server: MinecraftServer): string {
  return props.model.statusText(server.status?.status)
}

/** 行操作统一收进操作菜单；结束/恢复运营保持确认流程 */
const rowActions: ActionMenuItem<MinecraftServer>[] = [
  { label: '编辑', icon: 'morph:pencil', onClick: row => go('Editor', row.id) },
  { label: '周目', icon: 'morph:history', onClick: row => go('Seasons', row.id) },
  { label: '操作记录', icon: 'morph:clipboard-list', onClick: row => go('Operations', row.id) },
  { label: '玩家统计', icon: 'morph:users', onClick: row => go('Players', row.id) },
  {
    label: row => (row.enabled ? '结束服务器' : '恢复运营'),
    icon: 'morph:power',
    danger: true,
    onClick: row => confirmToggleServer(row),
  },
]

const pageActions: YdTablePageAction[] = [
  { label: '新增服务器', icon: 'morph:plus', onClick: () => go('Editor') },
]

function go(component: 'Editor' | 'Seasons' | 'Operations' | 'Players', id?: string) {
  return router.push({ path: `/platform/plugins/minecraft-server/admin/${component.toLowerCase()}`, query: id ? { id } : {} })
}

function confirmToggleServer(server: MinecraftServer) {
  const ending = server.enabled
  modal.confirm({
    title: ending ? '结束服务器' : '恢复服务器',
    content: ending
      ? `确认结束“${server.name}”吗？结束后将从 QQ 服务器列表移除，并归档到网站的已关闭服务器列表。`
      : `确认恢复“${server.name}”运营吗？`,
    onConfirm: () => props.model.toggleServerEnabled(server),
  })
}
</script>

<template>
  <YdTablePageLayout title="服务器管理" :show-search="false" :actions="pageActions">
    <template #table>
      <YdResponsiveEnhancedTable
        row-key="id"
        table-root-class="rounded-lg overflow-hidden"
        table-class="min-w-[640px]"
        border
        stripe
        column-visibility
        :actions="rowActions"
        :columns="columns"
        :data="model.servers"
        empty-text="暂无服务器"
      >
        <template #cell-name="{ row }">
          <div class="grid gap-1">
            <strong>{{ row.original.name }}</strong>
            <span class="font-mono text-xs text-muted-foreground">ID: {{ row.original.id }}</span>
          </div>
        </template>
        <template #cell-status="{ row }">
          <span class="yd-status-tone" :data-tone="statusTone(row.original)">{{ statusText(row.original) }}</span>
        </template>
        <template #cell-enabled="{ row }">
          <span class="yd-status-tone" :data-tone="row.original.enabled ? 'info' : 'neutral'">{{ row.original.enabled ? '已发布' : '未发布' }}</span>
        </template>
      </YdResponsiveEnhancedTable>
    </template>

    <template #footer>
      <YdPagination
        v-model:page="model.serverPager.page"
        v-model:size="model.serverPager.size"
        :total="model.serverPager.total"
        @page-change="() => props.model.load(true)"
        @size-change="() => props.model.load(true)"
      />
    </template>
  </YdTablePageLayout>
</template>
