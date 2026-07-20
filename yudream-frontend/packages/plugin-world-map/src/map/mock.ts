import * as THREE from 'three'
import type { HiresTile, MapSettings } from '../types'
import type { WorldMapSource } from './types'

const MOCK_SETTINGS: MapSettings = {
  id: 'mock',
  name: 'Mock 演示地图',
  dimension: 'overworld',
  spawn: { x: 4, y: 70, z: 4 },
  minY: -64,
  maxY: 320,
  hiresTileSize: 32,
  lowresTileSize: 512,
  lowresMaxLod: 0,
  generationId: 'mock',
  atlasUrl: '',
  renderedAt: 0,
}

/** 1×1 白色 atlas（DataTexture 直接构造，免去 dataURL 解码） */
function createMockAtlas(): THREE.Texture {
  const texture = new THREE.DataTexture(new Uint8Array([255, 255, 255, 255]), 1, 1)
  texture.magFilter = THREE.NearestFilter
  texture.minFilter = THREE.NearestFilter
  texture.generateMipmaps = false
  texture.colorSpace = THREE.SRGBColorSpace
  texture.needsUpdate = true
  return texture
}

interface MockFace {
  /** 4 个角点的单位偏移，逆时针（外向） */
  corners: [number, number, number][]
  ao: number
  skylight: number
}

// 立方体的 5 个外向面（底面省略），顶点顺序已按 CCW 校验法线方向
const FACES: MockFace[] = [
  { corners: [[0, 1, 1], [1, 1, 1], [1, 1, 0], [0, 1, 0]], ao: 1.0, skylight: 15 }, // +Y 顶面
  { corners: [[1, 0, 1], [1, 0, 0], [1, 1, 0], [1, 1, 1]], ao: 0.8, skylight: 13 }, // +X
  { corners: [[0, 0, 0], [0, 0, 1], [0, 1, 1], [0, 1, 0]], ao: 0.8, skylight: 13 }, // -X
  { corners: [[0, 0, 1], [1, 0, 1], [1, 1, 1], [0, 1, 1]], ao: 0.9, skylight: 13 }, // +Z
  { corners: [[1, 0, 0], [0, 0, 0], [0, 1, 0], [1, 1, 0]], ao: 0.9, skylight: 13 }, // -Z
]

/** Constructs a full visible hires tile with varied block columns for development preview. */
function buildMockTile(): HiresTile {
  const positions: number[] = []
  const indices: number[] = []
  const uvs: number[] = []
  const colors: number[] = []
  const ao: number[] = []
  const blocklight: number[] = []
  const skylight: number[] = []

  let seed = 42
  const rand = (): number => {
    seed = (seed * 1103515245 + 12345) % 2147483648
    return seed / 2147483648
  }

  const baseY = 64
  for (let x = 0; x < 32; x += 1) {
    for (let z = 0; z < 32; z += 1) {
      const height = 1 + Math.floor(rand() * 5)
      const r = 0.25 + rand() * 0.75
      const g = 0.25 + rand() * 0.75
      const b = 0.25 + rand() * 0.75
      const torch = rand() < 0.12 ? 15 : 0
      for (const face of FACES) {
        const base = positions.length / 3
        for (const [ox, oy, oz] of face.corners) {
          positions.push(x + ox, baseY + oy * height, z + oz)
          uvs.push(0, 0) // 1×1 atlas，任意 uv 都取到同一 texel
          colors.push(r, g, b)
          ao.push(face.ao)
          blocklight.push(torch)
          skylight.push(face.skylight)
        }
        indices.push(base, base + 1, base + 2, base, base + 2, base + 3)
      }
    }
  }

  return { x: 0, z: 0, positions, indices, uvs, colors, ao, blocklight, skylight }
}

/**
 * Mock 数据源（URL query mock=1 时由 Viewer 启用）：
 * 仅 tile (0,0) 有内容，其余 404 语义返回 null；lowres 不提供；markers 空集。
 * 保证无后端也能渲染自查。
 */
export function createMockMapSource(): WorldMapSource {
  let tile: HiresTile | null = null
  return {
    loadSettings: async () => MOCK_SETTINGS,
    loadAtlas: async () => createMockAtlas(),
    fetchHiresTile: async (tx, tz) => {
      if (tx !== 0 || tz !== 0) {
        return null
      }
      tile ??= buildMockTile()
      return tile
    },
    lowresTileUrl: () => null,
    fetchMarkers: async () => ({ markerSets: [] }),
  }
}
