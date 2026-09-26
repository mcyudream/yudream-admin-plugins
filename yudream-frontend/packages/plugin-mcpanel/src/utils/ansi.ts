/**
 * ANSI 转义序列解析：节点 shell（bash 彩色 ls/PS1）与部分 MC 服务端日志会携带
 * SGR 颜色码，直接按纯文本渲染会出现 `32m` 残渣。这里把一段原始文本解析成
 * 带样式的片段（span 列表），由 TerminalConsole 渲染为着色 span。
 *
 * 支持：SGR 0/1/2/3/4/7/9/22/23/24/27/29、前景/背景 8 色（30-37/40-47）、
 * 亮色（90-97/100-107）、256 色（38;5;n / 48;5;n）、truecolor（38;2;r;g;b）。
 * 其余 CSI 序列与 OSC 序列（光标移动、窗口标题、超链接等）一律剔除。
 */

export interface AnsiSpan {
  text: string
  color: string | null
  background: string | null
  bold: boolean
  dim: boolean
  italic: boolean
  underline: boolean
  strike: boolean
  inverse: boolean
}

interface SgrState {
  color: string | null
  background: string | null
  bold: boolean
  dim: boolean
  italic: boolean
  underline: boolean
  strike: boolean
  inverse: boolean
}

const FOREGROUND_8 = [
  '#3d4a5c', '#f25f58', '#5db924', '#e3b341',
  '#4d9ff5', '#c660e7', '#39c5cf', '#c9d1d9',
]

const BACKGROUND_8 = [
  '#161c26', '#a1433f', '#2d5a1e', '#6e5518',
  '#274f82', '#5c2f70', '#1f5c61', '#58627a',
]

const FOREGROUND_BRIGHT = [
  '#55627a', '#ff7b72', '#7ee787', '#f2cc60',
  '#79c0ff', '#d2a8ff', '#76e3ea', '#e6edf3',
]

const BACKGROUND_BRIGHT = [
  '#30363d', '#c9514d', '#3f8f28', '#966c1e',
  '#316dca', '#81459f', '#2a7f88', '#8b9bb0',
]

function palette(code: number): string {
  if (code >= 30 && code <= 37) {
    return FOREGROUND_8[code - 30]
  }
  if (code >= 90 && code <= 97) {
    return FOREGROUND_BRIGHT[code - 90]
  }
  if (code >= 40 && code <= 47) {
    return BACKGROUND_8[code - 40]
  }
  return BACKGROUND_BRIGHT[code - 100]
}

function initialState(): SgrState {
  return {
    color: null,
    background: null,
    bold: false,
    dim: false,
    italic: false,
    underline: false,
    strike: false,
    inverse: false,
  }
}

/** 256 色板的前 16 档与 xterm 标准序一致，直接复用主题色；高档按 tier 生成稳定色。 */
function color256(index: number): string {
  if (index < 8) {
    return FOREGROUND_8[index]
  }
  if (index < 16) {
    return FOREGROUND_BRIGHT[index - 8]
  }
  const cube = index - 16
  if (cube < 216) {
    const steps = [0, 95, 135, 175, 215, 255]
    const r = steps[Math.floor(cube / 36)]
    const g = steps[Math.floor((cube % 36) / 6)]
    const b = steps[cube % 6]
    return `#${hex(r)}${hex(g)}${hex(b)}`
  }
  const gray = 8 + Math.round(((cube - 216) / 23) * 46)
  return `#${hex(gray)}${hex(gray)}${hex(gray)}`
}

function hex(value: number): string {
  return value.toString(16).padStart(2, '0')
}

function truecolor(r: number, g: number, b: number): string {
  return `#${hex(clamp(r))}${hex(clamp(g))}${hex(clamp(b))}`
}

function clamp(value: number): number {
  return Math.max(0, Math.min(255, value))
}

/** 解析一条 SGR 参数序列，原地更新状态；无法识别的参数忽略。 */
function applySgr(state: SgrState, params: number[]): void {
  let index = 0
  while (index < params.length) {
    const code = params[index]
    if (code === 0) {
      Object.assign(state, initialState())
      index += 1
      continue
    }
    if (code === 1) { state.bold = true; index += 1; continue }
    if (code === 2) { state.dim = true; index += 1; continue }
    if (code === 3) { state.italic = true; index += 1; continue }
    if (code === 4) { state.underline = true; index += 1; continue }
    if (code === 7) { state.inverse = true; index += 1; continue }
    if (code === 9) { state.strike = true; index += 1; continue }
    if (code === 22) { state.bold = false; state.dim = false; index += 1; continue }
    if (code === 23) { state.italic = false; index += 1; continue }
    if (code === 24) { state.underline = false; index += 1; continue }
    if (code === 27) { state.inverse = false; index += 1; continue }
    if (code === 29) { state.strike = false; index += 1; continue }
    if ((code >= 30 && code <= 37) || (code >= 90 && code <= 97)) {
      state.color = palette(code)
      index += 1
      continue
    }
    if (code === 39) { state.color = null; index += 1; continue }
    if ((code >= 40 && code <= 47) || (code >= 100 && code <= 107)) {
      state.background = palette(code)
      index += 1
      continue
    }
    if (code === 49) { state.background = null; index += 1; continue }
    if (code === 38 || code === 48) {
      const mode = params[index + 1]
      if (mode === 5 && typeof params[index + 2] === 'number') {
        const value = color256(params[index + 2])
        if (code === 38) state.color = value
        else state.background = value
        index += 3
        continue
      }
      if (mode === 2
        && typeof params[index + 2] === 'number'
        && typeof params[index + 3] === 'number'
        && typeof params[index + 4] === 'number') {
        const value = truecolor(params[index + 2], params[index + 3], params[index + 4])
        if (code === 38) state.color = value
        else state.background = value
        index += 5
        continue
      }
      // 参数不完整：跳过该引导码，避免死循环。
      index += 1
      continue
    }
    index += 1
  }
}

/**
 * 把可能携带 ANSI 转义的原始文本解析为样式片段。
 * 空输入返回空数组；纯文本（无转义）返回单片段。
 */
export function parseAnsi(text: string): AnsiSpan[] {
  if (!text) {
    return []
  }
  const spans: AnsiSpan[] = []
  const state = initialState()
  let buffer = ''
  let index = 0
  const flush = () => {
    if (buffer.length) {
      spans.push({
        text: buffer,
        color: state.color,
        background: state.background,
        bold: state.bold,
        dim: state.dim,
        italic: state.italic,
        underline: state.underline,
        strike: state.strike,
        inverse: state.inverse,
      })
      buffer = ''
    }
  }
  while (index < text.length) {
    const char = text[index]
    if (char !== '\u001b') {
      buffer += char
      index += 1
      continue
    }
    const next = text[index + 1]
    if (next === '[') {
      // CSI：参数字节 0x30-0x3F，中间字节 0x20-0x2F，终止字节 0x40-0x7E。
      const end = index + 2
      let cursor = end
      while (cursor < text.length && /[\x30-\x3f\x20-\x2f]/.test(text[cursor])) {
        cursor += 1
      }
      if (cursor >= text.length) {
        break
      }
      const final = text[cursor]
      const raw = text.slice(end, cursor)
      if (final === 'm') {
        // 先按旧样式落盘缓冲文本，再让新 SGR 生效。
        flush()
        const params = raw.length
          ? raw.split(';').map(item => (/^\d+$/.test(item) ? Number(item) : 0))
          : [0]
        applySgr(state, params)
      }
      index = cursor + 1
      continue
    }
    if (next === ']') {
      // OSC：到 BEL 或 ST（ESC \）为止，整体剔除。
      const bel = text.indexOf('\u0007', index + 2)
      const st = text.indexOf('\u001b\\', index + 2)
      if (bel >= 0 && (st < 0 || bel < st)) {
        index = bel + 1
      }
      else if (st >= 0) {
        index = st + 2
      }
      else {
        break
      }
      flush()
      continue
    }
    if (next === '(' || next === ')' || next === '#') {
      // 字符集选择等：跳过引导符和一个后随字节。
      index += 3
      continue
    }
    // 其他单字节转义（ESC 后接任意字符）：剔除转义本身。
    index += next ? 2 : 1
  }
  flush()
  return spans
}

/** 去除 ANSI 转义，得到纯文本（用于关键词过滤、行分类）。 */
export function stripAnsi(text: string): string {
  return parseAnsi(text).map(span => span.text).join('')
}
