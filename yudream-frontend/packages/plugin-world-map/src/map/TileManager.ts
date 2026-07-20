import * as THREE from 'three'
import type { HiresTileGeometry, MapSettings } from '../types'
import type { WorldMapSource } from './types'
import { PrbmDecodePool } from '../bluemap-adapter/PrbmDecodePool'
import { shouldCreatePrbmDecoder } from '../bluemap-adapter/prbmDecodePolicy'
import { ensureBlueMapMaterialTextures, hasActiveBlueMapAnimations, stepBlueMapAnimations } from '../bluemap-adapter/BlueMapMaterials'
import { blueMapTilePosition } from './blueMapTilePosition'
import { blueMapLodForDistance, nextBlueMapHiresEnabled } from './blueMapLodPolicy'
import { hasBlueMapLowresTile } from '../bluemap-adapter/BlueMapLowresIndex'
import { configureBlueMapLowresTexture } from './blueMapLowresTexture'
import { createBlueMapLowresGeometry } from './blueMapLowresGeometry'
import { shouldMarkLowresLoadFailed, shouldRetainLowresTexture } from './lowresTextureLifecycle'
import { enqueueLowresRequest } from './lowresRequestQueue'
import type { LowresRequest as QueuedLowresRequest } from './lowresRequestQueue'
import { decodeLowresImage, releaseLowresImage } from './lowresImageDecode'
import { BlueMapVisibleMaterials } from './blueMapVisibleMaterials'
import { tileRequestPriority } from './tileRequestPriority'
import { shouldRetainHiresTile } from './hiresTileLifecycle'
import type { TileLoadStatus } from './tileLoadStatus'
import { normalizeHiresRadius, normalizeLowresCoverage } from './renderDistancePolicy'
import { createBlueMapLowresHeightSampler } from './blueMapLowresHeight'
import type { BlueMapLowresHeightSampler } from './blueMapLowresHeight'

interface HiresRecord {
  /** null 表示已请求但 tile 为空（404），缓存负结果避免重复请求 */
  mesh: THREE.Mesh | null
  /** 半透明段 mesh（水面等），可空 */
  translucentMesh?: THREE.Mesh | null
  /** BlueMap PRBM materials retained by this visible tile. */
  blueMapMaterials?: readonly THREE.ShaderMaterial[]
  lastUsed: number
}

interface LowresRecord {
  mesh: THREE.Mesh<THREE.PlaneGeometry, THREE.Material>
  texture: THREE.Texture | null
  heightSampler: BlueMapLowresHeightSampler | null
  request: AbortController | null
  disposed: boolean
  /** 纹理加载失败（404 等）时保留底色平面 */
  failed: boolean
  lastUsed: number
}

interface TileRequest {
  key: string
  tx: number
  tz: number
  priority: number
}

interface LowresRequestValue {
  record: LowresRecord
  url: string
  tx: number
  tz: number
}

export interface TileManagerOptions {
  /** hires 加载半径（单位：tile，默认 4 ≈ 128 方块） */
  hiresRadius?: number
  /** Lowres coverage radius multiplier used for the overview fallback. */
  lowresCoverage?: number
  /** 最大并发 tile 请求数（默认 6） */
  maxConcurrent?: number
  /** 有界队列容量，避免快速跳转时积压旧视野请求 */
  maxQueued?: number
  /** 瓦片可见内容变化时请求一帧绘制 */
  onChanged?: () => void
}

/**
 * hires tile 调度：
 * - 每帧按相机目标点收集半径内 tile，未加载的进入 FIFO 队列，并发上限加载
 * - 滚出 evictRadius 的 tile 按最近使用时间（最旧优先）卸载并释放 geometry
 * - 同时维护 lowres 金字塔平面作为加载中 / 远处的兜底
 */
export class TileManager {
  private readonly group = new THREE.Group()
  private readonly lowresGroup = new THREE.Group()
  private readonly hires = new Map<string, HiresRecord>()
  private readonly lowres = new Map<string, LowresRecord>()
  private readonly queue: TileRequest[] = []
  private readonly queued = new Set<string>()
  private readonly inFlightRequests = new Map<string, AbortController>()
  private readonly lowresQueue: Array<QueuedLowresRequest<LowresRequestValue>> = []
  private readonly lowresQueued = new Set<string>()
  private readonly lowresDesired = new Set<string>()
  private readonly failedUntil = new Map<string, number>()
  private readonly failureCounts = new Map<string, number>()
  /** Reference counts keep animation work proportional to visible terrain, not the global material table. */
  private readonly visibleBlueMapMaterials = new BlueMapVisibleMaterials()
  /** Created only when the first BlueMap PRBM tile actually needs decoding. */
  private prbmDecoder: PrbmDecodePool | null = null
  private inFlight = 0
  private lowresInFlight = 0
  private frame = 0
  private centerTx = 0
  private centerTz = 0
  private desired = new Set<string>()
  private dayFactor = 1
  private blueMapHiresEnabled = true
  private hiresRadius: number
  private lowresCoverage: number
  private readonly cameraDirection = new THREE.Vector3()
  private disposed = false

  constructor(
    private readonly source: WorldMapSource,
    private readonly settings: MapSettings,
    /** 共享地形材质，由 MapViewer 持有与释放，这里不 dispose */
    private readonly material: THREE.Material | THREE.Material[],
    /** 共享半透明地形材质（水面等），同样由 MapViewer 持有与释放 */
    private readonly translucentMaterial: THREE.ShaderMaterial,
    parent: THREE.Object3D,
    private readonly options: TileManagerOptions = {},
  ) {
    this.hiresRadius = normalizeHiresRadius(options.hiresRadius)
    this.lowresCoverage = normalizeLowresCoverage(options.lowresCoverage)
    this.group.name = 'world-map-hires'
    this.lowresGroup.name = 'world-map-lowres'
    parent.add(this.lowresGroup, this.group)
  }

  update(camera: THREE.Camera, target: THREE.Vector3): void {
    if (this.disposed) {
      return
    }
    this.frame += 1
    camera.getWorldDirection(this.cameraDirection)
    const tileSize = this.settings.hiresTileSize
    const radius = this.hiresRadius
    const offset = this.settings.hiresTileOffset ?? { x: 0, z: 0 }
    const nextCenterTx = Math.floor((target.x - offset.x) / tileSize)
    const nextCenterTz = Math.floor((target.z - offset.z) / tileSize)
    if (nextCenterTx !== this.centerTx || nextCenterTz !== this.centerTz) {
      this.centerTx = nextCenterTx
      this.centerTz = nextCenterTz
    }
    const desired = new Set<string>()

    // 1. BlueMap stops requesting expensive PRBM tiles when the camera is in distant overview mode.
    const distance = camera.position.distanceTo(target)
    if (this.settings.renderer === 'BLUEMAP') {
      this.blueMapHiresEnabled = nextBlueMapHiresEnabled(this.blueMapHiresEnabled, distance)
    }
    const loadHires = this.settings.renderer !== 'BLUEMAP' || this.blueMapHiresEnabled
    if (loadHires) {
      for (let dx = -radius; dx <= radius; dx += 1) {
        for (let dz = -radius; dz <= radius; dz += 1) {
          if (dx * dx + dz * dz > radius * radius) {
            continue
          }
          const key = `${this.centerTx + dx},${this.centerTz + dz}`
          desired.add(key)
          const record = this.hires.get(key)
          if (record) {
            record.lastUsed = this.frame
            continue
          }
          if (!this.queued.has(key) && !this.inFlightRequests.has(key) && !this.isInBackoff(key)) {
            this.enqueue(key, this.centerTx + dx, this.centerTz + dz, this.priorityForOffset(dx, dz))
          }
        }
      }
    }
    this.replaceDesired(desired)
    this.reprioritizeHires()

    // 2. 卸载超半径 tile（LRU：最旧未使用的先卸）
    const evictRadius = radius + 1.5
    const stale: [string, HiresRecord][] = []
    for (const [key, record] of this.hires) {
      const [tx, tz] = key.split(',').map(Number)
      if (!loadHires || Math.hypot(tx - this.centerTx, tz - this.centerTz) > evictRadius) {
        stale.push([key, record])
      }
    }
    stale.sort((a, b) => a[1].lastUsed - b[1].lastUsed)
    for (const [key, record] of stale) {
      this.disposeHiresRecord(record)
      this.hires.delete(key)
    }

    this.updateLowres(camera, target)
    this.pump()
    this.pumpLowres()
  }

  /** Applies a bounded terrain distance without recreating the map or decoded tiles. */
  setHiresRadius(radius: number): void {
    this.hiresRadius = normalizeHiresRadius(radius)
  }

  /** Applies a bounded overview distance while preserving tiles that remain in range. */
  setLowresCoverage(coverage: number): void {
    this.lowresCoverage = normalizeLowresCoverage(coverage)
  }

  /** 按并发上限驱动加载队列 */
  private pump(): void {
    const maxConcurrent = this.options.maxConcurrent ?? 6
    const evictRadius = this.hiresRadius + 1.5
    while (this.inFlight < maxConcurrent && this.queue.length > 0) {
      const request = this.queue.shift()!
      this.queued.delete(request.key)
      const { key, tx, tz } = request
      if (Math.hypot(tx - this.centerTx, tz - this.centerTz) > evictRadius) {
        continue // 排队期间已滚出范围，直接丢弃
      }
      const controller = new AbortController()
      this.inFlightRequests.set(key, controller)
      this.inFlight += 1
      this.source.fetchHiresTile(tx, tz, controller.signal)
        .then(async (tile) => {
          // A move may change the center tile while this request remains inside the new visible
          // disk. Retain that useful work instead of making every tile stale by view generation.
          if (!shouldRetainHiresTile(this.disposed, this.desired.has(key))) {
            return
          }
          if (!tile || (!(tile instanceof ArrayBuffer) && tile.positions.length === 0)) {
            this.hires.set(key, { mesh: null, lastUsed: this.frame })
            this.failureCounts.delete(key)
            this.options.onChanged?.()
            return
          }
          const mesh = tile instanceof ArrayBuffer
            ? await this.buildPrbmMesh(tile, this.material, tx, tz)
            : this.buildMesh(tile, this.material)
          if (!shouldRetainHiresTile(this.disposed, this.desired.has(key))) {
            mesh.geometry.dispose()
            return
          }
          const translucentMesh = !(tile instanceof ArrayBuffer) && tile.translucent && tile.translucent.positions.length > 0
            ? this.buildMesh(tile.translucent, this.translucentMaterial)
            : null
          const blueMapMaterials = tile instanceof ArrayBuffer
            ? this.retainBlueMapMaterials(mesh.geometry)
            : undefined
          this.hires.set(key, { mesh, translucentMesh, blueMapMaterials, lastUsed: this.frame })
          this.group.add(mesh)
          if (translucentMesh) {
            this.group.add(translucentMesh)
          }
          this.failureCounts.delete(key)
          this.options.onChanged?.()
        })
        .catch((error: unknown) => {
          if (!controller.signal.aborted) {
            const failures = (this.failureCounts.get(key) ?? 0) + 1
            this.failureCounts.set(key, failures)
            const delay = this.retryDelay(failures)
            this.failedUntil.set(key, performance.now() + delay)
            setTimeout(() => {
              if (!this.disposed && this.desired.has(key)) {
                this.options.onChanged?.()
              }
            }, delay)
            void error
          }
        })
        .finally(() => {
          this.inFlight -= 1
          this.inFlightRequests.delete(key)
          if (!this.disposed) {
            this.pump()
          }
        })
    }
  }

  private enqueue(key: string, tx: number, tz: number, priority: number): void {
    const maxQueued = this.options.maxQueued ?? 96
    if (this.queue.length >= maxQueued) {
      const worst = this.queue.reduce((index, item, candidate) =>
        item.priority > this.queue[index]!.priority ? candidate : index, 0)
      if (this.queue[worst]!.priority <= priority) {
        return
      }
      this.queued.delete(this.queue[worst]!.key)
      this.queue.splice(worst, 1)
    }
    this.queued.add(key)
    this.queue.push({ key, tx, tz, priority })
    this.queue.sort((a, b) => a.priority - b.priority || a.key.localeCompare(b.key))
  }

  /** Re-ranks outstanding requests after a camera turn without aborting useful in-flight work. */
  private reprioritizeHires(): void {
    for (const request of this.queue) {
      request.priority = this.priorityForOffset(request.tx - this.centerTx, request.tz - this.centerTz)
    }
    this.queue.sort((a, b) => a.priority - b.priority || a.key.localeCompare(b.key))
  }

  private priorityForOffset(dx: number, dz: number): number {
    return tileRequestPriority(dx, dz, this.cameraDirection.x, this.cameraDirection.z)
  }

  private replaceDesired(desired: Set<string>): void {
    this.desired = desired
    for (const [key, controller] of this.inFlightRequests) {
      if (!desired.has(key)) {
        controller.abort()
      }
    }
    for (let index = this.queue.length - 1; index >= 0; index -= 1) {
      if (!desired.has(this.queue[index]!.key)) {
        this.queued.delete(this.queue[index]!.key)
        this.queue.splice(index, 1)
      }
    }
  }

  private isInBackoff(key: string): boolean {
    const until = this.failedUntil.get(key)
    if (!until) {
      return false
    }
    if (performance.now() >= until) {
      this.failedUntil.delete(key)
      return false
    }
    return true
  }

  private retryDelay(failures: number): number {
    return Math.min(30_000, 1_000 * 2 ** Math.min(failures - 1, 5))
  }

  private buildMesh(tile: HiresTileGeometry, material: THREE.Material | THREE.Material[]): THREE.Mesh {
    const geometry = new THREE.BufferGeometry()
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(tile.positions, 3))
    // 顶点数可能超过 65535，setIndex(number[]) 会自动选用 Uint32
    geometry.setIndex(tile.indices)
    geometry.setAttribute('uv', new THREE.Float32BufferAttribute(tile.uvs, 2))
    geometry.setAttribute('color', new THREE.Float32BufferAttribute(tile.colors, 3))
    geometry.setAttribute('ao', new THREE.Float32BufferAttribute(tile.ao, 1))
    geometry.setAttribute('blocklight', new THREE.Float32BufferAttribute(tile.blocklight, 1))
    geometry.setAttribute('skylight', new THREE.Float32BufferAttribute(tile.skylight, 1))
    geometry.computeBoundingSphere()
    const mesh = new THREE.Mesh(geometry, material)
    // 顶点为世界绝对坐标，mesh 保持单位变换
    mesh.matrixAutoUpdate = false
    return mesh
  }

  private async buildPrbmMesh(data: ArrayBuffer, material: THREE.Material | THREE.Material[], tx: number, tz: number): Promise<THREE.Mesh> {
    const geometry = await this.decoderForPrbm().decode(data)
    if (Array.isArray(material)) {
      const invalidGroup = geometry.groups.find(group => {
        const materialIndex = group.materialIndex
        return materialIndex === undefined || materialIndex < 0 || materialIndex >= material.length
      })
      if (invalidGroup) {
        geometry.dispose()
        throw new Error(`PRBM tile references unavailable material ${invalidGroup.materialIndex ?? 'unknown'}`)
      }
      void ensureBlueMapMaterialTextures(material as THREE.ShaderMaterial[], geometry).then(
        () => this.options.onChanged?.(),
        () => this.options.onChanged?.(),
      )
    }
    const mesh = new THREE.Mesh(geometry, material)
    // BlueMap PRBM vertices are tile-local. Its viewer translates every tile by
    // tile coordinate * grid size + the grid's configured translate vector.
    const position = blueMapTilePosition(this.settings, tx, tz)
    mesh.position.set(position.x, 0, position.z)
    mesh.matrixAutoUpdate = false
    mesh.updateMatrix()
    return mesh
  }

  private decoderForPrbm(): PrbmDecodePool {
    if (!shouldCreatePrbmDecoder(this.settings.renderer)) {
      throw new Error('PRBM terrain is only supported by the BlueMap renderer')
    }
    this.prbmDecoder ??= new PrbmDecodePool()
    return this.prbmDecoder
  }

  private retainBlueMapMaterials(geometry: THREE.BufferGeometry): readonly THREE.ShaderMaterial[] | undefined {
    if (!Array.isArray(this.material)) {
      return undefined
    }
    const materials = new Set<THREE.ShaderMaterial>()
    for (const group of geometry.groups) {
      const index = group.materialIndex
      const material = index === undefined ? undefined : this.material[index]
      if (material instanceof THREE.ShaderMaterial) {
        materials.add(material)
      }
    }
    const retained = [...materials]
    this.visibleBlueMapMaterials.retain(retained)
    return retained
  }

  private disposeHiresRecord(record: HiresRecord): void {
    this.visibleBlueMapMaterials.release(record.blueMapMaterials ?? [])
    record.mesh?.geometry.dispose()
    record.mesh?.removeFromParent()
    record.translucentMesh?.geometry.dispose()
    record.translucentMesh?.removeFromParent()
  }

  /** lowres 金字塔平面：按相机距离选 lod，覆盖视野范围，作为 hires 的兜底 */
  private updateLowres(camera: THREE.Camera, target: THREE.Vector3): void {
    const minLod = this.settings.lowresMinLod ?? 0
    const maxLod = this.settings.lowresMaxLod
    const distance = camera.position.distanceTo(target)
    const lod = this.settings.renderer === 'BLUEMAP'
      ? blueMapLodForDistance(distance, minLod, maxLod, this.settings.lowresLodFactor ?? 5)
      : THREE.MathUtils.clamp(Math.floor(Math.log2(Math.max(distance, 1) / 256)), minLod, maxLod)
    const tileSize = this.settings.lowresTileSize * (this.settings.lowresLodFactor ?? 2) ** (lod - minLod)
    const centerTx = Math.floor(target.x / tileSize)
    const centerTz = Math.floor(target.z / tileSize)
    if (this.source.lowresTileUrl(lod, centerTx, centerTz) === null) {
      return // 数据源不提供 lowres（如 mock）
    }

    const coverRadius = Math.max(tileSize * this.lowresCoverage, distance * this.lowresCoverage)
    const range = Math.ceil(coverRadius / tileSize)
    const wanted = new Set<string>()
    for (let dx = -range; dx <= range; dx += 1) {
      for (let dz = -range; dz <= range; dz += 1) {
        const tx = centerTx + dx
        const tz = centerTz + dz
        if (this.settings.renderer === 'BLUEMAP'
          && !hasBlueMapLowresTile(this.settings.lowresTileIndex, lod, tx, tz)) {
          continue
        }
        const key = `${lod},${tx},${tz}`
        wanted.add(key)
        let record = this.lowres.get(key)
        if (!record) {
          record = this.createLowresTile(lod, tx, tz, tileSize)
          this.lowres.set(key, record)
          this.lowresGroup.add(record.mesh)
        }
        const url = this.source.lowresTileUrl(lod, tx, tz)
        if (url && !record.texture && !record.request && !record.failed && !this.lowresQueued.has(key)) {
          this.enqueueLowres(key, record, url, tx, tz, this.priorityForOffset(dx, dz))
        }
        record.lastUsed = this.frame
        // 该区域 hires 已全部就位时隐藏低清平面，避免平面切入山体
        record.mesh.visible = !this.isCoveredByHires(tx, tz, tileSize)
      }
    }

    this.lowresDesired.clear()
    for (const key of wanted) this.lowresDesired.add(key)
    for (let index = this.lowresQueue.length - 1; index >= 0; index -= 1) {
      if (!wanted.has(this.lowresQueue[index]!.key)) {
        this.lowresQueued.delete(this.lowresQueue[index]!.key)
        this.lowresQueue.splice(index, 1)
      }
    }
    this.reprioritizeLowres(centerTx, centerTz)

    for (const [key, record] of this.lowres) {
      if (!wanted.has(key)) {
        this.disposeLowres(record)
        this.lowres.delete(key)
      }
    }
  }

  private createLowresTile(lod: number, tx: number, tz: number, tileSize: number): LowresRecord {
    const segments = this.settings.renderer === 'BLUEMAP' ? Math.max(4, Math.ceil(100 / (lod * 2))) : 1
    const isBlueMap = this.settings.renderer === 'BLUEMAP'
    const minLod = this.settings.lowresMinLod ?? 0
    const lodScale = isBlueMap
      ? (this.settings.lowresLodFactor ?? 5) ** (lod - minLod)
      : 1
    const baseTileSize = isBlueMap ? this.settings.lowresTileSize : tileSize
    const geometry = isBlueMap
      ? createBlueMapLowresGeometry(baseTileSize, segments)
      : new THREE.PlaneGeometry(tileSize, tileSize, segments, segments)
    if (!isBlueMap) geometry.rotateX(-Math.PI / 2)
    const material = this.settings.renderer === 'BLUEMAP'
      ? createBlueMapLowresMaterial()
      : new THREE.MeshBasicMaterial({
      color: 0x1a2436,
      fog: true,
      polygonOffset: true,
      polygonOffsetFactor: 1,
      polygonOffsetUnits: 1,
      // 不写深度 + 先渲染：hires 几何始终在兜底平面之上，
      // 避免平面高度（spawn.y 近似）高于实际地形时遮挡 hires
      depthWrite: false,
      transparent: true,
    })
    const mesh = new THREE.Mesh(geometry, material)
    if (material instanceof THREE.ShaderMaterial) {
      material.uniforms.tileSize.value.set(baseTileSize, baseTileSize)
      material.uniforms.lodScale.value = lodScale
      material.uniforms.sunlight.value = 0.75 * this.dayFactor
      material.uniforms.ambient.value = 0.18 + 0.14 * this.dayFactor
    }
    mesh.renderOrder = -1
    // 低清为俯视正交投影，平面放在地表平均高度附近（契约 §5：近似 64 或 settings 推导）
    if (isBlueMap) {
      mesh.position.set(tx * baseTileSize * lodScale, 0, tz * baseTileSize * lodScale)
      mesh.scale.set(lodScale, 1, lodScale)
    } else {
      mesh.position.set((tx + 0.5) * tileSize, this.settings.spawn.y, (tz + 0.5) * tileSize)
    }
    mesh.matrixAutoUpdate = false
    mesh.updateMatrix()
    const record: LowresRecord = { mesh, texture: null, heightSampler: null, request: null, disposed: false, failed: false, lastUsed: this.frame }
    return record
  }

  private enqueueLowres(key: string, record: LowresRecord, url: string, tx: number, tz: number, priority: number): void {
    const request = { key, priority, value: { record, url, tx, tz } }
    if (enqueueLowresRequest(this.lowresQueue, request, 48)) {
      this.lowresQueued.add(key)
    }
  }

  private reprioritizeLowres(centerTx: number, centerTz: number): void {
    for (const request of this.lowresQueue) {
      request.priority = this.priorityForOffset(request.value.tx - centerTx, request.value.tz - centerTz)
    }
    this.lowresQueue.sort((a, b) => a.priority - b.priority || a.key.localeCompare(b.key))
  }

  /** Lowres raster work gets its own small budget so it cannot starve nearby PRBM detail tiles. */
  private pumpLowres(): void {
    const maxConcurrent = 4
    while (this.lowresInFlight < maxConcurrent && this.lowresQueue.length > 0) {
      const queued = this.lowresQueue.shift()!
      this.lowresQueued.delete(queued.key)
      const { key, value: { record, url } } = queued
      if (record.disposed || !this.lowresDesired.has(key) || record.texture || record.request || record.failed) continue
      const request = new AbortController()
      record.request = request
      this.lowresInFlight += 1
      this.loadLowresTexture(url, request.signal)
        .then((texture) => {
          if (!shouldRetainLowresTexture(this.disposed, record.disposed)) {
            disposeLowresTexture(texture)
            return
          }
          if (this.settings.renderer === 'BLUEMAP') {
            configureBlueMapLowresTexture(texture)
          } else {
            texture.colorSpace = THREE.SRGBColorSpace
            texture.magFilter = THREE.LinearFilter
            texture.minFilter = THREE.LinearFilter
            texture.generateMipmaps = false
            texture.flipY = true
          }
          record.texture = texture
          try {
            record.heightSampler = this.settings.renderer === 'BLUEMAP'
              ? createBlueMapLowresHeightSampler(texture.image as ImageBitmap | HTMLImageElement)
              : null
          }
          catch {
            // A browser can reject a temporary 2D canvas while WebGL remains healthy.
            record.heightSampler = null
          }
          const material = record.mesh.material
          if (material instanceof THREE.ShaderMaterial) {
            material.uniforms.textureImage.value = texture
            const image = texture.image as { width?: number, height?: number }
            material.uniforms.textureSize.value.set(Number(image.width) || 1, Number(image.height) || 2)
          } else if (material instanceof THREE.MeshBasicMaterial) {
            material.map = texture
            material.color.set(0xffffff)
          }
          material.needsUpdate = true
          this.options.onChanged?.()
        })
        .catch(() => {
          if (shouldMarkLowresLoadFailed(record.disposed, request.signal.aborted)) {
            record.failed = true
          }
        })
        .finally(() => {
          if (record.request === request) record.request = null
          this.lowresInFlight -= 1
          if (!this.disposed) this.pumpLowres()
        })
    }
  }

  /** 判断 lowres tile 覆盖的 hires 区域是否已全部加载完成 */
  private isCoveredByHires(tx: number, tz: number, tileSize: number): boolean {
    const hiresSize = this.settings.hiresTileSize
    const offset = this.settings.hiresTileOffset ?? { x: 0, z: 0 }
    const x0 = Math.floor((tx * tileSize - offset.x) / hiresSize)
    const z0 = Math.floor((tz * tileSize - offset.z) / hiresSize)
    const count = Math.ceil(tileSize / hiresSize)
    for (let x = x0; x < x0 + count; x += 1) {
      for (let z = z0; z < z0 + count; z += 1) {
        if (!this.hires.has(`${x},${z}`)) {
          return false
        }
      }
    }
    return true
  }

  private disposeLowres(record: LowresRecord): void {
    if (record.disposed) return
    record.disposed = true
    record.request?.abort()
    record.request = null
    if (record.texture) disposeLowresTexture(record.texture)
    record.texture = null
    record.heightSampler = null
    if (record.mesh.material instanceof THREE.ShaderMaterial) {
      record.mesh.material.uniforms.textureImage.value = null
    } else if (record.mesh.material instanceof THREE.MeshBasicMaterial) {
      record.mesh.material.map = null
    }
    record.mesh.geometry.dispose()
    record.mesh.material.dispose()
    record.mesh.removeFromParent()
  }

  /** Fetch gives lowres tiles the same abort behavior as hires requests during rapid navigation. */
  private async loadLowresTexture(url: string, signal: AbortSignal): Promise<THREE.Texture> {
    const response = await fetch(url, { signal })
    if (!response.ok) throw new Error(`lowres tile: HTTP ${response.status}`)
    const image = await decodeLowresImage(await response.blob())
    const texture = new THREE.Texture(image)
    texture.needsUpdate = true
    return texture
  }

  /** 排队中 + 加载中的 hires tile 数 */
  get pendingCount(): number {
    return this.queue.length + this.inFlight + this.lowresQueue.length + this.lowresInFlight
  }

  /** Breakdown for UI feedback; retrying entries are temporarily backed off after a transport failure. */
  get loadStatus(): TileLoadStatus {
    const now = performance.now()
    let retrying = 0
    for (const until of this.failedUntil.values()) {
      if (until > now) retrying += 1
    }
    return {
      hiresQueued: this.queue.length,
      hiresLoading: this.inFlight,
      lowresQueued: this.lowresQueue.length,
      lowresLoading: this.lowresInFlight,
      retrying,
    }
  }

  /** Whether a currently visible PRBM mesh references a decoded animated material. */
  get hasActiveAnimations(): boolean {
    return hasActiveBlueMapAnimations(this.visibleBlueMapMaterials.values)
  }

  /** Raycasts only currently loaded terrain; this is intentionally user-gesture driven, never per frame. */
  raycastTerrain(raycaster: THREE.Raycaster): THREE.Intersection<THREE.Object3D> | null {
    const intersections = raycaster.intersectObjects([this.group, this.lowresGroup], true)
    const hit = intersections.find(intersection => intersection.object.visible) ?? null
    if (!hit) return null
    const lowresRecord = [...this.lowres.values()].find(record => record.mesh === hit.object)
    if (lowresRecord?.heightSampler) {
      this.correctLowresHit(raycaster.ray, hit, lowresRecord)
    }
    return hit
  }

  /** Corrects a base-plane ray hit to the GPU-displaced BlueMap lowres terrain surface. */
  private correctLowresHit(ray: THREE.Ray, hit: THREE.Intersection<THREE.Object3D>, record: LowresRecord): void {
    let point = hit.point.clone()
    const normal = new THREE.Vector3(0, 1, 0).transformDirection(record.mesh.matrixWorld).normalize()
    const denominator = ray.direction.dot(normal)
    if (Math.abs(denominator) < 0.000_001) return
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const local = record.mesh.worldToLocal(point.clone())
      const height = record.heightSampler?.(local.x, local.z)
      if (height === null || height === undefined) return
      const terrainPoint = new THREE.Vector3(local.x, height, local.z).applyMatrix4(record.mesh.matrixWorld)
      const distance = terrainPoint.clone().sub(ray.origin).dot(normal) / denominator
      if (!Number.isFinite(distance) || distance < 0) return
      point = ray.at(distance, new THREE.Vector3())
    }
    hit.point.copy(point)
    hit.distance = ray.origin.distanceTo(point)
  }

  /** Advances only the material subset currently referenced by visible PRBM terrain. */
  stepVisibleBlueMapAnimations(deltaMs: number): void {
    stepBlueMapAnimations(this.visibleBlueMapMaterials.values, deltaMs)
  }

  setDayFactor(dayFactor: number): void {
    this.dayFactor = dayFactor
    for (const record of this.lowres.values()) {
      if (!(record.mesh.material instanceof THREE.ShaderMaterial)) continue
      record.mesh.material.uniforms.sunlight.value = 0.75 * dayFactor
      record.mesh.material.uniforms.ambient.value = 0.18 + 0.14 * dayFactor
    }
  }

  dispose(): void {
    this.disposed = true
    this.queue.length = 0
    this.queued.clear()
    this.lowresQueue.length = 0
    this.lowresQueued.clear()
    this.lowresDesired.clear()
    for (const controller of this.inFlightRequests.values()) {
      controller.abort()
    }
    this.inFlightRequests.clear()
    this.failedUntil.clear()
    this.failureCounts.clear()
    for (const record of this.hires.values()) {
      this.disposeHiresRecord(record)
    }
    this.hires.clear()
    this.visibleBlueMapMaterials.clear()
    this.prbmDecoder?.dispose()
    this.prbmDecoder = null
    for (const record of this.lowres.values()) {
      this.disposeLowres(record)
    }
    this.lowres.clear()
    this.group.removeFromParent()
    this.lowresGroup.removeFromParent()
  }
}

function disposeLowresTexture(texture: THREE.Texture): void {
  releaseLowresImage(texture.image as ImageBitmap | HTMLImageElement | null)
  texture.dispose()
}

/** BlueMap lowres PNGs pack color in the top half and height/light metadata below it. */
export function createBlueMapLowresMaterial(): THREE.ShaderMaterial {
  const uniforms = THREE.UniformsUtils.merge([
    THREE.UniformsLib.fog,
    {
      textureImage: { value: null },
      tileSize: { value: new THREE.Vector2(1, 1) },
      textureSize: { value: new THREE.Vector2(1, 2) },
      lodScale: { value: 1 },
      sunlight: { value: 0.78 },
      ambient: { value: 0.22 },
      voidColor: { value: new THREE.Color(0x1a2436) },
    },
  ])
  return new THREE.ShaderMaterial({
    uniforms,
    fog: true,
    transparent: true,
    depthWrite: false,
    vertexShader: /* glsl */ `
      uniform sampler2D textureImage;
      uniform vec2 textureSize;
      varying vec2 vLocal;
      varying float vLight;
      float heightFromMeta(vec4 meta) {
        float unsignedHeight = meta.g * 65280.0 + meta.b * 255.0;
        return unsignedHeight >= 32768.0 ? -(65535.0 - unsignedHeight) : unsignedHeight;
      }
      #include <fog_pars_vertex>
      void main() {
        vLocal = position.xz;
        vec2 metaUv = vec2(vLocal.x / textureSize.x, vLocal.y / textureSize.y + 0.5);
        vec4 meta = texture2D(textureImage, metaUv);
        vLight = meta.r * 255.0;
        vec3 displaced = position;
        displaced.y = heightFromMeta(meta) + 1.0 - position.x * 0.0001 - position.z * 0.0002;
        vec4 mvPosition = modelViewMatrix * vec4(displaced, 1.0);
        gl_Position = projectionMatrix * mvPosition;
        #include <fog_vertex>
      }
    `,
    fragmentShader: /* glsl */ `
      uniform sampler2D textureImage;
      uniform vec2 tileSize;
      uniform vec2 textureSize;
      uniform float lodScale;
      uniform float sunlight;
      uniform float ambient;
      uniform vec3 voidColor;
      varying vec2 vLocal;
      varying float vLight;
      float heightFromMeta(vec4 meta) {
        float unsignedHeight = meta.g * 65280.0 + meta.b * 255.0;
        return unsignedHeight >= 32768.0 ? -(65535.0 - unsignedHeight) : unsignedHeight;
      }
      #include <fog_pars_fragment>
      void main() {
        vec2 colorUv = vec2(vLocal.x / textureSize.x, min(vLocal.y, tileSize.y) / textureSize.y);
        vec2 metaUv = vec2(vLocal.x / textureSize.x, vLocal.y / textureSize.y + 0.5);
        vec4 color = texture2D(textureImage, colorUv);
        color = sRGBTransferEOTF(color);
        float height = heightFromMeta(texture2D(textureImage, metaUv));
        float heightX = heightFromMeta(texture2D(textureImage, metaUv + vec2(1.0 / textureSize.x, 0.0)));
        float heightZ = heightFromMeta(texture2D(textureImage, metaUv + vec2(0.0, 1.0 / textureSize.y)));
        float shade = clamp(((height - heightX) + (height - heightZ)) / lodScale * 0.06, -0.2, 0.04);
        float light = mix(vLight, 15.0, sunlight);
        color.rgb *= mix(ambient, 1.0, light / 15.0);
        color.rgb += shade;
        color.rgb = mix(voidColor, color.rgb, color.a);
        gl_FragColor = vec4(color.rgb, 1.0);
        #include <fog_fragment>
        #include <colorspace_fragment>
      }
    `,
  })
}
