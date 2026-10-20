<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { WalletPluginModel } from '../composables/useWalletPlugin'
import type { RechargeRuleForm } from '../types'
import { ref } from 'vue'
import { YdButton, YdIcon, YdInput, YdPageHeader, YdSwitch, YdResponsiveTable, YdSettingsLayout, YdSettingsSection } from '@yudream/components'

defineProps<{ model: WalletPluginModel }>()

const columns: TableColumn<RechargeRuleForm>[] = [
  { accessorKey: 'assetCode', header: '币种', minWidth: 160 },
  { id: 'enabled', header: '启用', width: 90 },
  { id: 'ratio', header: '支付 1 元到账', width: 160 },
  { id: 'minPayAmount', header: '最低支付', width: 150 },
  { id: 'maxPayAmount', header: '最高支付', width: 150 },
]
/** 设置页分区：单分区页保持统一骨架 */
const activeSection = ref('main')
const sectionList: { icon?: string, label: string, value: string }[] = [
  { label: "充值规则", value: 'main' },
]
</script>

<template>
  <div class="settings-page-shell">
  <YdPageHeader title="充值配置" class="mb-0">
    <YdButton :loading="model.saving" @click="model.saveRechargeSettings">
      <YdIcon name="morph:save" />保存充值配置
    </YdButton>
  </YdPageHeader>

  <YdSettingsLayout
    v-model="activeSection"
    :list="sectionList"
    surface
    scroll
    width-class="max-w-[880px]"
  >
    <template #main>
      <YdSettingsSection
        title="充值规则"
        description="充值开关与各币种汇率规则。"
      >

    <div class="flex flex-col gap-4">
      <div class="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-4">
        <div class="grid gap-1">
          <strong>充值总开关</strong>
          <span class="text-sm text-muted-foreground">关闭后用户端不再提供充值入口。</span>
        </div>
        <YdSwitch v-model="model.rechargeSettingsForm.enabled" />
      </div>
      <YdResponsiveTable
        row-key="assetCode"
        table-root-class="rounded-lg overflow-hidden"
        table-class="min-w-[720px]"
        border
        stripe
        :columns="columns"
        :data="model.rechargeSettingsForm.rules"
        empty-text="暂无可配置的充值币种"
      >
        <template #cell-assetCode="{ row }">{{ model.assetName(row.original.assetCode) }} ({{ row.original.assetCode }})</template>
        <template #cell-enabled="{ row }"><YdSwitch v-model="row.original.enabled" /></template>
        <template #cell-ratio="{ row }"><YdInput v-model="row.original.ratio" inputmode="decimal" /></template>
        <template #cell-minPayAmount="{ row }"><YdInput v-model="row.original.minPayAmount" inputmode="decimal" /></template>
        <template #cell-maxPayAmount="{ row }"><YdInput v-model="row.original.maxPayAmount" inputmode="decimal" placeholder="不限制" /></template>
        <template #card="{ row }">
          
            <div class="flex flex-col gap-3">
              <div class="flex items-center justify-between gap-2">
                <span class="min-w-0 break-words text-base font-semibold">{{ model.assetName(row.assetCode) }} ({{ row.assetCode }})</span>
                <YdSwitch v-model="row.enabled" />
              </div>
              <div class="flex flex-col gap-2 text-sm">
                <div class="flex items-center gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">支付 1 元到账</span>
                  <YdInput v-model="row.ratio" inputmode="decimal" />
                </div>
                <div class="flex items-center gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">最低支付</span>
                  <YdInput v-model="row.minPayAmount" inputmode="decimal" />
                </div>
                <div class="flex items-center gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">最高支付</span>
                  <YdInput v-model="row.maxPayAmount" inputmode="decimal" placeholder="不限制" />
                </div>
              </div>
            </div>
          
        </template>
      </YdResponsiveTable>
    </div>
      </YdSettingsSection>
    </template>
  </YdSettingsLayout>
</div>

</template>
