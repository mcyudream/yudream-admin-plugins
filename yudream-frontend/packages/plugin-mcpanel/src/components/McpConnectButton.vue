<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdAlert, YdButton, YdCode, YdCopyButton, YdForm, YdFormItem, YdInput, YdModal, useYdToast } from '@yudream/components'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { accountHasPermission, MCPANEL_PERMISSION } from '../composables/permissions'
import { mcpClientConfig, vscodeMcpInstallUri } from '../utils/mcpConnection'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const router = useRouter()
const route = useRoute()
const toast = useYdToast()
const open = ref(false)
const apiKey = ref('')
// The host registers this menu route only after capability and access filtering.
// No host-private capability client or session credential is used here.
const available = computed(() => {
  void route.fullPath
  return accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.view)
    && router.getRoutes().some(item => item.path.replace(/\/$/, '') === '/platform/mcpserver')
})
const endpoint = new URL('/api/mcp/mcpanel', window.location.origin).href
const validKey = computed(() => apiKey.value.trim().length > 0 && !/[\r\n]/.test(apiKey.value))
const config = computed(() => mcpClientConfig(endpoint, validKey.value ? apiKey.value.trim() : 'yda_<你的 API Key>'))
watch(open, (value) => { if (!value) apiKey.value = '' })
watch(available, (value) => { if (!value) open.value = false })
function install() {
  if (!validKey.value) return
  window.location.href = vscodeMcpInstallUri(endpoint, apiKey.value.trim())
}
</script>
<template>
  <template v-if="available">
    <YdButton variant="outline" @click="open = true">一键连接 MCP</YdButton>
    <YdModal v-model="open" title="连接 MC 面板 MCP" :footer="false">
      <div class="space-y-4">
        <p>让支持 Streamable HTTP 的 MCP 客户端连接当前面板，管理权限由 API Key 决定。</p>
        <div class="flex flex-wrap items-center gap-2"><span class="min-w-0 break-all">{{ endpoint }}</span><YdCopyButton :text="endpoint" label="复制端点" @error="toast.error('复制失败，请手动选择端点')" /></div>
        <YdForm :model="{ apiKey }">
          <YdFormItem field="apiKey" label="用于 MCP 的 API Key" extra="粘贴已授权 MC 面板权限的 API Key。">
            <YdInput v-model="apiKey" type="password" autocomplete="off" placeholder="yda_…" />
          </YdFormItem>
        </YdForm>
        <YdAlert title="连接凭据" description="Key 仅在本次弹窗中使用，关闭后清除。一键安装会把 Key 交给 VS Code 保存；复制的配置也包含 Key，请妥善保管。" />
        <div class="flex flex-wrap gap-2">
          <YdButton :disabled="!validKey" @click="install">一键添加到 VS Code</YdButton>
          <YdButton variant="outline" @click="open = false; router.push('/platform/mcpserver')">平台 MCP 设置</YdButton>
        </div>
        <YdCode :code="config" language="json" title="通用 MCP 客户端配置" />
      </div>
    </YdModal>
  </template>
</template>
