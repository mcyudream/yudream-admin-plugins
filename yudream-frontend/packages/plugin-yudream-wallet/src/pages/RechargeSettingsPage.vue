<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { WalletPluginModel } from '../composables/useWalletPlugin'
import type { RechargeRuleForm } from '../types'
import { FaButton, FaIcon, FaInput, FaPageHeader, FaPageMain, FaSwitch, FaTable } from '@yudream/components'

defineProps<{ model: WalletPluginModel }>()

const columns: TableColumn<RechargeRuleForm>[] = [
  { accessorKey: 'assetCode', header: '币种', minWidth: 160, fixed: 'left' },
  { id: 'enabled', header: '启用', width: 90 },
  { id: 'ratio', header: '支付 1 元到账', width: 160 },
  { id: 'minPayAmount', header: '最低支付', width: 150 },
  { id: 'maxPayAmount', header: '最高支付', width: 150 },
]
</script>

<template>
  <FaPageHeader title="充值配置" class="mb-0" />
  <FaPageMain>
    <form class="grid gap-4" @submit.prevent="model.saveRechargeSettings">
      <div class="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-4">
        <div class="grid gap-1">
          <strong>充值总开关</strong>
          <span class="text-sm text-muted-foreground">关闭后用户端不再提供充值入口。</span>
        </div>
        <FaSwitch v-model="model.rechargeSettingsForm.enabled" />
      </div>
      <FaTable row-key="assetCode" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[720px]" border stripe :columns="columns" :data="model.rechargeSettingsForm.rules">
        <template #cell-assetCode="{ row }">{{ model.assetName(row.original.assetCode) }} ({{ row.original.assetCode }})</template>
        <template #cell-enabled="{ row }"><FaSwitch v-model="row.original.enabled" /></template>
        <template #cell-ratio="{ row }"><FaInput v-model="row.original.ratio" inputmode="decimal" /></template>
        <template #cell-minPayAmount="{ row }"><FaInput v-model="row.original.minPayAmount" inputmode="decimal" /></template>
        <template #cell-maxPayAmount="{ row }"><FaInput v-model="row.original.maxPayAmount" inputmode="decimal" placeholder="不限制" /></template>
      </FaTable>
      <div class="flex flex-wrap justify-end gap-2">
        <FaButton :loading="model.saving" type="submit"><FaIcon name="i-ri:save-3-line" />保存充值配置</FaButton>
      </div>
    </form>
  </FaPageMain>
</template>
