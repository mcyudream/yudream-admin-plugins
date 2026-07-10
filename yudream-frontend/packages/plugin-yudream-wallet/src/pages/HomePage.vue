<script setup lang="ts">
import type { WalletPluginModel } from '../composables/useWalletPlugin'
import { computed } from 'vue'
import { FaButton, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaSelect, FaTextarea } from '@yudream/components'
import BalanceGrid from '../components/BalanceGrid.vue'
import TransactionList from '../components/TransactionList.vue'
import WalletPanel from '../components/WalletPanel.vue'

const props = defineProps<{ model: WalletPluginModel }>()
const assetOptions = computed(() => props.model.transferableAssets.map(asset => ({ label: `${asset.name} (${asset.code})`, value: asset.code })))
</script>

<template>
  <FaPageHeader title="我的钱包" class="mb-0">
    <a v-if="model.hasRecharge" class="wallet-nav-button" href="/platform/plugins/yudream-wallet/recharge"><FaIcon name="i-ri:bank-card-line" />充值</a>
  </FaPageHeader>
  <FaPageMain>
    <BalanceGrid :model="model" />
    <div class="wallet-layout mt-4">
      <WalletPanel v-if="model.transferableAssets.length" title="余额转账" eyebrow="Transfer">
        <form class="wallet-form" @submit.prevent="model.submitTransfer">
          <label><span>转入用户</span><FaInput v-model="model.transferForm.toAccount" placeholder="用户 ID / 用户名 / 邮箱" /></label>
          <label><span>资产</span><FaSelect v-model="model.transferForm.assetCode" :options="assetOptions" /></label>
          <label><span>金额</span><FaInput v-model="model.transferForm.amount" inputmode="decimal" placeholder="0" /></label>
          <label><span>备注</span><FaTextarea v-model="model.transferForm.remark" placeholder="可选" /></label>
          <div class="wallet-actions"><FaButton :loading="model.saving" type="submit"><FaIcon name="i-ri:exchange-dollar-line" />提交转账</FaButton></div>
        </form>
      </WalletPanel>
      <WalletPanel title="资产说明" eyebrow="Assets">
        <div class="wallet-asset-list">
          <div v-for="asset in model.assets" :key="asset.code" class="wallet-asset-row">
            <div><strong>{{ asset.name }}</strong><span>{{ asset.code }} · 精度 {{ asset.scale }} · {{ asset.transferEnabled ? '允许转账' : '不可转账' }}</span></div>
            <strong class="wallet-asset-balance">{{ model.assetSymbol(asset.code) }}{{ model.formatAmount(model.balanceOf(asset.code), asset.code) }}</strong>
          </div>
        </div>
      </WalletPanel>
    </div>
    <WalletPanel title="钱包流水" eyebrow="Transactions" class="mt-4">
      <TransactionList :model="model" :items="model.transactions" />
      <FaPagination v-model:page="model.transactionPager.page" v-model:size="model.transactionPager.size" :total="model.transactionPager.total" class="mt-3" @page-change="model.loadMyTransactions" @size-change="model.loadMyTransactions" />
    </WalletPanel>
  </FaPageMain>
</template>
