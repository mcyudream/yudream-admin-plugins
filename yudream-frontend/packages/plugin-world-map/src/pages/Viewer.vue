<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaButton, FaIcon, FaSelect, FaSlider } from '@yudream/components'
import { useWorldMapViewer } from '../composables/useWorldMapViewer'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const {
  container,
  maps,
  currentMapId,
  mapOptions,
  loading,
  error,
  cameraMode,
  timeOfDay,
  cameraPos,
  mock,
  pendingTiles,
  isFullscreen,
  screenshot,
  toggleFullscreen,
} = useWorldMapViewer(props.sdk, props.route)

function toggleCameraMode() {
  cameraMode.value = cameraMode.value === 'orbit' ? 'fly' : 'orbit'
}

function setContainer(el: unknown) {
  container.value = el instanceof HTMLElement ? el : null
}
</script>

<template>
  <div class="world-map-viewer">
    <div :ref="setContainer" class="world-map-canvas" />

    <div class="world-map-toolbar">
      <FaSelect
        v-if="mapOptions.length > 1"
        v-model="currentMapId"
        :options="mapOptions"
        class="world-map-toolbar-select"
      />
      <span v-else class="world-map-toolbar-title">{{ maps[0]?.name || '世界地图' }}</span>

      <div class="world-map-toolbar-sun">
        <FaIcon name="i-mdi:weather-night" />
        <FaSlider
          v-model="timeOfDay"
          :min="0"
          :max="1000"
          :step="1"
          :tooltip="false"
          class="world-map-toolbar-slider"
        />
        <FaIcon name="i-mdi:weather-sunny" />
      </div>

      <FaButton size="sm" variant="outline" @click="toggleCameraMode">
        <FaIcon :name="cameraMode === 'orbit' ? 'i-mdi:airplane' : 'i-mdi:orbit'" />
        {{ cameraMode === 'orbit' ? '飞行' : '轨道' }}
      </FaButton>

      <FaButton size="sm" variant="outline" title="截图下载" @click="screenshot">
        <FaIcon name="i-mdi:camera" />
      </FaButton>

      <FaButton size="sm" variant="outline" :title="isFullscreen ? '退出全屏' : '全屏'" @click="toggleFullscreen">
        <FaIcon :name="isFullscreen ? 'i-mdi:fullscreen-exit' : 'i-mdi:fullscreen'" />
      </FaButton>

      <span v-if="pendingTiles > 0" class="world-map-toolbar-pending">
        瓦片加载中 {{ pendingTiles }}
      </span>

      <span class="world-map-toolbar-coords">
        XYZ: {{ cameraPos.x }} / {{ cameraPos.y }} / {{ cameraPos.z }}
      </span>
      <span v-if="mock" class="world-map-toolbar-mock">MOCK</span>
    </div>

    <div v-if="loading" class="world-map-overlay">地图加载中……</div>
    <div v-else-if="error" class="world-map-overlay">{{ error }}</div>
    <div v-else-if="cameraMode === 'fly'" class="world-map-hint">
      点击画面锁定鼠标，WASD 移动，Space/Q 升降，Shift 加速，Esc 退出
    </div>
  </div>
</template>
