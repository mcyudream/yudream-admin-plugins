<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { PlayerActivity } from '../types'
import type { MinecraftServerPluginModel } from '../composables/useMinecraftServerPlugin'
import { FaPageHeader, FaPageMain, FaPagination, FaTable } from '@yudream/components'
import StatusPill from '../components/StatusPill.vue'
const props = defineProps<{ model: MinecraftServerPluginModel }>()
const columns: TableColumn<PlayerActivity>[] = [{ id: 'player', header: '玩家', width: 220, fixed: 'left' }, { id: 'status', header: '状态', width: 100 }, { id: 'online', header: '累计在线', width: 130 }, { id: 'afk', header: '累计挂机', width: 130 }, { id: 'updatedAt', header: '更新时间', width: 180 }]
async function reload() { await props.model.loadPlayerActivities() }
</script>
<template><FaPageHeader title="玩家时长统计" class="mb-0" /><FaPageMain><FaTable row-key="playerId" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[820px]" border stripe column-visibility :columns="columns" :data="model.playerActivities"><template #cell-player="{ row }"><div class="grid gap-1"><strong>{{ row.original.playerName || row.original.playerId }}</strong><span class="text-sm text-muted-foreground">{{ row.original.playerId }}</span></div></template><template #cell-status="{ row }"><StatusPill :status="row.original.online ? 'ONLINE' : 'OFFLINE'" /></template><template #cell-online="{ row }">{{ model.formatDuration(row.original.totalOnlineMillis) }}</template><template #cell-afk="{ row }">{{ model.formatDuration(row.original.totalAfkMillis) }}</template><template #cell-updatedAt="{ row }">{{ model.formatTime(row.original.updatedAt) }}</template></FaTable><FaPagination v-model:page="model.playerActivitiesPager.page" v-model:size="model.playerActivitiesPager.size" :total="model.playerActivitiesPager.total" class="mt-3" @page-change="reload" @size-change="reload" /></FaPageMain></template>
