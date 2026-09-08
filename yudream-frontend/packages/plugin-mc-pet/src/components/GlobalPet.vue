<template>
  <div
    v-if="pet && !pet.hidden"
    ref="rootRef"
    class="mc-pet-global"
    :class="[`mc-pet-global--${pet.corner}`, { 'mc-pet-global--dragging': dragging }]"
    :style="positionStyle"
    @pointerdown="onPointerDown"
  >
    <transition name="mc-pet-speech">
      <div v-if="speech" class="mc-pet-global__speech" @pointerdown.stop>
        {{ speech }}
      </div>
    </transition>
    <div class="mc-pet-global__stage" :style="{ width: `${pet.size}px`, height: `${Math.round(pet.size * 1.2)}px` }">
      <PetStage
        :skin="skinUrl"
        :slim="pet.model === 'slim'"
        :width="pet.size"
        :height="Math.round(pet.size * 1.2)"
        :animation="currentAnimation"
        floating
      />
    </div>
    <transition name="mc-pet-bubble">
      <div v-if="menuOpen" class="mc-pet-global__bubble" @pointerdown.stop>
        <button
          v-for="item in menuItems"
          :key="item.key"
          type="button"
          class="mc-pet-global__bubble-item"
          @click="item.run"
        >
          <span :class="item.icon" />
          {{ item.label }}
        </button>
      </div>
    </transition>
  </div>
</template>

<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { MyPet, PetAnimation } from '../types'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createMcPetApi } from '../api/mc-pet-api'
import { getPetAction, registerPetAction } from '../pet/actions'
import PetStage from './PetStage.vue'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  widget?: { code?: string }
}>()

const api = createMcPetApi(props.sdk)
const router = useRouter()

const pet = ref<MyPet | null>(null)
const menuOpen = ref(false)
const dragging = ref(false)
const speech = ref<string | null>(null)
const burst = ref<PetAnimation | null>(null)
const rootRef = ref<HTMLElement | null>(null)
const dragOffset = ref<{ x: number, y: number } | null>(null)

const DRAG_THRESHOLD = 4
/** 各类短动画的时长（毫秒），结束后回到 idle。 */
const BURST_DURATION: Record<PetAnimation, number> = {
  idle: 0,
  walk: 4000,
  run: 2000,
  fly: 2000,
  wave: 2000,
  crouch: 2600,
  hit: 1200,
}
const SPEECH_DURATION = 4000

/** 闲时碎碎念（MC 风味）。 */
const CHATTER_LINES = [
  '嘶嘶……开玩笑的，别紧张',
  '今天挖到钻石了吗？',
  '这附近的区块归我罩着',
  '想去下界看看，又怕烤糊',
  '下雨了记得收庄稼',
  '嗯……我好像闻到苦力怕的味道',
  '别只啃面包，记得喝汤',
  '红石什么的，最难懂了',
  '周末一起盖房子吧',
  '我数到三，一……二……睡着了',
]

/** 点击打招呼语句。 */
const GREET_LINES = [
  '你好呀！',
  '嗨，今天也要元气满满',
  '哇，是你！',
  '需要我帮你打怪吗？',
  '见到你真高兴',
  '一起冒险吧！',
]

let pointerStart: { x: number, y: number } | null = null
let moved = false
let burstTimer: ReturnType<typeof setTimeout> | null = null
let speechTimer: ReturnType<typeof setTimeout> | null = null
let ambientTimer: ReturnType<typeof setTimeout> | null = null

const skinUrl = computed(() => {
  if (!pet.value) {
    return ''
  }
  if (pet.value.source === 'texture' && pet.value.skinUrl) {
    return pet.value.skinUrl
  }
  return props.sdk.assets.url('assets/steve.png')
})

const currentAnimation = computed<PetAnimation>(() => {
  if (dragging.value) {
    return 'fly'
  }
  if (burst.value) {
    return burst.value
  }
  return 'idle'
})

const positionStyle = computed(() => {
  if (dragOffset.value) {
    return { left: `${dragOffset.value.x}px`, top: `${dragOffset.value.y}px`, right: 'auto', bottom: 'auto' }
  }
  const current = pet.value
  if (current && current.positionX != null && current.positionY != null) {
    const size = current.size
    return {
      left: `${(current.positionX * 100).toFixed(2)}%`,
      top: `${(current.positionY * 100).toFixed(2)}%`,
      right: 'auto',
      bottom: 'auto',
      transform: `translate(-${Math.round(size / 2)}px, -${Math.round(size * 0.6)}px)`,
    }
  }
  return {}
})

interface MenuItem {
  key: string
  label: string
  icon: string
  run: () => void
}

const menuItems = computed<MenuItem[]>(() => [
  {
    key: 'open-settings',
    label: '我的宠物',
    icon: 'i-ri:ghost-smile-line',
    run: () => {
      menuOpen.value = false
      void runAction('open-settings')
    },
  },
  {
    key: 'greet',
    label: '打招呼',
    icon: 'i-ri:hand-heart-line',
    run: () => {
      menuOpen.value = false
      void runAction('greet')
    },
  },
  {
    key: 'hide',
    label: '隐藏宠物',
    icon: 'i-ri:eye-off-line',
    run: () => {
      menuOpen.value = false
      void runAction('hide')
    },
  },
])

onMounted(() => {
  registerLocalActions()
  void load()
  document.addEventListener('pointerdown', onDocumentPointerDown, true)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocumentPointerDown, true)
  clearTimer('burst')
  clearTimer('speech')
  clearTimer('ambient')
})

async function load() {
  try {
    pet.value = await api.me.pet()
    scheduleAmbient()
  }
  catch {
    pet.value = null
  }
}

function registerLocalActions() {
  registerPetAction({
    type: 'local',
    name: 'open-settings',
    handler: () => {
      void router.push('/platform/plugins/mc-pet')
    },
  })
  registerPetAction({
    type: 'local',
    name: 'greet',
    handler: () => greet(),
  })
  registerPetAction({
    type: 'local',
    name: 'hide',
    handler: async () => {
      const next = await api.me.savePet({ hidden: true })
      pet.value = next
    },
  })
}

async function runAction(name: string) {
  const action = getPetAction('local', name)
  if (action) {
    await action.handler({ sdk: props.sdk })
  }
}

function greet() {
  playBurst('wave')
  say(pick(GREET_LINES))
}

/** 播放一段短动画，结束后回到 idle。 */
function playBurst(animation: PetAnimation) {
  burst.value = animation
  clearTimer('burst')
  burstTimer = setTimeout(() => {
    burst.value = null
    burstTimer = null
  }, BURST_DURATION[animation] || 2000)
}

/** 说一句 MC 风格气泡。 */
function say(line: string) {
  speech.value = line
  clearTimer('speech')
  speechTimer = setTimeout(() => {
    speech.value = null
    speechTimer = null
  }, SPEECH_DURATION)
}

/** 管理端开启动画时，闲时随机碎碎念或做个小动作。 */
function scheduleAmbient() {
  clearTimer('ambient')
  ambientTimer = setTimeout(() => {
    ambientTimer = null
    const current = pet.value
    if (current && !current.hidden && !dragging.value && !document.hidden) {
      if (current.animation) {
        const roll = Math.random()
        if (roll < 0.4) {
          say(pick(CHATTER_LINES))
        }
        else if (roll < 0.7) {
          playBurst('walk')
        }
        else if (roll < 0.85) {
          playBurst('crouch')
        }
        else {
          playBurst('hit')
        }
      }
      scheduleAmbient()
    }
  }, 30000 + Math.random() * 40000)
}

function pick(lines: string[]) {
  return lines[Math.floor(Math.random() * lines.length)]
}

function clearTimer(kind: 'burst' | 'speech' | 'ambient') {
  if (kind === 'burst' && burstTimer) {
    clearTimeout(burstTimer)
    burstTimer = null
  }
  if (kind === 'speech' && speechTimer) {
    clearTimeout(speechTimer)
    speechTimer = null
  }
  if (kind === 'ambient' && ambientTimer) {
    clearTimeout(ambientTimer)
    ambientTimer = null
  }
}

function onPointerDown(event: PointerEvent) {
  if (event.button !== 0 || !pet.value) {
    return
  }
  pointerStart = { x: event.clientX, y: event.clientY }
  moved = false
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp, { once: true })
}

function onPointerMove(event: PointerEvent) {
  if (!pointerStart || !rootRef.value) {
    return
  }
  const deltaX = event.clientX - pointerStart.x
  const deltaY = event.clientY - pointerStart.y
  if (!moved && Math.hypot(deltaX, deltaY) < DRAG_THRESHOLD) {
    return
  }
  if (!moved) {
    moved = true
    dragging.value = true
    menuOpen.value = false
    const rect = rootRef.value.getBoundingClientRect()
    dragOffset.value = { x: rect.left, y: rect.top }
  }
  if (dragOffset.value && rootRef.value) {
    const rect = rootRef.value.getBoundingClientRect()
    dragOffset.value = {
      x: clamp(event.clientX - (pointerStart.x - rect.left), 0, window.innerWidth - rect.width),
      y: clamp(event.clientY - (pointerStart.y - rect.top), 0, window.innerHeight - rect.height),
    }
    pointerStart = { x: event.clientX, y: event.clientY }
  }
}

function onPointerUp(event: PointerEvent) {
  window.removeEventListener('pointermove', onPointerMove)
  const wasDrag = moved
  pointerStart = null
  moved = false
  dragging.value = false
  if (wasDrag) {
    void persistPosition(event)
    return
  }
  onClick()
}

async function persistPosition(event: PointerEvent) {
  const current = pet.value
  const root = rootRef.value
  if (!current || !root) {
    return
  }
  const rect = root.getBoundingClientRect()
  const centerX = rect.left + rect.width / 2
  const centerY = rect.top + rect.height / 2
  const positionX = clamp(centerX / window.innerWidth, 0, 1)
  const positionY = clamp(centerY / window.innerHeight, 0, 1)
  dragOffset.value = null
  try {
    pet.value = await api.me.savePet({ positionX, positionY })
  }
  catch {
    // 拖拽持久化失败时保持本地位置，下次加载回退
    dragOffset.value = { x: event.clientX - rect.width / 2, y: event.clientY - rect.height / 2 }
  }
}

function onClick() {
  if (!pet.value) {
    return
  }
  if (pet.value.clickAction === 'menu') {
    menuOpen.value = !menuOpen.value
    playBurst('wave')
  }
  else if (pet.value.clickAction === 'greet') {
    greet()
  }
}

function onDocumentPointerDown(event: PointerEvent) {
  if (!menuOpen.value || !rootRef.value) {
    return
  }
  if (!rootRef.value.contains(event.target as Node)) {
    menuOpen.value = false
  }
}

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max)
}
</script>
