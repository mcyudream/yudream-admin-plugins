import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createTextFileAccess, decodeRawText, decodeTextChunk, readRawChunk } from './textFileAccess.ts'

const b64 = (text: string) => Buffer.from(text).toString('base64')
test('空文件安全打开，中文按原始字节计算', () => {
  assert.deepEqual(readRawChunk({ content: '', size: 0, eof: true }), { rawBase64: '', size: 0, truncated: false })
  assert.equal(decodeTextChunk({ content: b64('中文'), size: '6', eof: true }).text, '中文')
})
test('截断元数据优先于解码后的文本长度', () => {
  assert.equal(readRawChunk({ content: b64('abc'), size: 999, eof: false }).truncated, true)
  const partial = Buffer.from('中文').subarray(0, 4).toString('base64')
  assert.equal(decodeTextChunk({ content: partial, size: 6, eof: false }).text, '中')
})
test('拒绝二进制、非法UTF8、缺失内容和缺失大小', () => {
  assert.throws(() => decodeTextChunk({ content: b64('a\0b'), size: 3 }), /二进制/)
  assert.throws(() => decodeTextChunk({ content: '/w==', size: 1, eof: true }), /UTF-8/)
  assert.throws(() => readRawChunk({ size: 2 }), /内容/)
  assert.throws(() => readRawChunk({ content: '' }), /大小/)
})
test('GBK 字节可切换编码解码，UTF-8 严格模式仍拒绝', () => {
  const gbkBytes = Buffer.from('中文配置', 'utf8')
  // 手工构造 GBK 字节：用面板等价物——这里直接用已知 GBK 编码
  const gbk = Buffer.from([0xd6, 0xd0, 0xce, 0xc4, 0xc5, 0xe4, 0xd6, 0xc3]) // "中文配置" 的 GBK
  assert.equal(gbk.length, 8)
  const raw = readRawChunk({ content: gbk.toString('base64'), size: 8, eof: true })
  assert.throws(() => decodeRawText(raw, 'utf-8'), /UTF-8/)
  assert.equal(decodeRawText(raw, 'gbk'), '中文配置')
  assert.equal(decodeRawText(raw, 'GB18030'), '中文配置')
  assert.equal(gbkBytes.length, 12)
})
test('读取只有一个有界分块请求，返回原始字节由标签层解码', async () => {
  let reads = 0
  const api = createTextFileAccess({ readChunk: async () => { reads++; return { content: b64('ok'), size: 2, eof: true } }, write: async () => {} })
  const raw = await api.load('a.txt')
  assert.equal(decodeRawText(raw, 'utf-8'), 'ok')
  assert.equal(reads, 1)
})
test('保存透传目标编码，UTF-8 为默认', async () => {
  const writes: Array<{ content: string, charset: string }> = []
  const api = createTextFileAccess({ readChunk: async () => ({}),
    write: async (_path, content, charset) => { writes.push({ content, charset }) } })
  await api.save('a.txt', 'x')
  await api.save('a.txt', '中文', 'gbk')
  assert.equal(writes.length, 2)
  assert.equal(writes[0]!.charset, 'utf-8')
  assert.equal(writes[1]!.charset, 'gbk')
  assert.equal(Buffer.from(writes[1]!.content, 'base64').toString('utf8'), '中文')
})
test('保存超时保留不确定语义，不自动重试', async () => {
  let writes = 0
  const api = createTextFileAccess({ readChunk: async () => ({}), saveTimeoutMs: 5,
    write: () => { writes++; return new Promise(() => {}) } })
  await assert.rejects(api.save('a.txt', 'x'), /保存结果未确认/)
  assert.equal(writes, 1)
})
