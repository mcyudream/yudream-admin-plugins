import * as THREE from 'three'
import type { HiresTileGeometry, MapSettings } from '../types'
import type { WorldMapSource } from './types'

interface HiresRecord {
  /** null 表示已请求但 tile 为空（404），缓存负结果避免重复请求 */
  mesh: THREE.Mesh | null
  /** 半透明段 mesh（水面等），可空 */
  translucentMesh?: THREE.Mesh | null
  lastUsed: number
}

interface LowresRecord {
  mesh: THREE.Mesh<THREE.PlaneGeometry, THREE.MeshBasicMaterial>
  texture: THREE.Texture | null
  /** 纹理加载失败（404 等）时保留底色平面 */
  failed: boolean
  lastUsed: number
}

export interface TileManagerOptions {
  /** hires 加载半径（单位：tile，默认 4 ≈ 128 方块） */
  hiresRadius?: number
  /** 最大并发 tile 请求数（默认 6） */
  maxConcurrent?: number
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
  private readonly queue: string[] = []
  private readonly queued = new Set<string>()
  private readonly loader = new THREE.TextureLoader()
  private inFlight = 0
  private frame = 0
  private centerTx = 0
  private centerTz = 0
  private disposed = false

  constructor(
    private readonly source: WorldMapSource,
    private readonly settings: MapSettings,
    /** 共享地形材质，由 MapViewer 持有与释放，这里不 dispose */
    private readonly material: THREE.ShaderMaterial,
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
    this.centerTx = Math.floor(target.x / tileSize)
    this.centerTz = Math.floor(target.z / tileSize)

    // 1. 收集半径内的 hires tile，未加载的入队
    for (let dx = -radius; dx <= radius; dx += 1) {
      for (let dz = -radius; dz <= radius; dz += 1) {
        if (dx * dx + dz * dz > radius * radius) {
          continue
        }
        const key = `${this.centerTx + dx},${this.centerTz + dz}`
        const record = this.hires.get(key)
        if (record) {
          record.lastUsed = this.frame
          continue
        }
        if (!this.queued.has(key)) {
          this.queued.add(key)
          this.queue.push(key)
        }
      }
    }

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
      const key = this.queue.shift()!
      this.queued.delete(key)
      const [tx, tz] = key.split(',').map(Number)
      if (Math.hypot(tx - this.centerTx, tz - this.centerTz) > evictRadius) {
        continue // 排队期间已滚出范围，直接丢弃
      }
      this.inFlight += 1
      this.source.fetchHiresTile(tx, tz)
        .then((tile) => {
          if (this.disposed) {
            return
          }
          if (!tile || tile.positions.length === 0) {
            this.hires.set(key, { mesh: null, lastUsed: this.frame })
            return
          }
          const mesh = this.buildMesh(tile, this.material)
          const translucentMesh = tile.translucent && tile.translucent.positions.length > 0
            ? this.buildMesh(tile.translucent, this.translucentMaterial)
            : null
          this.hires.set(key, { mesh, translucentMesh, lastUsed: this.frame })
          this.group.add(mesh)
          if (translucentMesh) {
            this.group.add(translucentMesh)
          }
        })
        .catch(() => { /* 网络错误不做负缓存，下次进入范围时重试 */ })
        .finally(() => {
          this.inFlight -= 1
          if (!this.disposed) {
            this.pump()
          }
        })
    }
  }

  private buildMesh(tile: HiresTileGeometry, material: THREE.ShaderMaterial): THREE.Mesh {
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

  /** lowres 金字塔平面：按相机距离选 lod，覆盖视野范围，作为 hires 的兜底 */
  private updateLowres(camera: THREE.Camera, target: THREE.Vector3): void {
    const maxLod = this.settings.lowresMaxLod
    const distance = camera.position.distanceTo(target)
    const lod = THREE.MathUtils.clamp(Math.floor(Math.log2(Math.max(distance, 1) / 256)), 0, maxLod)
    const tileSize = this.settings.lowresTileSize * 2 ** lod
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
    const geometry = new THREE.PlaneGeometry(tileSize, tileSize)
    geometry.rotateX(-Math.PI / 2)
    const material = new THREE.MeshBasicMaterial({
      color: 0x1a2436,
      fog: true,
      polygonOffset: true,
      polygonOffsetFactor: 1,
      polygonOffsetUnits: 1,
      // 不写深度 + 先渲染：hires 几何始终在兜底平面之上，
      // 避免平面高度（spawn.y 近似）高于实际地形时遮挡 hires
      depthWrite: false,
    })
    const mesh = new THREE.Mesh(geometry, material)
    mesh.renderOrder = -1
    // 低清为俯视正交投影，平面放在地表平均高度附近（契约 §5：近似 64 或 settings 推导）
    mesh.position.set((tx + 0.5) * tileSize, this.settings.spawn.y, (tz + 0.5) * tileSize)
    mesh.matrixAutoUpdate = false
    mesh.updateMatrix()
    const record: LowresRecord = { mesh, texture: null, failed: false, lastUsed: this.frame }
    const rawUrl = this.source.lowresTileUrl(lod, tx, tz)
    // 以 renderedAt 为资产版本号，重渲染后 URL 变化使浏览器缓存失效
    const url = rawUrl ? `${rawUrl}${rawUrl.includes('?') ? '&' : '?'}v=${this.settings.renderedAt ?? 0}` : rawUrl
    if (url) {
      this.loader.loadAsync(url)
        .then((texture) => {
          if (this.disposed) {
            texture.dispose()
            return
          }
          texture.colorSpace = THREE.SRGBColorSpace
          texture.magFilter = THREE.LinearFilter
          texture.minFilter = THREE.LinearFilter
          record.texture = texture
          material.map = texture
          material.color.set(0xffffff)
          material.needsUpdate = true
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
    const x0 = Math.floor((tx * tileSize) / hiresSize)
    const z0 = Math.floor((tz * tileSize) / hiresSize)
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

  dispose(): void {
    this.disposed = true
    this.queue.length = 0
    this.queued.clear()
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
