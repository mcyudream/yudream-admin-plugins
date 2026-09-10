/**
 * 8-bit 交互音效运行时：WebAudio 实时合成（方波/三角波芯片音），不依赖音频资产。
 *
 * 仅在主题处于激活状态（宿主 theme-runtime 注入的样式 link 存在且未 disabled）
 * 且事件发生在公开页容器内时播放；宿主切换路由禁用 link 后音效自动静默。
 */

const PLUGIN_CODE = 'neco-pixel'
const STORAGE_KEY = 'neco-pixel:sound'

/** 公开页容器白名单：宿主公开站骨架、wiki、表单公开页与各插件公开页 */
const PUBLIC_CONTAINERS = '.site-page, .site-chrome, .site-layout-header__mobile, .wiki-public, .wiki-home, .wiki-search-page, .public-form-page, .tl-page, .ev-page, .world-map-viewer, .qb-screen, .qb-shared, .plugin-site-page'

/** 触发音效的交互元素 */
const INTERACTIVE_SELECTOR = 'a, button, summary, input, select, textarea, [role="button"], .site-nav-item, .tl-card, .wiki-public-nav a, .wiki-home__space'

let audioContext: AudioContext | null = null
let masterGain: GainNode | null = null
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
  return !!link && !link.disabled
}

function ensureAudioContext() {
  if (audioContext) {
    if (audioContext.state === 'suspended') {
      void audioContext.resume().catch(() => {})
    }
    return audioContext
  }
  try {
    audioContext = new AudioContext()
    masterGain = audioContext.createGain()
    masterGain.gain.value = 0.12
    masterGain.connect(audioContext.destination)
  }
  catch {
    audioContext = null
  }
  return audioContext
}

/** 播放一段芯片音：方波扫频 + 指数衰减包络 */
function blip(from: number, to: number, duration: number, type: OscillatorType = 'square', volume = 1) {
  const ctx = ensureAudioContext()
  if (!ctx || !masterGain || ctx.state !== 'running') {
    return
  }
  const osc = ctx.createOscillator()
  const gain = ctx.createGain()
  const now = ctx.currentTime
  osc.type = type
  osc.frequency.setValueAtTime(from, now)
  osc.frequency.exponentialRampToValueAtTime(Math.max(to, 1), now + duration)
  gain.gain.setValueAtTime(volume, now)
  gain.gain.exponentialRampToValueAtTime(0.001, now + duration)
  osc.connect(gain)
  gain.connect(masterGain)
  osc.start(now)
  osc.stop(now + duration + 0.02)
}

function playHover() {
  blip(880, 990, 0.05, 'square', 0.35)
}

function playClick() {
  blip(660, 330, 0.08, 'square', 0.8)
  window.setTimeout(() => blip(990, 990, 0.04, 'triangle', 0.5), 40)
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
  playHover()
}

function onClick(event: MouseEvent) {
  if (!soundEnabled() || !themeActive() || !inPublicContainer(event.target)) {
    return
  }
  if (interactiveTarget(event.target)) {
    playClick()
  }
}

/** 首次任意交互时解锁 AudioContext（浏览器自动播放策略） */
function onFirstPointerDown() {
  ensureAudioContext()
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
  document.addEventListener('pointerdown', onFirstPointerDown, true)
  toggleButton = createToggleButton()
  // 主题 link 的 disabled 随路由切换，观察 head 属性变化同步开关显隐
  headObserver = new MutationObserver(syncToggleVisibility)
  headObserver.observe(document.head, { attributes: true, subtree: true, attributeFilter: ['disabled'] })
  syncToggleVisibility()
}

export function disposeSounds() {
  if (!installed) {
    return
  }
  installed = false
  document.removeEventListener('pointerover', onPointerOver, true)
  document.removeEventListener('click', onClick, true)
  document.removeEventListener('pointerdown', onFirstPointerDown, true)
  headObserver?.disconnect()
  headObserver = null
  toggleButton?.remove()
  toggleButton = null
  lastHoverTarget = null
  if (audioContext) {
    void audioContext.close().catch(() => {})
    audioContext = null
    masterGain = null
  }
}
