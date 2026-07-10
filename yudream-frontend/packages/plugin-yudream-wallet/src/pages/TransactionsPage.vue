<script setup lang="ts">
import type { WalletPluginModel } from '../composables/useWalletPlugin'
import { computed } from 'vue'
import { FaButton, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaSelect } from '@yudream/components'
import TransactionList from '../components/TransactionList.vue'

const props = defineProps<{ model: WalletPluginModel }>()
const assetOptions = computed(() => [{ label: '全部币种', value: '' }, ...props.model.assets.map(asset => ({ label: `${asset.name} (${asset.code})`, value: asset.code }))])
const sourceOptions = [
  { label: '全部来源', value: '' }, { label: '管理员', value: 'ADMIN' }, { label: '用户转账', value: 'TRANSFER' },
  { label: '支付宝', value: 'ALIPAY' }, { label: '周目继承', value: 'MINECRAFT_SEASON' },
]
const typeOptions = [{ label: '全部类型', value: '' }, { label: '入账', value: 'CREDIT' }, { label: '扣账', value: 'DEBIT' }, { label: '转账', value: 'TRANSFER' }]
function onPageChange() { props.model.loadTransactions() }
function onSizeChange() { props.model.transactionPager.page = 1; props.model.loadTransactions() }
</script>

<template>
  <FaPageHeader title="钱包交易记录" class="mb-0" />
  <FaPageMain>
    <FaSearchBar class="w-full mb-3">
      <div class="wallet-filter-bar">
        <FaSelect v-model="model.transactionFilters.assetCode" :options="assetOptions" />
        <FaSelect v-model="model.transactionFilters.source" :options="sourceOptions" />
        <FaSelect v-model="model.transactionFilters.type" :options="typeOptions" />
        <FaInput v-model="model.transactionFilters.user" clearable placeholder="ID / 用户名 / 邮箱" @keydown.enter="model.applyTransactionFilters" />
        <div class="wallet-actions wallet-filter-actions">
          <FaButton variant="outline" type="button" @click="model.resetTransactionFilters">重置</FaButton>
          <FaButton type="button" @click="model.applyTransactionFilters"><FaIcon name="i-ri:search-line" />查询</FaButton>
        </div>
      </div>
    </FaSearchBar>
    <TransactionList :model="model" :items="model.transactions" />
    <FaPagination v-model:page="model.transactionPager.page" v-model:size="model.transactionPager.size" :total="model.transactionPager.total" class="mt-3" @page-change="onPageChange" @size-change="onSizeChange" />
  </FaPageMain>
</template>
