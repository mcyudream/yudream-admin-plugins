<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AlipayConfig } from '../types'
import { onMounted, reactive, ref } from 'vue'
import { YdButton, YdForm, YdFormItem, YdIcon, YdInput, YdLoadingOverlay, YdPageHeader, YdSelect, YdSettingsLayout, YdSettingsSection, YdSwitch, YdTextarea, useYdToast } from '@yudream/components'
import { createAlipayApi } from '../api/alipay-api'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createAlipayApi(props.sdk)
const toast = useYdToast()
const loading = ref(false)
const saving = ref(false)
const DEFAULT_GATEWAY_URL = 'https://openapi.alipay.com/gateway.do'
const form = reactive<AlipayConfig>(defaults({}))
const signOptions = [{ label: 'RSA2', value: 'RSA2' }, { label: 'RSA', value: 'RSA' }]
const charsetOptions = [{ label: 'UTF-8', value: 'UTF-8' }, { label: 'GBK', value: 'GBK' }]

/** 设置页分区：当前只有接口配置一组，保持与其他插件一致的骨架 */
const activeSection = ref('config')
const sectionList: { icon?: string, label: string, value: string }[] = [
  { label: '接口配置', value: 'config', icon: 'morph:settings' },
]

function defaults(config: Partial<AlipayConfig>): AlipayConfig {
  return {
    appId: config.appId || '',
    privateKey: config.privateKey || '',
    alipayPublicKey: config.alipayPublicKey || '',
    gatewayUrl: config.gatewayUrl || DEFAULT_GATEWAY_URL,
    notifyUrl: config.notifyUrl || '',
    returnUrl: config.returnUrl || '',
    signType: config.signType || 'RSA2',
    charset: config.charset || 'UTF-8',
    enabled: config.enabled ?? false,
  }
}

async function load() {
  loading.value = true
  try {
    Object.assign(form, defaults(await api.adminConfig()))
  }
  finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    Object.assign(form, defaults(await api.saveAdminConfig(defaults({ ...form }))))
    toast.success('支付宝配置已保存')
  }
  finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="settings-page-shell">
    <YdPageHeader title="支付宝配置" class="mb-0">
      <YdButton :loading="saving" @click="save">
        <YdIcon name="morph:save" />保存设置
      </YdButton>
    </YdPageHeader>

    <YdLoadingOverlay :loading="loading">
      <YdSettingsLayout
        v-model="activeSection"
        :list="sectionList"
        surface
        scroll
        width-class="max-w-[880px]"
      >
        <template #config>
          <YdSettingsSection
            title="接口配置"
            description="支付宝开放平台应用凭据与回调地址；私钥保存后脱敏显示。"
          >
            <YdForm :model="form" layout="vertical">
              <div class="flex flex-col gap-4 max-w-xl">
                <YdFormItem label="启用状态" extra="关闭后站点内不再发起新的支付宝充值。">
                  <YdSwitch v-model="form.enabled" />
                </YdFormItem>
                <YdFormItem label="AppId" required>
                  <YdInput v-model="form.appId" placeholder="支付宝开放平台应用 AppId" />
                </YdFormItem>
                <YdFormItem label="签名类型">
                  <YdSelect v-model="form.signType" :options="signOptions" />
                </YdFormItem>
                <YdFormItem label="字符集">
                  <YdSelect v-model="form.charset" :options="charsetOptions" allow-create />
                </YdFormItem>
                <YdFormItem label="网关地址">
                  <YdInput v-model="form.gatewayUrl" placeholder="https://openapi.alipay.com/gateway.do" />
                </YdFormItem>
                <YdFormItem label="异步通知地址" extra="支付宝服务器 POST 通知的站点地址，需公网可达。">
                  <YdInput v-model="form.notifyUrl" placeholder="https://example.com/api/plugins/yudream-alipay/notify" />
                </YdFormItem>
                <YdFormItem label="同步返回地址" extra="支付完成后浏览器跳回的页面地址。">
                  <YdInput v-model="form.returnUrl" placeholder="https://example.com/pay/result" />
                </YdFormItem>
                <YdFormItem label="应用私钥" extra="保存后会脱敏显示。">
                  <YdTextarea v-model="form.privateKey" />
                </YdFormItem>
                <YdFormItem label="支付宝公钥" extra="保存后会脱敏显示。">
                  <YdTextarea v-model="form.alipayPublicKey" />
                </YdFormItem>
              </div>
            </YdForm>
          </YdSettingsSection>
        </template>
      </YdSettingsLayout>
    </YdLoadingOverlay>
  </div>
</template>
