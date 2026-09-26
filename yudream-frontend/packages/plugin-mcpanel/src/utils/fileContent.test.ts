import assert from 'node:assert/strict'
import { test } from 'node:test'

import {
  decodeBase64ToBytes,
  decodeBase64Utf8,
  decodeBase64Utf8Lenient,
  encodeUtf8ToBase64,
  eulaAcceptedFromText,
  exceedsEditorLimit,
  looksLikeBinary,
  utf8ByteLength,
  withTimeout,
  EDITOR_TEXT_LIMIT_BYTES,
} from './fileContent.ts'

test('decodeBase64Utf8: 标准 base64 解码为 UTF-8 文本', () => {
  const text = '# spigot 配置'
  const encoded = Buffer.from(text, 'utf-8').toString('base64')
  assert.equal(decodeBase64Utf8(encoded), text)
})

test('decodeBase64Utf8: 空内容返回空串（允许空文件保存）', () => {
  assert.equal(decodeBase64Utf8(''), '')
})

test('decodeBase64Utf8: 严格解码——非法 UTF-8 序列抛出而不是静默替换', () => {
  const invalid = Buffer.from([0x61, 0xff, 0x62]).toString('base64')
  assert.throws(() => decodeBase64Utf8(invalid))
})

test('decodeBase64ToBytes: 与 base64 编码互逆', () => {
  const bytes = new Uint8Array([0, 1, 254, 255])
  const encoded = Buffer.from(bytes).toString('base64')
  assert.deepEqual(Array.from(decodeBase64ToBytes(encoded)), Array.from(bytes))
})

test('encodeUtf8ToBase64: 与解码互逆', () => {
  const text = '中文 + ascii 混合内容'
  const roundTrip = decodeBase64Utf8(encodeUtf8ToBase64(text))
  assert.equal(roundTrip, text)
})

test('utf8ByteLength: 中文按 3 字节计', () => {
  assert.equal(utf8ByteLength('abc中'), 6)
})

test('exceedsEditorLimit: 基于原始 size 判断 96KiB 上限', () => {
  assert.equal(exceedsEditorLimit(EDITOR_TEXT_LIMIT_BYTES), false)
  assert.equal(exceedsEditorLimit(EDITOR_TEXT_LIMIT_BYTES + 1), true)
  assert.equal(exceedsEditorLimit('100'), false)
  assert.equal(exceedsEditorLimit('999999'), true)
  assert.equal(exceedsEditorLimit(undefined), false)
  assert.equal(exceedsEditorLimit(null), false)
  assert.equal(exceedsEditorLimit('not-a-number'), false)
})

test('withTimeout: 竞争超时抛出可读错误', async () => {
  const slow = new Promise(resolve => setTimeout(resolve, 200))
  await assert.rejects(
    () => withTimeout(slow, 30, '节点响应超时'),
    /节点响应超时/,
  )
})

test('withTimeout: 先完成则透传结果', async () => {
  const fast = Promise.resolve(42)
  assert.equal(await withTimeout(fast, 1000, '不该超时'), 42)
})

test('looksLikeBinary: 文本返回 false，含 NUL 返回 true', () => {
  assert.equal(looksLikeBinary('hello world' + nl() + 'plain text'), false)
  assert.equal(looksLikeBinary('PK' + nulChar() + 'u0003 binary jar'), true)
})

test('looksLikeBinary: 高占比控制字符判定为二进制', () => {
  let gibberish = ''
  for (let i = 0; i < 100; i++) {
    gibberish += String.fromCharCode(1 + (i % 7))
  }
  assert.equal(looksLikeBinary(gibberish), true)
})

test('decodeBase64Utf8Lenient: 中文可解，且非法 UTF-8 不抛错', () => {
  const text = '# 由面板写入' + nl() + 'eula=true'
  assert.equal(decodeBase64Utf8Lenient(encodeUtf8ToBase64(text)), text)
  // “e” + 非法字节 0xff + “=true”：严格解码会抛，宽松解码替换为 U+FFFD 后 rest 仍可读。
  const invalid = Buffer.from([0x65, 0xff, 0x3d, 0x74, 0x72, 0x75, 0x65]).toString('base64')
  assert.equal(decodeBase64Utf8Lenient(invalid), `e${String.fromCharCode(0xfffd)}=true`)
  assert.equal(decodeBase64Utf8Lenient(''), '')
})

test('eulaAcceptedFromText: 真实 eula.txt 判定为已同意', () => {
  const text = [
    '#By changing the setting below to TRUE you are indicating your agreement to our EULA.',
    '#Mon Sep 22 10:00:00 CST 2026',
    'eula=true',
    '',
  ].join(nl())
  assert.equal(eulaAcceptedFromText(text), true)
})

test('eulaAcceptedFromText: 大小写与空格等价，false 不算同意', () => {
  assert.equal(eulaAcceptedFromText('  eula = TRUE  '), true)
  assert.equal(eulaAcceptedFromText('eula=false'), false)
  assert.equal(eulaAcceptedFromText('eula='), false)
})

test('eulaAcceptedFromText: 注释行忽略，同名键后者生效', () => {
  assert.equal(eulaAcceptedFromText('# eula=true'), false)
  assert.equal(eulaAcceptedFromText('eula=true' + nl() + 'eula=false'), false)
  assert.equal(eulaAcceptedFromText('eula=false' + nl() + 'eula=true'), true)
})

test('eulaAcceptedFromText: CRLF、空内容与其他键不影响判定', () => {
  assert.equal(eulaAcceptedFromText('server-port=25565\r\neula=true\r\n'), true)
  assert.equal(eulaAcceptedFromText(''), false)
  assert.equal(eulaAcceptedFromText('eula-extra=true'), false)
})

function nl() {
  return String.fromCharCode(10)
}

function nulChar() {
  return String.fromCharCode(0)
}
