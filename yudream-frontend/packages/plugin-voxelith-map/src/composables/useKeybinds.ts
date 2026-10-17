import { onBeforeUnmount, onMounted, reactive, readonly } from 'vue'

/**
 * 查看器键位自定义。
 *
 * vendored 的飞行/第一人称控制器把物理键硬编码为 e.code（KeyW/KeyS/KeyA/KeyD/Space）且
 * 监听挂在 window 冒泡阶段，vendor 只读不可改。于是在插件侧做「事件重映射」：
 *
 *   window 捕获阶段拦截被用户改过的自定义键 → stopPropagation 切断传播
 *   → 伪造一个对应标准键的 KeyboardEvent 再 dispatch，vendor 收到的仍是它认识的 code。
 *
 * 合成事件会再次经过捕获监听器，但合成 code 是标准键、不在重映射表里，直接放行，不会递归。
 * 键位存 localStorage（voxelith-map.keybinds.v1），跨会话保留。
 */

export const KEYBIND_ACTIONS = ['forward', 'back', 'left', 'right', 'up'] as const

export type KeybindAction = (typeof KEYBIND_ACTIONS)[number]

export const KEYBIND_ACTION_LABELS: Record<KeybindAction, string> = {
  forward: '前进',
  back: '后退',
  left: '左移',
  right: '右移',
  up: '上升 / 跳跃',
}

/** vendor 控制器认识的标准物理键（e.code）。 */
const STANDARD_CODES: Record<KeybindAction, string> = {
  forward: 'KeyW',
  back: 'KeyS',
  left: 'KeyA',
  right: 'KeyD',
  up: 'Space',
}

const STANDARD_KEYS: Record<string, string> = {
  KeyW: 'w',
  KeyS: 's',
  KeyA: 'a',
  KeyD: 'd',
  Space: ' ',
}

const STORAGE_KEY = 'voxelith-map.keybinds.v1'

type KeybindMap = Record<KeybindAction, string>

function defaults(): KeybindMap {
  return { ...STANDARD_CODES }
}

function isKeybindAction(value: string): value is KeybindAction {
  return (KEYBIND_ACTIONS as readonly string[]).includes(value)
}

function loadSaved(): Partial<KeybindMap> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as Record<string, unknown>
    const out: Partial<KeybindMap> = {}
    for (const [action, code] of Object.entries(parsed)) {
      if (isKeybindAction(action) && typeof code === 'string' && code) {
        out[action] = code
      }
    }
    return out
  }
  catch {
    return {}
  }
}

/** 展示用：把 e.code 翻成人话（KeyW → W，Space → Space，ArrowUp → ↑）。 */
export function prettyCode(code: string): string {
  if (code.startsWith('Key')) return code.slice(3)
  if (code.startsWith('Digit')) return code.slice(5)
  const named: Record<string, string> = {
    Space: 'Space',
    ArrowUp: '↑',
    ArrowDown: '↓',
    ArrowLeft: '←',
    ArrowRight: '→',
    ShiftLeft: 'Shift',
    ShiftRight: 'Shift',
    ControlLeft: 'Ctrl',
    ControlRight: 'Ctrl',
    AltLeft: 'Alt',
    AltRight: 'Alt',
  }
  return named[code] ?? code
}

export function useKeybinds() {
  const binds = reactive<KeybindMap>({ ...defaults(), ...loadSaved() })

  /** 正在录制哪个动作（点「改键」后等下一次按键）；null = 未在录制。 */
  const recording = reactive<{ action: KeybindAction | null }>({ action: null })

  /** 物理键 → 动作。code 与标准键相同的映射不收（vendor 原生就认，无需翻译）。 */
  function remapByCode(): Map<string, KeybindAction> {
    const map = new Map<string, KeybindAction>()
    for (const action of KEYBIND_ACTIONS) {
      const code = binds[action]
      if (code && code !== STANDARD_CODES[action]) {
        map.set(code, action)
      }
    }
    return map
  }

  function onKeyEvent(type: 'keydown' | 'keyup'): (e: KeyboardEvent) => void {
    return (e) => {
      // 录制模式：吃掉这次按键，绑定给正在录制的动作
      if (recording.action) {
        e.stopPropagation()
        e.preventDefault()
        if (e.code !== 'Escape') {
          binds[recording.action] = e.code
        }
        recording.action = null
        persist()
        return
      }
      const action = remapByCode().get(e.code)
      if (!action) return
      // 自定义键：拦截原始事件，转投 vendor 认识的标准键
      e.stopImmediatePropagation()
      e.preventDefault()
      const standard = STANDARD_CODES[action]
      window.dispatchEvent(new KeyboardEvent(type, {
        code: standard,
        key: STANDARD_KEYS[standard] ?? standard,
        bubbles: true,
        cancelable: true,
      }))
    }
  }

  let onKeyDown: ((e: KeyboardEvent) => void) | null = null
  let onKeyUp: ((e: KeyboardEvent) => void) | null = null

  onMounted(() => {
    onKeyDown = onKeyEvent('keydown')
    onKeyUp = onKeyEvent('keyup')
    // 捕获阶段先于 vendor 的 window 冒泡监听；不捕获的话 vendor 可能已经先处理
    window.addEventListener('keydown', onKeyDown, true)
    window.addEventListener('keyup', onKeyUp, true)
  })

  onBeforeUnmount(() => {
    if (onKeyDown) window.removeEventListener('keydown', onKeyDown, true)
    if (onKeyUp) window.removeEventListener('keyup', onKeyUp, true)
  })

  function persist(): void {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(binds))
    }
    catch {
      // 隐私模式等存不进去就算了：本次会话内依然生效
    }
  }

  function resetDefaults(): void {
    Object.assign(binds, defaults())
    persist()
  }

  return {
    binds: readonly(binds),
    recording,
    startRecording: (action: KeybindAction) => { recording.action = action },
    resetDefaults,
  }
}
