<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { ref } from 'vue'
import { FaButton, FaCheckbox, FaIcon, FaInput, FaSelect, FaSlider } from '@yudream/components'
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
  viewMode,
  timeOfDay,
  cameraPos,
  markerSets,
  layerVisibility,
  selectedMarker,
  hiresRadius,
  lowresCoverage,
  mock,
  tileLoadingMessage,
  isFullscreen,
  screenshot,
  toggleFullscreen,
  setLayerVisible,
  focusSelectedMarker,
  focusCoordinates,
  resetToSpawn,
  retryCurrentMap,
} = useWorldMapViewer(props.sdk, props.route)

const coordinateX = ref('')
const coordinateZ = ref('')
const renderSettingsOpen = ref(false)

function toggleCameraMode() {
  cameraMode.value = cameraMode.value === 'orbit' ? 'fly' : 'orbit'
}

function toggleViewMode() {
  if (viewMode.value === 'perspective') {
    cameraMode.value = 'orbit'
    viewMode.value = 'flat'
  }
  else {
    viewMode.value = 'perspective'
  }
}

function setContainer(el: unknown) {
  container.value = el instanceof HTMLElement ? el : null
}

function focusCoordinateInput() {
  const x = Number(coordinateX.value)
  const z = Number(coordinateZ.value)
  if (Number.isFinite(x) && Number.isFinite(z) && focusCoordinates(x, z)) {
    coordinateX.value = String(Math.round(x))
    coordinateZ.value = String(Math.round(z))
  }
}
</script>

<template>
  <div class="world-map-viewer">
    <div :ref="setContainer" class="world-map-canvas" />

    <div class="world-map-toolbar">
      <div class="world-map-toolbar-map">
        <FaSelect
          v-if="mapOptions.length > 1"
          v-model="currentMapId"
          :options="mapOptions"
          class="world-map-toolbar-select"
        />
        <span v-else class="world-map-toolbar-title">{{ maps[0]?.name || '世界地图' }}</span>
      </div>

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

      <div class="world-map-toolbar-actions">
        <FaButton
          size="sm"
          variant="outline"
          :title="viewMode === 'perspective' ? '切换到俯视地图' : '切换到三维视图'"
          @click="toggleViewMode"
        >
          <FaIcon :name="viewMode === 'perspective' ? 'i-mdi:map' : 'i-mdi:cube-outline'" />
        </FaButton>

        <FaButton
          size="sm"
          variant="outline"
          :disabled="viewMode === 'flat'"
          :title="cameraMode === 'orbit' ? '切换到飞行模式' : '切换到轨道模式'"
          @click="toggleCameraMode"
        >
          <FaIcon :name="cameraMode === 'orbit' ? 'i-mdi:airplane' : 'i-mdi:orbit'" />
          <span class="world-map-toolbar-mode-label">{{ cameraMode === 'orbit' ? '飞行' : '轨道' }}</span>
        </FaButton>

        <FaButton size="sm" variant="outline" title="重置到出生点" @click="resetToSpawn">
          <FaIcon name="i-mdi:home-map-marker" />
        </FaButton>

        <FaButton size="sm" variant="outline" title="截图下载" @click="screenshot">
          <FaIcon name="i-mdi:camera" />
        </FaButton>

        <FaButton size="sm" variant="outline" :title="isFullscreen ? '退出全屏' : '全屏'" @click="toggleFullscreen">
          <FaIcon :name="isFullscreen ? 'i-mdi:fullscreen-exit' : 'i-mdi:fullscreen'" />
        </FaButton>
        <FaButton
          size="sm"
          variant="outline"
          title="渲染距离"
          :aria-pressed="renderSettingsOpen"
          @click="renderSettingsOpen = !renderSettingsOpen"
        >
          <FaIcon name="i-mdi:tune-variant" />
        </FaButton>
      </div>

      <div class="world-map-toolbar-status">
        <span v-if="tileLoadingMessage" class="world-map-toolbar-pending">
          {{ tileLoadingMessage }}
        </span>

        <span class="world-map-toolbar-coords">
          XYZ: {{ cameraPos.x }} / {{ cameraPos.y }} / {{ cameraPos.z }}
        </span>
        <form class="world-map-coordinate-form" @submit.prevent="focusCoordinateInput">
          <FaInput v-model="coordinateX" aria-label="X coordinate" inputmode="decimal" placeholder="X" />
          <FaInput v-model="coordinateZ" aria-label="Z coordinate" inputmode="decimal" placeholder="Z" />
          <FaButton size="sm" variant="outline" title="定位到坐标" type="submit">
            <FaIcon name="i-mdi:crosshairs-gps" />
          </FaButton>
        </form>
        <span v-if="mock" class="world-map-toolbar-mock">MOCK</span>
      </div>
    </div>

    <div v-if="renderSettingsOpen" class="world-map-render-settings">
      <label>
        <span>地形细节 {{ hiresRadius[0] }}</span>
        <FaSlider v-model="hiresRadius" :min="2" :max="6" :step="1" :tooltip="false" />
      </label>
      <label>
        <span>概览范围 {{ lowresCoverage[0]?.toFixed(1) }}x</span>
        <FaSlider v-model="lowresCoverage" :min="1" :max="2.5" :step="0.25" :tooltip="false" />
      </label>
    </div>

    <div v-if="markerSets.length" class="world-map-layers">
      <FaCheckbox
        v-for="set in markerSets"
        :key="set.id"
        :model-value="layerVisibility[set.id ?? ''] ?? set.defaultVisible !== false"
        @update:model-value="value => setLayerVisible(set.id ?? '', Boolean(value))"
      >
        {{ set.label || set.id }}
      </FaCheckbox>
    </div>

    <div v-if="selectedMarker" class="world-map-marker-popup">
      <div class="world-map-marker-popup-title">
        <strong>{{ selectedMarker.label || selectedMarker.id || '标注' }}</strong>
        <FaButton size="sm" variant="outline" title="定位到标注" @click="focusSelectedMarker">
          <FaIcon name="i-mdi:crosshairs-gps" />
        </FaButton>
      </div>
      <span v-if="selectedMarker.position">
        {{ Math.round(selectedMarker.position.x) }} / {{ Math.round(selectedMarker.position.y) }} / {{ Math.round(selectedMarker.position.z) }}
      </span>
    </div>

    <div v-if="loading" class="world-map-overlay">地图加载中……</div>
    <div v-else-if="error" class="world-map-overlay world-map-error">
      <span>{{ error }}</span>
      <FaButton size="sm" variant="outline" @click="retryCurrentMap">重试</FaButton>
    </div>
    <div v-else-if="viewMode === 'flat'" class="world-map-hint">
      拖拽平移，滚轮或双指缩放
    </div>
    <div v-else-if="cameraMode === 'fly'" class="world-map-hint">
      点击画面锁定鼠标，WASD 移动，Space/Q 升降，Shift 加速，Esc 退出
    </div>
  </div>
</template>
