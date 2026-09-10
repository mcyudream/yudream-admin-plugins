<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { configList, configText, pluginAsset, themeAsset, useThemeContext, useThemeSeo } from './use-theme-context'

interface ServerPlayer {
  name?: string
  uuid?: string
}

const props = defineProps<{ sdk: YuDreamPluginSdk, route?: { meta?: { plugin?: { component?: string } } } }>()

const { context, loaded } = useThemeContext(props.sdk, { blocks: ['server-list'], limit: 24 })

useThemeSeo(props.sdk, {
  title: '服务器列表',
  description: 'Minecraft 服务器列表：实时在线状态、人数与延迟。',
  canonicalPath: '/servers',
})

const config = computed(() => context.value?.themeConfig ?? {})
const liveServers = computed(() => {
  const servers = context.value?.blocks?.['server-list']?.servers
  return Array.isArray(servers) && servers.length ? servers : null
})
const staticServers = computed(() => configList(config.value, 'staticServers'))
const servers = computed(() => liveServers.value || staticServers.value)
const friendLinks = computed(() => configList(config.value, 'friendLinks'))
const departments = computed(() => configList(config.value, 'departments'))
const listBackground = computed(() => pluginAsset(props.sdk, 'background/list-background.jpg'))
const copyHint = computed(() => configText(config.value, 'serversHint', '双击卡片复制地址，进游戏粘贴直接加入。'))
const unreachableSrc = computed(() => pluginAsset(props.sdk, 'ui/server/Server_Unreachable.png'))

const focusIndex = ref(-1)
const pingFrame = ref(1)
const pingDirection = ref(1)
const expandedPlayers = ref<Record<number, boolean>>({})
let pingTimer: number | undefined

const pingSrc = computed(() => {
  const frame = pingFrame.value
  return {
    pinging: pluginAsset(props.sdk, `ui/server/Server_Pinging_${frame}.png`),
    ping: (level: number) => pluginAsset(props.sdk, `ui/server/Server_Ping_${level}.png`),
    unreachable: unreachableSrc.value,
  }
})

function pingIcon(server: Record<string, any>) {
  if (!liveServers.value) {
    return pingSrc.value.unreachable
  }
  if (server.online === true) {
    const latency = Number(server.latency ?? server.ping ?? 0)
    if (latency <= 150) return pingSrc.value.ping(5)
    if (latency <= 300) return pingSrc.value.ping(4)
    if (latency <= 450) return pingSrc.value.ping(3)
    if (latency <= 600) return pingSrc.value.ping(2)
    return pingSrc.value.ping(1)
  }
  if (server.online === false) {
    return pingSrc.value.unreachable
  }
  return pingSrc.value.pinging
}

function playerCount(server: Record<string, any>) {
  const current = server.playersOnline ?? server.onlinePlayers ?? server.playerCount
  const max = server.playersMax ?? server.maxPlayers ?? server.capacity
  if (current == null && max == null) return ''
  return `${current ?? 0}/${max ?? 0}`
}

function motd(server: Record<string, any>) {
  return liveServers.value ? (server.motd || server.description || '') : (server.description || '')
}

function version(server: Record<string, any>) {
  return server.version || server.gameVersion || ''
}

function iconSrc(server: Record<string, any>) {
  return themeAsset(props.sdk, server.icon) || pluginAsset(props.sdk, 'ui/Player.png')
}

function players(server: Record<string, any>): ServerPlayer[] {
  return Array.isArray(server.players) ? server.players : []
}

function avatarSrc(player: ServerPlayer) {
  const uuid = (player.uuid || '').replaceAll('-', '')
  const name = (player.name || '').trim()
  if (uuid) return `https://mc-heads.net/avatar/${encodeURIComponent(uuid)}/32`
  if (name) return `https://mc-heads.net/avatar/${encodeURIComponent(name)}/32`
  return 'https://mc-heads.net/avatar/MHF_Steve/32'
}

async function copyAddress(address: string) {
  if (!address) return
  try {
    await navigator.clipboard.writeText(address)
  }
  catch {
    // 剪贴板不可用时静默忽略
  }
}

function onAvatarError(event: Event) {
  const img = event.target as HTMLImageElement
  img.src = 'https://mc-heads.net/avatar/MHF_Steve/32'
}

onMounted(() => {
  pingTimer = window.setInterval(() => {
    if (pingFrame.value > 4) pingDirection.value = -1
    else if (pingFrame.value <= 1) pingDirection.value = 1
    pingFrame.value += pingDirection.value
  }, 150)
})

onUnmounted(() => {
  if (pingTimer) window.clearInterval(pingTimer)
})

watch(servers, () => {
  expandedPlayers.value = {}
  focusIndex.value = -1
})
</script>

<template>
  <div class="list-area" :style="{ backgroundImage: `url('${listBackground}')` }">
    <section class="list-item-container">
      <h1 class="list-title mcfont">服务器列表</h1>
      <p class="list-hint">{{ copyHint }}</p>
      <article
        v-for="(server, index) in servers"
        :key="index"
        class="server-card"
        :class="{ 'is-focus': focusIndex === index }"
        :style="{ '--delay': `${index * 0.2}s` }"
        @click="focusIndex = index"
        @dblclick="copyAddress(server.address)"
      >
        <div class="item-border">
          <button type="button" class="server-icon-button" :aria-label="`复制 ${server.name} 的服务器地址`" @click.stop="copyAddress(server.address)">
            <img :src="iconSrc(server)" class="server-icon" alt="">
          </button>
          <div class="item-info mcfont">
            <span class="server-name">{{ server.name }}</span>
            <span class="server-motd">{{ motd(server) }}</span>
            <span class="server-version">{{ version(server) }}</span>
          </div>
          <div class="item-status">
            <span class="server-status">
              <span v-if="liveServers && server.online" class="status-text">{{ playerCount(server) }}</span>
              <img class="status-img" :src="pingIcon(server)" alt="">
            </span>
            <span class="server-actions">
              <a v-if="server.onlineMapUrl || server.mapUrl" :href="server.onlineMapUrl || server.mapUrl" target="_blank" rel="noopener noreferrer">网页地图</a>
              <button
                v-if="liveServers && server.online && players(server).length"
                type="button"
                class="player-toggle-button"
                @click.stop="expandedPlayers[index] = !expandedPlayers[index]"
              >
                {{ expandedPlayers[index] ? '收起玩家' : '查看玩家' }}
              </button>
            </span>
          </div>
        </div>
        <section v-if="expandedPlayers[index]" class="player-list-panel">
          <div class="player-list-header">
            <span>在线玩家</span>
            <span>{{ players(server).length }}</span>
          </div>
          <div class="player-list">
            <img
              v-for="player in players(server)"
              :key="player.uuid || player.name"
              class="player-avatar"
              :src="avatarSrc(player)"
              :alt="player.name"
              :title="player.name"
              @error="onAvatarError"
            >
          </div>
        </section>
      </article>
      <p v-if="loaded && !servers.length" class="department-status">服务器清单维护中，敬请期待。</p>
    </section>

    <section v-if="departments.length" class="list-item-container">
      <h1 class="list-title mcfont">部门与成员</h1>
      <article v-for="(dept, index) in departments" :key="index" class="dept-card">
        <h2>{{ dept.name }}</h2>
        <p>{{ dept.description }}</p>
      </article>
    </section>

    <section v-if="friendLinks.length" class="list-item-container">
      <h1 class="list-title mcfont">友情链接</h1>
      <a v-for="(link, index) in friendLinks" :key="index" class="link-card" :href="link.url" target="_blank" rel="noopener noreferrer">
        <div class="link-item-border">
          <img v-if="link.icon" class="link-icon" :src="themeAsset(props.sdk, link.icon)" :alt="link.name">
          <div class="link-item-info">
            <span class="link-item-name">{{ link.name }}</span>
            <span class="link-item-desc">{{ link.description }}</span>
          </div>
          <span class="link-item-arrow">▶</span>
        </div>
      </a>
    </section>
  </div>
</template>

<style scoped>
.list-area {
  min-height: 100vh;
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 5rem;
  box-sizing: border-box;
  background-position: center;
  background-size: cover;
  position: relative;
}

.list-item-container {
  width: 100%;
  border-top: 2px solid var(--neco-border-strong, #aaaaaa);
  border-bottom: 2px solid var(--neco-border-strong, #aaaaaa);
  padding: 20px 0;
  background-color: rgba(0, 0, 0, 0.75);
  margin-bottom: 24px;
}

.list-title {
  margin: 0;
  padding: 0 1rem 0.75rem;
  color: var(--neco-text, #fff);
  font-size: 1.25rem;
  user-select: none;
  text-align: center;
}

.list-hint {
  margin: 0 0 1rem;
  text-align: center;
  color: var(--neco-text-muted, rgba(255, 255, 255, 0.72));
}

.server-card {
  width: 100%;
  max-width: 768px;
  margin: 0 auto;
  opacity: 0;
  animation: fade-in-right 0.5s ease-in-out forwards;
  animation-delay: var(--delay);
}

.item-border {
  position: relative;
  width: 100%;
  padding: 2px 4px;
  display: flex;
  flex-direction: row;
  box-sizing: border-box;
  border: 2px solid transparent;
}

.server-card.is-focus > .item-border,
.item-border:hover {
  background-color: var(--neco-bg-sunken, #111111);
  border: 2px solid var(--neco-focus-ring, #fff);
}

.server-icon-button {
  width: 64px;
  height: 64px;
  border: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
}

.server-icon {
  width: 64px;
  height: 64px;
  border: 1px solid var(--neco-border-strong, #aaaaaa);
  image-rendering: pixelated;
}

.item-info {
  display: flex;
  flex-direction: column;
  margin-left: 1rem;
  min-width: 0;
}

.server-name {
  color: var(--neco-text, #fff);
  line-height: 1.1rem;
  font-size: 1.1rem;
  margin: 4px 0 5px;
}

.server-motd {
  line-height: 1rem;
  margin-bottom: 3px;
}

.server-version {
  line-height: 1rem;
  color: var(--neco-accent-light, #6cc349);
}

.item-status {
  display: flex;
  flex-direction: column;
  text-align: right;
  align-items: flex-end;
  margin: 0.5rem 0.5rem 0.5rem auto;
}

.server-status {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 0.5rem;
}

.status-text {
  color: var(--neco-text-gray, #aaaaaa);
}

.status-img {
  width: 20px;
  height: 14px;
  image-rendering: pixelated;
}

.server-actions {
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.player-toggle-button,
.server-actions a {
  color: var(--neco-accent-light, #6cc349);
  background: transparent;
  border: 0;
  padding: 0;
  font: inherit;
  cursor: pointer;
  text-decoration: underline;
}

.player-list-panel {
  width: calc(100% - 1rem);
  margin: 0 auto 0.35rem;
  padding: 0.5rem;
  color: var(--neco-text, #fff);
  background-color: color-mix(in srgb, var(--neco-bg-sunken, #111) 72%, transparent);
}

.player-list-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 0.5rem;
  color: var(--neco-accent-light, #6cc349);
}

.player-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.player-avatar {
  width: 32px;
  height: 32px;
  image-rendering: pixelated;
  border: 1px solid #777;
}

.dept-card,
.link-card {
  width: 100%;
  max-width: 768px;
  margin: 0 auto 0.5rem;
  color: inherit;
  text-decoration: none;
}

.dept-card {
  padding: 0.75rem 1rem;
}

.dept-card h2 {
  margin: 0 0 0.35rem;
}

.link-item-border {
  display: flex;
  align-items: center;
  padding: 2px 4px;
  border: 2px solid transparent;
}

.link-card:hover .link-item-border {
  background-color: var(--neco-bg-sunken, #111);
  border: 2px solid var(--neco-focus-ring, #fff);
}

.link-icon {
  width: 60px;
  height: 60px;
  border: 1px solid grey;
  image-rendering: pixelated;
}

.link-item-info {
  display: flex;
  flex-direction: column;
  margin-left: 1rem;
}

.link-item-name {
  color: var(--neco-text, #fff);
  font-size: 1.1rem;
}

.link-item-desc {
  color: var(--neco-text-muted, rgba(255, 255, 255, 0.72));
}

.link-item-arrow {
  margin-left: auto;
  padding-right: 0.5rem;
  color: #aaaaaa;
}

.department-status {
  text-align: center;
  color: var(--neco-text-muted, rgba(255, 255, 255, 0.72));
}

@keyframes fade-in-right {
  from { opacity: 0; transform: translateX(2rem); }
  to { opacity: 1; transform: translateX(0); }
}
</style>
