<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { TokUI } from '@jboltai/tokui'
import '@jboltai/tokui/css'

const props = withDefaults(defineProps<{ content: string; streaming?: boolean; label?: string; fallback?: string }>(), {
  streaming: false,
  label: 'Agent 回复',
  fallback: '',
})

const container = ref<HTMLElement | null>(null)
const failed = ref(false)
const isDsl = computed(() => props.content.trimStart().startsWith('['))
const fallbackText = computed(() => props.fallback || props.content)
let renderer: TokUI | null = null
let fedContent = ''
let streamStarted = false

function createRenderer() {
  if (!container.value) return null
  renderer?.disconnect()
  container.value.replaceChildren()
  renderer = new TokUI({ container: container.value, locale: 'zh-CN', streaming: true })
  failed.value = false
  return renderer
}

function render() {
  try {
    const current = createRenderer()
    if (!current) return
    if (props.streaming) {
      current.startStream(container.value!)
      streamStarted = true
      fedContent = props.content
      if (fedContent) current.feed(fedContent)
      return
    }
    streamStarted = false
    fedContent = props.content
    if (props.content) current.render(props.content, container.value!)
  } catch {
    failed.value = true
  }
}

watch(() => props.content, async content => {
  await nextTick()
  if (!renderer || !container.value) return render()
  try {
    if (!props.streaming || !streamStarted || !content.startsWith(fedContent)) return render()
    const delta = content.slice(fedContent.length)
    fedContent = content
    if (delta) renderer.feed(delta)
  } catch {
    failed.value = true
  }
})

watch(() => props.streaming, async streaming => {
  await nextTick()
  if (streaming) return render()
  if (renderer && streamStarted) {
    try {
      renderer.endStream()
      streamStarted = false
      fedContent = props.content
    } catch {
      failed.value = true
    }
  } else {
    render()
  }
})

onMounted(render)
onBeforeUnmount(() => renderer?.disconnect())
</script>

<template>
  <div class="agent-output" :class="{ 'is-fallback': failed }">
    <div v-show="!failed && isDsl" ref="container" class="tokui-host" aria-hidden="true"/>
    <p :class="failed || !isDsl ? 'visible-fallback' : 'sr-fallback'" role="status" aria-live="polite" aria-atomic="true">
      <span class="sr-only">{{ label }}：</span>{{ fallbackText || (streaming ? '正在生成…' : '') }}
    </p>
  </div>
</template>

<style scoped>
.agent-output{min-width:0}.tokui-host{min-width:0;color:inherit}.visible-fallback{margin:0;white-space:pre-wrap;line-height:1.7}.sr-fallback{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
</style>
