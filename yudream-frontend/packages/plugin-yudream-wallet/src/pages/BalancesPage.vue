<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { WalletPluginModel } from '../composables/useWalletPlugin'
import type { WalletBalance } from '../types'
import { computed, ref } from 'vue'
import { FaButton, FaIcon, FaInput, FaModal, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaSelect, FaTable, FaTextarea } from '@yudream/components'

const props = defineProps<{ model: WalletPluginModel }>()
const changeVisible = ref(false)
const assetOptions = computed(() => props.model.assets.map(asset => ({ label: `${asset.name} (${asset.code})`, value: asset.code })))
const columns: TableColumn<WalletBalance>[] = [
  { id: 'user', header: '用户', minWidth: 220, fixed: 'left' },
  { id: 'email', header: '邮箱', minWidth: 200 },
  { id: 'asset', header: '币种', width: 110 },
  { id: 'balance', header: '余额', width: 150 },
  { id: 'historical', header: '历史总额', width: 150 },
  { id: 'updatedAt', header: '更新时间', width: 180 },
]

function openChange() { props.model.changeForm.amount = ''; props.model.changeForm.remark = ''; changeVisible.value = true }
async function changeBalance(kind: 'credit' | 'debit') { await props.model.changeBalance(kind); changeVisible.value = false }
function onPageChange() { props.model.loadAdminBalances() }
function onSizeChange() { props.model.balancePager.page = 1; props.model.loadAdminBalances() }
</script>

<template>
  <FaPageHeader title="余额管理" class="mb-0">
    <FaButton @click="openChange"><FaIcon name="i-ri:exchange-funds-line" />余额处理</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaTable
      v-loading="model.loading"
      :columns="columns"
      :data="model.adminBalances"
      :row-key="row => `${row.userId}:${row.assetCode}`"
      table-root-class="rounded-lg overflow-hidden"
      table-class="min-w-[1050px]"
      border stripe column-visibility
      empty-text="暂无余额记录"
    >
      <template #toolbar>
        <FaSearchBar class="w-full">
          <div class="wallet-filter-bar">
            <FaSelect v-model="model.selectedBalanceAssetCode" :options="assetOptions" @change="model.applyBalanceAsset(model.selectedBalanceAssetCode)" />
          </div>
        </FaSearchBar>
      </template>
      <template #cell-user="{ row }">{{ model.userLabel(row.original.user, row.original.userId) }}</template>
      <template #cell-email="{ row }">{{ row.original.user?.email || '-' }}</template>
      <template #cell-asset="{ row }">{{ model.assetName(row.original.assetCode) }}</template>
      <template #cell-balance="{ row }">{{ model.assetSymbol(row.original.assetCode) }}{{ model.formatAmount(row.original.balance, row.original.assetCode) }}</template>
      <template #cell-historical="{ row }">{{ model.assetSymbol(row.original.assetCode) }}{{ model.formatAmount(row.original.historicalTotalAmount, row.original.assetCode) }}</template>
      <template #cell-updatedAt="{ row }">{{ model.formatTime(row.original.updatedAt) }}</template>
    </FaTable>
    <FaPagination v-model:page="model.balancePager.page" v-model:size="model.balancePager.size" :total="model.balancePager.total" class="mt-3" @page-change="onPageChange" @size-change="onSizeChange" />

    <FaModal v-model="changeVisible" title="余额处理" description="为指定用户执行入账或扣账操作。" show-cancel-button :show-confirm-button="false" class="sm:max-w-2xl">
      <form class="wallet-form gap-3" @submit.prevent>
        <label><span>用户 ID</span><FaInput v-model="model.changeForm.userId" placeholder="系统用户 ID" /></label>
        <label><span>币种</span><FaSelect v-model="model.changeForm.assetCode" :options="assetOptions" /></label>
        <label><span>金额</span><FaInput v-model="model.changeForm.amount" inputmode="decimal" placeholder="0" /></label>
        <label><span>备注</span><FaTextarea v-model="model.changeForm.remark" placeholder="可选" /></label>
        <div class="wallet-actions">
          <FaButton :loading="model.saving" type="button" @click="changeBalance('credit')"><FaIcon name="i-ri:add-circle-line" />入账</FaButton>
          <FaButton :loading="model.saving" variant="destructive" type="button" @click="changeBalance('debit')"><FaIcon name="i-ri:subtract-line" />扣账</FaButton>
        </div>
      </form>
    </FaModal>
  </FaPageMain>
</template>
