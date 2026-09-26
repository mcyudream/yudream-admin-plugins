<script setup lang="ts">
import type { McpEnrollCredential } from '../types'
import { computed, ref, watch } from 'vue'
import { FaAlert, FaButton, FaIcon, FaModal, useFaToast } from '@yudream/components'
import { formatDateTime, toEpochMs } from '../composables/utils'

/**
 * 一次性注册凭据展示：token 只在签发响应中出现一次，本组件仅当次展示、不写
 * 任何持久缓存。禁止渲染携带 token 的 shell 命令（防进入 shell history），
 * 只展示不含秘密的配置步骤说明。
 */
const props = defineProps<{
  credential: McpEnrollCredential | null
}>()

const open = defineModel<boolean>('open', { default: false })
const toast = useFaToast()

const copied = ref(false)

watch(open, (visible) => {
  if (visible) {
    copied.value = false
  }
})

const expiresText = computed(() => {
  const ms = toEpochMs(props.credential?.expiresAt)
  return ms === null ? '以服务端为准（默认 10 分钟）' : formatDateTime(ms)
})

async function copyToken() {
  const token = props.credential?.token
  if (!token) {
    return
  }
  try {
    await navigator.clipboard.writeText(token)
    copied.value = true
    toast.success('已复制')
  }
  catch {
    toast.error('复制失败，请手动选择复制')
  }
}

/** 新标签页打开部署指南：本弹窗是一次性凭据的唯一展示窗口，不能因跳转丢失。 */
function openDeployGuide() {
  window.open('/platform/plugins/mcpanel/admin/node-deploy', '_blank', 'noopener')
}
</script>

<template>
  <FaModal
    v-model="open"
    title="节点注册凭据（仅本次显示）"
    :show-cancel-button="false"
    confirm-button-text="我已保存"
    class="max-w-[min(42rem,calc(100vw-2rem))]"
  >
    <div v-if="credential" class="mcp-form">
      <FaAlert variant="destructive" title="一次性凭据，关闭后无法再查看">
  <template #description>
        请立即复制并配置到节点；过期或注册完成后自动失效，遗失需重新签发。
  </template>
      </FaAlert>
      <div class="mcp-form-item">
        <div class="flex items-center justify-between gap-2">
          <span class="mcp-form-label">注册 token</span>
          <FaButton size="sm" variant="outline" @click="copyToken">
            {{ copied ? '已复制' : '复制' }}
          </FaButton>
        </div>
        <code class="mcp-token-block">{{ credential.token }}</code>
      </div>
      <div class="mcp-form-item">
        <span class="mcp-form-label">有效期至</span>
        <span>{{ expiresText }}</span>
      </div>
      <div class="mcp-form-item">
        <span class="mcp-form-label">节点侧注册（一条命令；token 只经 stdin/环境进入进程，不进命令行参数或 shell history）</span>
        <ol class="mcp-step-list">
          <li>
            裸机 / systemd：执行 <code>mcpanel-node join https://&lt;本面板站点地址&gt;</code>，按提示粘贴上方 token——注册成功后自动常驻，无需 config.yaml。
          </li>
          <li>
            Docker：运行节点镜像并注入 <code>MCNODE_PANEL_URL</code> 与 <code>MCNODE_ENROLL_TOKEN</code> 两个环境变量，serve 启动时自动完成注册（生产建议 <code>--env-file</code> 挂 0600 凭据文件）。
          </li>
        </ol>
        <span class="mcp-form-hint">token 是一次性凭据：注册成功或过期即失效。注册时节点自签证书指纹会自动登记为面板侧钉住依据（pinned 指纹留空时），无需在节点上手工取指纹。</span>
        <div class="mcp-row">
          <FaButton size="sm" variant="outline" @click="openDeployGuide">
            <FaIcon name="i-ri:book-open-line" />
            查看完整部署教程
          </FaButton>
          <span class="mcp-form-hint">二进制 / Docker / Compose 三种部署方式与常见问题（新标签页打开，不影响本弹窗）。</span>
        </div>
      </div>
    </div>
  </FaModal>
</template>
