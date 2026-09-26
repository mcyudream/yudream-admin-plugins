import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { parseAnsi, stripAnsi } from './ansi.ts'

/**
 * ANSI SGR 解析回归：8/16/256/truecolor、样式开关、非 SGR 序列剔除、
 * 截断序列、缓冲 flush 顺序（换样式前旧文本样式不串段）。
 */

describe('parseAnsi', () => {
  it('纯文本返回单片段', () => {
    assert.deepEqual(parseAnsi('hello world'), [{
      text: 'hello world',
      color: null,
      background: null,
      bold: false,
      dim: false,
      italic: false,
      underline: false,
      strike: false,
      inverse: false,
    }])
  })

  it('空输入返回空数组', () => {
    assert.deepEqual(parseAnsi(''), [])
  })

  it('8 色前景着色', () => {
    const spans = parseAnsi('\u001b[32mgreen\u001b[0m plain')
    assert.equal(spans.length, 2)
    assert.equal(spans[0].text, 'green')
    assert.match(spans[0].color ?? '', /^#/)
    assert.equal(spans[1].color, null)
    assert.equal(spans[1].text, ' plain')
  })

  it('换样式前先落盘旧文本（flush 顺序）', () => {
    const spans = parseAnsi('\u001b[31mred\u001b[32mgreen')
    assert.equal(spans.length, 2)
    assert.equal(spans[0].text, 'red')
    assert.notEqual(spans[0].color, spans[1].color)
    assert.equal(spans[1].text, 'green')
  })

  it('bold / underline / strike / inverse 样式位', () => {
    const spans = parseAnsi('\u001b[1;4;9;7mx\u001b[22;24;29;27my')
    assert.equal(spans[0].bold, true)
    assert.equal(spans[0].underline, true)
    assert.equal(spans[0].strike, true)
    assert.equal(spans[0].inverse, true)
    assert.equal(spans[1].bold, false)
    assert.equal(spans[1].underline, false)
    assert.equal(spans[1].strike, false)
    assert.equal(spans[1].inverse, false)
  })

  it('亮色系 90-97 / 100-107', () => {
    const spans = parseAnsi('\u001b[90mfg\u001b[0m \u001b[103mbg')
    assert.equal(spans[0].color, '#55627a')
    assert.equal(spans[2].background, '#966c1e')
  })

  it('256 色与 truecolor', () => {
    const spans = parseAnsi('\u001b[38;5;196ma\u001b[0m\u001b[48;5;21mb\u001b[0m\u001b[38;2;12;34;56mc')
    assert.equal(spans[0].color, '#ff0000')
    assert.ok(spans[1].background?.startsWith('#'))
    assert.equal(spans[2].color, '#0c2238')
  })

  it('非 SGR 的 CSI 序列被剔除（光标移动）', () => {
    assert.equal(stripAnsi('\u001b[2K\u001b[1Gabc\u001b[0J'), 'abc')
  })

  it('OSC 序列（标题/BEL、ST 结尾）被剔除', () => {
    assert.equal(stripAnsi('\u001b]0;title\u0007text'), 'text')
    assert.equal(stripAnsi('\u001b]8;;http://x\u001b\\link\u001b]8;;\u001b\\'), 'link')
  })

  it('字符串在序列中间截断不抛错', () => {
    assert.doesNotThrow(() => parseAnsi('abc\u001b[3'))
    assert.doesNotThrow(() => parseAnsi('abc\u001b]0;title'))
  })

  it('不完整的 38 参数跳过不死循环', () => {
    assert.equal(stripAnsi('\u001b[38;x'), '')
  })

  it('docker pull 风格的回车进度行：回车符保留由行解析层切分', () => {
    const spans = parseAnsi('layer\u001b[0m')
    assert.equal(spans[0].text, 'layer')
  })
})
