<script setup lang="ts">
import { FaIcon } from '@yudream/components'
</script>

<template>
  <div class="mced-body is-empty">
    <div class="flex flex-col items-center gap-3 text-center">
      <FaIcon name="i-ri:file-edit-line" class="text-3xl opacity-40" />
      <div class="mced-empty-title">从左侧目录选择要编辑的文本文件</div>
      <div class="mced-empty-hint">
        支持多文件同时打开；超过 96 KiB 的文件会以只读预览方式加载。
      </div>
    </div>
  </div>
</template>
