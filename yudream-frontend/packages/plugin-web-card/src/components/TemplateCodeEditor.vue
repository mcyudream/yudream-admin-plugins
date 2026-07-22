<script setup lang="ts">
import { basicSetup } from 'codemirror'
import { css } from '@codemirror/lang-css'
import { html } from '@codemirror/lang-html'
import { json } from '@codemirror/lang-json'
import { EditorState } from '@codemirror/state'
import { EditorView, keymap } from '@codemirror/view'
import { indentWithTab } from '@codemirror/commands'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  modelValue: string
  language: 'json' | 'html' | 'css'
  readonly?: boolean
  minHeight?: number
}>(), {
  readonly: false,
  minHeight: 480,
})

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const host = ref<HTMLElement>()
let editor: EditorView | undefined
let applyingExternalValue = false

function languageExtension() {
  if (props.language === 'html') return html()
  if (props.language === 'css') return css()
  return json()
}

function createEditor() {
  if (!host.value) return
  editor = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.modelValue,
      extensions: [
        basicSetup,
        keymap.of([indentWithTab]),
        languageExtension(),
        EditorState.readOnly.of(props.readonly),
        EditorView.lineWrapping,
        EditorView.theme({
          '&': { height: '100%', minHeight: `${props.minHeight}px`, backgroundColor: '#fbfcfd', color: '#17212b', fontSize: '13px' },
          '&.cm-focused': { outline: 'none' },
          '.cm-scroller': { fontFamily: '"JetBrains Mono", "SFMono-Regular", Consolas, monospace', lineHeight: '1.65' },
          '.cm-content': { padding: '14px 0' },
          '.cm-gutters': { backgroundColor: '#f3f5f6', color: '#87909a', borderRight: '1px solid #e2e6e9' },
          '.cm-activeLine, .cm-activeLineGutter': { backgroundColor: '#edf3f6' },
          '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { backgroundColor: '#cfe0ec !important' },
        }),
        EditorView.updateListener.of((update) => {
          if (!update.docChanged || applyingExternalValue) return
          emit('update:modelValue', update.state.doc.toString())
        }),
      ],
    }),
  })
}

watch(() => props.modelValue, (value) => {
  if (!editor || value === editor.state.doc.toString()) return
  applyingExternalValue = true
  editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } })
  applyingExternalValue = false
})

watch(() => [props.language, props.readonly, props.minHeight], () => {
  editor?.destroy()
  editor = undefined
  createEditor()
})

onMounted(createEditor)
onBeforeUnmount(() => editor?.destroy())
</script>

<template>
  <div ref="host" class="template-code-editor" />
</template>

<style scoped>
.template-code-editor {
  min-width: 0;
  overflow: hidden;
  border: 1px solid #dfe4e7;
  border-radius: 6px;
  background: #fbfcfd;
}
</style>
