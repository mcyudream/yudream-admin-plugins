import * as THREE from 'three'
import type { MapMarker, MapMarkerSet } from '../types'
import type { WorldMapSource } from './types'

export interface MarkerPickResult {
  setId: string
  marker: MapMarker
}

/**
 * 标注层：消费插件公开 API 的纯 marker DTO，不依赖任何具体渲染引擎类型。
 */
export class MarkerLayer {
  readonly group = new THREE.Group()
  private markerSets: MapMarkerSet[] = []
  private readonly visibility = new Map<string, boolean>()
  private pickables: THREE.Object3D[] = []
  private readonly raycaster = new THREE.Raycaster()
  private readonly ndc = new THREE.Vector2()

  constructor(parent: THREE.Object3D) {
    this.group.name = 'world-map-markers'
    parent.add(this.group)
  }

  /** 当前已加载的标注集（一期恒为空数组） */
  get sets(): readonly MapMarkerSet[] {
    return this.markerSets
  }

  async load(source: WorldMapSource): Promise<void> {
    try {
      const res = await source.fetchMarkers()
      this.setMarkers(res.markerSets ?? [])
    }
    catch {
      this.setMarkers([])
    }
  }

  setMarkers(markerSets: MapMarkerSet[]): void {
    this.markerSets = markerSets
    this.renderMarkers()
  }

  setVisible(setId: string, visible: boolean): void {
    this.visibility.set(setId, visible)
    this.renderMarkers()
  }

  private renderMarkers(): void {
    this.clear()
    for (const set of this.markerSets) {
      const setId = set.id ?? ''
      const visible = this.visibility.get(setId) ?? set.defaultVisible !== false
      if (!visible) {
        continue
      }
      for (const marker of set.markers ?? []) {
        const object = this.createMarker(marker)
        if (!object) {
          continue
        }
        object.userData.markerPick = { setId, marker } satisfies MarkerPickResult
        this.group.add(object)
        this.pickables.push(object)
      }
    }
  }

  /** 点击拾取（二期 UI 接入点）：ndcX/ndcY 为归一化设备坐标 [-1,1] */
  pick(ndcX: number, ndcY: number, camera: THREE.Camera): MarkerPickResult | null {
    if (this.pickables.length === 0) {
      return null
    }
    this.ndc.set(ndcX, ndcY)
    this.raycaster.setFromCamera(this.ndc, camera)
    const hit = this.raycaster.intersectObjects(this.pickables, false)[0]?.object
    return (hit?.userData?.markerPick as MarkerPickResult | undefined) ?? null
  }

  private clear(): void {
    const children = [...this.group.children]
    for (const child of children) {
      this.group.remove(child)
      child.traverse((object) => this.disposeObject(object))
    }
    this.pickables = []
  }

  private createMarker(marker: MapMarker): THREE.Object3D | null {
    const color = new THREE.Color(marker.color ?? '#f6c845')
    const type = marker.type?.toUpperCase()
    if (type === 'POINT' && marker.position) {
      const mesh = new THREE.Mesh(
        new THREE.SphereGeometry(2.5, 12, 8),
        new THREE.MeshBasicMaterial({ color, depthTest: false }),
      )
      mesh.position.set(marker.position.x, marker.position.y, marker.position.z)
      mesh.renderOrder = 2
      return mesh
    }
    const points = marker.points ?? []
    if ((type === 'LINE' || type === 'REGION') && points.length >= 2) {
      const geometry = new THREE.BufferGeometry().setFromPoints(points.map(point =>
        new THREE.Vector3(point.x, point.y, point.z),
      ))
      const material = new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.9, depthTest: false })
      const line = type === 'REGION' ? new THREE.LineLoop(geometry, material) : new THREE.Line(geometry, material)
      line.renderOrder = 2
      return line
    }
    return null
  }

  private disposeObject(object: THREE.Object3D): void {
    if (object instanceof THREE.Mesh || object instanceof THREE.Line) {
      object.geometry.dispose()
      const materials = Array.isArray(object.material) ? object.material : [object.material]
      materials.forEach(material => material.dispose())
    }
  }

  dispose(): void {
    this.clear()
    this.group.removeFromParent()
  }
}
