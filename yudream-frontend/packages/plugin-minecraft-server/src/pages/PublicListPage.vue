<script setup lang="ts">
// 公开服务器列表（匿名）：读取本插件提供给公开站的 server-list 数据块，只含公开字段（名称、简介、地址、在线状态）。
// 插件停用时路由不注册；站点主题经宿主页面承载为它提供外壳。
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { useYdToast, YdButton, YdCard, YdEmpty, YdIcon, YdLoadingOverlay, YdPageHeader, YdResult, YdTag } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'

interface PublicServer {
  name: string
  icon?: string
  description?: string
  address?: string
  online?: boolean
  statusText?: string
  playersText?: string
  latencyText?: string
}

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const toast = useYdToast()

const servers = ref<PublicServer[]>([])
const loading = ref(true)
const failed = ref(false)
const onlineCount = computed(() => servers.value.filter(server => server.online).length)

onMounted(async () => {
  props.sdk.site.applySeo({ title: '服务器', description: '公开的 Minecraft 服务器列表与在线状态' })
  try {
    const context = await props.sdk.site.context({ blocks: ['server-list'], limit: 50 })
    const block = (context.blocks?.['server-list'] ?? {}) as { servers?: PublicServer[] }
    servers.value = Array.isArray(block.servers) ? block.servers : []
  }
  catch {
    failed.value = true
  }
  finally {
    loading.value = false
  }
})

async function copyAddress(server: PublicServer) {
  if (!server.address) {
    return
  }
  try {
    await navigator.clipboard.writeText(server.address)
    toast.success(`已复制 ${server.address}`)
  }
  catch {
    toast.error('复制失败，请手动选择地址')
  }
}
</script>

<template>
  <div class="mc-public-servers">
    <YdPageHeader title="服务器" :description="loading ? '正在读取服务器状态…' : `共 ${servers.length} 台服务器，${onlineCount} 台在线。复制地址后在游戏「多人游戏」中添加即可进入。`" class="mb-0" />
    <YdLoadingOverlay :loading="loading">
      <YdResult v-if="failed" status="error" title="服务器列表暂时无法读取" description="请稍后刷新重试。" />
      <YdEmpty v-else-if="!loading && !servers.length" icon="i-ri:server-line" description="暂时没有公开的服务器，敬请期待。" />
      <div v-else class="mc-public-servers__list">
        <YdCard v-for="server in servers" :key="server.address || server.name" class="mc-public-servers__card">
          <div class="mc-public-servers__row">
            <span class="mc-public-servers__icon" aria-hidden="true">
              <img v-if="server.icon" :src="sdk.site.assetUrl(server.icon)" alt="">
              <YdIcon v-else name="i-ri:server-line" />
            </span>
            <div class="mc-public-servers__main">
              <h2>{{ server.name }}</h2>
              <p v-if="server.description">
                {{ server.description }}
              </p>
              <div class="mc-public-servers__meta">
                <YdTag :variant="server.online ? 'default' : 'secondary'">
                  {{ server.statusText || (server.online ? '在线' : '离线') }}
                </YdTag>
                <span v-if="server.online && server.playersText">{{ server.playersText }} 人在线</span>
                <span v-if="server.latencyText">{{ server.latencyText }}</span>
                <code v-if="server.address">{{ server.address }}</code>
              </div>
            </div>
            <YdButton v-if="server.address" variant="outline" @click="copyAddress(server)">
              <YdIcon name="i-ri:file-copy-line" />
              复制地址
            </YdButton>
          </div>
        </YdCard>
      </div>
    </YdLoadingOverlay>
  </div>
</template>

<style scoped>
.mc-public-servers {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: min(1240px, 100%);
  margin: 0 auto;
}

.mc-public-servers__list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.mc-public-servers__row {
  display: flex;
  gap: 18px;
  align-items: center;
}

.mc-public-servers__icon {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  font-size: 24px;
}

.mc-public-servers__icon img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  image-rendering: pixelated;
}

.mc-public-servers__main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.mc-public-servers__main h2 {
  margin: 0;
  font-size: 18px;
}

.mc-public-servers__main p {
  margin: 0;
  color: var(--yb-site-muted, var(--color-text-3));
}

.mc-public-servers__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  font-size: 13px;
}

/* 窄屏：图标与名称同行，简介占满一行，复制按钮单独一行占满宽度 */
@media (width <= 640px) {
  .mc-public-servers__row {
    flex-wrap: wrap;
    gap: 12px;
  }

  .mc-public-servers__main {
    flex: 1 1 calc(100% - 64px);
  }

  .mc-public-servers__row > :last-child {
    width: 100%;
  }
}
</style>
