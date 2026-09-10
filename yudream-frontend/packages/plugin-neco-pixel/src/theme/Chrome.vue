<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { pluginAsset } from './use-theme-context'

interface NavItem {
  id?: string
  label: string
  url: string
  children?: NavItem[]
}

const props = defineProps<{
  sdk: YuDreamPluginSdk
  navigation?: NavItem[]
  footerNavigation?: NavItem[]
  siteName?: string
  siteLogo?: string
  siteDescription?: string
  isLogin?: boolean
  account?: string
  avatar?: string
  blank?: boolean
  footerTitle?: string
  footerDescription?: string
  footerCopyright?: string
}>()

const route = useRoute()
const router = useRouter()
const navFolded = ref(false)
const items = computed(() => (props.navigation || []).filter(item => item.url && item.label))

const activeIndex = computed(() => {
  const current = route.path
  let best = -1
  let bestLength = -1
  items.value.forEach((item, index) => {
    const pathname = item.url
    const matched = pathname === '/'
      ? current === '/' || current === '/site'
      : current === pathname || current.startsWith(`${pathname}/`) || (pathname === '/site' && (current === '/site' || current === '/'))
    if (matched && pathname.length > bestLength) {
      best = index
      bestLength = pathname.length
    }
  })
  return best
})

const sliderStyle = computed(() => ({
  width: items.value.length ? `${100 / items.value.length}%` : '0',
  transform: `translateX(${Math.max(activeIndex.value, 0) * 100}%)`,
  transition: 'transform 0.5s ease',
}))

function playClick() {
  const audio = new Audio(pluginAsset(props.sdk, 'button.click.ogg'))
  audio.volume = 0.3
  audio.play().catch(() => {})
}

function onResize() {
  if (window.innerWidth >= 641 && navFolded.value) {
    navFolded.value = false
  }
}

function go(url: string) {
  playClick()
  if (/^(?:https?:|mailto:|tel:|#)/i.test(url)) {
    window.location.assign(url)
    return
  }
  router.push(url)
}

watch(() => route.path, () => {
  if (window.innerWidth < 641) {
    navFolded.value = true
  }
})

onMounted(() => {
  if (window.innerWidth < 641) {
    navFolded.value = true
  }
  window.addEventListener('resize', onResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', onResize)
})
</script>

<template>
  <div class="neco-chrome">
    <div v-if="!blank" class="nav-container">
      <div class="nav-controls">
        <button
          type="button"
          class="nav-fold-btn"
          :aria-expanded="!navFolded"
          aria-controls="site-nav"
          aria-label="展开或收起导航栏"
          @click="navFolded = !navFolded; playClick()"
        >
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
            <path d="M4 6h16M4 12h16M4 18h16" />
          </svg>
          <span class="nav-fold-text">菜单</span>
        </button>
      </div>
      <nav id="site-nav" class="nav-bar" :class="{ 'is-folded': navFolded }">
        <a
          v-for="(item, index) in items"
          :key="item.id || item.url"
          class="nav-item mcfont"
          :href="item.url"
          :aria-current="activeIndex === index ? 'page' : undefined"
          @click.prevent="go(item.url)"
        >{{ item.label }}</a>
        <div v-if="items.length" class="slider" :style="sliderStyle">
          <div class="slider-box" />
        </div>
      </nav>
    </div>

    <main class="neco-chrome__body">
      <slot />
    </main>

    <footer v-if="!blank" class="footer-area">
      <div class="footer-description">
        <img v-if="siteLogo" :src="siteLogo" alt="" class="footer-logo">
        <p class="mctitle">{{ footerTitle || siteName }}</p>
        <span class="mcfont">{{ footerDescription || siteDescription }}</span>
        <span class="footer-copy">{{ footerCopyright }}</span>
        <span class="footer-disclaimer">
          NOT AN OFFICIAL MINECRAFT ORGANIZATION<br>
          NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT
        </span>
        <RouterLink v-if="isLogin" class="footer-admin" to="/">后台</RouterLink>
        <a v-else class="footer-admin" href="/login" @click.prevent="go('/login')">登录</a>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.neco-chrome {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: var(--neco-bg, #171615);
  color: var(--neco-text-dim, rgba(255, 255, 255, 0.8));
}

.neco-chrome__body {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  min-width: 0;
}

.neco-chrome__body > :deep(*) {
  flex: 1 1 auto;
}

.nav-container {
  display: flex;
  flex-direction: column;
  position: absolute;
  top: 0.5rem;
  left: 0;
  right: 0;
  margin: 0 auto;
  width: max-content;
  gap: 0.5rem;
  user-select: none;
  z-index: 1024;
}

.nav-bar {
  height: calc(1rem + 28px);
  display: flex;
  background-color: var(--neco-bg-overlay, rgba(0, 0, 0, 0.5));
  border: 2px solid var(--neco-border-strong, #aaaaaa);
  position: relative;
  box-shadow: 4px 4px var(--neco-shadow, rgba(0, 0, 0, 0.5));
  transition: all 0.3s ease-in-out;
  opacity: 1;
  overflow: hidden;
  color: #f7f5ee;
}

.nav-bar.is-folded {
  height: 0;
  opacity: 0;
  border-width: 0;
}

.nav-item {
  color: inherit;
  text-decoration: none;
  position: relative;
  flex: 1;
  text-align: center;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 10px 14px;
  cursor: pointer;
  z-index: 1;
  transition: color 0.3s ease;
  font-size: 1rem;
  white-space: nowrap;
}

.nav-item:focus-visible {
  outline: 3px solid #fff;
  outline-offset: -3px;
}

.slider {
  position: absolute;
  top: 0;
  left: 0;
  height: 100%;
  z-index: 0;
  box-sizing: border-box;
  padding: 4px;
}

.slider-box {
  box-sizing: border-box;
  background-color: var(--neco-nav-slider, #7e0c6b);
  border-top: 4px solid var(--neco-nav-slider-light, #9b428c);
  border-bottom: 4px solid var(--neco-nav-slider-dark, #46073b);
  height: 100%;
  width: 100%;
  box-shadow: 2px 2px var(--neco-shadow, rgba(0, 0, 0, 0.5));
}

.nav-controls {
  position: fixed;
  top: 0.75rem;
  right: 0.75rem;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  z-index: 1025;
}

.nav-fold-btn {
  display: none;
  height: 2.75rem;
  padding: 0 0.9rem;
  align-items: center;
  gap: 0.4rem;
  cursor: pointer;
  font: inherit;
  font-size: 0.95rem;
  color: var(--neco-text, #fff);
  background-color: var(--neco-bg-card, #313131);
  border: 2px solid var(--neco-border-strong, #aaaaaa);
  box-shadow: 4px 4px var(--neco-shadow, rgba(0, 0, 0, 0.5));
}

.footer-area {
  padding: 2rem;
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  background-color: var(--neco-bg, #171615);
  border-top: 1px solid var(--neco-border-divider, #747271);
}

.footer-description {
  display: flex;
  flex-direction: column;
  margin-bottom: 1rem;
  max-width: 30rem;
}

.footer-logo {
  width: 5rem;
  user-select: none;
}

.footer-description p {
  font-size: 1.2rem;
  font-weight: bold;
  margin: 0.5rem 0;
}

.footer-copy,
.footer-admin,
.footer-disclaimer {
  user-select: none;
  color: var(--neco-text-placeholder, #808080);
}

.footer-copy,
.footer-admin {
  font-size: 0.8rem;
}

.footer-disclaimer {
  font-size: 0.6rem;
}

.footer-admin {
  width: fit-content;
  text-decoration: none;
}

@media screen and (max-width: 640px) {
  .nav-container {
    gap: 0.35rem;
  }

  .nav-controls {
    position: static;
    justify-content: center;
  }

  .nav-fold-btn {
    display: flex;
  }

  .nav-item {
    padding: 10px 5px;
    font-size: 0.9rem;
  }
}
</style>
