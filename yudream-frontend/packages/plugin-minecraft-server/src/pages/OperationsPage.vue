<script setup lang="ts">
import type { ActionMenuItem, TableColumn } from '@yudream/components'
import type { SeasonOperation } from '../types'
import type { MinecraftServerPluginModel } from '../composables/useMinecraftServerPlugin'
import { YdButton, YdCard, YdPagination, YdResponsiveEnhancedTable, YdTablePageLayout, YdTag } from '@yudream/components'
const props = defineProps<{ model: MinecraftServerPluginModel }>()
const columns: TableColumn<SeasonOperation>[] = [{ id: 'createdAt', header: '时间', width: 180 }, { accessorKey: 'toSeasonName', header: '周目', width: 160 }, { id: 'status', header: '状态', width: 100 }, { id: 'count', header: '处理条数', width: 100 }, { accessorKey: 'remark', header: '备注', minWidth: 200 }]

/** 行操作统一收进操作菜单：只有最近一次「已应用」的操作能撤回，其余行不显示菜单项 */
const rowActions: ActionMenuItem<SeasonOperation>[] = [
  {
    label: '撤回',
    icon: 'morph:undo-2',
    danger: true,
    visible: row => row.status === 'APPLIED' && props.model.latestOperation?.id === row.id,
    onClick: row => props.model.rollbackOperation(row),
  },
]

async function reload() { await props.model.loadOperations() }
</script>
<template>
  <YdTablePageLayout title="周目操作记录" :show-search="false">
      <template #table>
        <YdResponsiveEnhancedTable
          row-key="id"
          table-root-class="max-w-full overflow-x-auto rounded-lg"
          table-class="min-w-[900px]"
          border
          stripe
          :actions="rowActions"
          :columns="columns"
          :data="model.operations"
        >
          <template #cell-createdAt="{ row }">{{ model.formatTime(row.original.createdAt) }}</template>
          <template #cell-status="{ row }">{{ model.statusText(row.original.status) }}</template>
          <template #cell-count="{ row }">{{ row.original.adjustments.length }}</template>
          <template #card="{ row }">
            <YdCard class="w-full">
              <div class="flex flex-col gap-3">
                <div class="flex items-center justify-between gap-2">
                  <span class="min-w-0 break-words text-base font-semibold">{{ row.toSeasonName }}</span>
                  <div class="flex gap-1">
                    <YdTag :variant="row.status === 'APPLIED' ? 'default' : 'secondary'">{{ model.statusText(row.status) }}</YdTag>
                  </div>
                </div>
                <div class="flex flex-col gap-1 text-sm">
                  <div class="flex gap-2">
                    <span class="shrink-0 text-secondary-foreground/60">处理条数</span>
                    <span class="break-all">{{ row.adjustments.length }}</span>
                  </div>
                  <div class="flex gap-2">
                    <span class="shrink-0 text-secondary-foreground/60">时间</span>
                    <span>{{ model.formatTime(row.createdAt) }}</span>
                  </div>
                  <div v-if="row.remark" class="flex gap-2">
                    <span class="shrink-0 text-secondary-foreground/60">备注</span>
                    <span class="break-all">{{ row.remark }}</span>
                  </div>
                </div>
                <div class="flex flex-wrap gap-2 border-t pt-3">
                  <YdButton v-if="row.status === 'APPLIED' && model.latestOperation?.id === row.id" size="sm" variant="destructive" @click="model.rollbackOperation(row)">撤回</YdButton>
                </div>
              </div>
            </YdCard>
          </template>
        </YdResponsiveEnhancedTable>
      </template>
      <template #footer>
        <YdPagination
          v-model:page="model.operationsPager.page"
          v-model:size="model.operationsPager.size"
          :total="model.operationsPager.total"
          @page-change="reload"
          @size-change="reload"
        />
      </template>
  </YdTablePageLayout>
</template>
