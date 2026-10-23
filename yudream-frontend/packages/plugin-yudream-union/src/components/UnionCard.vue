<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed, onMounted, ref } from 'vue'
import { createUnionApi } from '../api/union-api'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const settings = ref<{ siteCode?: string, siteName?: string, origin?: string }>({})
const peerTotal = ref(0)

const authlibApiRoot = computed(() =>
  settings.value.origin ? `${settings.value.origin}/api/plugins/authlib-injector` : '')

onMounted(async () => {
  try {
    settings.value = await createUnionApi(props.sdk).settings()
    peerTotal.value = (await createUnionApi(props.sdk).pagePeers(1, 1)).total ?? 0
  }
  catch (ignored) {
    // 工作台卡片静默降级
  }
})
</script>

<template>
  <div class="flex h-full flex-col gap-3 overflow-hidden p-4">
    <div class="grid gap-1">
      <strong>联合认证</strong>
      <span class="text-sm text-muted-foreground">
        {{ settings.siteCode ? `站点 ${settings.siteCode} · 受信站 ${peerTotal} 个` : '尚未配置站点身份' }}
      </span>
    </div>
    <code v-if="authlibApiRoot" class="block min-w-0 truncate rounded-md border p-2 text-xs" :title="authlibApiRoot">
      {{ authlibApiRoot }}
    </code>
    <div class="mt-auto border-t pt-2">
      <a class="text-sm text-primary hover:underline" href="/platform/plugins/yudream-union/admin/peers">管理受信站点 →</a>
    </div>
  </div>
</template>
