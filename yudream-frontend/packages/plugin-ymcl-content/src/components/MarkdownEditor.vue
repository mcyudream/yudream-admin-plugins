<script setup lang="ts">
import { MdEditor } from 'md-editor-v3'
import { computed } from 'vue'

const props = defineProps<{
  modelValue: string
  placeholder?: string
  height?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const editorValue = computed({
  get: () => props.modelValue || '',
  set: value => emit('update:modelValue', value),
})

const editorStyle = computed(() => ({
  height: props.height || 'clamp(220px, 36vh, 360px)',
}))
</script>

<template>
  <div class="ymcl-markdown">
    <MdEditor
      v-model="editorValue"
      language="zh-CN"
      preview-theme="github"
      code-theme="github"
      :placeholder="placeholder"
      :no-upload-img="true"
      :style="editorStyle"
    />
  </div>
</template>
