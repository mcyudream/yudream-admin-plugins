import assert from 'node:assert/strict'
import { test } from 'node:test'

import { indentUnitsFor, indentLine, newlineIndent, outdentLine, suggest, wordBefore } from './editorAssist.ts'

test('indentUnitsFor: yaml/json 2 空格，其余 4 空格', () => {
  assert.equal(indentUnitsFor('server.yml'), 2)
  assert.equal(indentUnitsFor('config.JSON'), 2)
  assert.equal(indentUnitsFor('server.properties'), 4)
  assert.equal(indentUnitsFor('notes.txt'), 4)
})

test('indentLine: 无选区插入，有选区整行缩进', () => {
  const single = indentLine('a\nb', 2, 2, 4)
  assert.equal(single.text, 'a\n    b')
  assert.deepEqual([single.caretStart, single.caretEnd], [6, 6])

  const multi = indentLine('a\nb\nc', 2, 5, 2)
  assert.equal(multi.text, 'a\n  b\n  c')
  assert.deepEqual([multi.caretStart, multi.caretEnd], [4, 9])
})

test('outdentLine: 最多移除一级行首空白', () => {
  const result = outdentLine('    a\n      b', 0, 12, 2)
  assert.equal(result.text, '  a\n    b')
})

test('newlineIndent: 继承缩进，冒号/大括号追加一级', () => {
  const yaml = newlineIndent('permissions:', 12, 2)
  assert.equal(yaml.text, 'permissions:\n  ')
  assert.equal(yaml.caret, 15)

  // 光标在换行后的空行上：普通换行，不连续加深缩进。
  const afterBreak = newlineIndent('permissions:\n', 13, 2)
  assert.equal(afterBreak.text, 'permissions:\n\n')

  const brace = newlineIndent('{', 1, 2)
  assert.equal(brace.text, '{\n  ')

  const plain = newlineIndent('hello\nworld', 11, 4)
  assert.equal(plain.text, 'hello\nworld\n')
  assert.equal(plain.caret, 12)
})

test('suggest: properties 关键词 + 文档单词前缀匹配，排除自身', () => {
  const items = suggest('server.properties', 'difficulty=easy\ngamemode=survival', 'ga')
  assert.ok(items.includes('gamemode'))
  assert.ok(!items.includes('ga'))
  assert.ok(items.length <= 8)
})

test('suggest: yml 关键词与文档单词混合', () => {
  const items = suggest('bukkit.yml', 'settings:\n  allow-end: true', 'tr')
  assert.ok(items.includes('true'))
})

test('wordBefore: 提取光标前进行中单词，短于 2 字符返回 null', () => {
  assert.deepEqual(wordBefore('gam', 3), { word: 'gam', start: 0 })
  assert.equal(wordBefore('a', 1), null)
  assert.deepEqual(wordBefore('mo', 2), { word: 'mo', start: 0 })
})
