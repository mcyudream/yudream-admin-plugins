import type { Ref } from 'vue'
import type { CompletionItem } from '../utils/commandComplete.ts'
import { applyCompletion, completingCommandWord, suggestCommands } from '../utils/commandComplete.ts'
import { buildTermLine, clockNow, levelBucket } from '../utils/terminalLine.ts'
import type { LevelBucket, TermLine } from '../utils/terminalLine.ts'
import { createRawLineAssembler } from '../utils/terminalStream.ts'
import { computed, ref } from 'vue'

/**
 * 终端控制台共享状态：实例控制台（MC 日志）与节点终端（shell SSE）共用的
 * 行缓冲、级别/关键词筛选、渲染窗口、吸底滚动、命令历史与补全下拉。
 * 传输层（SSE/拉取）留在页面，这里只消费解析后的文本。
 *
 * 关键语义：
 * - 「暂停」（consoleAuto=false）只暂停视图跟随，输出继续入缓冲，绝不丢流；
 * - appendLines 经 RawLineAssembler 装配：半行与 ANSI 序列跨块保持完整，
 *   直到真实换行才成行；流结束时页面应调用 flushPending()；
 * - appendedTotal 是单调递增的新增行序号：跟随/未读计数按序号推进，
 *   不受渲染窗口（固定行数）饱和影响；
 * - 缓冲裁剪只移动窗口并累计 elidedCount（状态栏提示「已省略 N 行」），
 *   不向日志流注入伪行；
 * - 命令回显先于发送（页面负责时序），发送失败用 markCommandFailed 显式
 *   标记失败行，避免伪成功。
 */
export interface TerminalConsoleOptions {
  /** 行解析风格：mc = log4j 前缀解析；shell = PTY 输出按关键词分级。 */
  mode: 'mc' | 'shell'
  /** sessionStorage 键；提供后历史跨刷新保留。传函数可在切换资源时换键。 */
  historyKey?: string | (() => string)
  /** 内置命令表（补全下拉）。 */
  builtins?: () => string[]
  /**
   * 真实系统命令补全（节点终端）：按命令词查询节点 PATH 下的可执行文件名，
   * 返回前缀命中的 top N。实现需自带超时与错误兜底（返回 []）；
   * 未提供或查询失败时补全仅用历史/内置，行为与旧节点一致。
   */
  systemCommands?: (word: string) => Promise<string[]>
  /** 上下文词（如在线玩家名）。 */
  contextWords?: () => string[]
  /** 行缓冲上限与裁剪目标（默认 2500 → 1800）。 */
  maxBuffer?: number
  trimTo?: number
  /** 回车提交回调：页面在此做权限/状态校验并发送命令。 */
  onSubmit?: (command: string) => Promise<void> | void
  /** 输出解码编码切换后的回调：页面负责重拉历史/提示生效范围等副作用。 */
  onEncodingChanged?: (encoding: string) => void
}

export interface TerminalConsoleState {
  lines: Ref<TermLine[]>
  pendingLine: Ref<TermLine | null>
  revision: Ref<number>
  lineNo: Ref<number>
  /** 单调递增的累计新增行数：视图按其增量做吸底/未读计数。 */
  appendedTotal: Ref<number>
  /** 因行缓冲裁剪而被省略的累计行数（状态栏提示，非日志内容）。 */
  elidedCount: Ref<number>
  /** 复制用：当前过滤视图内的全部日志文本。 */
  visibleText: () => string
  keyword: Ref<string>
  levelFilter: Ref<LevelBucket>
  windowSize: Ref<number>
  consoleAuto: Ref<boolean>
  /** 输出解码编码（节点 data 原始字节按它解码；旧节点 text 兜底不受影响）。 */
  encoding: Ref<string>
  /** 切换解码编码并触发页面副作用（重拉历史/提示生效范围）。 */
  setEncoding: (encoding: string) => void
  commandText: Ref<string>
  busy: Ref<boolean>
  history: Ref<string[]>
  filteredLines: Ref<TermLine[]>
  visibleLines: Ref<TermLine[]>
  hiddenCount: Ref<number>
  suggestionList: Ref<CompletionItem[]>
  dropdownVisible: Ref<boolean>
  selectedIndex: Ref<number>
  /** 追加原始输出块（跨块半行/ANSI 由内部装配器保证完整）。 */
  appendLines: (raw: string) => void
  /** 流结束：把未闭合半行作为最后一行落盘。 */
  flushPending: () => void
  appendDivider: (text: string) => void
  /** 命令回显（页面应在发送前调用），返回行号供 markCommandFailed 定位。 */
  appendCommandEcho: (command: string) => number
  /** 发送失败：显式标记对应回显行并附失败原因，避免伪成功。 */
  markCommandFailed: (echoNo: number, reason: string) => void
  appendSystem: (text: string) => void
  clear: () => void
  /** 按（当前）historyKey 重新载入历史；切换资源后调用。 */
  reloadHistory: () => void
  commitCommand: (command: string) => void
  onInput: () => void
  onInputKeydown: (event: KeyboardEvent) => void
  acceptSuggestion: (item: CompletionItem) => void
  closeDropdown: () => void
  submit: () => Promise<void>
}

const DEFAULT_MAX_BUFFER = 2500
const DEFAULT_TRIM_TO = 1800
const HISTORY_LIMIT = 60
/** 真实补全查询的防抖间隔：按键级请求不逐键外呼。 */
const SYSTEM_COMPLETION_DEBOUNCE_MS = 160

export function useTerminalConsole(options: TerminalConsoleOptions): TerminalConsoleState {
  const maxBuffer = options.maxBuffer ?? DEFAULT_MAX_BUFFER
  const trimTo = options.trimTo ?? DEFAULT_TRIM_TO

  const lines = ref<TermLine[]>([])
  const pendingLine = ref<TermLine | null>(null)
  const revision = ref(0)
  const lineNo = ref(0)
  const appendedTotal = ref(0)
  const elidedCount = ref(0)
  const keyword = ref('')
  const levelFilter = ref<LevelBucket>('all')
  const windowSize = ref(500)
  const consoleAuto = ref(true)
  const encoding = ref('utf-8')
  const commandText = ref('')
  const busy = ref(false)
  const history = ref<string[]>([])
  const historyIndex = ref(-1)
  const selectedIndex = ref(0)
  const dropdownOpen = ref(false)
  const systemWords = ref<string[]>([])
  let systemDebounce: ReturnType<typeof setTimeout> | null = null

  function setEncoding(next: string) {
    if (next === encoding.value) {
      return
    }
    encoding.value = next
    options.onEncodingChanged?.(next)
  }

  loadHistory()

  const filteredLines = computed(() => {
    const kw = keyword.value.trim().toLowerCase()
    const bucket = levelFilter.value
    return lines.value.filter((line) => {
      if (bucket !== 'all' && levelBucket(line.level) !== bucket) {
        return false
      }
      if (kw && !line.text.toLowerCase().includes(kw)) {
        return false
      }
      return true
    })
  })

  const visibleLines = computed(() => {
    if (windowSize.value > 0 && filteredLines.value.length > windowSize.value) {
      return filteredLines.value.slice(-windowSize.value)
    }
    return filteredLines.value
  })

  const hiddenCount = computed(() => Math.max(0, filteredLines.value.length - visibleLines.value.length))

  const suggestionList = computed(() => suggestCommands({
    input: commandText.value,
    history: history.value,
    systemWords: systemWords.value,
    builtins: options.builtins?.() ?? [],
    contextWords: options.contextWords?.(),
  }))

  const dropdownVisible = computed(() =>
    dropdownOpen.value && commandText.value.trim().length > 0 && suggestionList.value.length > 0)

  // ---------- 原始输出装配（跨块半行 / ANSI 安全） ----------

  const assembler = createRawLineAssembler({
    onLine: (text) => {
      pushParsedLine(text)
    },
  })

  function nextLineNo(): number {
    lineNo.value += 1
    return lineNo.value
  }

  function pushLine(line: TermLine) {
    lines.value.push(line)
    appendedTotal.value += 1
    trimBuffer()
  }

  function pushParsedLine(raw: string) {
    if (!raw.length) {
      return
    }
    pushLine(buildTermLine(nextLineNo(), raw, options.mode, clockNow()))
  }

  function appendLines(raw: string) {
    assembler.push(raw)
    const pending = assembler.pendingText()
    pendingLine.value = pending ? buildTermLine(lineNo.value + 1, pending, options.mode, clockNow()) : null
    revision.value++
  }

  function flushPending() {
    assembler.flush()
    pendingLine.value = null
    revision.value++
  }

  function appendDivider(text: string) {
    const line = buildTermLine(nextLineNo(), text, options.mode, clockNow())
    line.cls = 'is-divider'
    line.level = ''
    line.ts = ''
    pushLine(line)
  }

  function appendCommandEcho(command: string): number {
    const prompt = options.mode === 'mc' ? '> ' : '$ '
    const line = buildTermLine(nextLineNo(), `${prompt}${command}`, options.mode, clockNow())
    line.cls = 'is-cmd'
    line.level = 'CMD'
    pushLine(line)
    return line.no
  }

  function markCommandFailed(echoNo: number, reason: string) {
    const echo = lines.value.find(line => line.no === echoNo)
    if (echo && echo.cls === 'is-cmd') {
      echo.cls = 'is-cmd is-failed'
    }
    const note = buildTermLine(nextLineNo(), `✕ 命令发送失败：${reason}`, options.mode, clockNow())
    note.cls = 'is-error'
    note.level = 'ERROR'
    pushLine(note)
  }

  function appendSystem(text: string) {
    const line = buildTermLine(nextLineNo(), text.trim(), options.mode, clockNow())
    line.cls = 'is-muted'
    line.level = 'SYS'
    pushLine(line)
  }

  function trimBuffer() {
    if (lines.value.length > maxBuffer) {
      const removed = lines.value.length - trimTo
      lines.value = lines.value.slice(-trimTo)
      elidedCount.value += removed
    }
  }

  function clear() {
    lines.value = []
    lineNo.value = 0
    appendedTotal.value = 0
    elidedCount.value = 0
    assembler.reset()
    pendingLine.value = null
    revision.value++
  }

  // ---------- 历史 ----------

  function historyKeyValue(): string | undefined {
    return typeof options.historyKey === 'function' ? options.historyKey() : options.historyKey
  }

  function loadHistory() {
    const key = historyKeyValue()
    if (!key) {
      return
    }
    try {
      const raw = sessionStorage.getItem(key)
      history.value = raw ? (JSON.parse(raw) as string[]) : []
    }
    catch {
      history.value = []
    }
  }

  function saveHistory() {
    const key = historyKeyValue()
    if (!key) {
      return
    }
    try {
      sessionStorage.setItem(key, JSON.stringify(history.value.slice(-HISTORY_LIMIT)))
    }
    catch {
      // 隐私模式等场景写入失败可忽略
    }
  }

  function reloadHistory() {
    loadHistory()
    historyIndex.value = -1
  }

  function commitCommand(command: string) {
    history.value = [...history.value.filter(item => item !== command), command].slice(-HISTORY_LIMIT)
    saveHistory()
    historyIndex.value = -1
    commandText.value = ''
    systemWords.value = []
    dropdownOpen.value = false
  }

  // ---------- 补全下拉 ----------

  /** 按当前输入防抖查询节点真实命令补全（仅命令位；越界或失败时清空=静默降级）。 */
  function refreshSystemWords() {
    if (systemDebounce) {
      clearTimeout(systemDebounce)
      systemDebounce = null
    }
    if (!options.systemCommands) {
      return
    }
    const word = completingCommandWord(commandText.value)
    if (!word) {
      systemWords.value = []
      return
    }
    systemDebounce = setTimeout(() => {
      systemDebounce = null
      void Promise.resolve(options.systemCommands?.(word))
        .then((names) => {
          // 仅当输入仍停留在发起查询的命令词上才应用结果，避免串词。
          if (completingCommandWord(commandText.value) === word) {
            systemWords.value = Array.isArray(names) ? names : []
          }
        })
        .catch(() => {
          if (completingCommandWord(commandText.value) === word) {
            systemWords.value = []
          }
        })
    }, SYSTEM_COMPLETION_DEBOUNCE_MS)
  }

  function onInput() {
    dropdownOpen.value = commandText.value.trim().length > 0
    selectedIndex.value = 0
    refreshSystemWords()
  }

  function acceptSuggestion(selected: CompletionItem | undefined) {
    const item = selected ?? suggestionList.value[0]
    if (!item) {
      return
    }
    commandText.value = applyCompletion(item)
    // 回填后按新输入继续给候选（参数续输场景），无候选时自动收起。
    selectedIndex.value = 0
    refreshSystemWords()
    dropdownOpen.value = commandText.value.trim().length > 0 && suggestionList.value.length > 0
  }

  function historyPrev() {
    if (!history.value.length) {
      return
    }
    historyIndex.value = historyIndex.value < 0
      ? history.value.length - 1
      : Math.max(0, historyIndex.value - 1)
    commandText.value = history.value[historyIndex.value] ?? ''
    dropdownOpen.value = false
  }

  function historyNext() {
    if (historyIndex.value < 0) {
      return
    }
    historyIndex.value += 1
    if (historyIndex.value >= history.value.length) {
      historyIndex.value = -1
      commandText.value = ''
    }
    else {
      commandText.value = history.value[historyIndex.value] ?? ''
    }
    dropdownOpen.value = false
  }

  async function submit() {
    const command = commandText.value.trim()
    if (!command || busy.value || !options.onSubmit) {
      return
    }
    busy.value = true
    try {
      await options.onSubmit(command)
    }
    finally {
      busy.value = false
    }
  }

  /** 上一次处理的 keydown 事件：FaInput 同时把监听器显式透传到内层 input
   *  又经根元素自动 fallthrough 落到包裹 div，同一次按键会冒泡触发两次；
   *  按「同一事件对象只处理一次」去重，否则上下键每次移动两格（候选少时看似失效）。 */
  let lastHandledKeydown: KeyboardEvent | null = null

  function onInputKeydown(event: KeyboardEvent) {
    if (event === lastHandledKeydown) {
      return
    }
    lastHandledKeydown = event
    if (event.key === 'Tab') {
      event.preventDefault()
      if (suggestionList.value.length) {
        dropdownOpen.value = true
        if (dropdownVisible.value) {
          acceptSuggestion(suggestionList.value[selectedIndex.value])
        }
      }
      return
    }
    if (event.key === 'Escape') {
      dropdownOpen.value = false
      return
    }
    if (dropdownVisible.value && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
      event.preventDefault()
      const total = suggestionList.value.length
      selectedIndex.value = event.key === 'ArrowDown'
        ? (selectedIndex.value + 1) % total
        : (selectedIndex.value - 1 + total) % total
      return
    }
    if (event.key === 'Enter') {
      event.preventDefault()
      dropdownOpen.value = false
      void submit()
      return
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault()
      historyPrev()
    }
    else if (event.key === 'ArrowDown') {
      event.preventDefault()
      historyNext()
    }
  }

  function closeDropdown() {
    dropdownOpen.value = false
  }

  /** 复制用：当前过滤视图内的全部日志文本（时间戳 + 级别 + 正文）。 */
  function visibleText(): string {
    return filteredLines.value
      .map(line => {
        const prefix = line.ts ? `[${line.ts}] ` : ''
        const level = line.level ? `${line.level} ` : ''
        return `${prefix}${level}${line.text}`
      })
      .join('\n')
  }

  return {
    visibleText,
    lines,
    pendingLine,
    revision,
    lineNo,
    appendedTotal,
    elidedCount,
    keyword,
    levelFilter,
    windowSize,
    consoleAuto,
    encoding,
    setEncoding,
    commandText,
    busy,
    history,
    filteredLines,
    visibleLines,
    hiddenCount,
    suggestionList,
    dropdownVisible,
    selectedIndex,
    appendLines,
    flushPending,
    appendDivider,
    appendCommandEcho,
    markCommandFailed,
    appendSystem,
    clear,
    reloadHistory,
    commitCommand,
    onInput,
    onInputKeydown,
    acceptSuggestion,
    closeDropdown,
    submit,
  }
}

export type TerminalConsole = TerminalConsoleState
