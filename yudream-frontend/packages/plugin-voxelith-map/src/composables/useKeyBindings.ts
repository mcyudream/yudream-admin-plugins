import { onBeforeUnmount, onMounted, reactive } from 'vue'

/**
 * 公开地图页的按键自定义。
 *
 * 移动类动作的键位处理在 vendor 控制器内部（只读，不可改），它们只认一套
 * 固定的 KeyboardEvent.code（KeyW/KeyA/...）。因此重映射的实现方式是：
 * 在 window 的捕获阶段监听按键，用户按下的若是「重新绑定的键」，就拦截原始
 * 事件（preventDefault + stopPropagation）并合成一个对应标准键的
 * KeyboardEvent 派发给 window——vendor 控制器照常收到熟悉的 code。
 * 拦截是必须的：vendor 原生认识 Shift/Ctrl（加速/下降），放行原始键会让
 * 重映射键与 vendor 内建键同时生效（按 Ctrl 加速又触发了原生下降）。
 * 绑定键与标准键相同（如默认 WASD/Space）时不拦截不合成，vendor 原生处理。
 *
 * UI 类动作（呼出界面/切模式/重置视角/标注）由插件层直接消费，不经过 vendor。
 *
 * 键位表持久化在 localStorage（voxelith-keymap），绑定 UI 负责查重。
 */

export interface KeyBindingAction {
  action: string
  label: string
  defaultKey: string
  /** move = 移动类（合成标准键喂给 vendor 控制器）；ui = 界面动作（插件层消费） */
  kind: 'move' | 'ui'
}

export const KEY_ACTIONS: KeyBindingAction[] = [
  { action: 'forward', label: '前进', defaultKey: 'KeyW', kind: 'move' },
  { action: 'back', label: '后退', defaultKey: 'KeyS', kind: 'move' },
  { action: 'left', label: '左移', defaultKey: 'KeyA', kind: 'move' },
  { action: 'right', label: '右移', defaultKey: 'KeyD', kind: 'move' },
  { action: 'up', label: '上升', defaultKey: 'Space', kind: 'move' },
  { action: 'down', label: '下降', defaultKey: 'ShiftLeft', kind: 'move' },
  { action: 'sprint', label: '加速', defaultKey: 'ControlLeft', kind: 'move' },
  { action: 'toggleUi', label: '显示 / 隐藏界面', defaultKey: 'KeyH', kind: 'ui' },
  { action: 'toggleImmersion', label: '沉浸模式（仅保留地图）', defaultKey: 'Backslash', kind: 'ui' },
  { action: 'cycleMode', label: '切换视角模式', defaultKey: 'KeyV', kind: 'ui' },
  { action: 'resetView', label: '重置视角', defaultKey: 'KeyR', kind: 'ui' },
  { action: 'markers', label: '标注图层', defaultKey: 'KeyM', kind: 'ui' },
  { action: 'toggleMap2d', label: '二维地图', defaultKey: 'KeyT', kind: 'ui' },
  { action: 'togglePoiPick', label: '点位标注（起/停）', defaultKey: 'KeyP', kind: 'ui' },
  { action: 'toggleRangePick', label: '范围标注（起/停）', defaultKey: 'KeyZ', kind: 'ui' },
  { action: 'toggleTeleport', label: '坐标传送', defaultKey: 'KeyY', kind: 'ui' },
  { action: 'fullscreen', label: '全屏展示', defaultKey: 'KeyF', kind: 'ui' },
  // 取点三键：Enter 确定、Backspace 撤回上一个点、Esc 取消。
  // 撤回刻意放在 Backspace 上：取点时误触只丢一个角，比整段重来轻得多；
  // Viewer 会 preventDefault，不会真的触发浏览器后退。
  // 取消仍是 Esc，作用域收窄到「仅在取点/标注弹窗进行中」（见 Viewer 的 onAnnotateCancelKey），
  // 不与浏览器全屏退出、弹窗关闭之外的行为叠加。
  { action: 'annotateConfirm', label: '确定标注（完成取点）', defaultKey: 'Enter', kind: 'ui' },
  { action: 'annotateUndo', label: '撤回上一标注点', defaultKey: 'Backspace', kind: 'ui' },
  { action: 'annotateCancel', label: '取消取点', defaultKey: 'Escape', kind: 'ui' },
]

/** 动作 → 控制器原生键的映射（合成事件的目标 code） */
const STANDARD_MOVE_KEYS: Record<string, string> = {
  forward: 'KeyW',
  back: 'KeyS',
  left: 'KeyA',
  right: 'KeyD',
  up: 'Space',
  down: 'KeyC',
  sprint: 'ShiftLeft',
}

/**
 * vendor 控制器原生消费的移动键（FreeFlightControls 内建：WASD/Space 移动、
 * Ctrl 或 C 下降、Shift 加速）。这些键即使没有被用户绑定，也会被 vendor 直认——
 * 例如下降改绑 L-Shift 后按 C，vendor 仍会原生下降。未绑定的原生键一律拦截丢弃。
 */
const NATIVE_VENDOR_CODES = new Set([
  'KeyW', 'KeyS', 'KeyA', 'KeyD', 'Space', 'KeyC', 'ControlLeft', 'ShiftLeft',
])

const STORAGE_KEY = 'voxelith-keymap'

type KeyMap = Record<string, string>

function loadKeymap(): KeyMap {
  const map: KeyMap = {}
  for (const a of KEY_ACTIONS) map[a.action] = a.defaultKey
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const saved = JSON.parse(raw) as KeyMap
      for (const a of KEY_ACTIONS) {
        const value = saved[a.action]
        if (typeof value === 'string' && value) map[a.action] = value
      }
    }
  }
  catch {
    // 存量数据损坏：整表回默认
  }
  return map
}

/** KeyboardEvent.code → 展示名 */
export function describeKey(code: string): string {
  if (!code) return '—'
  const named: Record<string, string> = {
    Space: 'Space',
    ShiftLeft: 'L-Shift',
    ShiftRight: 'R-Shift',
    ControlLeft: 'L-Ctrl',
    ControlRight: 'R-Ctrl',
    AltLeft: 'L-Alt',
    AltRight: 'R-Alt',
    ArrowUp: '↑',
    ArrowDown: '↓',
    ArrowLeft: '←',
    ArrowRight: '→',
    NumpadAdd: 'Num+',
    NumpadSubtract: 'Num-',
    Enter: 'Enter',
    Escape: 'Esc',
    Tab: 'Tab',
    Backquote: '`',
    Minus: '-',
    Equal: '=',
    BracketLeft: '[',
    BracketRight: ']',
    Semicolon: ';',
    Quote: '\'',
    Comma: ',',
    Period: '.',
    Slash: '/',
    Backslash: '\\',
    Insert: 'Ins',
    Home: 'Home',
    Delete: 'Del',
    End: 'End',
    PageUp: 'PgUp',
    PageDown: 'PgDn',
  }
  if (named[code]) return named[code]
  if (code.startsWith('Key')) return code.slice(3)
  if (code.startsWith('Digit')) return code.slice(5)
  if (code.startsWith('Numpad')) return `Num${code.slice(6)}`
  if (/^F\d+$/.test(code)) return code
  return code
}

export function useKeyBindings() {
  const keymap = reactive<KeyMap>(loadKeymap())

  /** 正在等待用户按下新键的动作（改键捕获态） */
  let listeningAction: string | null = null
  let listeningResolve: ((code: string | null, conflict?: string) => void) | null = null

  const actionHandlers = new Map<string, Set<(e: KeyboardEvent) => void>>()

  /** 移动类动作的合成键当前是否处于按下态（keydown 会 auto-repeat，去重） */
  const syntheticDown = new Map<string, boolean>()

  function persist(): void {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(keymap))
    }
    catch {
      // localStorage 不可用时只当次会话生效
    }
  }

  /**
   * 修改某个动作的绑定。
   * @returns 冲突的动作名（绑定时查重）；成功返回 null
   */
  function setBinding(action: string, code: string): string | null {
    if (!code) return null
    const conflict = KEY_ACTIONS.find(a => a.action !== action && keymap[a.action] === code)
    if (conflict) return conflict.label
    keymap[action] = code
    persist()
    return null
  }

  /** 全部恢复默认键位 */
  function resetBindings(): void {
    for (const a of KEY_ACTIONS) keymap[a.action] = a.defaultKey
    persist()
  }

  /** 进入改键捕获态：下一个按下的键成为该动作的新绑定；Esc 取消。
   *  onDone：code=null 表示取消（Esc）；conflict 非空表示新键与现有动作冲突、未应用。 */
  function startListening(
    action: string,
    onDone?: (code: string | null, conflict?: string) => void,
  ): void {
    listeningAction = action
    listeningResolve = onDone ?? null
  }

  function cancelListening(): void {
    const resolve = listeningResolve
    listeningAction = null
    listeningResolve = null
    resolve?.(null)
  }

  /** 注册 UI 动作回调（toggleUi / cycleMode / resetView / markers）。 */
  function onAction(action: string, handler: (e: KeyboardEvent) => void): void {
    let set = actionHandlers.get(action)
    if (!set) {
      set = new Set()
      actionHandlers.set(action, set)
    }
    set.add(handler)
  }

  /** 合成一个标准键事件（vendor 控制器只认这套 code） */
  function dispatchSynthetic(type: 'keydown' | 'keyup', code: string): void {
    window.dispatchEvent(new KeyboardEvent(type, { code, bubbles: true }))
  }

  /** 事件发生在可输入元素上（输入框/文本域/下拉/可编辑区域）：打字不该驱动相机或触发动作。 */
  function isTypingTarget(e: KeyboardEvent): boolean {
    const target = e.target as HTMLElement | null
    if (!target) return false
    const tag = target.tagName
    return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable
  }

  function onKeyDown(e: KeyboardEvent): void {
    // 合成事件也会进入捕获阶段：防重入
    if (!e.isTrusted) return

    // 打字优先：在输入框里按 W/空格/Shift 不该被改键逻辑拦下再合成成移动键
    // （vendor 控制器那边的原生识别由 VoxelithViewer 的捕获守卫一并挡住）
    if (isTypingTarget(e)) return

    // 改键捕获态：这个按键就是新绑定，不再往下传
    if (listeningAction) {
      e.preventDefault()
      e.stopPropagation()
      const action = listeningAction
      const resolve = listeningResolve
      listeningAction = null
      listeningResolve = null
      if (e.code !== 'Escape') {
        const conflict = setBinding(action, e.code)
        resolve?.(e.code, conflict ?? undefined)
      }
      else {
        resolve?.(null)
      }
      return
    }

    // 移动类重映射：按下的键若绑定为非标准键，拦截原始事件并补发标准键事件。
    // 必须拦截：vendor 控制器原生认识 Shift/Ctrl（加速/下降），若放行原始键，
    // 重映射键与 vendor 内建键会同时生效（如按 Ctrl 加速时 vendor 又当它是下降）。
    for (const a of KEY_ACTIONS) {
      if (a.kind !== 'move' || keymap[a.action] !== e.code) continue
      const standard = STANDARD_MOVE_KEYS[a.action]
      if (!standard || standard === e.code) continue
      // keydown 有 auto-repeat，合成键去重（keyup 时清除）
      if (syntheticDown.get(a.action)) continue
      e.preventDefault()
      e.stopPropagation()
      syntheticDown.set(a.action, true)
      dispatchSynthetic('keydown', standard)
      return
    }

    // 未绑定的 vendor 原生键一律拦截：否则像 C（vendor 内建下降）这种键
    // 即使没设置绑定也会触发动作，与键位表不一致
    if (NATIVE_VENDOR_CODES.has(e.code)) {
      const bound = KEY_ACTIONS.some(a => a.kind === 'move' && keymap[a.action] === e.code)
      if (!bound) {
        e.preventDefault()
        e.stopPropagation()
      }
    }
  }

  function onKeyUp(e: KeyboardEvent): void {
    if (!e.isTrusted) return
    for (const a of KEY_ACTIONS) {
      if (a.kind !== 'move' || keymap[a.action] !== e.code) continue
      const standard = STANDARD_MOVE_KEYS[a.action]
      if (!standard || standard === e.code) continue
      e.preventDefault()
      e.stopPropagation()
      syntheticDown.set(a.action, false)
      dispatchSynthetic('keyup', standard)
      return
    }
    // 与 keydown 对称：未绑定的原生键也拦掉 keyup，保持事件成对
    if (NATIVE_VENDOR_CODES.has(e.code)) {
      const bound = KEY_ACTIONS.some(a => a.kind === 'move' && keymap[a.action] === e.code)
      if (!bound) {
        e.preventDefault()
        e.stopPropagation()
      }
    }
  }

  /**
   * 释放所有移动键：向 window 补发全部原生键的 keyup，让 vendor 控制器内部的
   * 按键集合清空。用于「长按被其他操作打断」的场景——弹窗/失焦/prompt 期间
   * keyup 丢失，松键事件到不了 vendor，回落后角色会一直移动。
   */
  function releaseAllKeys(): void {
    syntheticDown.clear()
    for (const code of NATIVE_VENDOR_CODES) {
      dispatchSynthetic('keyup', code)
    }
  }

  function onWindowBlur(): void {
    // 失焦时松键事件收不到：清合成态并补发 keyup，避免「按住 W 切窗口 → 回来后一直前移」
    releaseAllKeys()
  }

  function onVisibilityChange(): void {
    if (document.hidden) releaseAllKeys()
  }

  /** UI 动作分发（冒泡阶段；输入控件聚焦时不触发，避免打字误触）。 */
  function onActionKeyDown(e: KeyboardEvent): void {
    if (!e.isTrusted) return
    const target = e.target as HTMLElement | null
    if (target) {
      const tag = target.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable) {
        return
      }
    }
    for (const a of KEY_ACTIONS) {
      if (a.kind !== 'ui' || keymap[a.action] !== e.code) continue
      const handlers = actionHandlers.get(a.action)
      if (handlers?.size) {
        e.preventDefault()
        for (const handler of handlers) handler(e)
      }
      return
    }
  }

  onMounted(() => {
    window.addEventListener('keydown', onKeyDown, { capture: true })
    window.addEventListener('keyup', onKeyUp, { capture: true })
    window.addEventListener('blur', onWindowBlur)
    document.addEventListener('visibilitychange', onVisibilityChange)
    window.addEventListener('keydown', onActionKeyDown)
  })

  onBeforeUnmount(() => {
    window.removeEventListener('keydown', onKeyDown, { capture: true })
    window.removeEventListener('keyup', onKeyUp, { capture: true })
    window.removeEventListener('blur', onWindowBlur)
    document.removeEventListener('visibilitychange', onVisibilityChange)
    window.removeEventListener('keydown', onActionKeyDown)
  })

  return {
    keymap,
    actions: KEY_ACTIONS,
    setBinding,
    resetBindings,
    startListening,
    cancelListening,
    isListening: () => listeningAction,
    onAction,
    describeKey,
    releaseAllKeys,
  }
}
