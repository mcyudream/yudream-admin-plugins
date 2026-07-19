import * as THREE from 'three'
import type { HiresTileGeometry, MapSettings } from '../types'
import type { WorldMapSource } from './types'
import { decodePrbm } from '../bluemap-adapter/PrbmDecoder'
import { ensureBlueMapMaterialTextures } from '../bluemap-adapter/BlueMapMaterials'
import { blueMapTilePosition } from './blueMapTilePosition'

interface HiresRecord {
  /** null 表示已请求但 tile 为空（404），缓存负结果避免重复请求 */
  mesh: THREE.Mesh | null
  /** 半透明段 mesh（水面等），可空 */
  translucentMesh?: THREE.Mesh | null
  lastUsed: number
}

interface LowresRecord {
  mesh: THREE.Mesh<THREE.PlaneGeometry, THREE.Material>
  texture: THREE.Texture | null
  /** 纹理加载失败（404 等）时保留底色平面 */
  failed: boolean
  lastUsed: number
}

interface TileRequest {
  key: string
  tx: number
  tz: number
  epoch: number
  priority: number
}

export interface TileManagerOptions {
  /** hires 加载半径（单位：tile，默认 4 ≈ 128 方块） */
  hiresRadius?: number
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
  private readonly failedUntil = new Map<string, number>()
  private readonly failureCounts = new Map<string, number>()
  private readonly loader = new THREE.TextureLoader()
  private inFlight = 0
  private frame = 0
  private centerTx = 0
  private centerTz = 0
  private viewEpoch = 0
  private desired = new Set<string>()
  private dayFactor = 1
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
    this.group.name = 'world-map-hires'
    this.lowresGroup.name = 'world-map-lowres'
    parent.add(this.lowresGroup, this.group)
  }

  update(camera: THREE.Camera, target: THREE.Vector3): void {
    if (this.disposed) {
      return
    }
    this.frame += 1
    const tileSize = this.settings.hiresTileSize
    const radius = this.options.hiresRadius ?? 4
    const offset = this.settings.hiresTileOffset ?? { x: 0, z: 0 }
    const nextCenterTx = Math.floor((target.x - offset.x) / tileSize)
    const nextCenterTz = Math.floor((target.z - offset.z) / tileSize)
    if (nextCenterTx !== this.centerTx || nextCenterTz !== this.centerTz) {
      this.centerTx = nextCenterTx
      this.centerTz = nextCenterTz
      this.viewEpoch += 1
    }
    const desired = new Set<string>()

    // 1. 收集半径内的 hires tile，未加载的入队
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
          this.enqueue(key, this.centerTx + dx, this.centerTz + dz, dx * dx + dz * dz)
        }
      }
    }
    this.replaceDesired(desired)

    // 2. 卸载超半径 tile（LRU：最旧未使用的先卸）
    const evictRadius = radius + 1.5
    const stale: [string, HiresRecord][] = []
    for (const [key, record] of this.hires) {
      const [tx, tz] = key.split(',').map(Number)
      if (Math.hypot(tx - this.centerTx, tz - this.centerTz) > evictRadius) {
        stale.push([key, record])
      }
    }
    stale.sort((a, b) => a[1].lastUsed - b[1].lastUsed)
    for (const [key, record] of stale) {
      record.mesh?.geometry.dispose()
      record.mesh?.removeFromParent()
      record.translucentMesh?.geometry.dispose()
      record.translucentMesh?.removeFromParent()
      this.hires.delete(key)
    }

    this.pump()
    this.updateLowres(camera, target)
  }

  /** 按并发上限驱动加载队列 */
  private pump(): void {
    const maxConcurrent = this.options.maxConcurrent ?? 6
    const evictRadius = (this.options.hiresRadius ?? 4) + 1.5
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
        .then((tile) => {
          if (this.disposed || request.epoch !== this.viewEpoch || !this.desired.has(key)) {
            return
          }
          if (!tile || (!(tile instanceof ArrayBuffer) && tile.positions.length === 0)) {
            this.hires.set(key, { mesh: null, lastUsed: this.frame })
            this.failureCounts.delete(key)
            this.options.onChanged?.()
            return
          }
          const mesh = tile instanceof ArrayBuffer
            ? this.buildPrbmMesh(tile, this.material, tx, tz)
            : this.buildMesh(tile, this.material)
          const translucentMesh = !(tile instanceof ArrayBuffer) && tile.translucent && tile.translucent.positions.length > 0
            ? this.buildMesh(tile.translucent, this.translucentMaterial)
            : null
          this.hires.set(key, { mesh, translucentMesh, lastUsed: this.frame })
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
    this.queue.push({ key, tx, tz, epoch: this.viewEpoch, priority })
    this.queue.sort((a, b) => a.priority - b.priority || a.key.localeCompare(b.key))
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

  private buildPrbmMesh(data: ArrayBuffer, material: THREE.Material | THREE.Material[], tx: number, tz: number): THREE.Mesh {
    const geometry = decodePrbm(data)
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

  /** lowres 金字塔平面：按相机距离选 lod，覆盖视野范围，作为 hires 的兜底 */
  private updateLowres(camera: THREE.Camera, target: THREE.Vector3): void {
    const minLod = this.settings.lowresMinLod ?? 0
    const maxLod = this.settings.lowresMaxLod
    const distance = camera.position.distanceTo(target)
    const lod = THREE.MathUtils.clamp(Math.floor(Math.log2(Math.max(distance, 1) / 256)), minLod, maxLod)
    const tileSize = this.settings.lowresTileSize * (this.settings.lowresLodFactor ?? 2) ** (lod - minLod)
    const centerTx = Math.floor(target.x / tileSize)
    const centerTz = Math.floor(target.z / tileSize)
    if (this.source.lowresTileUrl(lod, centerTx, centerTz) === null) {
      return // 数据源不提供 lowres（如 mock）
    }

    const coverRadius = Math.max(tileSize * 1.5, distance * 1.2)
    const range = Math.ceil(coverRadius / tileSize)
    const wanted = new Set<string>()
    for (let dx = -range; dx <= range; dx += 1) {
      for (let dz = -range; dz <= range; dz += 1) {
        const tx = centerTx + dx
        const tz = centerTz + dz
        const key = `${lod},${tx},${tz}`
        wanted.add(key)
        let record = this.lowres.get(key)
        if (!record) {
          record = this.createLowresTile(lod, tx, tz, tileSize)
          this.lowres.set(key, record)
          this.lowresGroup.add(record.mesh)
        }
        record.lastUsed = this.frame
        // 该区域 hires 已全部就位时隐藏低清平面，避免平面切入山体
        record.mesh.visible = !this.isCoveredByHires(tx, tz, tileSize)
      }
    }

    for (const [key, record] of this.lowres) {
      if (!wanted.has(key)) {
        this.disposeLowres(record)
        this.lowres.delete(key)
      }
    }
  }

  private createLowresTile(lod: number, tx: number, tz: number, tileSize: number): LowresRecord {
    const segments = this.settings.renderer === 'BLUEMAP' ? Math.max(4, Math.ceil(100 / (lod * 2))) : 1
    const geometry = new THREE.PlaneGeometry(tileSize, tileSize, segments, segments)
    geometry.rotateX(-Math.PI / 2)
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
      material.uniforms.tileExtent.value = tileSize
      material.uniforms.sunlight.value = 0.75 * this.dayFactor
      material.uniforms.ambient.value = 0.18 + 0.14 * this.dayFactor
    }
    mesh.renderOrder = -1
    // 低清为俯视正交投影，平面放在地表平均高度附近（契约 §5：近似 64 或 settings 推导）
    mesh.position.set((tx + 0.5) * tileSize, this.settings.spawn.y, (tz + 0.5) * tileSize)
    mesh.matrixAutoUpdate = false
    mesh.updateMatrix()
    const record: LowresRecord = { mesh, texture: null, failed: false, lastUsed: this.frame }
    const rawUrl = this.source.lowresTileUrl(lod, tx, tz)
    const url = rawUrl
    if (url) {
      this.loader.loadAsync(url)
        .then((texture) => {
          if (this.disposed) {
            texture.dispose()
            return
          }
          texture.colorSpace = THREE.SRGBColorSpace
          texture.magFilter = this.settings.renderer === 'BLUEMAP' ? THREE.NearestFilter : THREE.LinearFilter
          texture.minFilter = this.settings.renderer === 'BLUEMAP' ? THREE.NearestFilter : THREE.LinearFilter
          texture.generateMipmaps = false
          texture.flipY = this.settings.renderer !== 'BLUEMAP'
          record.texture = texture
          if (material instanceof THREE.ShaderMaterial) {
            material.uniforms.textureImage.value = texture
          } else {
            material.map = texture
            material.color.set(0xffffff)
          }
          material.needsUpdate = true
          this.options.onChanged?.()
        })
        .catch(() => {
          record.failed = true
        })
    }
    else {
      record.failed = true
    }
    return record
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
    record.texture?.dispose()
    record.mesh.geometry.dispose()
    record.mesh.material.dispose()
    record.mesh.removeFromParent()
  }

  /** 排队中 + 加载中的 hires tile 数 */
  get pendingCount(): number {
    return this.queue.length + this.inFlight
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
    for (const controller of this.inFlightRequests.values()) {
      controller.abort()
    }
    this.inFlightRequests.clear()
    this.failedUntil.clear()
    this.failureCounts.clear()
    for (const record of this.hires.values()) {
      record.mesh?.geometry.dispose()
      record.mesh?.removeFromParent()
      record.translucentMesh?.geometry.dispose()
      record.translucentMesh?.removeFromParent()
    }
    this.hires.clear()
    for (const record of this.lowres.values()) {
      this.disposeLowres(record)
    }
    this.lowres.clear()
    this.group.removeFromParent()
    this.lowresGroup.removeFromParent()
  }
}

/** BlueMap lowres PNGs pack color in the top half and height/light metadata below it. */
function createBlueMapLowresMaterial(): THREE.ShaderMaterial {
  const uniforms = THREE.UniformsUtils.merge([
    THREE.UniformsLib.fog,
    {
      textureImage: { value: null },
      tileExtent: { value: 1 },
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
      uniform float tileExtent;
      varying vec2 vLocal;
      varying float vLight;
      float heightFromMeta(vec4 meta) {
        float unsignedHeight = meta.g * 65280.0 + meta.b * 255.0;
        return unsignedHeight >= 32768.0 ? -(65535.0 - unsignedHeight) : unsignedHeight;
      }
      #include <fog_pars_vertex>
      void main() {
        vLocal = position.xz + vec2(tileExtent * 0.5);
        vec2 textureUv = vec2(vLocal.x / tileExtent, vLocal.y / tileExtent);
        vec4 meta = texture2D(textureImage, vec2(textureUv.x, textureUv.y * 0.5 + 0.5));
        vLight = meta.r * 255.0;
        vec3 displaced = position;
        displaced.y = heightFromMeta(meta) + 0.5;
        vec4 mvPosition = modelViewMatrix * vec4(displaced, 1.0);
        gl_Position = projectionMatrix * mvPosition;
        #include <fog_vertex>
      }
    `,
    fragmentShader: /* glsl */ `
      uniform sampler2D textureImage;
      uniform float tileExtent;
      uniform float sunlight;
      uniform float ambient;
      uniform vec3 voidColor;
      varying vec2 vLocal;
      varying float vLight;
      #include <fog_pars_fragment>
      void main() {
        vec2 textureUv = vec2(vLocal.x / tileExtent, vLocal.y / tileExtent);
        vec4 color = texture2D(textureImage, vec2(textureUv.x, textureUv.y * 0.5));
        float light = mix(vLight, 15.0, sunlight);
        color.rgb *= mix(ambient, 1.0, light / 15.0);
        color.rgb = mix(voidColor, color.rgb, color.a);
        gl_FragColor = vec4(color.rgb, 1.0);
        #include <fog_fragment>
      }
    `,
  })
}
