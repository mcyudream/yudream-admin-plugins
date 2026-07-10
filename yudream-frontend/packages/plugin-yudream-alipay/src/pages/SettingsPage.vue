<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AlipayConfig } from '../types'
import { onMounted, reactive, ref } from 'vue'
import { FaButton, FaIcon, FaInput, FaPageHeader, FaPageMain, FaSelect, FaSwitch, FaTextarea, useFaToast } from '@yudream/components'
import { createAlipayApi } from '../api/alipay-api'
import AlipayPanel from '../components/AlipayPanel.vue'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createAlipayApi(props.sdk)
const toast = useFaToast()
const saving = ref(false)
const DEFAULT_GATEWAY_URL = 'https://openapi.alipay.com/gateway.do'
const form = reactive<AlipayConfig>(defaults({}))
const signOptions = [{ label: 'RSA2', value: 'RSA2' }, { label: 'RSA', value: 'RSA' }]

function defaults(config: Partial<AlipayConfig>): AlipayConfig { return { appId: config.appId || '', privateKey: config.privateKey || '', alipayPublicKey: config.alipayPublicKey || '', gatewayUrl: config.gatewayUrl || DEFAULT_GATEWAY_URL, notifyUrl: config.notifyUrl || '', returnUrl: config.returnUrl || '', signType: config.signType || 'RSA2', charset: config.charset || 'UTF-8', enabled: config.enabled ?? false } }
async function load() { Object.assign(form, defaults(await api.adminConfig())) }
async function save() { saving.value = true; try { Object.assign(form, defaults(await api.saveAdminConfig(defaults({ ...form })))); toast.success('支付宝配置已保存') } finally { saving.value = false } }
onMounted(load)
</script>

<template>
  <FaPageHeader title="支付宝配置" class="mb-0" />
  <FaPageMain>
    <AlipayPanel title="接口配置" eyebrow="Config">
      <form class="alipay-form" @submit.prevent="save">
        <div class="alipay-form-grid">
          <label><span>启用状态</span><FaSwitch v-model="form.enabled" /></label>
          <label><span>AppId</span><FaInput v-model="form.appId" placeholder="支付宝开放平台应用 AppId" /></label>
          <label><span>签名类型</span><FaSelect v-model="form.signType" :options="signOptions" /></label>
          <label><span>字符集</span><FaInput v-model="form.charset" placeholder="UTF-8" /></label>
        </div>
        <label><span>网关地址</span><FaInput v-model="form.gatewayUrl" placeholder="https://openapi.alipay.com/gateway.do" /></label>
        <div class="alipay-form-grid">
          <label><span>异步通知地址</span><FaInput v-model="form.notifyUrl" placeholder="https://example.com/api/plugins/yudream-alipay/notify" /></label>
          <label><span>同步返回地址</span><FaInput v-model="form.returnUrl" placeholder="https://example.com/pay/result" /></label>
        </div>
        <label><span>应用私钥</span><FaTextarea v-model="form.privateKey" placeholder="保存后会脱敏显示" /></label>
        <label><span>支付宝公钥</span><FaTextarea v-model="form.alipayPublicKey" placeholder="保存后会脱敏显示" /></label>
        <div class="alipay-actions"><FaButton :loading="saving" type="submit"><FaIcon name="i-ri:save-3-line" />保存配置</FaButton></div>
      </form>
    </AlipayPanel>
  </FaPageMain>
</template>
