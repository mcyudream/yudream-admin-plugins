import { computed, getCurrentScope, onScopeDispose, reactive, ref } from 'vue'
import { EDITOR_DEFAULT_ENCODING, EDITOR_TEXT_LIMIT_BYTES, estimateEncodedByteLength, normalizeEditorEncoding, utf8ByteLength } from '../utils/fileContent.ts'
import { decodeRawText } from '../utils/textFileAccess.ts'
import type { RawFileContent } from '../utils/textFileAccess.ts'
import { errorMessage } from './utils.ts'

export interface EditorLoadResult {
  /** 未解码的原始字节（base64）：切换显示编码不重读，保存转码也以它为准。 */
  rawBase64: string
  size?: number
  truncated?: boolean
}

export interface EditorTab {
  path: string
  name: string
  text: string
  savedText: string
  /** 当前显示/保存编码；savedEncoding 是磁盘字节对应的编码。 */
  encoding: string
  savedEncoding: string
  /** 原始字节（base64），解码失败也保留，供换编码重新打开。 */
  rawBase64: string
  loading: boolean
  saving: boolean
  readOnly: boolean
  truncated: boolean
  size?: number
  error: string
  saveFailed: string
  loadingSeconds: number
}

export interface EditorTabsOptions {
  load: (path: string, hints: { size?: number }) => Promise<EditorLoadResult>
  save: (path: string, text: string, charset: string) => Promise<void>
  canSave?: () => boolean
  onSaved?: (path: string) => void
  onNotify?: (type: 'success' | 'error' | 'warning', message: string) => void
  confirmDiscard?: (names: string[]) => Promise<boolean>
}

export function useEditorTabs(options: EditorTabsOptions) {
  const tabs = ref<EditorTab[]>([])
  const activePath = ref('')
  const active = computed(() => tabs.value.find(tab => tab.path === activePath.value) ?? null)
  const saving = computed(() => tabs.value.some(tab => tab.saving))
  // 脏 = 文本改过的，或保存编码与磁盘字节编码不一致（待转换）。
  const isDirty = (tab: EditorTab) => tab.text !== tab.savedText || tab.encoding !== tab.savedEncoding
  const dirtyCount = computed(() => tabs.value.filter(tab => isDirty(tab)).length)
  const hasDirty = computed(() => dirtyCount.value > 0)
  const timers = new Set<ReturnType<typeof setInterval>>()
  let generation = 0

  function alive(tab: EditorTab, epoch: number) {
    return epoch === generation && tabs.value.includes(tab)
  }

  function applyDecode(tab: EditorTab, encoding: string): boolean {
    const raw: RawFileContent = { rawBase64: tab.rawBase64, size: tab.size ?? 0, truncated: tab.truncated }
    try {
      const text = decodeRawText(raw, encoding)
      tab.text = text
      tab.savedText = text
      tab.encoding = encoding
      tab.savedEncoding = encoding
      tab.error = ''
      tab.saveFailed = ''
      tab.readOnly = !(options.canSave?.() ?? true) || tab.truncated
      return true
    }
    catch (error) {
      tab.error = errorMessage(error, `读取失败：${tab.name}`)
      return false
    }
  }

  async function loadInto(tab: EditorTab) {
    const epoch = generation
    tab.loading = true
    tab.error = ''
    tab.loadingSeconds = 0
    const timer = setInterval(() => { tab.loadingSeconds += 1 }, 1000)
    timers.add(timer)
    try {
      const result = await options.load(tab.path, { size: tab.size })
      if (!alive(tab, epoch)) return
      tab.rawBase64 = result.rawBase64
      tab.size = result.size
      tab.truncated = Boolean(result.truncated)
      applyDecode(tab, tab.encoding)
    }
    catch (error) {
      if (alive(tab, epoch)) tab.error = errorMessage(error, `读取失败：${tab.name}`)
    }
    finally {
      clearInterval(timer)
      timers.delete(timer)
      if (alive(tab, epoch)) tab.loading = false
    }
  }

  async function openFile(path: string, hints?: { size?: number }) {
    const target = path.replace(/^\/+/, '')
    if (!target) return
    const existing = tabs.value.find(tab => tab.path === target)
    if (existing) {
      activePath.value = target
      return
    }
    if (tabs.value.length >= 20) {
      options.onNotify?.('warning', '最多同时打开 20 个文件，请先关闭不需要的标签')
      return
    }
    // 异步读取必须写入响应式对象，不能保留 push 前的原始对象引用。
    const tab = reactive<EditorTab>({
      path: target,
      name: target.split('/').pop() || target,
      text: '', savedText: '', encoding: EDITOR_DEFAULT_ENCODING, savedEncoding: EDITOR_DEFAULT_ENCODING,
      rawBase64: '', loading: true, saving: false,
      readOnly: !(options.canSave?.() ?? true), truncated: false,
      size: hints?.size, error: '', saveFailed: '', loadingSeconds: 0,
    })
    tabs.value.push(tab)
    activePath.value = target
    await loadInto(tab)
  }

  async function confirmDiscard(selected: EditorTab[]): Promise<boolean> {
    if (selected.some(tab => tab.saving)) {
      options.onNotify?.('warning', '文件正在保存，请等待写入结果后再离开')
      return false
    }
    const dirty = selected.filter(tab => isDirty(tab))
    if (!dirty.length) return true
    return options.confirmDiscard ? options.confirmDiscard(dirty.map(tab => tab.name)) : false
  }

  async function retryLoad(tab: EditorTab) {
    if (tab.loading || !tabs.value.includes(tab) || !(await confirmDiscard([tab]))) return
    await loadInto(tab)
  }

  /**
   * 切换显示编码：用保留的原始字节重新解码（不重读文件）。
   * 有未保存修改时需确认——重新解码会丢弃当前编辑内容。
   */
  async function reopenWithEncoding(tab: EditorTab, encoding: string) {
    const label = normalizeEditorEncoding(encoding)
    if (label === tab.encoding && !tab.error) return
    if (isDirty(tab) && !(await confirmDiscard([tab]))) return
    if (!tab.rawBase64) {
      await loadInto(tab)
      return
    }
    if (applyDecode(tab, label)) {
      options.onNotify?.('success', `已以 ${label.toUpperCase()} 重新打开 ${tab.name}`)
    }
  }

  /**
   * 保存时转换编码：不重新解码正文，仅改变保存目标编码；
   * 编码与磁盘不一致即视为待转换（保存按钮可用）。
   */
  function setSaveEncoding(tab: EditorTab, encoding: string) {
    if (tab.error || tab.loading) return
    tab.encoding = normalizeEditorEncoding(encoding)
  }

  async function save(path?: string) {
    const tab = path ? tabs.value.find(item => item.path === path) : active.value
    if (!tab || tab.loading || tab.error || tab.readOnly || saving.value
      || !(options.canSave?.() ?? true) || !isDirty(tab)) return
    const submitted = tab.text
    const charset = tab.encoding
    if (estimateEncodedByteLength(submitted, charset) > EDITOR_TEXT_LIMIT_BYTES) {
      tab.saveFailed = `内容按 ${charset.toUpperCase()} 编码超过 96 KiB 在线保存上限，请使用 SFTP 或下载后编辑`
      options.onNotify?.('error', tab.saveFailed)
      return
    }
    const epoch = generation
    tab.saving = true
    tab.saveFailed = ''
    try {
      await options.save(tab.path, submitted, charset)
      if (!alive(tab, epoch)) return
      // 基线始终对应真正提交的内容；保存期间的新输入仍与此基线比较。
      tab.savedText = submitted
      tab.savedEncoding = charset
      tab.size = charset === 'utf-8' ? utf8ByteLength(submitted) : undefined
      options.onNotify?.('success', tab.text === submitted ? `已保存：${tab.name}` : `已保存上一份内容，${tab.name} 仍有新修改`)
      options.onSaved?.(tab.path)
    }
    catch (error) {
      if (!alive(tab, epoch)) return
      tab.saveFailed = errorMessage(error, `保存失败：${tab.name}`)
      options.onNotify?.('error', tab.saveFailed)
    }
    finally {
      if (alive(tab, epoch)) tab.saving = false
    }
  }

  async function closeTab(path: string): Promise<boolean> {
    const tab = tabs.value.find(item => item.path === path)
    if (!tab || !(await confirmDiscard([tab]))) return false
    const index = tabs.value.indexOf(tab)
    tabs.value = tabs.value.filter(item => item !== tab)
    if (activePath.value === path) {
      activePath.value = tabs.value[Math.max(0, index - 1)]?.path ?? ''
    }
    return true
  }

  function confirmLeave() { return confirmDiscard(tabs.value) }

  async function closeAll() {
    if (!(await confirmLeave())) return false
    reset()
    return true
  }

  function reset() {
    generation += 1
    for (const timer of timers) clearInterval(timer)
    timers.clear()
    tabs.value = []
    activePath.value = ''
  }

  if (getCurrentScope()) onScopeDispose(reset)
  return { tabs, activePath, active, saving, dirtyCount, hasDirty, isDirty, openFile, retryLoad, reopenWithEncoding, setSaveEncoding, save, closeTab, closeAll, confirmLeave, reset }
}

export type EditorTabs = ReturnType<typeof useEditorTabs>
