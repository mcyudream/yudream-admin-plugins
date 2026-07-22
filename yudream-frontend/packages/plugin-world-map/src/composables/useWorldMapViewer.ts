import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createHttpMapSource, createWorldMapApi } from '../api/world-map-api'
import { MapViewer } from '../map/MapViewer'
import { createMockMapSource } from '../map/mock'
import type { CameraMode, MapViewMode } from '../map/types'
import type { MapMarker, MapMarkerSet, MapSummary } from '../types'
import { layerVisibilityFromHash, mapIdFromHash, viewerHash } from '../map/viewerHash'
import { EMPTY_TILE_LOAD_STATUS, tileLoadMessage } from '../map/tileLoadStatus'
import type { TileLoadStatus } from '../map/tileLoadStatus'
import { DEFAULT_HIRES_RADIUS, DEFAULT_LOWRES_COVERAGE, normalizeHiresRadius, normalizeLowresCoverage } from '../map/renderDistancePolicy'

const HIRES_RADIUS_STORAGE_KEY = 'yudream.world-map.hires-radius'
const LOWRES_COVERAGE_STORAGE_KEY = 'yudream.world-map.lowres-coverage'
export const INITIAL_CAMERA_MODE: CameraMode = 'orbit'

interface InitialMapView {
  viewMode: MapViewMode
  position: { x: number, y: number, z: number }
  target: { x: number, y: number, z: number }
  zoom?: number
}

function storedNumber(key: string, fallback: number): number {
  if (typeof window === 'undefined') return fallback
  try {
    return Number(window.localStorage.getItem(key))
  }
  catch {
    return fallback
  }
}

function persistNumber(key: string, value: number): void {
  try {
    window.localStorage.setItem(key, String(value))
  }
  catch {
    // Private browsing or host storage policies must not block map navigation.
  }
}

/** Viewer 页编排：引擎生命周期 + 地图列表 + 工具条状态 */
export function useWorldMapViewer(sdk: YuDreamPluginSdk, route?: RouteLocationNormalizedLoaded) {
  const container = ref<HTMLElement | null>(null)
  const maps = ref<MapSummary[]>([])
  const currentMapId = ref('')
  const loading = ref(true)
  const error = ref('')
  // Start in a terrain-facing orbit view. Flight remains available on demand, but an initial
  // free-flight heading can point above the world and make a fully loaded map look blank.
  const cameraMode = ref<CameraMode>(INITIAL_CAMERA_MODE)
  const viewMode = ref<MapViewMode>('perspective')
  /** 昼夜滑杆，0..1000 映射 0..1（500 = 正午） */
  const timeOfDay = ref<number[]>([500])
  const cameraPos = ref({ x: 0, y: 0, z: 0 })
  const markerSets = ref<MapMarkerSet[]>([])
  const layerVisibility = ref<Record<string, boolean>>({})
  const selectedMarker = ref<MapMarker | null>(null)
  const hiresRadius = ref<number[]>([normalizeHiresRadius(storedNumber(HIRES_RADIUS_STORAGE_KEY, DEFAULT_HIRES_RADIUS))])
  const lowresCoverage = ref<number[]>([normalizeLowresCoverage(storedNumber(LOWRES_COVERAGE_STORAGE_KEY, DEFAULT_LOWRES_COVERAGE))])

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
  const fps = ref(0)
  const currentTileLoadStatus = ref<TileLoadStatus>(EMPTY_TILE_LOAD_STATUS)
  const tileLoadingMessage = computed(() => tileLoadMessage(currentTileLoadStatus.value))
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
      history.replaceState(null, '', viewerHash({
        mapId: currentMapId.value,
        viewMode: viewMode.value,
        position: view.position,
        target: view.target,
        zoom: view.zoom,
        layerVisibility: layerVisibility.value,
      }))
    }, 400)
  }

  /** Reads a shared view before source initialization so its center receives the first tiles. */
  function initialViewFromHash(): InitialMapView | null {
    if (!viewer || typeof window === 'undefined') {
      return null
    }
    const params = new URLSearchParams(window.location.hash.replace(/^#/, ''))
    if (params.get('map') && params.get('map') !== currentMapId.value) {
      return null
    }
    const pos = (params.get('pos') || '').split(',').map(Number)
    const target = (params.get('target') || '').split(',').map(Number)
    if (pos.length !== 3 || target.length !== 3 || ![...pos, ...target].every(Number.isFinite)) return null
    const zoom = Number(params.get('zoom'))
    return {
      viewMode: params.get('view') === 'flat' ? 'flat' : 'perspective',
      position: { x: pos[0]!, y: pos[1]!, z: pos[2]! },
      target: { x: target[0]!, y: target[1]!, z: target[2]! },
      zoom: Number.isFinite(zoom) ? zoom : undefined,
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
    if (document.fullscreenElement === el) {
      await document.exitFullscreen()
    }
    else if (!document.fullscreenElement) {
      await el.requestFullscreen()
    }
  }

  function onFullscreenChange(): void {
    isFullscreen.value = document.fullscreenElement === container.value
  }

  function setLayerVisible(setId: string, visible: boolean): void {
    layerVisibility.value = { ...layerVisibility.value, [setId]: visible }
    viewer?.setLayerVisible(setId, visible)
    scheduleHashWrite()
  }

  function focusSelectedMarker(): void {
    if (selectedMarker.value && viewer?.focusMarker(selectedMarker.value)) {
      scheduleHashWrite()
    }
  }

  function focusMarker(marker: MapMarker): void {
    if (viewer?.focusMarker(marker)) {
      selectedMarker.value = marker
      scheduleHashWrite()
    }
  }

  function resetToSpawn(): void {
    if (viewer?.resetView()) scheduleHashWrite()
  }

  function focusCoordinates(x: number, z: number): boolean {
    const current = viewer?.getView().target
    const focused = viewer?.focusPosition({ x, y: current?.y, z }) ?? false
    if (focused) scheduleHashWrite()
    return focused
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
      const initialView = initialViewFromHash()
      if (initialView) {
        viewMode.value = initialView.viewMode
        if (initialView.viewMode === 'flat') cameraMode.value = 'orbit'
        viewer?.setViewMode(initialView.viewMode)
      }
      await viewer?.setSource(source, initialView ?? undefined)
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

  function retryCurrentMap(): void {
    if (currentMapId.value) {
      void loadCurrentMap()
    }
  }

  async function boot(): Promise<void> {
    if (!container.value) {
      return
    }
    viewer = new MapViewer(container.value, {
      hiresRadius: hiresRadius.value[0],
      lowresCoverage: lowresCoverage.value[0],
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
        const visibility = layerVisibilityFromHash(window.location.hash, sets)
        layerVisibility.value = visibility
        for (const [setId, visible] of Object.entries(visibility)) {
          viewer?.setLayerVisible(setId, visible)
        }
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
      fps.value = viewer?.fps ?? 0
      currentTileLoadStatus.value = viewer?.tileLoadStatus ?? EMPTY_TILE_LOAD_STATUS
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
      // A shared link wins over the default map when it names one currently published map.
      const sharedMapId = mapIdFromHash(window.location.hash)
      currentMapId.value = maps.value.some(map => map.id === sharedMapId)
        ? sharedMapId!
        : maps.value[0]!.id
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
  watch(hiresRadius, value => {
    const radius = normalizeHiresRadius(value[0])
    viewer?.setHiresRadius(radius)
    persistNumber(HIRES_RADIUS_STORAGE_KEY, radius)
  })
  watch(lowresCoverage, value => {
    const coverage = normalizeLowresCoverage(value[0])
    viewer?.setLowresCoverage(coverage)
    persistNumber(LOWRES_COVERAGE_STORAGE_KEY, coverage)
  })

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
    hiresRadius,
    lowresCoverage,
    mock,
    pendingTiles,
    fps,
    tileLoadingMessage,
    isFullscreen,
    screenshot,
    toggleFullscreen,
    setLayerVisible,
    focusSelectedMarker,
    focusMarker,
    focusCoordinates,
    resetToSpawn,
    retryCurrentMap,
  }
}
