/**
 * 交互音效：使用上游 neco 打包的原版 MC 按钮点击音（public/button.click.ogg，MIT）。
 * 仅在主题处于激活状态且事件发生在公开页容器内时播放。
 */

const PLUGIN_CODE = 'neco-pixel'
const STORAGE_KEY = 'neco-pixel:sound'

const PUBLIC_CONTAINERS = '.site-page, .site-chrome, .site-layout-header__mobile, .wiki-public, .wiki-home, .wiki-search-page, .discover, .plugin, .public-form-page, .tl-page, .ev-page, .world-map-viewer, .qb-screen, .qb-shared, .plugin-site-page, .neco-chrome'
const INTERACTIVE_SELECTOR = 'a, button, summary, input, select, textarea, [role="button"], .site-nav-item, .tl-card, .wiki-public-nav a, .wiki-home__space, .discover-card, .nav-item'

let clickAudio: HTMLAudioElement | null = null
let lastHoverTarget: EventTarget | null = null
let toggleButton: HTMLButtonElement | null = null
let headObserver: MutationObserver | null = null
let installed = false

function soundEnabled() {
  try {
    return localStorage.getItem(STORAGE_KEY) !== 'off'
  }
  catch {
    return true
  }
}

function themeActive() {
  const link = document.head.querySelector<HTMLLinkElement>(`link[data-yudream-theme-plugin="${PLUGIN_CODE}"][data-yudream-theme-scope="site"]`)
  if (!link) {
    return false
  }
  return !link.disabled && link.media !== 'not all'
}

function clickSrc() {
  const link = document.head.querySelector<HTMLLinkElement>(`link[data-yudream-theme-plugin="${PLUGIN_CODE}"][data-yudream-theme-scope="site"]`)
  if (!link?.href) {
    return ''
  }
  try {
    return new URL('button.click.ogg', link.href).toString()
  }
  catch {
    return ''
  }
}

function playClick() {
  const src = clickSrc()
  if (!src) {
    return
  }
  try {
    if (!clickAudio || clickAudio.src !== src) {
      clickAudio = new Audio(src)
      clickAudio.volume = 0.3
    }
    clickAudio.currentTime = 0
    void clickAudio.play().catch(() => {})
  }
  catch {
    // 自动播放策略或解码失败时静默
  }
}

function inPublicContainer(target: EventTarget | null) {
  return target instanceof Element && !!target.closest(PUBLIC_CONTAINERS)
}

function interactiveTarget(target: EventTarget | null) {
  return target instanceof Element ? target.closest(INTERACTIVE_SELECTOR) : null
}

function onPointerOver(event: PointerEvent) {
  if (!soundEnabled() || !themeActive() || !inPublicContainer(event.target)) {
    lastHoverTarget = null
    return
  }
  const hit = interactiveTarget(event.target)
  if (!hit || hit === lastHoverTarget) {
    return
  }
  lastHoverTarget = hit
}

function onClick(event: MouseEvent) {
  if (!soundEnabled() || !themeActive() || !inPublicContainer(event.target)) {
    return
  }
  if (interactiveTarget(event.target)) {
    playClick()
  }
}

function syncToggleVisibility() {
  if (toggleButton) {
    toggleButton.hidden = !themeActive()
  }
}

function createToggleButton() {
  const button = document.createElement('button')
  button.type = 'button'
  button.className = 'neco-sound-toggle'
  button.title = '像素音效开关'
  const render = () => {
    const enabled = soundEnabled()
    button.textContent = enabled ? '♪' : '×'
    button.classList.toggle('neco-sound-off', !enabled)
    button.setAttribute('aria-pressed', String(enabled))
  }
  button.addEventListener('click', (event) => {
    event.stopPropagation()
    try {
      localStorage.setItem(STORAGE_KEY, soundEnabled() ? 'off' : 'on')
    }
    catch {
      // localStorage 不可用时仅切换当次会话
    }
    render()
    if (soundEnabled()) {
      playClick()
    }
  })
  render()
  document.body.appendChild(button)
  return button
}

export function installSounds() {
  if (installed) {
    return
  }
  installed = true
  document.addEventListener('pointerover', onPointerOver, true)
  document.addEventListener('click', onClick, true)
  toggleButton = createToggleButton()
  headObserver = new MutationObserver(syncToggleVisibility)
  headObserver.observe(document.head, { attributes: true, subtree: true, attributeFilter: ['disabled', 'media'] })
  syncToggleVisibility()
}

export function disposeSounds() {
  if (!installed) {
    return
  }
  installed = false
  document.removeEventListener('pointerover', onPointerOver, true)
  document.removeEventListener('click', onClick, true)
  headObserver?.disconnect()
  headObserver = null
  toggleButton?.remove()
  toggleButton = null
  lastHoverTarget = null
  clickAudio = null
}
