/**
 * 导航活动项像素滑块运行时：移植上游 neco NavBar 的 .slider 指示块（MIT）。
 *
 * 宿主导航（site-chrome）是普通锚点、不输出 active 类，这里按当前路径与
 * 各导航项 href 的最长前缀匹配计算活动项，再用测量到的 offsetLeft/width
 * 移动绝对定位滑块。路由变化（popstate / history 补丁）、视口缩放与
 * 导航结构变更（菜单动态注入）都会触发重新定位。
 */

const NAV_SELECTOR = '.site-chrome .site-layout-header__nav'
const ITEM_SELECTOR = '.site-nav-item'
const ACTIVE_CLASS = 'neco-nav-active'

let slider: HTMLDivElement | null = null
let navObserver: MutationObserver | null = null
let resizeHandler: (() => void) | null = null
let popstateHandler: (() => void) | null = null
let originalPushState: typeof history.pushState | null = null
let originalReplaceState: typeof history.replaceState | null = null
let installed = false

function findNav() {
  return document.querySelector<HTMLElement>(NAV_SELECTOR)
}

/** 取当前路径下最匹配的导航项：非根路径按前缀匹配，取最长者；根路径只精确匹配 */
function findActiveItem(nav: HTMLElement) {
  const current = window.location.pathname
  let best: HTMLElement | null = null
  let bestLength = -1
  nav.querySelectorAll<HTMLElement>(ITEM_SELECTOR).forEach((item) => {
    const anchor = item.querySelector<HTMLAnchorElement>('a[href]')
    if (!anchor) {
      return
    }
    let pathname: string
    try {
      pathname = new URL(anchor.href, window.location.href).pathname
    }
    catch {
      return
    }
    const matched = pathname === '/'
      ? current === '/'
      : current === pathname || current.startsWith(`${pathname}/`)
    if (matched && pathname.length > bestLength) {
      best = item
      bestLength = pathname.length
    }
  })
  return best
}

function positionSlider() {
  const nav = findNav()
  if (!nav || !slider) {
    return
  }
  if (slider.parentElement !== nav) {
    nav.appendChild(slider)
  }
  nav.querySelectorAll<HTMLElement>(ITEM_SELECTOR).forEach(item => item.classList.remove(ACTIVE_CLASS))
  const active = findActiveItem(nav)
  if (!active) {
    slider.hidden = true
    return
  }
  active.classList.add(ACTIVE_CLASS)
  slider.hidden = false
  slider.style.width = `${active.offsetWidth}px`
  slider.style.transform = `translateX(${active.offsetLeft}px)`
}

function schedulePosition() {
  // 等 SPA 路由提交完成、导航渲染稳定后再测量
  window.requestAnimationFrame(() => positionSlider())
}

function createSlider() {
  const el = document.createElement('div')
  el.className = 'neco-nav-slider'
  el.hidden = true
  el.setAttribute('aria-hidden', 'true')
  const box = document.createElement('div')
  box.className = 'neco-nav-slider__box'
  el.appendChild(box)
  return el
}

/** SPA 的 history.pushState/replaceState 不触发 popstate，补一层钩子 */
function patchHistory() {
  originalPushState = history.pushState
  originalReplaceState = history.replaceState
  history.pushState = function (...args) {
    const result = originalPushState!.apply(this, args)
    schedulePosition()
    return result
  }
  history.replaceState = function (...args) {
    const result = originalReplaceState!.apply(this, args)
    schedulePosition()
    return result
  }
}

function restoreHistory() {
  if (originalPushState) {
    history.pushState = originalPushState
    originalPushState = null
  }
  if (originalReplaceState) {
    history.replaceState = originalReplaceState
    originalReplaceState = null
  }
}

export function installNavSlider() {
  if (installed) {
    return
  }
  installed = true
  slider = createSlider()
  const nav = findNav()
  if (nav) {
    nav.appendChild(slider)
  }
  // 导航容器可能晚于 install 渲染（宿主异步注入菜单），观察 body 子树兜底挂接
  navObserver = new MutationObserver(() => schedulePosition())
  navObserver.observe(document.body, { childList: true, subtree: true })
  resizeHandler = () => schedulePosition()
  window.addEventListener('resize', resizeHandler)
  popstateHandler = () => schedulePosition()
  window.addEventListener('popstate', popstateHandler)
  patchHistory()
  schedulePosition()
}

export function disposeNavSlider() {
  if (!installed) {
    return
  }
  installed = false
  navObserver?.disconnect()
  navObserver = null
  if (resizeHandler) {
    window.removeEventListener('resize', resizeHandler)
    resizeHandler = null
  }
  if (popstateHandler) {
    window.removeEventListener('popstate', popstateHandler)
    popstateHandler = null
  }
  restoreHistory()
  const nav = findNav()
  nav?.querySelectorAll<HTMLElement>(ITEM_SELECTOR).forEach(item => item.classList.remove(ACTIVE_CLASS))
  slider?.remove()
  slider = null
}
