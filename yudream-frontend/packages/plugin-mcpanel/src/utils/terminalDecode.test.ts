import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createTerminalDecoder } from './terminalDecode.ts'

/** "中文配置" 的 GBK 字节 */
const GBK = Buffer.from([0xd6, 0xd0, 0xce, 0xc4, 0xc5, 0xe4, 0xd6, 0xc3]).toString('base64')

test('data 优先按当前编码解码，缺失时回退 text', () => {
  const decoder = createTerminalDecoder(() => 'gbk')
  assert.equal(decoder.decodeFrame(GBK, ''), '中文配置')
  assert.equal(decoder.decodeFrame(undefined, '兜底文本'), '兜底文本')
  assert.equal(decoder.decodeFrame('', 'x'), 'x')
})

test('跨帧半个多字节字符由流式解码保持完整', () => {
  const decoder = createTerminalDecoder(() => 'utf-8')
  const bytes = Buffer.from('中文', 'utf8')
  const part1 = bytes.subarray(0, 4).toString('base64') // 中 + 文的第一个字节
  const part2 = bytes.subarray(4).toString('base64')
  assert.equal(decoder.decodeFrame(part1, ''), '中')
  assert.equal(decoder.decodeFrame(part2, ''), '文')
})

test('切换编码后后续帧按新编码解码，reset 丢弃残留状态', () => {
  let encoding = 'utf-8'
  const decoder = createTerminalDecoder(() => encoding)
  assert.equal(decoder.decodeFrame(Buffer.from('ok').toString('base64'), ''), 'ok')
  encoding = 'gbk'
  decoder.reset()
  assert.equal(decoder.decodeFrame(GBK, ''), '中文配置')
})

test('非法字节不中断流，以替换符呈现', () => {
  const decoder = createTerminalDecoder(() => 'utf-8')
  const bad = Buffer.from([0xff, 0x61]).toString('base64')
  assert.equal(decoder.decodeFrame(bad, ''), '�a')
})

test('历史快照单块解码，截断尾部宽松处理', () => {
  const decoder = createTerminalDecoder(() => 'utf-8')
  const bytes = Buffer.from('中文', 'utf8')
  const partial = bytes.subarray(0, 4).toString('base64')
  assert.equal(decoder.decodeSnapshot(partial, ''), '中�')
  assert.equal(decoder.decodeSnapshot(undefined, '旧节点文本'), '旧节点文本')
})
