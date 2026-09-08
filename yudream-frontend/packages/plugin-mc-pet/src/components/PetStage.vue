<template>
  <div class="mc-pet-stage" :style="{ width: `${width}px`, height: `${height}px` }">
    <SkinView3d
      ref="viewerRef"
      :width="width"
      :height="height"
      :skin-url="skin || undefined"
      :skin-options="skinOptions"
      :fov="fov"
      :zoom="zoom"
      :auto-rotate="false"
      :animation="animationInstance"
      :enable-rotate="false"
      :enable-zoom="false"
      :enable-pan="false"
      :global-light="1.7"
      :camera-light="1.35"
    />
  </div>
</template>

<script setup lang="ts">
import type { SkinViewer } from 'skinview3d'
import type { SkinOptions } from 'vue-skinview3d'
import type { PetAnimation } from '../types'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import {
  CrouchAnimation,
  FlyingAnimation,
  HitAnimation,
  IdleAnimation,
  RunningAnimation,
  WalkingAnimation,
  WaveAnimation,
} from 'skinview3d'
import { SkinView3d } from 'vue-skinview3d'

const props = withDefaults(defineProps<{
  skin?: string
  slim?: boolean
  width?: number
  height?: number
  animation?: PetAnimation
  /** 挂件模式：更紧的取景；背景始终透明 */
  floating?: boolean
}>(), {
  skin: '',
  slim: false,
  width: 220,
  height: 270,
  animation: 'idle',
  floating: false,
})

const viewerRef = ref<InstanceType<typeof SkinView3d> | null>(null)

const fov = computed(() => props.floating ? 42 : 46)
const zoom = computed(() => props.floating ? 0.95 : 0.84)
const skinOptions = computed<SkinOptions>(() => ({
  model: props.slim ? 'slim' : 'auto-detect',
}))
const animationInstance = computed(() => {
  // vue-skinview3d 钉住 skinview3d 3.0.1 类型；PlayerAnimation 含 protected 成员，
  // 跨版本结构不兼容，运行时是鸭子类型，这里按组件 props 声明类型一次性断言。
  type AnimationProp = InstanceType<typeof SkinView3d>['$props']['animation']
  let instance: unknown
  switch (props.animation) {
    case 'walk':
      instance = new WalkingAnimation()
      break
    case 'run':
      instance = new RunningAnimation()
      break
    case 'fly':
      instance = new FlyingAnimation()
      break
    case 'wave':
      instance = new WaveAnimation()
      break
    case 'crouch':
      instance = new CrouchAnimation()
      break
    case 'hit':
      instance = new HitAnimation()
      break
    default:
      instance = new IdleAnimation()
  }
  return instance as AnimationProp
})

watch([() => props.skin, fov, zoom], () => {
  void applyFrame()
}, { flush: 'post' })

onMounted(() => {
  void applyFrame()
})

async function applyFrame() {
  await nextTick()
  const viewer = resolveViewer()
  if (!viewer) {
    return
  }
  viewer.playerWrapper.position.y = props.floating ? 2.2 : 2.6
  viewer.controls.target.set(0, 4, 0)
  viewer.controls.update()
  // skinview3d 默认相机看向模型正面，rotation.y = Math.PI 会展示后脑勺，实测验证保持 0 才是正脸
  viewer.playerWrapper.rotation.y = 0
  viewer.render()
}

function resolveViewer(): SkinViewer | null {
  const exposed = viewerRef.value?.viewer as unknown
  if (!exposed) {
    return null
  }
  if (typeof exposed === 'object' && 'value' in exposed) {
    return (exposed as { value?: SkinViewer | null }).value || null
  }
  return exposed as SkinViewer
}
</script>
