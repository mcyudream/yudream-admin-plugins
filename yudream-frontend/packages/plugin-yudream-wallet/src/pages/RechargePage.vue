<script setup lang="ts">
import type { WalletPluginModel } from '../composables/useWalletPlugin'
import { computed } from 'vue'
import { FaButton, FaIcon, FaInput, FaPageHeader, FaPageMain, FaSelect, FaTextarea, useFaToast } from '@yudream/components'
import WalletPanel from '../components/WalletPanel.vue'
const props = defineProps<{ model: WalletPluginModel }>()
const toast = useFaToast()
const channelOptions = computed(() => props.model.paymentChannels.map(item => ({ label: item.name, value: item.code })))
const assetOptions = computed(() => props.model.rechargeableAssets.map(item => ({ label: `${item.name} (${item.code})`, value: item.code })))
async function submit() { const result = await props.model.submitRecharge(); if (result) toast.success('充值订单已创建') }
</script>
<template>
  <FaPageHeader title="钱包充值" class="mb-0" />
  <FaPageMain>
    <WalletPanel title="创建充值订单" eyebrow="Recharge">
      <form class="wallet-form" @submit.prevent="submit">
        <label><span>支付渠道</span><FaSelect v-model="model.rechargeForm.channelCode" :options="channelOptions" /></label>
        <label><span>到账资产</span><FaSelect v-model="model.rechargeForm.assetCode" :options="assetOptions" /></label>
        <label><span>支付金额</span><FaInput v-model="model.rechargeForm.payAmount" inputmode="decimal" placeholder="0.00" /></label>
        <label><span>预计到账</span><FaInput :model-value="model.estimatedWalletAmount" disabled /></label>
        <label><span>备注</span><FaTextarea v-model="model.rechargeForm.remark" placeholder="可选" /></label>
        <div class="wallet-actions"><FaButton :loading="model.saving" type="submit"><FaIcon name="i-ri:bank-card-line" />创建订单</FaButton></div>
      </form>
    </WalletPanel>
  </FaPageMain>
</template>
