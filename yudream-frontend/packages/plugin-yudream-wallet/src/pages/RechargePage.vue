<script setup lang="ts">
import type { WalletPluginModel } from '../composables/useWalletPlugin'
import { computed } from 'vue'
import { YdButton, YdForm, YdFormItem, YdIcon, YdInput, YdPageHeader, YdPageMain, YdSelect, YdTextarea } from '@yudream/components'

const props = defineProps<{ model: WalletPluginModel }>()
const channelOptions = computed(() => props.model.paymentChannels.map(item => ({ label: item.name, value: item.code })))
const assetOptions = computed(() => props.model.rechargeableAssets.map(item => ({ label: `${item.name} (${item.code})`, value: item.code })))
</script>

<template>
  <YdPageHeader title="钱包充值" description="选择支付渠道与金额，创建订单后按渠道指引完成支付" class="mb-0" />
  <YdPageMain>
    <YdForm :model="model.rechargeForm" layout="vertical" class="max-w-xl" @submit="model.submitRecharge">
      <div class="flex flex-col gap-4">
        <YdFormItem label="支付渠道" required>
          <YdSelect v-model="model.rechargeForm.channelCode" :options="channelOptions" placeholder="请选择支付渠道" />
        </YdFormItem>
        <YdFormItem label="到账资产" required>
          <YdSelect v-model="model.rechargeForm.assetCode" :options="assetOptions" placeholder="请选择到账资产" />
        </YdFormItem>
        <YdFormItem label="支付金额" required>
          <YdInput v-model="model.rechargeForm.payAmount" inputmode="decimal" placeholder="0.00" />
        </YdFormItem>
        <YdFormItem label="预计到账">
          <YdInput :model-value="model.estimatedWalletAmount" disabled />
        </YdFormItem>
        <YdFormItem label="备注">
          <YdTextarea v-model="model.rechargeForm.remark" placeholder="选填，仅自己可见" />
        </YdFormItem>
        <YdFormItem>
          <YdButton :loading="model.saving" html-type="submit">
            <YdIcon name="morph:credit-card" />创建订单
          </YdButton>
        </YdFormItem>
      </div>
    </YdForm>
  </YdPageMain>
</template>
