<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { WalletPluginModel } from '../composables/useWalletPlugin'
import type { WalletTransaction } from '../types'
import { FaCard, FaResponsiveTable, FaTag } from '@yudream/components'

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
  <FaResponsiveTable
    row-key="id"
    table-root-class="max-w-full overflow-x-auto rounded-lg"
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
    <template #card="{ row }">
      <FaCard class="w-full">
        <div class="flex flex-col gap-3">
          <div class="flex items-center justify-between gap-2">
            <span class="min-w-0 break-words text-base font-semibold">{{ row.businessNo || '-' }}</span>
            <div class="flex gap-1">
              <FaTag>{{ model.transactionLabel(row.type) }}</FaTag>
            </div>
          </div>
          <div class="flex flex-col gap-1 text-sm">
            <div v-if="row.source" class="flex gap-2">
              <span class="shrink-0 text-secondary-foreground/60">来源</span>
              <span class="break-all">{{ model.sourceLabel(row.source) }}</span>
            </div>
            <div class="flex gap-2">
              <span class="shrink-0 text-secondary-foreground/60">金额</span>
              <span class="break-all">{{ model.assetSymbol(row.assetCode) }}{{ model.formatAmount(row.amount, row.assetCode) }}</span>
            </div>
            <div class="flex gap-2">
              <span class="shrink-0 text-secondary-foreground/60">用户</span>
              <span class="break-all">
                <template v-if="row.type === 'TRANSFER'">{{ model.userLabel(row.fromUser, row.fromUserId) }} → {{ model.userLabel(row.toUser, row.toUserId) }}</template>
                <template v-else>{{ model.userLabel(row.toUser || row.fromUser, row.toUserId || row.fromUserId) }}</template>
              </span>
            </div>
            <div class="flex gap-2">
              <span class="shrink-0 text-secondary-foreground/60">时间</span>
              <span>{{ model.formatTime(row.createdAt) }}</span>
            </div>
          </div>
        </div>
      </FaCard>
    </template>
  </FaResponsiveTable>
</template>
