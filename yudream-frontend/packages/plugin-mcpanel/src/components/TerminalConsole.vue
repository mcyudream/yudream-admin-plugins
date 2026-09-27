<script setup lang="ts">
import type { TerminalConsoleState, TerminalConsoleOptions } from '../composables/useTerminalConsole.ts'
import type { AnsiSpan } from '../utils/ansi.ts'
import { EDITOR_ENCODINGS } from '../utils/fileContent.ts'
import { levelBucket } from '../utils/terminalLine.ts'
import { FaButton, FaDropdown, FaIcon, FaInput, FaSelect, useFaToast } from '@yudream/components'
import type { CSSProperties } from 'vue'
import { computed, nextTick, ref, watch } from 'vue'

const props = defineProps<{
  term: TerminalConsoleState
  mode: TerminalConsoleOptions['mode']
  canUse: boolean
  transport?: { opening?: boolean, connected?: boolean }
  hint?: string
  emptyText?: string
  showReconnect?: boolean
}>()
const emit = defineEmits<{ reconnect: [] }>()
const term = props.term
const toast = useFaToast()
const screen = ref<HTMLElement | null>(null)
const suggestBox = ref<HTMLElement | null>(null)
// 窄屏（手机）默认收起文件浏览/会话工具两侧栏，终端独占可视区；
// 侧栏开启后内层列表独立滚动，工作区总高不再随日志撑开主页面。
const narrowScreen = typeof window !== 'undefined' && window.matchMedia('(max-width: 1000px)').matches
const leftOpen = ref(!narrowScreen)
const rightOpen = ref(!narrowScreen)
const atBottom = ref(true)
const unread = ref(0)
const levelOptions = [{ value: 'all', label: '全部级别' }, { value: 'info', label: '信息' }, { value: 'warn', label: '警告' }, { value: 'error', label: '错误' }]
const windowOptions = [{ value: 200, label: '最近 200 行' }, { value: 500, label: '最近 500 行' }, { value: 1000, label: '最近 1000 行' }, { value: 0, label: '全部缓冲' }]
const encodingItems = computed(() => [EDITOR_ENCODINGS.map(item => ({
  label: item.label,
  icon: term.encoding.value === item.value ? 'i-ri:check-line' : 'i-ri:file-line',
  handle: () => term.setEncoding(item.value),
}))])
const pending = computed(() => {
  const line = term.pendingLine.value
  if (!line || (term.levelFilter.value !== 'all' && levelBucket(line.level) !== term.levelFilter.value)) return null
  return line.text.toLowerCase().includes(term.keyword.value.toLowerCase().trim()) ? line : null
})
const emptyText = computed(() => term.lines.value.length ? '当前筛选没有匹配的输出' : props.emptyText ?? (props.transport?.opening ? '正在连接终端…' : '等待输出…'))
async function bottom() {
  await nextTick()
  if (screen.value) screen.value.scrollTop = screen.value.scrollHeight
  atBottom.value = true
  unread.value = 0
}
function onScroll() {
  const el = screen.value
  if (!el) return
  atBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 36
  if (atBottom.value) unread.value = 0
}
watch(() => term.appendedTotal.value, (now, old) => {
  const delta = now - old
  if (delta < 0) { unread.value = 0; void bottom(); return }
  if (atBottom.value && term.consoleAuto.value) void bottom()
  else unread.value += delta
})
watch(() => term.revision.value, () => { if (atBottom.value && term.consoleAuto.value) void bottom() })
watch(() => term.consoleAuto.value, follow => { if (follow) void bottom() })
watch([() => term.keyword.value, () => term.levelFilter.value, () => term.windowSize.value], () => void bottom())
watch([leftOpen, rightOpen], () => { if (atBottom.value) void bottom() })
/** 方向键换选时保证选中行在下拉可视区内。 */
watch(() => term.selectedIndex.value, async () => {
  await nextTick()
  suggestBox.value?.querySelector('.mcp-term-suggest-row.is-active')?.scrollIntoView({ block: 'nearest' })
})
async function copy() {
  const text = term.visibleText() + (pending.value ? `\n${pending.value.text}` : '')
  if (!text.trim()) { toast.warning('没有可复制的输出'); return }
  try { await navigator.clipboard.writeText(text); toast.success('已复制当前筛选的日志') }
  catch { toast.error('复制失败，请手动选择日志文本') }
}
function spanStyle(span: AnsiSpan): CSSProperties {
  return {
    color: (span.inverse ? span.background : span.color) ?? undefined,
    backgroundColor: (span.inverse ? span.color : span.background) ?? undefined,
    fontWeight: span.bold ? '700' : undefined,
    opacity: span.dim ? '.7' : undefined,
    fontStyle: span.italic ? 'italic' : undefined,
    textDecorationLine: [span.underline ? 'underline' : '', span.strike ? 'line-through' : ''].filter(Boolean).join(' '),
  }
}
</script>

<template>
  <section class="mcp-wt" aria-label="终端工作区">
    <header class="mcp-wt-head"><div class="mcp-wt-identity"><FaIcon name="i-ri:terminal-box-line" /><strong>{{ mode === 'mc' ? '实例控制台' : '节点终端' }}</strong><slot name="tags" /></div><span class="mcp-wt-connection" :class="{ 'is-connected': transport?.connected }"><i />{{ transport?.opening ? '连接中' : transport?.connected ? '实时连接' : '未连接' }}</span></header>
    <div class="mcp-wt-top">
      <div class="mcp-wt-filter"><FaInput v-model="term.keyword.value" clearable placeholder="搜索日志内容" aria-label="搜索日志" /><FaSelect v-model="term.levelFilter.value" :options="levelOptions" aria-label="日志级别" /><FaSelect v-model="term.windowSize.value" :options="windowOptions" aria-label="显示行数" /><FaDropdown :items="encodingItems" align="end"><FaButton size="sm" variant="outline" title="输出解码编码：需节点 0.3.1+ 按原始字节重解码，旧节点仅显示既有文本">{{ term.encoding.value.toUpperCase() }}</FaButton></FaDropdown></div>
      <div class="mcp-wt-toolbar-group"><FaButton size="sm" :variant="term.consoleAuto.value ? 'secondary' : 'outline'" :aria-pressed="term.consoleAuto.value" @click="term.consoleAuto.value = !term.consoleAuto.value"><FaIcon :name="term.consoleAuto.value ? 'i-ri:live-line' : 'i-ri:pause-line'" />{{ term.consoleAuto.value ? '跟随输出' : '暂停跟随' }}</FaButton><FaButton size="icon-sm" variant="ghost" title="复制日志" @click="copy"><FaIcon name="i-ri:file-copy-line" /></FaButton><FaButton size="icon-sm" variant="ghost" title="清空本地显示，不删除节点日志" @click="term.clear"><FaIcon name="i-ri:eraser-line" /></FaButton><FaButton v-if="showReconnect" size="sm" variant="outline" :disabled="transport?.opening" @click="emit('reconnect')">重连</FaButton><slot name="actions" /></div>
    </div>
    <div class="mcp-wt-panels"><FaButton v-if="$slots.left" size="sm" variant="ghost" :aria-expanded="leftOpen" @click="leftOpen = !leftOpen"><FaIcon name="i-ri:folder-line" />文件浏览</FaButton><FaButton v-if="$slots.right" size="sm" variant="ghost" :aria-expanded="rightOpen" @click="rightOpen = !rightOpen"><FaIcon name="i-ri:dashboard-line" />会话工具</FaButton><span>输出继续接收，暂停仅停止滚动</span></div>
    <div class="mcp-wt-body" :class="{ 'has-left': leftOpen && $slots.left, 'has-right': rightOpen && $slots.right }">
      <aside v-if="$slots.left && leftOpen" class="mcp-wt-left"><slot name="left" /></aside>
      <div class="mcp-wt-center">
        <slot name="above-screen" />
        <div ref="screen" class="mcp-wt-screen" tabindex="0" aria-label="终端输出" @scroll="onScroll">
          <div v-if="!term.visibleLines.value.length && !pending" class="mcp-wt-empty"><FaIcon name="i-ri:terminal-line" /><span>{{ emptyText }}</span></div>
          <div v-for="line in [...term.visibleLines.value, ...(pending ? [pending] : [])]" :key="line.no" class="mcp-wt-line" :class="line.cls">
            <span v-if="line.cls !== 'is-divider'" class="mcp-wt-meta"><span class="mcp-wt-ts">{{ line.ts }}</span><span class="mcp-wt-lv">{{ line.level }}</span></span>
            <span class="mcp-wt-text"><template v-if="line.spans?.length"><span v-for="(span, index) in line.spans" :key="index" :style="spanStyle(span)">{{ span.text }}</span></template><template v-else>{{ line.text || ' ' }}</template></span>
          </div>
        </div>
        <FaButton v-if="unread > 0" size="sm" class="mcp-term-jump" @click="bottom">{{ unread }} 行新输出 · 回到底部<FaIcon name="i-ri:arrow-down-line" /></FaButton>
        <div class="mcp-wt-inputwrap">
          <div v-if="term.dropdownVisible.value" ref="suggestBox" class="mcp-term-suggest" role="listbox" aria-label="命令补全"><button v-for="(item, index) in term.suggestionList.value" :key="`${item.kind}:${item.value}`" type="button" class="mcp-term-suggest-row" :class="{ 'is-active': index === term.selectedIndex.value }" @mousedown.prevent="term.acceptSuggestion(item)"><span>{{ item.value }}</span><small>{{ item.badge }}</small></button></div>
          <div class="mcp-wt-inputbar"><FaIcon name="i-ri:arrow-right-s-line" /><FaInput v-model="term.commandText.value" :disabled="!canUse || term.busy.value" input-class="mcp-mono" :placeholder="canUse ? '输入命令 · Enter 发送 · Tab/↑↓ 补全' : '当前会话只读'" aria-label="终端命令" @update:model-value="term.onInput()" @keydown="term.onInputKeydown" @blur="term.closeDropdown" /><FaButton :disabled="!canUse || !term.commandText.value.trim()" :loading="term.busy.value" @click="term.submit">发送</FaButton></div>
          <div v-if="hint" class="mcp-wt-hint" role="status">{{ hint }}</div>
        </div>
      </div>
      <aside v-if="$slots.right && rightOpen" class="mcp-wt-right"><slot name="right" /></aside>
    </div>
    <footer class="mcp-wt-status"><slot name="status-start" /><span>缓冲 {{ term.lines.value.length }} 行</span><span v-if="term.elidedCount.value">已裁剪更早 {{ term.elidedCount.value }} 行</span><span v-if="term.hiddenCount.value">窗口 {{ term.visibleLines.value.length }} 行</span><span class="mcp-spacer" /><span>{{ canUse ? '可操作' : '只读' }}</span><slot name="status-end" /></footer>
  </section>
</template>
