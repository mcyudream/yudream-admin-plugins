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

const HIDDEN_NAV = new Set(['/news', '/about', '/activities/:id'])
const HIDDEN_LABELS = new Set(['新闻', '关于我们', '活动详情'])

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
// 移动端默认收起：不能等 onMounted，否则首帧会先展开再折
const navFolded = ref(typeof window !== 'undefined' && window.innerWidth < 641)
const accountOpen = ref(false)
const navRef = ref<HTMLElement | null>(null)
const slider = ref({ left: 0, width: 0, visible: false })
let resizeObserver: ResizeObserver | null = null

const openMenu = ref('')

function navPath(url?: string) {
  const pathname = (url || '').trim().split(/[?#]/, 1)[0] || '/'
  return pathname.replace(/\/+$/, '') || '/'
}

function isHiddenNav(item: NavItem) {
  const path = navPath(item.url)
  if (!item.label) {
    return true
  }
  if (HIDDEN_NAV.has(path) || HIDDEN_LABELS.has(item.label.trim())) {
    return true
  }
  return path.startsWith('/activities/') && path !== '/activities'
}

function visibleChildren(item: NavItem) {
  return (item.children || []).filter(child => child.label && !isHiddenNav(child))
}

function itemKey(item: NavItem, index: number) {
  return item.id || item.url || `nav-${index}`
}

function pathMatches(url: string | undefined, current: string) {
  const pathname = navPath(url)
  if (!pathname || pathname === '/') {
    return current === '/' || current === '/site'
  }
  return current === pathname
    || current.startsWith(`${pathname}/`)
    || (pathname === '/site' && (current === '/site' || current === '/'))
}

const items = computed(() => (props.navigation || []).flatMap((item) => {
  const children = visibleChildren(item)
  if (isHiddenNav(item)) {
    // 隐藏项连同其二级菜单一起隐藏，禁止把子级平铺成一级
    return []
  }
  return [{ ...item, children }]
}))

const activeIndex = computed(() => {
  const current = route.path
  let best = -1
  let bestLength = -1
  items.value.forEach((item, index) => {
    const candidates = [item, ...visibleChildren(item)]
    candidates.forEach((candidate) => {
      if (!pathMatches(candidate.url, current)) {
        return
      }
      const length = navPath(candidate.url).length
      if (length > bestLength) {
        best = index
        bestLength = length
      }
    })
  })
  return best
})

function isChildActive(child: NavItem) {
  return pathMatches(child.url, route.path)
}

function isMobileNav() {
  return typeof window !== 'undefined' && window.innerWidth < 641
}

function toggleMenu(key: string) {
  playClick()
  openMenu.value = openMenu.value === key ? '' : key
}

function onNavItemClick(item: NavItem, index: number) {
  const children = visibleChildren(item)
  const key = itemKey(item, index)
  if (!children.length) {
    go(item.url)
    return
  }
  if (isMobileNav() || !item.url) {
    toggleMenu(key)
    return
  }
  go(item.url)
}

function closeMenus() {
  openMenu.value = ''
  accountOpen.value = false
}

const sliderStyle = computed(() => ({
  left: `${slider.value.left}px`,
  width: `${slider.value.width}px`,
  opacity: slider.value.visible ? 1 : 0,
  transition: 'left 0.5s ease, width 0.5s ease, opacity 0.2s ease',
}))

function measureSlider() {
  const nav = navRef.value
  const index = activeIndex.value
  const group = nav?.querySelectorAll<HTMLElement>('.nav-group')[index]
  const el = group?.querySelector<HTMLElement>(':scope > .nav-item')
  if (!nav || !group || !el || index < 0) {
    slider.value = { left: 0, width: 0, visible: false }
    return
  }
  slider.value = {
    left: group.offsetLeft,
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
  closeMenus()
  if (/^(?:https?:|mailto:|tel:|#)/i.test(url)) {
    window.location.assign(url)
    return
  }
  router.push(url)
}

function onDocumentClick(event: MouseEvent) {
  const target = event.target as Node | null
  if (!target) {
    return
  }
  if (accountOpen.value && !document.querySelector('.nav-account')?.contains(target)) {
    accountOpen.value = false
  }
  if (openMenu.value && !(target instanceof Element && target.closest('.nav-group'))) {
    openMenu.value = ''
  }
}

watch(() => route.path, () => {
  if (window.innerWidth < 641) {
    navFolded.value = true
  }
  closeMenus()
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
        <div
          v-for="(item, index) in items"
          :key="itemKey(item, index)"
          class="nav-group"
          :class="{ 'has-children': visibleChildren(item).length, 'is-open': openMenu === itemKey(item, index) }"
          @mouseenter="visibleChildren(item).length && !isMobileNav() && (openMenu = itemKey(item, index))"
          @mouseleave="!isMobileNav() && openMenu === itemKey(item, index) && (openMenu = '')"
        >
          <a
            class="nav-item mcfont"
            :href="item.url || visibleChildren(item)[0]?.url || '#'"
            :aria-current="activeIndex === index ? 'page' : undefined"
            :aria-haspopup="visibleChildren(item).length ? 'menu' : undefined"
            :aria-expanded="visibleChildren(item).length ? openMenu === itemKey(item, index) : undefined"
            @click.prevent="onNavItemClick(item, index)"
          >
            {{ item.label }}
            <span v-if="visibleChildren(item).length" class="nav-caret" aria-hidden="true">▾</span>
          </a>
          <div v-if="visibleChildren(item).length" class="nav-submenu" role="menu">
            <a
              v-for="child in visibleChildren(item)"
              :key="child.id || child.url"
              class="nav-subitem mcfont"
              :href="child.url"
              :class="{ 'is-active': isChildActive(child) }"
              role="menuitem"
              @click.prevent="go(child.url)"
            >{{ child.label }}</a>
          </div>
        </div>
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
  min-height: calc(1rem + 28px);
  display: flex;
  background-color: var(--neco-bg-overlay, rgba(0, 0, 0, 0.5));
  border: 2px solid var(--neco-border-strong, #aaaaaa);
  position: relative;
  box-shadow: 4px 4px var(--neco-shadow, rgba(0, 0, 0, 0.5));
  transition: min-height 0.3s ease-in-out, opacity 0.3s ease-in-out, border-width 0.3s ease;
  opacity: 1;
  overflow: visible;
  color: #f7f5ee;
}

.nav-bar.is-folded {
  min-height: 0;
  height: 0;
  opacity: 0;
  border-width: 0;
  overflow: hidden;
  pointer-events: none;
}

.nav-group {
  position: relative;
  flex: 0 0 auto;
  z-index: 1;
}

.nav-group.has-children:hover,
.nav-group.is-open {
  z-index: 4;
}

.nav-group.has-children::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  top: 100%;
  height: 0.5rem;
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
  gap: 0.3rem;
  padding: 10px 14px;
  cursor: pointer;
  z-index: 1;
  transition: color 0.3s ease;
  font-size: 1rem;
  white-space: nowrap;
}

.nav-caret {
  font-size: 0.7rem;
  opacity: 0.75;
  transform: translateY(1px);
  transition: transform 0.15s ease;
}

.nav-group.has-children:hover .nav-caret,
.nav-group.is-open .nav-caret {
  transform: translateY(1px) rotate(180deg);
}

.nav-submenu {
  position: absolute;
  top: calc(100% + 0.4rem);
  left: 0;
  min-width: max(100%, 8.5rem);
  display: none;
  flex-direction: column;
  background-color: var(--neco-bg-card, #313131);
  border: 2px solid var(--neco-border-strong, #aaaaaa);
  box-shadow: 4px 4px var(--neco-shadow, rgba(0, 0, 0, 0.5));
  z-index: 1030;
}

.nav-group.has-children:hover .nav-submenu,
.nav-group.is-open .nav-submenu {
  display: flex;
}

.nav-subitem {
  padding: 0.55rem 0.9rem;
  color: #f7f5ee;
  text-decoration: none;
  font-size: 0.92rem;
  white-space: nowrap;
}

.nav-subitem:hover,
.nav-subitem.is-active {
  background: var(--neco-nav-slider, var(--neco-accent, #3c8527));
  box-shadow: inset 0 3px var(--neco-nav-slider-light, var(--neco-accent-light, #6cc349)), inset 0 -3px var(--neco-nav-slider-dark, var(--neco-accent-dark, #2a641c));
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

  .nav-submenu {
    position: static;
    top: auto;
    left: auto;
    min-width: 100%;
    display: none;
    border-left: 0;
    border-right: 0;
    box-shadow: none;
  }

  .nav-group.has-children:hover .nav-submenu {
    display: none;
  }

  .nav-group.is-open .nav-submenu {
    display: flex;
  }

  .nav-bar {
    flex-wrap: wrap;
    overflow: visible;
  }

  .nav-bar.is-folded {
    overflow: hidden;
  }

  .nav-group {
    flex: 1 1 100%;
  }

  .nav-subitem {
    padding-left: 1.4rem;
  }

  .nav-account-name {
    max-width: 5rem;
  }
}
</style>
