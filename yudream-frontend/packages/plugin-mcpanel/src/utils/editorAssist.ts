/**
 * 文件编辑器输入辅助（纯函数，node --test 可测）：
 * - 按扩展名的缩进宽度（yaml/json 2，其余 4）
 * - Tab / Shift+Tab 的行级缩进与反缩进
 * - 回车自动缩进（继承当前行缩进；`:` `{` `[` 追加一级）
 * - 语法补全候选：语言关键词 + 文档内已有单词，前缀匹配
 */

const INDENT_2_EXT = new Set(['yml', 'yaml', 'json', 'jsonc'])

export function indentUnitsFor(path: string): number {
  const ext = path.split('.').pop()?.toLowerCase() ?? ''
  return INDENT_2_EXT.has(ext) ? 2 : 4
}

function indentOfLine(line: string): string {
  const match = /^[ \t]*/.exec(line)
  return match ? match[0] : ''
}

/** Tab：无选区在光标处插入一级缩进；有选区对覆盖到的行统一加一级。 */
export function indentLine(text: string, selStart: number, selEnd: number, units: number):
{ text: string, caretStart: number, caretEnd: number } {
  const pad = ' '.repeat(units)
  if (selStart === selEnd) {
    return {
      text: text.slice(0, selStart) + pad + text.slice(selStart),
      caretStart: selStart + units,
      caretEnd: selStart + units,
    }
  }
  const lines = text.split('\n')
  let offset = 0
  const spans: Array<{ index: number, start: number, end: number }> = []
  for (let i = 0; i < lines.length; i++) {
    const start = offset
    const end = offset + lines[i].length
    spans.push({ index: i, start, end })
    offset = end + 1
  }
  const first = (spans.find(s => selStart <= s.end) ?? spans[0]).index
  const last = (spans.find(s => selEnd - 1 <= s.end) ?? spans[spans.length - 1]).index
  let firstDelta = 0
  let totalDelta = 0
  for (let i = first; i <= last; i++) {
    lines[i] = pad + lines[i]
    if (i === first) {
      firstDelta = units
    }
    totalDelta += units
  }
  return { text: lines.join('\n'), caretStart: selStart + firstDelta, caretEnd: selEnd + totalDelta }
}

/** Shift+Tab：对选区（或光标所在行）移除最多一级行首空白。 */
export function outdentLine(text: string, selStart: number, selEnd: number, units: number):
{ text: string, caretStart: number, caretEnd: number } {
  const lines = text.split('\n')
  let offset = 0
  const spans: Array<{ index: number, start: number, end: number }> = []
  for (let i = 0; i < lines.length; i++) {
    const start = offset
    const end = offset + lines[i].length
    spans.push({ index: i, start, end })
    offset = end + 1
  }
  const firstLine = spans.find(s => selStart <= s.end) ?? spans[spans.length - 1]
  const lastLine = [...spans].reverse().find(s => s.end >= selEnd) ?? firstLine
  const first = firstLine.index
  const last = selEnd > selStart ? (lastLine ? lastLine.index : first) : first
  let firstDelta = 0
  let totalDelta = 0
  for (let i = first; i <= last; i++) {
    const line = lines[i]
    const cut = Math.min(units, indentOfLine(line).length)
    if (cut > 0) {
      lines[i] = line.slice(cut)
      const delta = -cut
      if (i === first) {
        firstDelta = delta
      }
      totalDelta += delta
    }
  }
  const clamp = (value: number, min: number) => Math.max(min, value)
  return { text: lines.join('\n'), caretStart: clamp(selStart + firstDelta, spans[first].start), caretEnd: clamp(selEnd + totalDelta, 0) }
}

/** 回车：换行后继承当前行缩进；行尾为 `:`/`{`/`[` 时追加一级。 */
export function newlineIndent(text: string, caret: number, units: number):
{ text: string, caret: number } {
  const lineStart = text.lastIndexOf('\n', Math.max(0, caret - 1)) + 1
  const line = text.slice(lineStart, caret)
  const base = indentOfLine(line)
  const trimmed = line.trimEnd()
  const deeper = /[:[{]\s*$/.test(trimmed) || /-\s+$/.test(trimmed)
  const insert = `\n${base}${deeper ? ' '.repeat(units) : ''}`
  return { text: text.slice(0, caret) + insert + text.slice(caret), caret: caret + insert.length }
}

const LANGUAGE_KEYWORDS: Record<string, string[]> = {
  properties: [
    'allow-flight', 'allow-nether', 'difficulty', 'enable-command-block', 'enable-query',
    'enable-rcon', 'enable-status', 'enforce-secure-profile', 'enforce-whitelist',
    'gamemode', 'generate-structures', 'hardcore', 'level-name', 'level-seed', 'level-type',
    'max-players', 'max-tick-time', 'max-world-size', 'motd', 'online-mode',
    'op-permission-level', 'player-idle-timeout', 'prevent-proxy-connections', 'pvp',
    'rcon.password', 'rcon.port', 'server-ip', 'server-port', 'simulation-distance',
    'spawn-monsters', 'spawn-protection', 'snooper-enabled', 'sync-chunk-writes',
    'texture-pack', 'view-distance', 'white-list',
  ],
  yaml: ['true', 'false', 'null', 'yes', 'no', 'on', 'off'],
  json: ['true', 'false', 'null'],
}

function languageKey(path: string): string | null {
  const ext = path.split('.').pop()?.toLowerCase() ?? ''
  if (ext === 'yml' || ext === 'yaml') {
    return 'yaml'
  }
  if (ext === 'json' || ext === 'jsonc') {
    return 'json'
  }
  if (ext === 'properties' || ext === 'conf' || ext === 'ini' || ext === 'cfg') {
    return 'properties'
  }
  return null
}

/** 光标前的进行中单词（≥2 字符才有补全价值）。 */
export function wordBefore(text: string, caret: number): { word: string, start: number } | null {
  const before = text.slice(0, caret)
  const match = /[A-Za-z0-9_.-]{2,}$/.exec(before)
  if (!match) {
    return null
  }
  return { word: match[0], start: caret - match[0].length }
}

/** 补全候选：语言关键词 + 文档内已有单词（去重、前缀匹配、排除自身），最多 8 条。 */
export function suggest(path: string, text: string, word: string): string[] {
  const key = languageKey(path)
  const lower = word.toLowerCase()
  const candidates: string[] = []
  const seen = new Set<string>([word])
  const push = (candidate: string) => {
    const value = candidate.toLowerCase()
    if (value === lower || !value.startsWith(lower) || seen.has(candidate)) {
      return
    }
    seen.add(candidate)
    candidates.push(candidate)
  }
  for (const keyword of key ? LANGUAGE_KEYWORDS[key] ?? [] : []) {
    push(keyword)
  }
  for (const match of text.matchAll(/[A-Za-z0-9_.-]{3,}/g)) {
    push(match[0])
    if (candidates.length >= 8) {
      return candidates
    }
  }
  return candidates.slice(0, 8)
}
