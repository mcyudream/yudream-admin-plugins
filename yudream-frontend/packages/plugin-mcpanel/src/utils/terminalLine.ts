import { parseAnsi, stripAnsi } from './ansi.ts'
import type { AnsiSpan } from './ansi.ts'

/**
 * 终端行模型：实例控制台（MC log4j 日志）与节点终端（shell PTY 输出）共用的
 * 行结构。raw 保留原始文本（含 ANSI）供着色渲染，text 为纯文本供过滤匹配；
 * raw 携带 ANSI 转义时构建期预解析为 spans，渲染层直接着色。
 */
export interface TermLine {
  no: number
  ts: string
  level: string
  text: string
  raw: string
  cls: string
  spans?: AnsiSpan[]
}

/** 级别筛选桶：全部之外提供 信息/警告/错误 三个快速档。 */
export type LevelBucket = 'all' | 'info' | 'warn' | 'error'

/**
 * MC 服务端日志行解析：剥掉 docker timestamps 与 log4j 双时间戳前缀，
 * 抽出 [HH:mm:ss LEVEL]，正文单独成列；无前缀行（堆栈续行等）继承上一级别。
 * 行样例：2026-09-20T09:31:19.516Z 2026-09-20 09:31:19.516Z [12:31:19 INFO]: text
 */
export const MC_LOG_PREFIX = /^(?:\S+Z\s+)?(?:\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d+)?Z?\s+)?\[(\d{2}:\d{2}:\d{2})\s+([A-Za-z]+)\]:\s?/

export function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

export function clockNow(): string {
  const now = new Date()
  return `${pad2(now.getHours())}:${pad2(now.getMinutes())}:${pad2(now.getSeconds())}`
}

/** 实例控制台行分类：命令回显 > 错误 > 警告 > 进服/退服 > 噪声 > 信息。 */
export function classifyMcLine(raw: string): string {
  const text = stripAnsi(raw)
  if (/^\s*>\s?/.test(text)) {
    return 'is-cmd'
  }
  if (/\bERROR\b|\bFATAL\b|Unable to access|Exception/i.test(text)) {
    return 'is-error'
  }
  if (/\bWARN(?:ING)?\b/i.test(text)) {
    return 'is-warn'
  }
  if (/logged in|joined the game|UUID of player|欢迎/i.test(text)) {
    return 'is-join'
  }
  if (/^\s*(<\?xml|<!DOCTYPE|<html|<\/)/i.test(text)) {
    return 'is-muted'
  }
  return 'is-info'
}

/** 节点 shell 行分类：先剥 ANSI 再按关键词与提示符形态归类。 */
export function classifyShellLine(raw: string): string {
  const text = stripAnsi(raw)
  if (/error|fail|denied|not found|permission/i.test(text)) {
    return 'is-error'
  }
  if (/warn/i.test(text)) {
    return 'is-warn'
  }
  if (/^\s*(?:root@|[\w.-]+@|[$#]\s|\u276f)/.test(text)) {
    return 'is-cmd'
  }
  return 'is-info'
}

export interface ParsedLine {
  ts: string
  level: string
  text: string
}

/** 解析 MC 日志前缀；无前缀的行落到 shell 式分类，时间取当前时钟。 */
export function parseConsoleLine(raw: string, mode: 'mc' | 'shell', fallback?: string): ParsedLine {
  const clean = raw.replace(/\r$/, '')
  if (mode === 'mc') {
    const match = clean.match(MC_LOG_PREFIX)
    return {
      ts: match?.[1] ?? fallback ?? clockNow(),
      level: match?.[2]?.toUpperCase() ?? 'LOG',
      text: stripAnsi(match ? clean.slice(match[0].length) : clean),
    }
  }
  const plain = stripAnsi(clean)
  const cls = classifyShellLine(clean)
  return {
    ts: fallback ?? clockNow(),
    level: cls === 'is-error' ? 'ERROR' : cls === 'is-warn' ? 'WARN' : cls === 'is-cmd' ? 'CMD' : 'LOG',
    text: plain,
  }
}

/** 行的样式类：MC 行用全文分类；shell 行用解析期的分类结果（parseConsoleLine 已定级）。 */
export function lineClass(raw: string, mode: 'mc' | 'shell'): string {
  return mode === 'mc' ? classifyMcLine(raw) : classifyShellLine(raw)
}

/** 级别标签 → 筛选桶；FATAL 并入错误，DEBUG/TRACE 并入信息。 */
export function levelBucket(level: string): LevelBucket {
  const value = level.toUpperCase()
  if (value === 'ERROR' || value === 'FATAL' || value === 'CRITICAL') {
    return 'error'
  }
  if (value === 'WARN' || value === 'WARNING') {
    return 'warn'
  }
  return 'info'
}

export function buildTermLine(no: number, raw: string, mode: 'mc' | 'shell', fallbackClock?: string): TermLine {
  const parsed = parseConsoleLine(raw, mode, fallbackClock)
  const line: TermLine = {
    no,
    ts: parsed.ts,
    level: parsed.level,
    text: parsed.text,
    raw,
    cls: lineClass(raw, mode),
  }
  if (raw.includes('\u001b')) {
    line.spans = parseAnsi(raw)
  }
  return line
}
