/**
 * Development-only fixture host. It is not part of the library build or packaged plugin JAR.
 * The real viewer still uses the same component and WebGL map engine as the remote module.
 */
import type { MapMarkersResponse, MapSettings } from './types'
import type { WorldMapSource } from './map/types'

const Vue = await import('vue')

const button = Vue.defineComponent({ template: '<button type="button"><slot /></button>' })
const icon = Vue.defineComponent({ template: '<span aria-hidden="true" />' })
const input = Vue.defineComponent({
  props: { modelValue: { type: String, default: '' } },
  emits: ['update:modelValue'],
  template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
})
const select = Vue.defineComponent({
  props: { modelValue: { type: String, default: '' }, options: { type: Array, default: () => [] } },
  emits: ['update:modelValue'],
  template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="option in options" :key="option.value" :value="option.value">{{ option.label }}</option></select>',
})
const slider = Vue.defineComponent({
  props: { modelValue: { type: Array, default: () => [0] }, min: Number, max: Number, step: Number },
  emits: ['update:modelValue'],
  template: '<input type="range" :value="modelValue[0]" :min="min" :max="max" :step="step" @input="$emit(\'update:modelValue\', [Number($event.target.value)])" />',
})
const checkbox = Vue.defineComponent({
  props: { modelValue: Boolean },
  emits: ['update:modelValue'],
  template: '<label><input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" /><slot /></label>',
})

const previewGlobal = globalThis as typeof globalThis & {
  __YUDREAM_PLUGIN_SHARED__?: Record<string, unknown>
}

previewGlobal.__YUDREAM_PLUGIN_SHARED__ = {
  vue: Vue,
  components: {
    FaButton: button,
    FaCheckbox: checkbox,
    FaIcon: icon,
    FaInput: input,
    FaSelect: select,
    FaSlider: slider,
  },
}

await import('./styles.css')
const { MapViewer } = await import('./map/MapViewer')
const originalSetSource = MapViewer.prototype.setSource
const fixtureEnabled = new URLSearchParams(window.location.search).get('bluemap-fixture') === '1'

function createBlueMapFixtureSource(): WorldMapSource {
  const root = '/__bluemap_fixture'
  const controller = new AbortController()
  const fixtureSettings: MapSettings = {
    id: 'bluemap-fixture',
    name: 'BlueMap CLI fixture',
    dimension: 'overworld',
    spawn: { x: 0, y: 70, z: 0 },
    minY: -64,
    maxY: 320,
    hiresTileSize: 32,
    lowresTileSize: 500,
    lowresMaxLod: 3,
    generationId: 'bluemap-fixture',
    atlasUrl: '',
    renderer: 'BLUEMAP',
  }
  const emptyMarkers: MapMarkersResponse = { markerSets: [] }

  async function response(path: string, signal?: AbortSignal): Promise<Response> {
    return fetch(`${root}${path}`, { signal: signal ?? controller.signal })
  }

  async function gzipJson(path: string): Promise<unknown> {
    const value = await response(path)
    if (!value.ok || !value.body) throw new Error(`fixture metadata: HTTP ${value.status}`)
    return new Response(value.body.pipeThrough(new DecompressionStream('gzip'))).json()
  }

  // This fixture was rendered from four regions. Production generations load the same compact
  // structure from lowres-index.json, so missing overview tiles are never represented as black planes.
  const lowresIndex = {
    levels: {
      1: [[-2, [[-2, 1]]], [-1, [[-2, 1]]], [0, [[-2, 1]]], [1, [[-2, 1]]]],
      2: [[-1, [[-1, 0]]], [0, [[-1, 0]]]],
      3: [[-1, [[-1, 0]]], [0, [[-1, 0]]]],
    },
  }

  return {
    loadSettings: async () => fixtureSettings,
    loadAtlas: async () => { throw new Error('BlueMap fixture does not use a legacy atlas') },
    loadBlueMapSettings: async () => {
      const value = await response('/settings.json')
      if (!value.ok) throw new Error(`fixture settings: HTTP ${value.status}`)
      return value.json()
    },
    loadBlueMapTextures: () => gzipJson('/textures.json.gz'),
    loadBlueMapLowresIndex: async () => lowresIndex,
    fetchHiresTile: async (tx, tz, signal) => {
      const value = await response(`/tiles/0/x${tx}/z${tz}.prbm.gz`, signal)
      if (value.status === 404) return null
      if (!value.ok || !value.body) throw new Error(`fixture hires tile ${tx},${tz}: HTTP ${value.status}`)
      return new Response(value.body.pipeThrough(new DecompressionStream('gzip'))).arrayBuffer()
    },
    lowresTileUrl: (lod, tx, tz) => `${root}/tiles/${lod}/x${tx}/z${tz}.png`,
    fetchMarkers: async () => emptyMarkers,
    dispose: () => controller.abort(),
  }
}

MapViewer.prototype.setSource = async function (source) {
  await originalSetSource.call(this, fixtureEnabled ? createBlueMapFixtureSource() : source)
  Object.assign(window, { __worldMapPreviewViewer: this })
}
const { default: Viewer } = await import('./pages/Viewer.vue')
const sdk = {
  version: 'development',
  pluginCode: 'world-map',
  account: { userId: 'development', username: 'development', permissions: [] },
  http: { get: async () => { throw new Error('Mock preview does not call HTTP') }, post: async () => { throw new Error('Mock preview does not call HTTP') }, request: async () => { throw new Error('Mock preview does not call HTTP') }, blob: async () => { throw new Error('Mock preview does not call HTTP') }, url: (path: string) => path },
  files: { uploadImage: async () => { throw new Error('Mock preview does not upload files') }, assetUrl: (url?: string) => url ?? '' },
}

Vue.createApp(Viewer, { sdk, route: { query: { mock: '1' } } }).mount('#app')
