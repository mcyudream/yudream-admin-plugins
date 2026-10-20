<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn, ActionMenuItem } from '@yudream/components'
import type { ActivityProofMapping } from '../types'
import { YdButton, YdIcon, YdInput, YdPageMain, YdPagination, YdSelect, YdResponsiveEnhancedTable } from '@yudream/components'
import { computed, onMounted } from 'vue'
import { useMappings } from '../composables/useMappings'
import { formatTime } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
}>()

const model = useMappings(props.sdk)
const { loading, saving, mappings, servers, minecraftReady, pager, selectedServerId, createForm } = model

const serverOptions = computed(() => servers.value.map(server => ({ label: server.name, value: server.id })))

const mappingColumns: TableColumn<ActivityProofMapping>[] = [
  { id: 'player', header: '玩家', width: 220, fixed: 'left' },
  { accessorKey: 'studentNo', header: '学号', width: 160 },
  { id: 'createdAt', header: '创建时间', width: 180 },
  { id: 'operation', header: '操作', width: 120, align: 'center', fixed: 'right' },
]

onMounted(model.load)

/** 行操作：删除学号映射 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '删除', icon: 'morph:trash-2', danger: true, disabled: () => saving.value, onClick: row => model.remove(row as never) },
]

</script>

<template>
  <YdPageMain>
    <div v-if="!loading && !minecraftReady" class="grid gap-2 rounded-lg border border-dashed p-8 text-center text-muted-foreground">
      <YdIcon name="i-ri:server-line" class="mx-auto text-3xl" />
      <p>需启用 Minecraft 服务器插件后才能维护玩家与学号的映射。</p>
    </div>
    <YdLoadingOverlay :loading="loading" v-else><div   class="grid gap-4">
      <div class="grid max-w-md gap-2">
        <span>服务器</span>
        <YdSelect v-model="selectedServerId" :options="serverOptions" placeholder="选择服务器" @update:model-value="model.changeServer" />
      </div>
      <form class="grid gap-3 rounded-lg border p-4 md:grid-cols-[1fr_1fr_1fr_auto] md:items-end" @submit.prevent="model.create">
        <label class="grid gap-2">
          <span>玩家 ID <em class="required-mark">*</em></span>
          <YdInput v-model="createForm.playerId" placeholder="玩家 UUID" />
        </label>
        <label class="grid gap-2">
          <span>玩家名称</span>
          <YdInput v-model="createForm.playerName" placeholder="游戏内昵称" />
        </label>
        <label class="grid gap-2">
          <span>学号 <em class="required-mark">*</em></span>
          <YdInput v-model="createForm.studentNo" placeholder="对应学生学号" />
        </label>
        <YdButton type="submit" :loading="saving">
          <YdIcon name="i-ri:add-line" />保存映射
        </YdButton>
      </form>
      <div class="proof-min-w-0">
      <YdTablePageLayout title="玩家学号映射" :show-search="false">
    <template #table>
<YdLoadingOverlay :loading="loading"><YdResponsiveEnhancedTable
          :actions="rowActions"
        
        
        row-key="id"
        table-root-class="proof-table-scroll"
        table-class="proof-table-w860"
        border
        stripe
        column-visibility
        :columns="mappingColumns"
        :data="mappings"
      >
        <template #cell-player="{ row }">
          <strong>{{ row.original.playerName || '-' }}</strong>
          <div>{{ row.original.playerId }}</div>
        </template>
        <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
</YdResponsiveEnhancedTable></YdLoadingOverlay>
    </template>
    <template #footer>
      <YdPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        @page-change="model.loadMappings"
        @size-change="model.loadMappings"
      />
    </template>
  </YdTablePageLayout>

      </div>

    </div></YdLoadingOverlay>
  </YdPageMain>
</template>
