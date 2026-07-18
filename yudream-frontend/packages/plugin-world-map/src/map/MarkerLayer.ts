import * as THREE from 'three'
import type { MapMarker, MapMarkerSet } from '../types'
import type { WorldMapSource } from './types'

export interface MarkerPickResult {
  setId: string
  marker: MapMarker
}

/**
 * 标注层（一期占位）：契约 /maps/{id}/markers 一期返回空集。
 * 这里预留完整结构：setMarkers 渲染入口、pick 点击拾取、资源释放；
 * 二期按 marker.type 创建 sprite/POI mesh 加入 group 并登记到 pickables 即可。
 */
export class MarkerLayer {
  readonly group = new THREE.Group()
  private markerSets: MapMarkerSet[] = []
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
    this.clear()
    this.markerSets = markerSets
    for (const set of markerSets) {
      for (const marker of set.markers ?? []) {
        // TODO 二期：按 marker.type 创建标注 mesh，
        // mesh.userData.markerPick = { setId: set.id, marker } 并 push 到 pickables
        void marker
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
      if (child instanceof THREE.Mesh) {
        child.geometry.dispose()
      }
    }
    this.pickables = []
  }

  dispose(): void {
    this.clear()
    this.group.removeFromParent()
  }
}
