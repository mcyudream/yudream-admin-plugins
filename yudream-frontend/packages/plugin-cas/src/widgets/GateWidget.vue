<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { GateConfig } from '../types'
import { YdButton, YdIcon } from '@yudream/components'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { createCasApi } from '../api/cas-api'

/** 宿主 /api/user/me/external-accounts 返回的绑定记录（只用到判断是否绑定所需的字段）。 */
interface HostExternalAccount {
  providerCode?: string
  platformType?: string
}

/**
 * 全站绑定门禁挂件（组件 key = cas/Gate，由 CasSsoPlugin.registerGlobalWidget 注册）。
 *
 * 行为约定：
 * - 未登录不渲染（登录页必须可用）；
 * - 持有 plugin:cas:manage 权限的管理员不渲染（防止把管理员锁死在设置页外）；
 * - 开关关闭或 CAS 登录入口未就绪时不渲染；
 * - 绑定状态以宿主 /api/user/me/external-accounts 为唯一权威来源（原生 fetch + localStorage token）；
 * - 任何查询异常一律放行（fail-open），门禁是 UI 层强制，不阻断直接调用 API 的客户端。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk, widget?: unknown }>()
const api = createCasApi(props.sdk)

const CHECK_INTERVAL_MS = 10_000
const visible = ref(false)
const displayName = ref('统一身份认证')
const binding = ref(false)
const bindingFailed = ref(false)
let gateCache: GateConfig | null = null
let timer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  check()
  // 挂件常驻：轮询覆盖「登录后才需要检查」「绑定完成后自动解除」两类时序
  timer = setInterval(check, CHECK_INTERVAL_MS)
})

onBeforeUnmount(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})

async function check() {
  if (binding.value) {
    return
  }
  try {
    const account = props.sdk.account
    if (!account || !account.userId) {
      return
    }
    if (account.permissions && account.permissions.includes('plugin:cas:manage')) {
      return
    }
    const gate = await api.gate()
    if (!gate || !gate.requireBinding) {
      return
    }
    gateCache = gate
    const accounts = await fetchExternalAccounts()
    if (accounts === null) {
      return
    }
    const bound = accounts.some(item =>
      item.providerCode === gate.providerCode && item.platformType === gate.type,
    )
    if (bound) {
      visible.value = false
      return
    }
    displayName.value = gate.displayName || '统一身份认证'
    visible.value = true
  }
  catch {
    // fail-open：查询失败不拦截
  }
}

async function fetchExternalAccounts(): Promise<HostExternalAccount[] | null> {
  const token = localStorage.getItem('token')
  if (!token) {
    return null
  }
  const response = await fetch('/api/user/me/external-accounts', {
    headers: { Authorization: token, 'Accept-Language': 'zh-CN' },
  })
  if (!response.ok) {
    return null
  }
  const result = await response.json() as { code?: number, data?: HostExternalAccount[] }
  if (result.code !== 200 || !Array.isArray(result.data)) {
    return null
  }
  return result.data
}

async function bind() {
  binding.value = true
  bindingFailed.value = false
  try {
    const gate = gateCache ?? await api.gate()
    const token = localStorage.getItem('token')
    const response = await fetch(
      `/api/user/me/external-accounts/${gate.providerCode}/${gate.type}/authorize`,
      { headers: { Authorization: token || '', 'Accept-Language': 'zh-CN' } },
    )
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    const result = await response.json() as { code?: number, data?: { authorizationUrl?: string } }
    if (result.code !== 200 || !result.data?.authorizationUrl) {
      throw new Error('未获取到授权地址')
    }
    window.location.assign(result.data.authorizationUrl)
  }
  catch {
    bindingFailed.value = true
    binding.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="cas-gate" role="dialog" aria-modal="true" aria-label="统一身份认证绑定">
      <div class="cas-gate__panel">
        <div class="cas-gate__icon">
          <YdIcon name="i-ri:graduation-cap-line" />
        </div>
        <h2 class="cas-gate__title">
          需要绑定{{ displayName }}
        </h2>
        <p class="cas-gate__desc">
          本站已开启统一身份认证：请先绑定学校账号，再使用其他功能。绑定仅需一次，之后可正常访问。
        </p>
        <div class="cas-gate__actions">
          <YdButton :loading="binding" @click="bind">
            <YdIcon name="i-ri:links-line" />
            立即绑定
          </YdButton>
        </div>
        <p v-if="bindingFailed" class="cas-gate__error">
          跳转认证失败，请刷新页面重试；若持续失败请联系站点管理员。
        </p>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.cas-gate {
  position: fixed;
  inset: 0;
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: color-mix(in srgb, var(--color-bg, #fff) 82%, transparent);
  backdrop-filter: blur(6px);
}

.cas-gate__panel {
  display: grid;
  gap: 12px;
  justify-items: center;
  max-width: 420px;
  padding: 32px 28px;
  border-radius: 14px;
  background: var(--color-bg, #fff);
  border: 1px solid var(--color-border, #e5e6eb);
  box-shadow: 0 12px 40px rgb(0 0 0 / 12%);
  text-align: center;
}

.cas-gate__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  font-size: 26px;
  border-radius: 50%;
  color: var(--color-primary, #165dff);
  background: color-mix(in srgb, var(--color-primary, #165dff) 10%, transparent);
}

.cas-gate__title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
}

.cas-gate__desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--color-text-3, #86909c);
}

.cas-gate__actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}

.cas-gate__error {
  margin: 0;
  font-size: 12px;
  color: var(--color-danger, #f53f3f);
}
</style>
