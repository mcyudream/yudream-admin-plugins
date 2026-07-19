import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createHttpMapSource, createWorldMapApi } from '../api/world-map-api'
import { MapViewer } from '../map/MapViewer'
import { createMockMapSource } from '../map/mock'
import type { CameraMode, MapViewMode } from '../map/types'
import type { MapMarker, MapMarkerSet, MapSummary } from '../types'

/** Viewer 页编排：引擎生命周期 + 地图列表 + 工具条状态 */
export function useWorldMapViewer(sdk: YuDreamPluginSdk, route?: RouteLocationNormalizedLoaded) {
  const container = ref<HTMLElement | null>(null)
  const maps = ref<MapSummary[]>([])
  const currentMapId = ref('')
  const loading = ref(true)
  const error = ref('')
  const cameraMode = ref<CameraMode>('orbit')
  const viewMode = ref<MapViewMode>('perspective')
  /** 昼夜滑杆，0..1000 映射 0..1（500 = 正午） */
  const timeOfDay = ref<number[]>([500])
  const cameraPos = ref({ x: 0, y: 0, z: 0 })
  const markerSets = ref<MapMarkerSet[]>([])
  const layerVisibility = ref<Record<string, boolean>>({})
  const selectedMarker = ref<MapMarker | null>(null)

  /** URL query mock=1 启用内置假数据（无后端渲染自查） */
  const mock = computed(() => {
    const query = route?.query?.mock
    if (query === '1' || query === 'true') {
      return true
    }
    if (typeof window !== 'undefined') {
      return new URLSearchParams(window.location.search).get('mock') === '1'
    }
    return false
  })

  const mapOptions = computed(() =>
    maps.value.map(m => ({ label: m.name || m.id, value: m.id })),
  )

  let viewer: MapViewer | null = null
  let loadSeq = 0
  const pendingTiles = ref(0)
  const isFullscreen = ref(false)
  let pendingTimer: ReturnType<typeof setInterval> | null = null
  let hashWriteTimer: ReturnType<typeof setTimeout> | null = null
  /** 首次加载完成前禁止回写 hash，避免覆盖待还原的分享视角 */
  let hashLocked = true

  /** 把当前视角写入 URL hash（可分享的视角链接），最多每 400ms 一次。 */
  function scheduleHashWrite(): void {
    if (!viewer || typeof window === 'undefined' || hashLocked) {
      return
    }
    if (hashWriteTimer) {
      return
    }
    hashWriteTimer = setTimeout(() => {
      hashWriteTimer = null
      if (!viewer) {
        return
      }
      const view = viewer.getView()
      const p = view.position
      const t = view.target
      const zoom = view.zoom ? `&zoom=${view.zoom.toFixed(3)}` : ''
      const hash = `#map=${currentMapId.value}&view=${viewMode.value}&pos=${p.x.toFixed(1)},${p.y.toFixed(1)},${p.z.toFixed(1)}&target=${t.x.toFixed(1)},${t.y.toFixed(1)},${t.z.toFixed(1)}${zoom}`
      history.replaceState(null, '', hash)
    }, 400)
  }

  /** 从 URL hash 还原视角（setSource 完成后调用） */
  function restoreViewFromHash(): void {
    if (!viewer || typeof window === 'undefined') {
      return
    }
    const params = new URLSearchParams(window.location.hash.replace(/^#/, ''))
    if (params.get('map') && params.get('map') !== currentMapId.value) {
      return
    }
    if (params.get('view') === 'flat') {
      viewMode.value = 'flat'
      cameraMode.value = 'orbit'
      viewer.setViewMode('flat')
    }
    const pos = (params.get('pos') || '').split(',').map(Number)
    const target = (params.get('target') || '').split(',').map(Number)
    if (pos.length === 3 && target.length === 3 && [...pos, ...target].every(Number.isFinite)) {
      const zoom = Number(params.get('zoom'))
      viewer.setView({ x: pos[0]!, y: pos[1]!, z: pos[2]! }, { x: target[0]!, y: target[1]!, z: target[2]! },
        Number.isFinite(zoom) ? zoom : undefined)
    }
  }

  function screenshot(): void {
    viewer?.captureScreenshot(`world-map-${currentMapId.value || 'view'}.png`)
  }

  async function toggleFullscreen(): Promise<void> {
    const el = container.value
    if (!el) {
      return
    }
    if (document.fullscreenElement) {
      await document.exitFullscreen()
    }
    else {
      await el.requestFullscreen()
    }
  }

  function onFullscreenChange(): void {
    isFullscreen.value = Boolean(document.fullscreenElement)
  }

  function setLayerVisible(setId: string, visible: boolean): void {
    layerVisibility.value = { ...layerVisibility.value, [setId]: visible }
    viewer?.setLayerVisible(setId, visible)
  }

  function focusSelectedMarker(): void {
    if (selectedMarker.value && viewer?.focusMarker(selectedMarker.value)) {
      scheduleHashWrite()
    }
  }

  async function loadCurrentMap(): Promise<void> {
    const seq = ++loadSeq
    loading.value = true
    error.value = ''
    selectedMarker.value = null
    const source = mock.value
      ? createMockMapSource()
      : createHttpMapSource(sdk, currentMapId.value)
    try {
      await viewer?.setSource(source)
      restoreViewFromHash()
      hashLocked = false
    }
    catch (e) {
      if (seq === loadSeq) {
        error.value = e instanceof Error ? e.message : '地图加载失败'
      }
    }
    finally {
      if (seq === loadSeq) {
        loading.value = false
      }
    }
  }

  async function boot(): Promise<void> {
    if (!container.value) {
      return
    }
    viewer = new MapViewer(container.value, {
      onCameraChanged: (position) => {
        cameraPos.value = {
          x: Math.round(position.x),
          y: Math.round(position.y),
          z: Math.round(position.z),
        }
        scheduleHashWrite()
      },
      onMarkerSetsChanged: sets => {
        markerSets.value = [...sets]
        layerVisibility.value = Object.fromEntries(sets.map(set => [set.id ?? '', set.defaultVisible !== false]))
      },
      onMarkerSelected: result => {
        selectedMarker.value = result?.marker ?? null
      },
    })
    viewer.setTimeOfDay((timeOfDay.value[0] ?? 500) / 1000)
    viewer.setCameraMode(cameraMode.value)
    // 轮询 tile 加载进度（轻量，500ms）
    pendingTimer = setInterval(() => {
      pendingTiles.value = viewer?.pendingTiles ?? 0
    }, 500)
    document.addEventListener('fullscreenchange', onFullscreenChange)

    if (mock.value) {
      maps.value = [{ id: 'mock', name: 'Mock 演示地图', dimension: 'overworld' }]
      currentMapId.value = 'mock'
      await loadCurrentMap()
      return
    }

    try {
      const api = createWorldMapApi(sdk)
      const res = await api.maps()
      maps.value = res.maps ?? []
      if (maps.value.length === 0) {
        error.value = '暂无可用地图'
        loading.value = false
        return
      }
      // 单地图直接加载；多地图默认第一张，由下拉切换
      currentMapId.value = maps.value[0]!.id
      await loadCurrentMap()
    }
    catch (e) {
      error.value = e instanceof Error ? e.message : '地图列表加载失败'
      loading.value = false
    }
  }

  // boot 已负责首次加载；这里只响应用户切换（old 为空串说明是初始化赋值）
  watch(currentMapId, (id, old) => {
    if (id && old && id !== old) {
      void loadCurrentMap()
    }
  })
  watch(cameraMode, mode => viewer?.setCameraMode(mode))
  watch(viewMode, mode => {
    viewer?.setViewMode(mode)
    scheduleHashWrite()
  })
  watch(timeOfDay, value => viewer?.setTimeOfDay((value[0] ?? 500) / 1000))

  onMounted(() => void boot())
  onBeforeUnmount(() => {
    loadSeq += 1
    if (pendingTimer) {
      clearInterval(pendingTimer)
      pendingTimer = null
    }
    if (hashWriteTimer) {
      clearTimeout(hashWriteTimer)
      hashWriteTimer = null
    }
    document.removeEventListener('fullscreenchange', onFullscreenChange)
    viewer?.dispose()
    viewer = null
  })

  return {
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
    mock,
    pendingTiles,
    isFullscreen,
    screenshot,
    toggleFullscreen,
    setLayerVisible,
    focusSelectedMarker,
  }
}
