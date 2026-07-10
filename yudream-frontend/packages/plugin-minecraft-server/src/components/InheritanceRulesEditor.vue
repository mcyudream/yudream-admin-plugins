<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { InheritanceRule } from '../types'
import { FaButton, FaIcon, FaInput, FaTable } from '@yudream/components'
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
    <FaTable :row-key="(_row, index) => String(index)" table-root-class="rounded-lg overflow-hidden" border stripe :columns="columns" :data="model.seasonForm.rules">
      <template #cell-assetPattern="{ row }"><FaInput v-model="row.original.assetPattern" /></template>
      <template #cell-minAmount="{ row }"><FaInput v-model="row.original.minAmount" /></template>
      <template #cell-maxAmount="{ row }"><FaInput v-model="row.original.maxAmount" /></template>
      <template #cell-inheritRate="{ row }"><FaInput v-model="row.original.inheritRate" /></template>
      <template #cell-operation="{ index }"><FaButton size="sm" variant="destructive" type="button" @click="model.removeRule(index)"><FaIcon name="i-ri:delete-bin-line" /></FaButton></template>
    </FaTable>
  </section>
</template>
