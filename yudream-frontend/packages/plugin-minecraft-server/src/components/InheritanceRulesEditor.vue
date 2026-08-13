<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { InheritanceRule } from '../types'
import { FaButton, FaCard, FaIcon, FaInput, FaResponsiveTable } from '@yudream/components'
import type { MinecraftServerPluginModel } from '../composables/useMinecraftServerPlugin'

defineProps<{ model: MinecraftServerPluginModel }>()
const columns: TableColumn<InheritanceRule>[] = [
  { id: 'assetPattern', header: '币种匹配', width: 180 }, { id: 'minAmount', header: '最低金额', width: 140 },
  { id: 'maxAmount', header: '最高金额', width: 140 }, { id: 'inheritRate', header: '继承比例', width: 140 },
  { id: 'operation', header: '操作', width: 90, align: 'center', fixed: 'right' },
]
</script>

<template>
  <section>
    <div class="mc-section-title">
      <strong>继承规则</strong>
      <div class="mc-actions"><FaButton size="sm" variant="outline" type="button" @click="model.resetRules">恢复默认</FaButton><FaButton size="sm" type="button" @click="model.addRule"><FaIcon name="i-ri:add-line" />新增规则</FaButton></div>
    </div>
    <FaResponsiveTable :row-key="(_row, index) => String(index)" table-root-class="max-w-full overflow-x-auto rounded-lg" border stripe :columns="columns" :data="model.seasonForm.rules">
      <template #cell-assetPattern="{ row }"><FaInput v-model="row.original.assetPattern" /></template>
      <template #cell-minAmount="{ row }"><FaInput v-model="row.original.minAmount" /></template>
      <template #cell-maxAmount="{ row }"><FaInput v-model="row.original.maxAmount" /></template>
      <template #cell-inheritRate="{ row }"><FaInput v-model="row.original.inheritRate" /></template>
      <template #cell-operation="{ index }"><FaButton size="sm" variant="destructive" type="button" @click="model.removeRule(index)"><FaIcon name="i-ri:delete-bin-line" /></FaButton></template>
      <template #card="{ row, index }">
        <FaCard class="w-full">
          <div class="flex flex-col gap-3">
            <div class="flex items-center justify-between gap-2">
              <span class="text-base font-semibold">币种匹配</span>
              <FaInput v-model="row.assetPattern" class="w-36 shrink-0" />
            </div>
            <div class="flex flex-col gap-2 text-sm">
              <div class="flex items-center justify-between gap-2">
                <span class="shrink-0 text-secondary-foreground/60">最低金额</span>
                <FaInput v-model="row.minAmount" class="w-28 shrink-0" />
              </div>
              <div class="flex items-center justify-between gap-2">
                <span class="shrink-0 text-secondary-foreground/60">最高金额</span>
                <FaInput v-model="row.maxAmount" class="w-28 shrink-0" />
              </div>
              <div class="flex items-center justify-between gap-2">
                <span class="shrink-0 text-secondary-foreground/60">继承比例</span>
                <FaInput v-model="row.inheritRate" class="w-28 shrink-0" />
              </div>
            </div>
            <div class="flex flex-wrap gap-2 border-t pt-3">
              <FaButton size="sm" variant="destructive" type="button" @click="model.removeRule(index)"><FaIcon name="i-ri:delete-bin-line" /></FaButton>
            </div>
          </div>
        </FaCard>
      </template>
    </FaResponsiveTable>
  </section>
</template>
