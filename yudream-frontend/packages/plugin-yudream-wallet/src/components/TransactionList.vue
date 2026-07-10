<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { WalletPluginModel } from '../composables/useWalletPlugin'
import type { WalletTransaction } from '../types'
import { FaTable, FaTag } from '@yudream/components'

defineProps<{ model: WalletPluginModel, items: WalletTransaction[] }>()

const columns: TableColumn<WalletTransaction>[] = [
  { accessorKey: 'type', header: '类型', width: 100 },
  { accessorKey: 'source', header: '来源', width: 110 },
  { accessorKey: 'assetCode', header: '资产', width: 100 },
  { id: 'amount', header: '金额', width: 140 },
  { id: 'user', header: '用户', minWidth: 240 },
  { accessorKey: 'businessNo', header: '业务单号', minWidth: 220 },
  { id: 'createdAt', header: '时间', width: 180 },
]
</script>

<template>
  <FaTable
    row-key="id"
    table-root-class="rounded-lg overflow-hidden"
    table-class="min-w-[1050px]"
    border
    stripe
    column-visibility
    :columns="columns"
    :data="items"
    empty-text="暂无流水"
  >
    <template #cell-type="{ row }">
      <FaTag>{{ model.transactionLabel(row.original.type) }}</FaTag>
    </template>
    <template #cell-source="{ row }">{{ model.sourceLabel(row.original.source) }}</template>
    <template #cell-assetCode="{ row }">{{ model.assetName(row.original.assetCode) }}</template>
    <template #cell-amount="{ row }">
      {{ model.assetSymbol(row.original.assetCode) }}{{ model.formatAmount(row.original.amount, row.original.assetCode) }}
    </template>
    <template #cell-user="{ row }">
      <template v-if="row.original.type === 'TRANSFER'">
        {{ model.userLabel(row.original.fromUser, row.original.fromUserId) }} → {{ model.userLabel(row.original.toUser, row.original.toUserId) }}
      </template>
      <template v-else>
        {{ model.userLabel(row.original.toUser || row.original.fromUser, row.original.toUserId || row.original.fromUserId) }}
      </template>
    </template>
    <template #cell-businessNo="{ row }">{{ row.original.businessNo || '-' }}</template>
    <template #cell-createdAt="{ row }">{{ model.formatTime(row.original.createdAt) }}</template>
  </FaTable>
</template>
