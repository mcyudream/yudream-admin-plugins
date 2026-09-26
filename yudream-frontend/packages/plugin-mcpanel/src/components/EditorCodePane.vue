<script setup lang="ts">
import { FaButton, FaIcon, FaTextarea } from '@yudream/components'
import { computed, nextTick, ref, watch } from 'vue'
import { indentLine, indentUnitsFor, newlineIndent, outdentLine, suggest, wordBefore } from '../utils/editorAssist.ts'
import type { EditorTab } from '../composables/useEditorTabs.ts'

const props = defineProps<{ tab: EditorTab, wrap: boolean }>()
const emit = defineEmits<{ 'update:text': [text: string], save: [] }>()
const frame = ref<HTMLElement | null>(null)
const scrollTop = ref(0)
const caret = ref({ line: 1, column: 1 })
const suggestions = ref<{ items: string[], selected: number, start: number, end: number } | null>(null)
const lineCount = computed(() => props.tab.text.split('\n').length)
const firstLine = computed(() => Math.max(1, Math.floor(scrollTop.value / 22) - 2))
const visibleNumbers = computed(() => Array.from({ length: Math.max(0, Math.min(120, lineCount.value - firstLine.value + 1)) }, (_, i) => firstLine.value + i))
function area() { return frame.value?.querySelector('textarea') ?? null }
function locateCaret() {
  const input = area()
  if (!input) return
  const prefix = input.value.slice(0, input.selectionStart)
  caret.value = { line: prefix.split('\n').length, column: prefix.length - prefix.lastIndexOf('\n') }
}
function syncScroll() { scrollTop.value = area()?.scrollTop ?? 0 }
function onTextUpdate(value: unknown) {
  if (props.tab.readOnly) return
  emit('update:text', String(value ?? ''))
  void nextTick(() => { locateCaret(); refreshSuggestions() })
}
function refreshSuggestions() {
  const input = area()
  if (!input || props.tab.readOnly) { suggestions.value = null; return }
  const word = wordBefore(input.value, input.selectionStart)
  const items = word && word.word.length >= 2 ? suggest(props.tab.path, input.value, word.word) : []
  suggestions.value = word && items.length ? { items, selected: 0, start: word.start, end: input.selectionStart } : null
}
function selectSuggestion(item?: string) {
  const input = area(), choice = suggestions.value
  if (!input || !choice || !item || props.tab.readOnly) return
  emit('update:text', props.tab.text.slice(0, choice.start) + item + props.tab.text.slice(choice.end))
  suggestions.value = null
  void nextTick(() => { input.focus(); input.setSelectionRange(choice.start + item.length, choice.start + item.length); locateCaret() })
}
function onKeydown(event: KeyboardEvent) {
  if (event.isComposing) return
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') { event.preventDefault(); emit('save'); return }
  if (props.tab.readOnly) return
  const input = area()
  if (!input) return
  const choices = suggestions.value
  if (choices && ['ArrowDown', 'ArrowUp', 'Escape', 'Tab'].includes(event.key)) {
    event.preventDefault()
    if (event.key === 'Escape') suggestions.value = null
    else if (event.key === 'Tab') selectSuggestion(choices.items[choices.selected])
    else choices.selected = (choices.selected + (event.key === 'ArrowDown' ? 1 : -1) + choices.items.length) % choices.items.length
    return
  }
  if (event.key === 'Tab') {
    event.preventDefault()
    const change = (event.shiftKey ? outdentLine : indentLine)(props.tab.text, input.selectionStart, input.selectionEnd, indentUnitsFor(props.tab.path))
    emit('update:text', change.text)
    void nextTick(() => input.setSelectionRange(change.caretStart, change.caretEnd))
  }
  else if (event.key === 'Enter' && !event.ctrlKey && !event.metaKey && !event.shiftKey) {
    event.preventDefault()
    const before = props.tab.text.slice(0, input.selectionStart) + props.tab.text.slice(input.selectionEnd)
    const change = newlineIndent(before, input.selectionStart, indentUnitsFor(props.tab.path))
    emit('update:text', change.text)
    suggestions.value = null
    void nextTick(() => { input.setSelectionRange(change.caret, change.caret); locateCaret() })
  }
}
watch(() => props.wrap, () => nextTick(syncScroll))
</script>

<template>
  <section class="mced-code" :class="{ 'is-wrapped': wrap }" @keydown.capture="onKeydown">
    <div v-if="tab.truncated || tab.readOnly" class="mced-notice"><FaIcon name="i-ri:lock-line" />{{ tab.truncated ? '只读预览 · 当前内容不完整，完整文件请下载或使用 SFTP。' : '只读模式 · 当前账号没有文件修改权限。' }}</div>
    <div class="mced-code-scroll">
      <div v-if="!wrap" class="mced-gutter" aria-hidden="true"><div :style="{ transform: `translateY(${(firstLine - 1) * 22 - scrollTop}px)` }"><span v-for="line in visibleNumbers" :key="line">{{ line }}</span></div></div>
      <div ref="frame" class="mced-frame" @scroll.capture="syncScroll" @click="locateCaret" @keyup="locateCaret">
        <FaTextarea :model-value="tab.text" :readonly="tab.readOnly" :wrap="wrap ? 'soft' : 'off'" :aria-label="`编辑 ${tab.name}`" spellcheck="false" autocapitalize="off" autocomplete="off" class="mced-area" input-class="mced-ta" @update:model-value="onTextUpdate" />
      </div>
    </div>
    <div v-if="suggestions" class="mced-sug" role="listbox" aria-label="文本补全">
      <FaButton v-for="(item, index) in suggestions.items.slice(0, 8)" :key="item" size="sm" :variant="index === suggestions.selected ? 'secondary' : 'ghost'" @mousedown.prevent="selectSuggestion(item)">{{ item }}</FaButton><span>Tab 补全 · Esc 收起</span>
    </div>
    <div class="mced-caret"><span>第 {{ caret.line }} 行，{{ caret.column }} 列</span><span>{{ wrap ? '自动换行 · 行号已隐藏' : `${lineCount} 行` }}</span><span>UTF-8</span></div>
  </section>
</template>
