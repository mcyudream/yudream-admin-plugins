/**
 * 6 色强调色切换器运行时：移植上游 neco 的 data-accent 体系（MIT）。
 *
 * 翠绿为默认色（不挂 data-accent 属性），其余五色在 <html> 上挂
 * data-accent，由 theme.css 的 scoped 变量块接管整套 --neco-* 阶梯与
 * 宿主 --yb-site-* 桥接变量。选择持久化 localStorage["neco-pixel:accent"]。
 * 浮动像素色板与音效开关并排，仅在主题激活时可见。
 */

const PLUGIN_CODE = 'neco-pixel'
const STORAGE_KEY = 'neco-pixel:accent'
const SWITCHING_CLASS = 'theme-switching'
const SWITCHING_MS = 400

interface AccentOption {
  id: string
  label: string
  /** 色板展示色（深色方案主色） */
  color: string
}

const ACCENTS: AccentOption[] = [
  { id: 'green', label: '翠绿', color: '#3c8527' },
  { id: 'red', label: '红石', color: '#b02e26' },
  { id: 'blue', label: '青金石', color: '#3c44aa' },
  { id: 'gold', label: '黄金', color: '#c8a03a' },
  { id: 'purple', label: '紫水晶', color: '#8932b8' },
  { id: 'cyan', label: '海晶', color: '#169c9c' },
]

const DEFAULT_ACCENT = 'green'

let toggleButton: HTMLButtonElement | null = null
let panel: HTMLDivElement | null = null
let headObserver: MutationObserver | null = null
let outsideClickHandler: ((event: MouseEvent) => void) | null = null
let installed = false

function currentAccent() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    return ACCENTS.some(item => item.id === saved) ? saved as string : DEFAULT_ACCENT
  }
  catch {
    return DEFAULT_ACCENT
  }
}

function themeActive() {
  const link = document.head.querySelector<HTMLLinkElement>(`link[data-yudream-theme-plugin="${PLUGIN_CODE}"][data-yudream-theme-scope="site"]`)
  return !!link && !link.disabled
}

function reducedMotion() {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
}

/** 切换瞬间挂过渡类，让整套变量 0.35s 平滑过渡（尊重 reduced-motion） */
function withSwitchingTransition(mutate: () => void) {
  if (reducedMotion()) {
    mutate()
    return
  }
  document.documentElement.classList.add(SWITCHING_CLASS)
  mutate()
  window.setTimeout(() => {
    document.documentElement.classList.remove(SWITCHING_CLASS)
  }, SWITCHING_MS)
}

function applyAccent(accent: string, animate: boolean) {
  const mutate = () => {
    if (accent === DEFAULT_ACCENT) {
      document.documentElement.removeAttribute('data-accent')
    }
    else {
      document.documentElement.setAttribute('data-accent', accent)
    }
  }
  if (animate) {
    withSwitchingTransition(mutate)
  }
  else {
    mutate()
  }
  syncSwatchState()
}

function selectAccent(accent: string) {
  try {
    localStorage.setItem(STORAGE_KEY, accent)
  }
  catch {
    // localStorage 不可用时仅切换当次会话
  }
  applyAccent(accent, true)
}

function syncSwatchState() {
  if (!panel) {
    return
  }
  const active = currentAccent()
  panel.querySelectorAll<HTMLButtonElement>('.neco-palette-swatch').forEach((swatch) => {
    const isActive = swatch.dataset.accent === active
    swatch.classList.toggle('active', isActive)
    swatch.setAttribute('aria-pressed', String(isActive))
  })
}

function syncVisibility() {
  const visible = themeActive()
  if (toggleButton) {
    toggleButton.hidden = !visible
  }
  if (!visible && panel) {
    panel.hidden = true
  }
}

function togglePanel() {
  if (!panel) {
    return
  }
  panel.hidden = !panel.hidden
}

function closePanel() {
  if (panel) {
    panel.hidden = true
  }
}

function createToggleButton() {
  const button = document.createElement('button')
  button.type = 'button'
  button.className = 'neco-palette-toggle'
  button.title = '强调色'
  button.setAttribute('aria-haspopup', 'true')
  // 2×2 彩色方块图标，取自色板中的四个代表色
  const iconColors = ['#3c8527', '#b02e26', '#3c44aa', '#c8a03a']
  for (const color of iconColors) {
    const square = document.createElement('i')
    square.style.background = color
    button.appendChild(square)
  }
  button.addEventListener('click', (event) => {
    event.stopPropagation()
    togglePanel()
  })
  document.body.appendChild(button)
  return button
}

function createPanel() {
  const el = document.createElement('div')
  el.className = 'neco-palette-panel neco-panel'
  el.hidden = true
  el.setAttribute('role', 'group')
  el.setAttribute('aria-label', '强调色')

  const label = document.createElement('span')
  label.className = 'neco-palette-panel__label'
  label.textContent = '强调色'
  el.appendChild(label)

  const swatches = document.createElement('div')
  swatches.className = 'neco-palette-swatches'
  for (const accent of ACCENTS) {
    const swatch = document.createElement('button')
    swatch.type = 'button'
    swatch.className = 'neco-palette-swatch'
    swatch.dataset.accent = accent.id
    swatch.title = accent.label
    swatch.style.setProperty('--swatch', accent.color)
    swatch.addEventListener('click', (event) => {
      event.stopPropagation()
      selectAccent(accent.id)
      closePanel()
    })
    swatches.appendChild(swatch)
  }
  el.appendChild(swatches)

  el.addEventListener('click', event => event.stopPropagation())
  document.body.appendChild(el)
  return el
}

export function installAccent() {
  if (installed) {
    return
  }
  installed = true
  applyAccent(currentAccent(), false)
  toggleButton = createToggleButton()
  panel = createPanel()
  syncSwatchState()
  outsideClickHandler = (event: MouseEvent) => {
    if (panel && !panel.hidden && event.target !== toggleButton) {
      closePanel()
    }
  }
  document.addEventListener('click', outsideClickHandler, true)
  // 主题 link 的 disabled 随路由切换，观察 head 属性变化同步色板显隐
  headObserver = new MutationObserver(syncVisibility)
  headObserver.observe(document.head, { attributes: true, subtree: true, attributeFilter: ['disabled'] })
  syncVisibility()
}

export function disposeAccent() {
  if (!installed) {
    return
  }
  installed = false
  headObserver?.disconnect()
  headObserver = null
  if (outsideClickHandler) {
    document.removeEventListener('click', outsideClickHandler, true)
    outsideClickHandler = null
  }
  toggleButton?.remove()
  toggleButton = null
  panel?.remove()
  panel = null
  document.documentElement.removeAttribute('data-accent')
  document.documentElement.classList.remove(SWITCHING_CLASS)
}
