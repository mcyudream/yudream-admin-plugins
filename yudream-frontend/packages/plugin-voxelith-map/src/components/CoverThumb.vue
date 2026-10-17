<script setup lang="ts">
import { acquireImageSlot } from '@yudream/plugin-sdk'
import { onBeforeUnmount, ref, watch } from 'vue'

/**
 * 列表封面图：先排队再取图。
 *
 * <p>站点跑在 HTTP/1.1 上，同源只有约 6 条连接。列表一屏十几张封面若一起发请求，会把连接
 * 占满，页面其它请求（含宿主的导航守卫请求）全得排队——刷新时表现为「页面无响应」。
 * 这里用 SDK 的 {@link acquireImageSlot} 把封面并发压到 2 条以内，API 请求永远有连接可用。</p>
 *
 * <p>尺寸交外部类控制（卡片可能用不同布局），这里只管取图时机与占位：
 * 排队/加载期间显示占位块，避免卡片高度跳动。</p>
 */

const props = defineProps<{
  /** 图片地址；为空则只显示占位（例如地图没有封面） */
  src?: string
  alt?: string
  /** 取图失败时的提示（默认不提示，仅保留占位） */
  fallback?: string
}>()

/** 真正赋给 <img> 的地址：拿到槽位后才设置，未拿到时为空 = 不发请求 */
const actualSrc = ref('')
const failed = ref(false)
/** 取图令牌：翻页/换数据时作废在途的排队，避免旧图盖到新格子上 */
let token = 0
let release: (() => void) | null = null

function freeSlot(): void {
  release?.()
  release = null
}

async function load(url: string): Promise<void> {
  const mine = ++token
  freeSlot()
  failed.value = false
  actualSrc.value = ''
  if (!url) return
  release = await acquireImageSlot()
  // 排队期间地址已变（翻页、封面更新）：这一格作废，槽位立刻还回去
  if (mine !== token) {
    freeSlot()
    return
  }
  actualSrc.value = url
}

watch(() => props.src, url => void load(url ?? ''), { immediate: true })
onBeforeUnmount(() => {
  token++
  freeSlot()
})

function onLoaded(): void {
  freeSlot()
}

function onError(): void {
  failed.value = true
  freeSlot()
}
</script>

<template>
  <img
    v-if="actualSrc"
    :src="actualSrc"
    :alt="alt"
    loading="lazy"
    decoding="async"
    draggable="false"
    class="block h-auto w-full"
    @load="onLoaded"
    @error="onError"
  >
  <div v-else class="flex aspect-video w-full items-center justify-center bg-muted/50">
    <span v-if="failed" class="px-2 text-center text-xs text-muted-foreground">
      {{ fallback || '封面加载失败' }}
    </span>
  </div>
</template>
