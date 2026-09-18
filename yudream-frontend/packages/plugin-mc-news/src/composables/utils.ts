export function errorMessage(error: unknown, fallback: string): string {
  const message = (error as { message?: string } | null | undefined)?.message
  return message && message.trim() ? message : fallback
}

/** 宿主会把 long 序列化为字符串，需要展示/比较时统一强转。 */
export function toNumber(value: unknown): number {
  const num = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(num) ? num : 0
}

export const SOURCE_TYPE_OPTIONS = [
  { label: '官网新闻（minecraft.net）', value: 'mcnet' },
  { label: '反馈隧道（Zendesk）', value: 'zendesk' },
]

export const PUSH_STATE_META: Record<string, { label: string, color: string }> = {
  pushed: { label: '已推送', color: 'green' },
  failed: { label: '推送失败', color: 'red' },
  skipped: { label: '未推送', color: 'gray' },
  deduped: { label: '重复未推', color: 'gray' },
  '': { label: '待推送', color: 'arcoblue' },
}

export const LOG_MODE_META: Record<string, { label: string, color: string }> = {
  poll: { label: '定时轮询', color: 'arcoblue' },
  manual: { label: '手动轮询', color: 'orangered' },
  push: { label: '手动推送', color: 'green' },
  test: { label: '测试推送', color: 'gray' },
}

/** 下次轮询的人性化文案：remainSeconds 来自服务端快照，前端本地递减即可。 */
export function nextPollText(status: { enabled?: boolean, lastPollAt?: number, nextPollAtLabel?: string } | null | undefined, remainSeconds: number): string {
  if (!status)
    return ''
  if (!status.enabled)
    return '已暂停'
  if (!status.lastPollAt)
    return '即将执行（启用后首个检查点）'
  if (remainSeconds <= 0)
    return `即将执行${status.nextPollAtLabel ? `（计划 ${status.nextPollAtLabel}）` : ''}`
  const minutes = Math.max(1, Math.ceil(remainSeconds / 60))
  return `约 ${minutes} 分钟后${status.nextPollAtLabel ? `（${status.nextPollAtLabel}）` : ''}`
}

/* ------------------------------------------------------------------ *
 * 关键词规则
 *
 * 后端存的是紧凑语法 `[!][字段:][类型:]模式`（一条字符串），对不懂正则的人不友好。
 * 这里把它拆成三个日常维度（动作 / 范围 / 方式）供「一格一条」的可视化编辑器使用，
 * 保存时再拼回紧凑语法 —— 存储格式不变，历史配置语义不变。
 * ------------------------------------------------------------------ */

/** 命中后做什么：收录这条新闻，或丢弃这条新闻。 */
export type KeywordAction = 'include' | 'exclude'
/** 去哪里找关键词。 */
export type KeywordScope = 'any' | 'title' | 'summary' | 'url'
/** 怎么比：包含这几个字 / 通配符 / 正则（高级）。 */
export type KeywordMode = 'contain' | 'wildcard' | 'regex'

/** 编辑器里的一格规则。 */
export interface KeywordRuleDraft {
  action: KeywordAction
  scope: KeywordScope
  mode: KeywordMode
  pattern: string
  /** 仅正则使用：i 大小写、m 多行、s 点匹配换行、u Unicode；留空即默认（不区分大小写）。 */
  flags: string
}

export const KEYWORD_ACTION_OPTIONS: { label: string, value: KeywordAction }[] = [
  { label: '收录', value: 'include' },
  { label: '排除', value: 'exclude' },
]

export const KEYWORD_SCOPE_OPTIONS: { label: string, value: KeywordScope }[] = [
  { label: '标题或摘要', value: 'any' },
  { label: '标题', value: 'title' },
  { label: '摘要', value: 'summary' },
  { label: '链接', value: 'url' },
]

export const KEYWORD_MODE_OPTIONS: { label: string, value: KeywordMode }[] = [
  { label: '包含这几个字', value: 'contain' },
  { label: '通配符匹配', value: 'wildcard' },
  { label: '正则表达式', value: 'regex' },
]

export const MAX_KEYWORD_PATTERN_LENGTH = 300
export const MAX_KEYWORD_RULES = 40

const SCOPE_LABELS: Record<KeywordScope, string> = {
  any: '标题或摘要',
  title: '标题',
  summary: '摘要',
  url: '链接',
}

/** 后端 NewsKeywordRule.Field 的 token 别名（保持一致，见 domain/NewsKeywordRule.java）。 */
const SCOPE_TOKENS: Record<string, KeywordScope> = {
  any: 'any',
  all: 'any',
  title: 'title',
  summary: 'summary',
  desc: 'summary',
  url: 'url',
  link: 'url',
}

/** 后端 NewsKeywordRule.Kind 的 token 别名。 */
const MODE_TOKENS: Record<string, KeywordMode> = {
  text: 'contain',
  word: 'contain',
  literal: 'contain',
  re: 'regex',
  regex: 'regex',
  regexp: 'regex',
  glob: 'wildcard',
  wildcard: 'wildcard',
}

const SCOPE_PREFIX: Record<KeywordScope, string> = {
  any: '',
  title: 'title:',
  summary: 'summary:',
  url: 'url:',
}

/** 斜杠形式 /正则/标志 的合法标志位。 */
const SLASH_FLAG_CHARS = 'imsu'

const MODE_LABELS: Record<KeywordMode, string> = {
  contain: '包含这几个字',
  wildcard: '通配符匹配',
  regex: '正则表达式',
}

/** 一个空白格子：默认「标题或摘要里包含…就收录」，即最常见的用法。 */
export function emptyKeywordDraft(): KeywordRuleDraft {
  return { action: 'include', scope: 'any', mode: 'contain', pattern: '', flags: '' }
}

/**
 * 把后端存储的紧凑语法反解成编辑器的一格（`checkKeywordRule` 的逆运算）。
 * 解析过程与后端 NewsKeywordRule.parse 一一对应：`!` 前缀 → 动作，已知 token + `:` → 范围/方式。
 */
export function parseKeywordDraft(raw: string): KeywordRuleDraft {
  const draft = emptyKeywordDraft()
  let text = (raw ?? '').trim()
  if (text.startsWith('!') || text.startsWith('！')) {
    draft.action = 'exclude'
    text = text.slice(1).trim()
  }
  while (true) {
    const colon = text.indexOf(':')
    if (colon <= 0)
      break
    const token = text.slice(0, colon).trim().toLowerCase()
    const scope = SCOPE_TOKENS[token]
    const mode = MODE_TOKENS[token]
    if (!scope && !mode)
      break
    if (scope)
      draft.scope = scope
    else if (mode)
      draft.mode = mode
    text = text.slice(colon + 1).trim()
  }
  if (draft.mode === 'contain' && text.length >= 3 && text.startsWith('/')) {
    const end = text.lastIndexOf('/')
    const flagText = text.slice(end + 1).trim()
    if (end > 0 && [...flagText].every(char => SLASH_FLAG_CHARS.includes(char))) {
      draft.mode = 'regex'
      draft.pattern = text.slice(1, end)
      draft.flags = flagText
      return draft
    }
  }
  draft.pattern = text
  return draft
}

/** 把一格规则拼回后端紧凑语法；内容为空时返回空串（保存时会被丢弃）。 */
export function serializeKeywordDraft(draft: KeywordRuleDraft): string {
  const pattern = (draft.pattern ?? '').trim()
  if (!pattern)
    return ''
  const bang = draft.action === 'exclude' ? '!' : ''
  const scope = SCOPE_PREFIX[draft.scope]
  if (draft.mode === 'regex') {
    const flags = (draft.flags ?? '').trim()
    // 带标志时用 /正则/标志 形式，否则用 re: 前缀，两者后端都能解析。
    return `${bang}${scope}${flags ? `/${pattern}/${flags}` : `re:${pattern}`}`
  }
  if (draft.mode === 'wildcard')
    return `${bang}${scope}glob:${pattern}`
  return `${bang}${scope}${pattern}`
}

/** 大白话复述这条规则做什么，直接显示在格子下方。 */
export function describeKeywordDraft(draft: KeywordRuleDraft): string {
  const where = `${SCOPE_LABELS[draft.scope] ?? SCOPE_LABELS.any}里`
  const pattern = (draft.pattern ?? '').trim()
  if (!pattern)
    return draft.action === 'exclude' ? '填好内容后，命中的新闻会被丢弃' : '填好内容后，命中的新闻会被收录'
  const tail = draft.action === 'exclude' ? '就丢弃' : '就收录'
  if (draft.mode === 'contain')
    return `${where}出现「${pattern}」${tail}`
  if (draft.mode === 'wildcard')
    return `${where}符合「${pattern}」${tail}（* 代表任意内容，? 代表一个字）`
  const flags = (draft.flags ?? '').trim()
  return `${where}符合正则 /${pattern}/${flags} ${tail}`
}

export interface KeywordRuleCheck {
  ok: boolean
  error: string
  warning: string
}

/**
 * 校验一格规则：内容为空视为「还没填」，不算错。
 * 用 JS RegExp 兜底抓正则笔误，另外提示两类「看起来像写法、其实是普通文字」的坑。
 */
export function checkKeywordDraft(draft: KeywordRuleDraft): KeywordRuleCheck {
  const pattern = (draft.pattern ?? '').trim()
  const pass: KeywordRuleCheck = { ok: true, error: '', warning: '' }
  const warn = (message: string): KeywordRuleCheck => ({ ok: true, error: '', warning: message })
  const fail = (message: string): KeywordRuleCheck => ({ ok: false, error: message, warning: '' })
  if (!pattern)
    return pass
  if (/[\r\n]/.test(draft.pattern))
    return fail('一格只能填一行内容')
  if (pattern.length > MAX_KEYWORD_PATTERN_LENGTH)
    return fail(`内容太长（上限 ${MAX_KEYWORD_PATTERN_LENGTH} 个字符）`)

  if (draft.mode === 'regex') {
    const flags = (draft.flags ?? '').trim()
    const badFlag = [...flags].find(char => !SLASH_FLAG_CHARS.includes(char))
    if (badFlag)
      return fail(`标志只能填 i、m、s、u（当前有「${badFlag}」），不确定就留空`)
    try {
      // eslint-disable-next-line no-new
      new RegExp(pattern, flags)
    }
    catch (error) {
      const message = (error as { message?: string } | null)?.message ?? ''
      return fail(`正则写法有误：${message.replace(/^Invalid regular expression:\s*/i, '')}`)
    }
    return pass
  }

  if (draft.mode === 'wildcard' && !pattern.includes('*') && !pattern.includes('?'))
    return warn('没有写 * 或 ?，效果和「包含这几个字」一样')

  // 这两类内容里的「写法」会被后端当成前缀吞掉，语义和界面上选的不一致，必须提示。
  const colon = pattern.indexOf(':')
  if (colon > 0) {
    const token = pattern.slice(0, colon).trim().toLowerCase()
    const mode = MODE_TOKENS[token]
    if (mode)
      return warn(`内容以「${token}:」开头，会被当成匹配方式（相当于选了「${MODE_LABELS[mode]}」），并不是要匹配的文字`)
    const scope = SCOPE_TOKENS[token]
    if (scope)
      return warn(`内容以「${token}:」开头，会被当成匹配范围（相当于选了「${SCOPE_LABELS[scope]}」），并不是要匹配的文字`)
  }
  if (draft.scope === 'any' && (pattern.startsWith('!') || pattern.startsWith('！')))
    return warn('内容以「!」开头，会被当成「排除」标记；想收录这种内容请改选「标题」「摘要」或「链接」范围')
  return pass
}

/** 示例规则：点一下添加一格，避免用户对着空白格不知道写什么。 */
export const KEYWORD_PRESETS: { label: string, description: string, draft: KeywordRuleDraft }[] = [
  {
    label: '快照',
    description: '标题或摘要里出现 Snapshot 就收录',
    draft: { action: 'include', scope: 'any', mode: 'contain', pattern: 'Snapshot', flags: '' },
  },
  {
    label: '版本发布',
    description: '标题或摘要里出现 Release 就收录',
    draft: { action: 'include', scope: 'any', mode: 'contain', pattern: 'Release', flags: '' },
  },
  {
    label: '版本号（正则）',
    description: '用正则匹配 Version 加版本号的写法',
    draft: { action: 'include', scope: 'any', mode: 'regex', pattern: 'Version\\s+\\d+(\\.\\d+)*', flags: '' },
  },
  {
    label: '不要基岩版',
    description: '标题或摘要里出现 Bedrock 就丢弃',
    draft: { action: 'exclude', scope: 'any', mode: 'contain', pattern: 'Bedrock', flags: '' },
  },
  {
    label: '不要市场内容',
    description: '标题或摘要里出现 Marketplace 就丢弃',
    draft: { action: 'exclude', scope: 'any', mode: 'contain', pattern: 'Marketplace', flags: '' },
  },
]
