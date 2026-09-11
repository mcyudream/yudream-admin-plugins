<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { pluginAsset } from './use-theme-context'

interface NavItem {
  id?: string
  label: string
  url: string
  children?: NavItem[]
}

const HIDDEN_NAV = new Set(['/news', '/about', '/wiki'])
const HIDDEN_LABELS = new Set(['新闻', '关于我们', '知识库'])

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
const accountOpen = ref(false)
const navRef = ref<HTMLElement | null>(null)
const slider = ref({ left: 0, width: 0, visible: false })
let resizeObserver: ResizeObserver | null = null

function navPath(url?: string) {
  const pathname = (url || '').trim().split(/[?#]/, 1)[0] || '/'
  return pathname.replace(/\/+$/, '') || '/'
}

const items = computed(() => (props.navigation || []).filter((item) => {
  if (!item.url || !item.label) {
    return false
  }
  const path = navPath(item.url)
  return !HIDDEN_NAV.has(path) && !HIDDEN_LABELS.has(item.label.trim())
}))

const activeIndex = computed(() => {
  const current = route.path
  let best = -1
  let bestLength = -1
  items.value.forEach((item, index) => {
    const pathname = navPath(item.url)
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
  left: `${slider.value.left}px`,
  width: `${slider.value.width}px`,
  opacity: slider.value.visible ? 1 : 0,
  transition: 'left 0.5s ease, width 0.5s ease, opacity 0.2s ease',
}))

function measureSlider() {
  const nav = navRef.value
  const index = activeIndex.value
  const el = nav?.querySelectorAll<HTMLElement>('.nav-item')[index]
  if (!nav || !el || index < 0) {
    slider.value = { left: 0, width: 0, visible: false }
    return
  }
  slider.value = {
    left: el.offsetLeft,
    width: el.offsetWidth,
    visible: true,
  }
}

function playClick() {
  const audio = new Audio(pluginAsset(props.sdk, 'button.click.ogg'))
  audio.volume = 0.3
  audio.play().catch(() => {})
}

function onResize() {
  if (window.innerWidth >= 641 && navFolded.value) {
    navFolded.value = false
  }
  measureSlider()
}

function go(url: string) {
  playClick()
  accountOpen.value = false
  if (/^(?:https?:|mailto:|tel:|#)/i.test(url)) {
    window.location.assign(url)
    return
  }
  router.push(url)
}

function onDocumentClick(event: MouseEvent) {
  const target = event.target as Node | null
  if (accountOpen.value && target && !document.querySelector('.nav-account')?.contains(target)) {
    accountOpen.value = false
  }
}

watch(() => route.path, () => {
  if (window.innerWidth < 641) {
    navFolded.value = true
  }
  accountOpen.value = false
  void nextTick(measureSlider)
})

watch(activeIndex, () => {
  void nextTick(measureSlider)
})

watch(items, () => {
  void nextTick(measureSlider)
})

onMounted(() => {
  if (window.innerWidth < 641) {
    navFolded.value = true
  }
  window.addEventListener('resize', onResize)
  document.addEventListener('click', onDocumentClick)
  void nextTick(() => {
    measureSlider()
    const fonts = (document as Document & { fonts?: { ready?: Promise<unknown> } }).fonts
    void fonts?.ready?.then(() => measureSlider())
    if (navRef.value && typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver(() => measureSlider())
      resizeObserver.observe(navRef.value)
    }
  })
})

onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  document.removeEventListener('click', onDocumentClick)
  resizeObserver?.disconnect()
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
        <div class="nav-auth">
          <template v-if="!isLogin">
            <a class="nav-auth-link" href="/login" @click.prevent="go('/login')">登录</a>
            <a class="nav-auth-link nav-auth-link--primary" href="/register" @click.prevent="go('/register')">注册</a>
          </template>
          <div v-else class="nav-account" :class="{ 'is-open': accountOpen }">
            <button
              type="button"
              class="nav-account-btn"
              :aria-expanded="accountOpen"
              aria-haspopup="true"
              @click.stop="accountOpen = !accountOpen; playClick()"
            >
              <img v-if="avatar" :src="avatar" :alt="account" class="nav-account-avatar">
              <span v-else class="nav-account-fallback mcfont" aria-hidden="true">{{ (account || '?').slice(0, 1) }}</span>
              <span class="nav-account-name mcfont">{{ account }}</span>
              <span class="nav-account-caret" aria-hidden="true">▾</span>
            </button>
            <div v-show="accountOpen" class="nav-account-menu" role="menu">
              <a href="/" role="menuitem" @click.prevent="go('/')">控制台</a>
              <a href="/profile" role="menuitem" @click.prevent="go('/profile')">个人资料</a>
              <a href="/logout" class="is-danger" role="menuitem" @click.prevent="go('/logout')">退出登录</a>
            </div>
          </div>
        </div>
      </div>
      <nav id="site-nav" ref="navRef" class="nav-bar" :class="{ 'is-folded': navFolded }">
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
  max-width: calc(100vw - 2rem);
  gap: 0.5rem;
  user-select: none;
  z-index: 1024;
  pointer-events: none;
}

.nav-container > * {
  pointer-events: auto;
}

.nav-bar {
  height: calc(1rem + 28px);
  display: flex;
  background-color: var(--neco-bg-overlay, rgba(0, 0, 0, 0.5));
  border: 2px solid var(--neco-border-strong, #aaaaaa);
  position: relative;
  box-shadow: 4px 4px var(--neco-shadow, rgba(0, 0, 0, 0.5));
  transition: height 0.3s ease-in-out, opacity 0.3s ease-in-out, border-width 0.3s ease;
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
  flex: 0 0 auto;
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
  height: 100%;
  z-index: 0;
  box-sizing: border-box;
  padding: 4px;
  pointer-events: none;
}

.slider-box {
  box-sizing: border-box;
  background-color: var(--neco-nav-slider, var(--neco-accent, #3c8527));
  border-top: 4px solid var(--neco-nav-slider-light, var(--neco-accent-light, #6cc349));
  border-bottom: 4px solid var(--neco-nav-slider-dark, var(--neco-accent-dark, #2a641c));
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

.nav-auth {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}

.nav-auth-link,
.nav-account-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  height: 2.75rem;
  padding: 0 0.85rem;
  color: #f7f5ee;
  text-decoration: none;
  font: inherit;
  font-size: 0.9rem;
  background-color: var(--neco-bg-overlay, rgba(0, 0, 0, 0.5));
  border: 2px solid var(--neco-border-strong, #aaaaaa);
  box-shadow: 4px 4px var(--neco-shadow, rgba(0, 0, 0, 0.5));
  cursor: pointer;
  white-space: nowrap;
}

.nav-auth-link--primary {
  background-color: var(--neco-accent, #3c8527);
  border-color: var(--neco-accent-light, #6cc349);
}

.nav-account {
  position: relative;
}

.nav-account-avatar,
.nav-account-fallback {
  width: 1.4rem;
  height: 1.4rem;
  object-fit: cover;
  image-rendering: pixelated;
  border: 1px solid #000;
}

.nav-account-fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--neco-accent, #3c8527);
  color: #fff;
  font-size: 0.8rem;
}

.nav-account-name {
  max-width: 8rem;
  overflow: hidden;
  text-overflow: ellipsis;
}

.nav-account-caret {
  font-size: 0.7rem;
  opacity: 0.8;
}

.nav-account-menu {
  position: absolute;
  top: calc(100% + 0.35rem);
  right: 0;
  min-width: 9rem;
  display: flex;
  flex-direction: column;
  background-color: var(--neco-bg-card, #313131);
  border: 2px solid var(--neco-border-strong, #aaaaaa);
  box-shadow: 4px 4px var(--neco-shadow, rgba(0, 0, 0, 0.5));
  z-index: 1026;
}

.nav-account-menu a {
  padding: 0.55rem 0.85rem;
  color: #f7f5ee;
  text-decoration: none;
  font-size: 0.9rem;
}

.nav-account-menu a:hover {
  background: var(--neco-bg-hover, rgba(255, 255, 255, 0.1));
}

.nav-account-menu a.is-danger {
  color: #f09a90;
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
    width: calc(100vw - 1.5rem);
  }

  .nav-controls {
    position: static;
    justify-content: flex-end;
    flex-wrap: wrap;
  }

  .nav-fold-btn {
    display: flex;
  }

  .nav-item {
    padding: 10px 5px;
    font-size: 0.9rem;
  }

  .nav-account-name {
    max-width: 5rem;
  }
}
</style>
