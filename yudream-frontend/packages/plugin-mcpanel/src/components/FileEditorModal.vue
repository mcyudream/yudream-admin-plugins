<script setup lang="ts">
import type { EditorLoadResult } from '../composables/useEditorTabs.ts'
import { FaIcon, FaModal, useFaModal } from '@yudream/components'
import { ref } from 'vue'
import EditorWorkspace from './EditorWorkspace.vue'
import type { BrowserListResult } from './FileBrowserPanel.vue'

const props = withDefaults(defineProps<{
  load: (path: string, hints: { size?: number }) => Promise<EditorLoadResult>
  save: (path: string, text: string, charset: string) => Promise<void>
  listDir?: (path: string, keyword?: string) => Promise<BrowserListResult>
  canSave?: boolean
  title?: string
  initialPath?: string
  initialDirectory?: string
}>(), { canSave: true, title: '文件编辑', initialPath: '', initialDirectory: '' })

const open = defineModel<boolean>('open', { default: false })
const emit = defineEmits<{ saved: [path: string] }>()
const modal = useFaModal()
const workspace = ref<InstanceType<typeof EditorWorkspace> | null>(null)

async function beforeClose(action: 'confirm' | 'cancel' | 'close', done: () => void) {
  if (action !== 'close') { done(); return }
  const allowed = await (workspace.value?.confirmLeave() ?? true)
  if (allowed) done()
}
function showHelp() {
  modal.info({ title: '编辑器提示', description: '保存采用提交快照；保存超时不会自动重试。大文件与二进制文件只提供只读提示，请使用 SFTP 或下载。', confirmButtonText: '知道了' })
}
</script>

<template>
  <FaModal v-model="open" :title="title" :footer="false" maximizable :closable="true" :before-close="beforeClose" class="mcp-editor-modal" content-class="mcp-editor-modal-content">
    <template #header>
      <div class="mcp-editor-modal-header">
        <div class="mcp-editor-modal-title"><FaIcon name="i-ri:file-edit-line" /><span>{{ title }}</span></div>
        <button type="button" class="mcp-editor-help" title="编辑提示" @click="showHelp">
          <FaIcon name="i-ri:question-line" />
        </button>
      </div>
    </template>
    <EditorWorkspace ref="workspace" fill :load="load" :save="save" :list-dir="listDir" :can-save="canSave" :initial-path="initialPath" :initial-directory="initialDirectory" @saved="path => emit('saved', path)" />
  </FaModal>
</template>
