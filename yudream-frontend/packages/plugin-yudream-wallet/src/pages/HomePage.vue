<script setup lang="ts">
import type { WalletPluginModel } from '../composables/useWalletPlugin'
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { YdButton, YdForm, YdFormItem, YdIcon, YdInput, YdPageHeader, YdPageMain, YdPagination, YdSelect, YdTextarea } from '@yudream/components'
import BalanceGrid from '../components/BalanceGrid.vue'
import TransactionList from '../components/TransactionList.vue'
import WalletPanel from '../components/WalletPanel.vue'

const props = defineProps<{ model: WalletPluginModel }>()
const assetOptions = computed(() => props.model.transferableAssets.map(asset => ({ label: `${asset.name} (${asset.code})`, value: asset.code })))
const router = useRouter()

function goRecharge() {
  return router.push({ path: '/platform/plugins/yudream-wallet/recharge' })
}
</script>

<template>
  <YdPageHeader title="我的钱包" class="mb-0">
    <YdButton v-if="model.hasRecharge" @click="goRecharge">
      <YdIcon name="morph:credit-card" />充值
    </YdButton>
  </YdPageHeader>
  <YdPageMain>
    <BalanceGrid :model="model" />
    <div class="wallet-layout mt-4">
      <WalletPanel v-if="model.transferableAssets.length" title="余额转账" eyebrow="Transfer">
        <YdForm :model="model.transferForm" layout="vertical" @submit="model.submitTransfer">
          <div class="flex flex-col gap-4">
            <YdFormItem label="转入用户" required extra="对方用户 ID / 用户名 / 邮箱。">
              <YdInput v-model="model.transferForm.toAccount" placeholder="用户 ID / 用户名 / 邮箱" />
            </YdFormItem>
            <YdFormItem label="资产" required>
              <YdSelect v-model="model.transferForm.assetCode" :options="assetOptions" />
            </YdFormItem>
            <YdFormItem label="金额" required>
              <YdInput v-model="model.transferForm.amount" inputmode="decimal" placeholder="0" />
            </YdFormItem>
            <YdFormItem label="备注">
              <YdTextarea v-model="model.transferForm.remark" placeholder="可选" />
            </YdFormItem>
            <YdFormItem>
              <YdButton :loading="model.saving" html-type="submit">
                <YdIcon name="morph:arrow-left-right" />提交转账
              </YdButton>
            </YdFormItem>
          </div>
        </YdForm>
      </WalletPanel>
      <WalletPanel title="资产说明" eyebrow="Assets">
        <div class="wallet-asset-list">
          <div v-for="asset in model.assets" :key="asset.code" class="wallet-asset-row">
            <div>
              <strong>{{ asset.name }}</strong>
              <span>{{ asset.code }} · 精度 {{ asset.scale }} · {{ asset.transferEnabled ? '允许转账' : '不可转账' }}</span>
            </div>
            <strong class="wallet-asset-balance">{{ model.assetSymbol(asset.code) }}{{ model.formatAmount(model.balanceOf(asset.code), asset.code) }}</strong>
          </div>
        </div>
      </WalletPanel>
    </div>
    <WalletPanel title="钱包流水" eyebrow="Transactions" class="mt-4">
      <TransactionList :model="model" :items="model.transactions" />
      <YdPagination
        v-model:page="model.transactionPager.page"
        v-model:size="model.transactionPager.size"
        :total="model.transactionPager.total"
        class="mt-3"
        @page-change="model.loadMyTransactions"
        @size-change="model.loadMyTransactions"
      />
    </WalletPanel>
  </YdPageMain>
</template>
