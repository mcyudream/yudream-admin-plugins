<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { configList, configNumber, configSwitch, configText, pluginAsset, themeAsset, useThemeContext } from './use-theme-context'

const props = defineProps<{ sdk: YuDreamPluginSdk, route?: { meta?: { plugin?: { component?: string } } } }>()

// 首页数据：服务器块（装了 minecraft-server 即实时状态）+ CMS 最新文章，配置始终随上下文返回
const { context } = useThemeContext(props.sdk, { blocks: ['server-list'], limit: 4, cmsLatest: 12 })

const config = computed(() => context.value?.themeConfig ?? {})
const heroBackground = computed(() => {
  if (typeof config.value.heroBackground === 'string') {
    return themeAsset(props.sdk, config.value.heroBackground)
  }
  return pluginAsset(props.sdk, 'background/beidalou.webp')
})
const heroLogo = computed(() => themeAsset(props.sdk, configText(config.value, 'heroLogo')))
const heroTitle = computed(() => configText(config.value, 'heroTitle', '方块世界，由此启程'))
const heroSubtitle = computed(() => configText(config.value, 'heroSubtitle'))
const aboutTitle = computed(() => configText(config.value, 'aboutTitle', '关于我们'))
const introItems = computed(() => configList(config.value, 'introItems'))
const showServers = computed(() => configSwitch(config.value, 'showServers'))
const liveServers = computed(() => {
  const servers = context.value?.blocks?.['server-list']?.servers
  return Array.isArray(servers) && servers.length ? servers : null
})
const staticServers = computed(() => configList(config.value, 'staticServers'))
const newsTitle = computed(() => configText(config.value, 'newsTitle', '最新动态'))
const newsLimit = computed(() => Math.min(configNumber(config.value, 'newsLimit', 4), 12))
const news = computed(() => (context.value?.cmsPagesLatest ?? []).slice(0, newsLimit.value))
</script>

<template>
  <div class="neco-home">
    <section class="neco-home__hero">
      <div class="neco-home__hero-bg" :style="{ backgroundImage: `url('${heroBackground}')` }" />
      <div class="neco-home__hero-veil" />
      <div class="neco-home__hero-inner">
        <img v-if="heroLogo" class="neco-home__logo" :src="heroLogo" :alt="heroTitle">
        <h1 class="neco-home__title">
          {{ heroTitle }}
        </h1>
        <p v-if="heroSubtitle" class="neco-home__tagline">
          {{ heroSubtitle }}
        </p>
        <div class="neco-home__actions">
          <RouterLink class="neco-home__cta neco-home__cta--primary" to="/servers">
            进入服务器
          </RouterLink>
          <a class="neco-home__cta neco-home__cta--ghost" href="#about">
            关于我们
          </a>
        </div>
      </div>
    </section>

    <section v-if="introItems.length" id="about" class="neco-home__about">
      <h2 class="neco-home__section-title neco-anim-down">
        {{ aboutTitle }}
      </h2>
      <div class="neco-home__intro-list">
        <div v-for="(item, index) in introItems" :key="index" class="neco-home__intro-card" :class="{ 'neco-home__intro-card--plain': !item.image }">
          <div v-if="item.image" class="neco-home__intro-media" :style="{ backgroundImage: `url('${themeAsset(props.sdk, item.image)}')` }" />
          <div class="neco-home__intro-body">
            <h3>{{ item.title }}</h3>
            <p>{{ item.description }}</p>
          </div>
        </div>
      </div>
    </section>

    <section v-if="showServers" class="neco-home__servers">
      <div class="neco-home__servers-inner">
        <h2 class="neco-home__section-title neco-anim">
          服务器
        </h2>
        <div class="neco-home__server-grid">
          <article v-for="(server, index) in (liveServers || staticServers)" :key="index" class="neco-panel neco-home__server">
            <div class="neco-home__server-head">
              <span v-if="server.icon" class="neco-home__server-icon" :style="{ backgroundImage: `url('${themeAsset(props.sdk, server.icon)}')` }" />
              <h3>{{ server.name }}</h3>
              <span v-if="liveServers" class="neco-home__server-badge" :class="server.online ? 'is-online' : 'is-offline'">
                {{ server.statusText }}
              </span>
            </div>
            <p class="neco-home__server-motd">
              {{ liveServers ? (server.motd || server.description) : server.description }}
            </p>
            <p class="neco-home__server-meta">
              {{ server.address }}
              <template v-if="liveServers">
                · {{ server.playersText }} 人<template v-if="server.latencyText"> · {{ server.latencyText }}</template>
              </template>
            </p>
          </article>
        </div>
        <div class="neco-home__more">
          <RouterLink class="neco-home__more-link" to="/servers">
            查看全部服务器 →
          </RouterLink>
        </div>
      </div>
    </section>

    <section class="neco-home__latest">
      <h2 class="neco-home__section-title neco-anim">
        {{ newsTitle }}
      </h2>
      <div v-if="news.length" class="neco-home__cards">
        <article v-for="item in news" :key="item.id || item.url" class="neco-panel neco-home__card">
          <h3 class="neco-home__card-title">
            <RouterLink :to="item.url">
              {{ item.title }}
            </RouterLink>
          </h3>
          <p class="neco-home__card-excerpt">
            {{ item.excerpt || item.summary }}
          </p>
          <span class="neco-home__card-date">{{ item.publishedAt }}</span>
        </article>
      </div>
      <p v-else class="neco-home__empty">
        内容建设中，敬请期待。
      </p>
      <div v-if="news.length && news[0]?.url" class="neco-home__more">
        <RouterLink class="neco-home__more-link" :to="news[0].url">
          阅读最新 →
        </RouterLink>
      </div>
    </section>
  </div>
</template>

<style scoped>
.neco-home {
  display: flex;
  flex-direction: column;
  width: 100%;
  box-sizing: border-box;
}

/* 满幅 hero：背景图 + 左压暗遮罩，文字区自带半透明底板 */
.neco-home__hero {
  position: relative;
  overflow: hidden;
  min-height: 40rem;
  width: 100%;
  display: flex;
  align-items: stretch;
  color: #f7f5ee;
  text-shadow: 2px 2px 2px #000;
}

.neco-home__hero-bg {
  position: absolute;
  inset: 0;
  background-size: cover;
  background-position: center;
  pointer-events: none;
}

.neco-home__hero-veil {
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, rgba(8, 8, 8, 0.62) 0%, rgba(8, 8, 8, 0.28) 42%, rgba(8, 8, 8, 0.08) 100%);
  pointer-events: none;
}

.neco-home__hero-inner {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 1rem;
  width: min(34rem, 100%);
  padding: 5.5rem 8% 3rem;
  box-sizing: border-box;
  background: rgba(8, 8, 8, 0.28);
  backdrop-filter: blur(2px);
}

.neco-home__logo {
  width: auto;
  max-width: 100%;
  height: 5rem;
  align-self: flex-start;
  image-rendering: pixelated;
}

.neco-home__title {
  margin: 0;
  font-family: 'Press Start 2P', 'Cubic 11', 'Microsoft YaHei', 'PingFang SC', sans-serif;
  font-size: clamp(1.5rem, 3.4vw, 2.3rem);
  font-weight: 700;
  color: #fff;
  line-height: 1.35;
  letter-spacing: 1px;
  text-shadow: 3px 3px 0 rgba(0, 0, 0, 0.45);
}

.neco-home__tagline {
  margin: 0;
  max-width: 30rem;
  line-height: 1.9;
  font-size: 1.05rem;
  font-weight: 700;
  color: rgba(255, 255, 255, 0.82);
  white-space: pre-line;
}

.neco-home__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-top: 0.5rem;
}

.neco-home__cta {
  display: inline-block;
  padding: 0.6rem 1.4rem;
  border: 2px solid #000 !important;
  border-radius: 0;
  color: #fff;
  text-decoration: none;
  box-shadow: inset 2px 2px 0 var(--neco-bevel-light), inset -2px -2px 0 var(--neco-bevel-dark);
  text-shadow: 2px 2px 0 rgba(0, 0, 0, 0.35);
}

.neco-home__cta--primary {
  background: var(--yb-site-primary-btn-bg);
}

.neco-home__cta--ghost {
  background: rgba(15, 14, 13, 0.55);
}

.neco-home__cta:hover {
  filter: brightness(1.12);
}

.neco-home__cta:active {
  box-shadow: inset -2px -2px 0 var(--neco-bevel-light), inset 2px 2px 0 var(--neco-bevel-dark);
}

/* 关于我们：奇偶左右交替 */
.neco-home__about,
.neco-home__latest {
  width: min(100%, 80rem);
  margin: 0 auto;
  padding: 3rem clamp(1rem, 4vw, 3rem);
  box-sizing: border-box;
}

.neco-home__section-title {
  text-align: center;
  margin: 0 0 1.5rem;
}

.neco-home__intro-list {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3rem;
}

.neco-home__intro-card {
  width: 100%;
  display: flex;
  flex-wrap: wrap;
  justify-content: space-around;
  align-items: center;
  gap: 1.5rem;
  animation: neco-fade-in-left 0.6s ease both;
}

.neco-home__intro-list > .neco-home__intro-card:nth-child(even) {
  flex-direction: row-reverse;
  animation-name: neco-fade-in-right;
}

.neco-home__intro-card--plain {
  flex-direction: column;
  text-align: center;
  border-top: 2px dashed var(--yb-site-border);
  padding-top: 2rem;
  animation-name: neco-fade-in-down;
}

.neco-home__intro-media {
  width: min(100%, 28rem);
  min-height: 12rem;
  background-size: cover;
  background-position: center;
  border: 2px solid #000;
  box-shadow: 4px 4px 0 rgba(0, 0, 0, 0.45);
}

.neco-home__intro-body {
  max-width: 30rem;
  padding: 1rem;
}

.neco-home__intro-card--plain .neco-home__intro-body {
  max-width: 45rem;
}

.neco-home__intro-body h3 {
  margin: 0 0 0.5rem;
  font-size: 1.5rem;
}

.neco-home__intro-body p {
  margin: 0;
  line-height: 1.9;
}

/* 服务器预览：列表纹理背景，装了 minecraft-server 插件即实时状态 */
.neco-home__servers {
  width: 100%;
  padding: 3rem clamp(1rem, 4vw, 3rem);
  box-sizing: border-box;
  background-image: url('/api/platform/plugins/neco-pixel/assets/background/list-background.jpg');
  background-size: cover;
  background-position: center;
}

.neco-home__servers-inner {
  width: min(100%, 80rem);
  margin: 0 auto;
}

.neco-home__server-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
  gap: 1.5rem;
}

.neco-home__server {
  padding: 1rem 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.neco-home__server-head {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}

.neco-home__server-head h3 {
  margin: 0;
  flex: 1;
}

.neco-home__server-icon {
  width: 2rem;
  height: 2rem;
  flex: none;
  background-size: cover;
  image-rendering: pixelated;
  border: 2px solid var(--yb-site-border);
}

.neco-home__server-badge {
  font-size: 0.8em;
  padding: 0.1rem 0.5rem;
  border: 2px solid currentColor;
}

.neco-home__server-badge.is-online {
  color: var(--yb-site-primary);
}

.neco-home__server-badge.is-offline {
  color: var(--yb-site-danger);
}

.neco-home__server-motd {
  margin: 0;
  line-height: 1.8;
  flex: 1;
}

.neco-home__server-meta {
  margin: 0;
  font-size: 0.85em;
  opacity: 0.7;
  word-break: break-all;
}

/* 新闻卡片 */
.neco-home__cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr));
  gap: 1.5rem;
}

.neco-home__card {
  padding: 0.25rem 1rem 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.neco-home__card-title {
  margin: 0.5rem 0 0;
}

.neco-home__card-title a {
  color: inherit;
  text-decoration: none;
}

.neco-home__card-title a:hover {
  color: var(--neco-accent-light);
}

.neco-home__card-excerpt {
  margin: 0;
  line-height: 1.8;
  flex: 1;
}

.neco-home__card-date {
  font-size: 0.85em;
  opacity: 0.65;
}

.neco-home__empty {
  text-align: center;
  opacity: 0.7;
}

.neco-home__more {
  text-align: center;
  margin-top: 1.5rem;
}

.neco-home__more-link {
  color: var(--neco-accent-light);
  text-decoration: none;
}

.neco-home__more-link:hover {
  text-decoration: underline;
}

@media (max-width: 640px) {
  .neco-home__hero {
    min-height: 28rem;
  }

  .neco-home__hero-inner {
    width: 100%;
    padding: 5rem 1.25rem 2.5rem;
    background: rgba(8, 8, 8, 0.42);
  }

  .neco-home__intro-card,
  .neco-home__intro-list > .neco-home__intro-card:nth-child(even) {
    flex-direction: column;
  }

  .neco-home__intro-media {
    width: 100%;
  }
}
</style>
