<script setup lang="ts">
import { FaIcon } from '@yudream/components'
import type { EditorTab } from '../composables/useEditorTabs.ts'
defineProps<{ tabs: EditorTab[], activePath: string }>()
const emit = defineEmits<{ select: [path: string], close: [path: string] }>()
</script>

<template>
  <div class="mced-tabs" role="tablist" aria-label="打开的文件">
    <div v-for="tab in tabs" :key="tab.path" class="mced-tab" :class="{ 'is-active': tab.path === activePath }">
      <button type="button" role="tab" :aria-selected="tab.path === activePath" :title="tab.path" class="mced-tab-select" @click="emit('select', tab.path)">
        <FaIcon :name="tab.loading ? 'i-ri:loader-4-line' : tab.error ? 'i-ri:error-warning-line' : tab.readOnly ? 'i-ri:lock-line' : 'i-ri:file-text-line'" :class="{ 'mced-spin': tab.loading }" />
        <span class="mced-tab-name">{{ tab.name }}</span><span v-if="tab.text !== tab.savedText" class="mced-tab-dot" title="未保存" />
      </button>
      <button type="button" class="mced-tab-close" :disabled="tab.saving" :aria-label="`关闭 ${tab.name}`" @click="emit('close', tab.path)"><FaIcon name="i-ri:close-line" /></button>
    </div>
    <span v-if="!tabs.length" class="mced-tab-empty">选择一个文件开始</span>
  </div>
</template>
