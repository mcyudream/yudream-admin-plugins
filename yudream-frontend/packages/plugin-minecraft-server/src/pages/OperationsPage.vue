<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { SeasonOperation } from '../types'
import type { MinecraftServerPluginModel } from '../composables/useMinecraftServerPlugin'
import { FaButton, FaPageHeader, FaPageMain, FaPagination, FaTable } from '@yudream/components'
const props = defineProps<{ model: MinecraftServerPluginModel }>()
const columns: TableColumn<SeasonOperation>[] = [{ id: 'createdAt', header: '时间', width: 180 }, { accessorKey: 'toSeasonName', header: '周目', width: 160 }, { id: 'status', header: '状态', width: 100 }, { id: 'count', header: '处理条数', width: 100 }, { accessorKey: 'remark', header: '备注', minWidth: 200 }, { id: 'operation', header: '操作', width: 100, fixed: 'right' }]
async function reload() { await props.model.loadOperations() }
</script>
<template><FaPageHeader title="周目操作记录" class="mb-0" /><FaPageMain><FaTable row-key="id" table-root-class="max-w-full overflow-x-auto rounded-lg" table-class="min-w-[900px]" border stripe :columns="columns" :data="model.operations"><template #cell-createdAt="{ row }">{{ model.formatTime(row.original.createdAt) }}</template><template #cell-status="{ row }">{{ model.statusText(row.original.status) }}</template><template #cell-count="{ row }">{{ row.original.adjustments.length }}</template><template #cell-operation="{ row }"><FaButton v-if="row.original.status === 'APPLIED' && model.latestOperation?.id === row.original.id" size="sm" variant="destructive" @click="model.rollbackOperation(row.original)">撤回</FaButton><span v-else>-</span></template></FaTable><FaPagination v-model:page="model.operationsPager.page" v-model:size="model.operationsPager.size" :total="model.operationsPager.total" class="mt-3" @page-change="reload" @size-change="reload" /></FaPageMain></template>
