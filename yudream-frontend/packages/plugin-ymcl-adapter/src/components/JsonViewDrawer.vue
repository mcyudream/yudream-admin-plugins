<script setup lang="ts">
import { FaButton, FaDrawer, FaIcon } from '@yudream/components'
import { computed } from 'vue'

const props = defineProps<{
  modelValue: boolean
  title: string
  payload?: unknown
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const formatted = computed(() => JSON.stringify(props.payload ?? {}, null, 2))

function copyPayload() {
  navigator.clipboard.writeText(formatted.value).catch(() => {})
}
</script>

<template>
  <FaDrawer
    :model-value="modelValue"
    :title="title"
    side="right"
    :show-confirm-button="false"
    :footer="false"
    content-class="ymcl-json-drawer"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div class="ymcl-json-drawer__body">
      <FaButton size="sm" variant="outline" @click="copyPayload">
        <FaIcon name="i-ri:file-copy-line" />
        复制 JSON
      </FaButton>
      <pre class="ymcl-json-pre">{{ formatted }}</pre>
    </div>
  </FaDrawer>
</template>
