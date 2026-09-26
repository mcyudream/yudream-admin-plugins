import assert from 'node:assert/strict'
import { test } from 'node:test'
import { nextTick, watchEffect } from 'vue'
import { useEditorTabs } from './useEditorTabs.ts'

const b64 = (text: string) => Buffer.from(text, 'utf8').toString('base64')
/** "中文配置" 的 GBK 字节 */
const GBK_SAMPLE = Buffer.from([0xd6, 0xd0, 0xce, 0xc4, 0xc5, 0xe4, 0xd6, 0xc3]).toString('base64')

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

test('首次读取完成会更新响应式内容和加载状态，不需要重试', async () => {
  const read = deferred<{ rawBase64: string }>()
  const editor = useEditorTabs({ load: () => read.promise, save: async () => {} })
  const states: string[] = []
  const stop = watchEffect(() => { states.push(`${editor.active.value?.loading}:${editor.active.value?.text}`) })
  const opening = editor.openFile('server.properties')
  await nextTick()
  read.resolve({ rawBase64: b64('motd=你好') })
  await opening
  await nextTick()
  assert.equal(states.at(-1), 'false:motd=你好')
  stop()
  editor.reset()
})

test('保存期间继续输入，保存基线只更新到提交快照', async () => {
  const write = deferred<void>()
  const editor = useEditorTabs({ load: async () => ({ rawBase64: b64('old') }), save: () => write.promise })
  await editor.openFile('a.txt')
  editor.active.value!.text = 'submitted'
  const saving = editor.save()
  editor.active.value!.text = 'new typing'
  assert.equal(await editor.closeTab('a.txt'), false)
  write.resolve()
  await saving
  assert.equal(editor.active.value!.savedText, 'submitted')
  assert.equal(editor.hasDirty.value, true)
  editor.reset()
})

test('离开检查非活动标签；取消后保留全部文件', async () => {
  const requested: string[][] = []
  const editor = useEditorTabs({ load: async () => ({ rawBase64: b64('') }), save: async () => {},
    confirmDiscard: async names => { requested.push(names); return false } })
  await editor.openFile('dirty.txt')
  editor.active.value!.text = 'unsaved'
  await editor.openFile('clean.txt')
  assert.equal(await editor.confirmLeave(), false)
  assert.deepEqual(requested, [['dirty.txt']])
  assert.equal(editor.tabs.value.length, 2)
  editor.reset()
})

test('旧读取和旧保存完成不能污染重开的文件或触发成功提示', async () => {
  const read = deferred<{ rawBase64: string }>()
  const write = deferred<void>()
  let first = true
  let saved = 0
  const editor = useEditorTabs({ load: () => first ? (first = false, read.promise) : Promise.resolve({ rawBase64: b64('new') }),
    save: () => write.promise, onSaved: () => { saved++ } })
  const oldRead = editor.openFile('a.txt')
  editor.reset()
  await editor.openFile('a.txt')
  read.resolve({ rawBase64: b64('old') })
  await oldRead
  assert.equal(editor.active.value!.text, 'new')
  editor.active.value!.text = 'write'
  const saving = editor.save()
  editor.reset()
  await editor.openFile('a.txt')
  write.resolve()
  await saving
  assert.equal(saved, 0)
  assert.equal(editor.active.value!.text, 'new')
  editor.reset()
})

test('截断预览和保存超出字节限制都不会发送写入', async () => {
  let writes = 0
  const editor = useEditorTabs({ load: async () => ({ rawBase64: b64('preview'), size: 999, truncated: true }), save: async () => { writes++ } })
  await editor.openFile('large.log')
  editor.active.value!.text = 'changed'
  await editor.save()
  assert.equal(writes, 0)
  editor.reset()
})

test('非 UTF-8 文件进入错误态但保留原始字节，可换编码重新打开', async () => {
  const editor = useEditorTabs({ load: async () => ({ rawBase64: GBK_SAMPLE, size: 8 }), save: async () => {} })
  await editor.openFile('gbk.txt')
  const tab = editor.active.value!
  assert.match(tab.error, /UTF-8/)
  assert.equal(tab.rawBase64, GBK_SAMPLE)
  await editor.reopenWithEncoding(tab, 'gbk')
  assert.equal(tab.error, '')
  assert.equal(tab.text, '中文配置')
  assert.equal(tab.encoding, 'gbk')
  assert.equal(editor.hasDirty.value, false)
  editor.reset()
})

test('以编码保存不重新解码正文，编码差异构成待转换脏状态', async () => {
  const saves: Array<{ text: string, charset: string }> = []
  const editor = useEditorTabs({ load: async () => ({ rawBase64: b64('中文配置'), size: 12 }),
    save: async (_path, text, charset) => { saves.push({ text, charset }) } })
  await editor.openFile('a.txt')
  const tab = editor.active.value!
  assert.equal(tab.encoding, 'utf-8')
  assert.equal(editor.hasDirty.value, false)
  // 仅切换保存编码：正文不动，立即成为待转换
  editor.setSaveEncoding(tab, 'gbk')
  assert.equal(tab.text, '中文配置')
  assert.equal(editor.hasDirty.value, true)
  await editor.save()
  assert.deepEqual(saves, [{ text: '中文配置', charset: 'gbk' }])
  assert.equal(editor.hasDirty.value, false)
  assert.equal(tab.savedEncoding, 'gbk')
  editor.reset()
})

test('有未保存修改时切换显示编码需要确认，取消则保留编辑内容', async () => {
  const requested: string[][] = []
  const editor = useEditorTabs({ load: async () => ({ rawBase64: GBK_SAMPLE, size: 8 }), save: async () => {},
    confirmDiscard: async names => { requested.push(names); return false } })
  await editor.openFile('gbk.txt')
  const tab = editor.active.value!
  await editor.reopenWithEncoding(tab, 'gbk')
  tab.text = '改过的内容'
  await editor.reopenWithEncoding(tab, 'big5')
  assert.deepEqual(requested, [['gbk.txt']])
  assert.equal(tab.text, '改过的内容')
  assert.equal(tab.encoding, 'gbk')
  editor.reset()
})
