<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { acquireCoverSlot } from '../composables/image-slot'

const props = defineProps<{
  src: string
  fallbackSrc?: string
  alt?: string
  imgClass?: string
}>()

const root = ref<HTMLElement | null>(null)
const imgEl = ref<HTMLImageElement | null>(null)
const imgSrc = ref('')
const failed = ref(false)
const usingFallback = ref(false)

let observer: IntersectionObserver | null = null
let releaseSlot: (() => void) | null = null
let cancelled = false
let requestToken = 0

function disconnectObserver() {
  observer?.disconnect()
  observer = null
}

function release() {
  releaseSlot?.()
  releaseSlot = null
}

async function loadWhenVisible() {
  const token = ++requestToken
  failed.value = false
  usingFallback.value = false
  imgSrc.value = ''
  release()
  disconnectObserver()
  if (!props.src || !root.value) {
    return
  }
  if (typeof IntersectionObserver === 'undefined') {
    void startLoad(token)
    return
  }
  observer = new IntersectionObserver((entries) => {
    if (!entries.some(entry => entry.isIntersecting)) {
      return
    }
    disconnectObserver()
    void startLoad(token)
  }, { rootMargin: '240px 0px' })
  observer.observe(root.value)
}

async function startLoad(token: number) {
  const slot = await acquireCoverSlot()
  if (cancelled || token !== requestToken) {
    slot()
    return
  }
  releaseSlot = slot
  imgSrc.value = props.src
}

function finish() {
  release()
}

function fail() {
  if (!usingFallback.value && props.fallbackSrc && props.fallbackSrc !== props.src) {
    usingFallback.value = true
    imgSrc.value = props.fallbackSrc
    return
  }
  failed.value = true
  imgSrc.value = ''
  release()
}

async function settleCachedImage() {
  await nextTick()
  const el = imgEl.value
  if (!el || !el.complete) {
    return
  }
  if (el.naturalWidth > 0) {
    finish()
  }
  else {
    fail()
  }
}

onMounted(() => {
  void loadWhenVisible()
})

watch(() => props.src, () => {
  void loadWhenVisible()
})

watch(imgSrc, (value) => {
  if (value) {
    void settleCachedImage()
  }
})

onBeforeUnmount(() => {
  cancelled = true
  disconnectObserver()
  release()
})
</script>

<template>
  <span ref="root" class="activity-cover-thumb" :class="imgClass">
    <img
      v-if="imgSrc && !failed"
      ref="imgEl"
      :src="imgSrc"
      :alt="alt || ''"
      decoding="async"
      fetchpriority="low"
      @load="finish"
      @error="fail"
    >
  </span>
</template>
