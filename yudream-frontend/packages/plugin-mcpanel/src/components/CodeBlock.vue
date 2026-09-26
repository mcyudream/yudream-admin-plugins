<script setup lang="ts">
import { FaButton, useFaToast } from '@yudream/components'
import { ref } from 'vue'

/** 可复制命令块：教程页的 shell / 配置片段统一入口，带一键复制与行数自适应。 */
const props = defineProps<{
  code: string
  /** 简短说明（选填），展示在块内顶部。 */
  label?: string
}>()

const toast = useFaToast()
const copied = ref(false)

async function copy() {
  try {
    await navigator.clipboard.writeText(props.code)
    copied.value = true
    toast.success('已复制')
    window.setTimeout(() => {
      copied.value = false
    }, 2000)
  }
  catch {
    toast.error('复制失败，请手动选择复制')
  }
}
</script>

<template>
  <div class="mcp-code-block">
    <div class="mcp-code-block-head">
      <span class="mcp-code-block-label">{{ label }}</span>
      <FaButton size="sm" variant="outline" @click="copy">
        {{ copied ? '已复制' : '复制' }}
      </FaButton>
    </div>
    <pre class="mcp-code-block-body">{{ code }}</pre>
  </div>
</template>
