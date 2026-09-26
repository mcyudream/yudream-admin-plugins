import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { buildTermLine, classifyMcLine, levelBucket, MC_LOG_PREFIX, parseConsoleLine } from './terminalLine.ts'

/**
 * 终端行解析回归：MC log4j 前缀剥离、shell 关键词分级、ANSI 剥离后
 * 过滤文本、级别筛选桶归类。
 */

describe('parseConsoleLine', () => {
  it('剥离 docker 与 log4j 双时间戳前缀', () => {
    const parsed = parseConsoleLine(
      '2026-09-20T09:31:19.516Z 2026-09-20 09:31:19.516Z [12:31:19 INFO]: Done (3.1s)! For help, type "help"',
      'mc',
    )
    assert.equal(parsed.ts, '12:31:19')
    assert.equal(parsed.level, 'INFO')
    assert.equal(parsed.text, 'Done (3.1s)! For help, type "help"')
  })

  it('无前缀堆栈续行落到 LOG 且时间回退', () => {
    const parsed = parseConsoleLine('\tat java.base@21/java.lang.Thread.run(Thread.java:1583)', 'mc', '08:00:01')
    assert.equal(parsed.ts, '08:00:01')
    assert.equal(parsed.level, 'LOG')
    assert.equal(parsed.text, '\tat java.base@21/java.lang.Thread.run(Thread.java:1583)')
  })

  it('shell 行剥离 ANSI 颜色后作为过滤文本', () => {
    const parsed = parseConsoleLine('\u001b[01;32mtarget\u001b[0m -> \u001b[01;35mlink\u001b[0m', 'shell')
    assert.equal(parsed.text, 'target -> link')
    assert.equal(parsed.level, 'LOG')
  })

  it('shell 错误/警告/提示符分级', () => {
    assert.equal(parseConsoleLine('rm: cannot remove: Permission denied', 'shell').level, 'ERROR')
    assert.equal(parseConsoleLine('warning: deprecated option', 'shell').level, 'WARN')
    assert.equal(parseConsoleLine('root@yudream:~# ls', 'shell').level, 'CMD')
  })

  it('带 ANSI 的 shell 错误行仍能识别（先剥码再匹配）', () => {
    const parsed = parseConsoleLine('\u001b[31mError:\u001b[0m connection refused', 'shell')
    assert.equal(parsed.level, 'ERROR')
  })
})

describe('classifyMcLine', () => {
  it('命令回显、错误、警告、进服、噪声、信息', () => {
    assert.equal(classifyMcLine('> list'), 'is-cmd')
    assert.equal(classifyMcLine('[12:00:00 ERROR]: Failed'), 'is-error')
    assert.equal(classifyMcLine('java.lang.RuntimeException: boom'), 'is-error')
    assert.equal(classifyMcLine('[12:00:00 WARN]: lag'), 'is-warn')
    assert.equal(classifyMcLine('Steve joined the game'), 'is-join')
    assert.equal(classifyMcLine('</html>'), 'is-muted')
    assert.equal(classifyMcLine('Starting minecraft server'), 'is-info')
  })
})

describe('MC_LOG_PREFIX', () => {
  it('裸 log4j 前缀也命中', () => {
    const match = '[21:02:03 WARN]: x'.match(MC_LOG_PREFIX)
    assert.equal(match?.[1], '21:02:03')
    assert.equal(match?.[2], 'WARN')
  })
})

describe('levelBucket', () => {
  it('FATAL 归错误，WARNING 归警告，其余归信息', () => {
    assert.equal(levelBucket('FATAL'), 'error')
    assert.equal(levelBucket('ERROR'), 'error')
    assert.equal(levelBucket('WARNING'), 'warn')
    assert.equal(levelBucket('WARN'), 'warn')
    assert.equal(levelBucket('INFO'), 'info')
    assert.equal(levelBucket('DEBUG'), 'info')
    assert.equal(levelBucket('LOG'), 'info')
  })
})

describe('buildTermLine', () => {
  it('raw 保留原文、text 为纯文本', () => {
    const line = buildTermLine(7, '\u001b[33mwarn text\u001b[0m', 'shell')
    assert.equal(line.no, 7)
    assert.ok(line.raw.includes('\u001b[33m'))
    assert.equal(line.text, 'warn text')
    assert.equal(line.cls, 'is-warn')
    assert.equal(line.level, 'WARN')
  })
})
