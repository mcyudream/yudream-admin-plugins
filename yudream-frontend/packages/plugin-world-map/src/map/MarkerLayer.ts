import * as THREE from 'three'
import type { MapMarker, MapMarkerSet } from '../types'
import type { WorldMapSource } from './types'

export interface MarkerPickResult {
  setId: string
  marker: MapMarker
}

/** Caps unique canvas textures so a dense marker set cannot exhaust WebGL memory. */
export const MAX_MARKER_LABELS = 250
const MAX_MARKER_LABEL_LENGTH = 64

/** Returns a stable navigation point for point, line, and region annotations. */
export function markerAnchor(marker: MapMarker): THREE.Vector3 | null {
  if (marker.position && finitePosition(marker.position)) {
    return new THREE.Vector3(marker.position.x, marker.position.y, marker.position.z)
  }
  const points = (marker.points ?? []).filter(finitePosition)
  if (points.length === 0) {
    return null
  }
  const anchor = new THREE.Vector3()
  for (const point of points) {
    anchor.add(new THREE.Vector3(point.x, point.y, point.z))
  }
  return anchor.multiplyScalar(1 / points.length)
}

/** Normalizes untrusted marker labels before using them in a canvas texture. */
export function markerLabel(marker: MapMarker): string | null {
  if (typeof marker.label !== 'string') {
    return null
  }
  const normalized = marker.label.replace(/\s+/g, ' ').trim()
  if (!normalized) {
    return null
  }
  return normalized.length > MAX_MARKER_LABEL_LENGTH
    ? `${normalized.slice(0, MAX_MARKER_LABEL_LENGTH - 3)}...`
    : normalized
}

/** Places labels just above the annotation so the label does not cover its geometry. */
export function markerLabelAnchor(marker: MapMarker): THREE.Vector3 | null {
  const anchor = markerAnchor(marker)
  if (!anchor) {
    return null
  }
  anchor.y += marker.position ? 5 : 2.5
  return anchor
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
    let labelCount = 0
    for (const set of this.markerSets) {
      const setId = set.id ?? ''
      const visible = this.visibility.get(setId) ?? set.defaultVisible !== false
      if (!visible) {
        continue
      }
      for (const marker of set.markers ?? []) {
        const label = labelCount < MAX_MARKER_LABELS ? markerLabel(marker) : null
        const object = this.createMarker(marker, label)
        if (!object) {
          continue
        }
        if (label) {
          labelCount += 1
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

  private createMarker(marker: MapMarker, label: string | null): THREE.Object3D | null {
    const color = new THREE.Color(marker.color ?? '#f6c845')
    const type = marker.type?.toUpperCase()
    if (type === 'POINT' && marker.position) {
      const mesh = new THREE.Mesh(
        new THREE.SphereGeometry(2.5, 12, 8),
        new THREE.MeshBasicMaterial({ color, depthTest: false }),
      )
      mesh.position.set(marker.position.x, marker.position.y, marker.position.z)
      mesh.renderOrder = 2
      this.addLabel(mesh, marker, label, color)
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
      this.addLabel(line, marker, label, color)
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
    if (object instanceof THREE.Sprite) {
      object.material.map?.dispose()
      object.material.dispose()
    }
  }

  private addLabel(parent: THREE.Object3D, marker: MapMarker, label: string | null, color: THREE.Color): void {
    if (!label) {
      return
    }
    const anchor = markerLabelAnchor(marker)
    if (!anchor) {
      return
    }
    const sprite = createLabelSprite(label, color)
    if (!sprite) {
      return
    }
    // Point markers carry their world position on the mesh, while line geometry is world-space.
    sprite.position.copy(anchor.sub(parent.position))
    sprite.renderOrder = 3
    parent.add(sprite)
  }

  dispose(): void {
    this.clear()
    this.group.removeFromParent()
  }
}

function createLabelSprite(label: string, color: THREE.Color): THREE.Sprite | null {
  const canvas = document.createElement('canvas')
  const context = canvas.getContext('2d')
  if (!context) {
    return null
  }
  const pixelRatio = Math.min(window.devicePixelRatio || 1, 2)
  const fontSize = 28
  const paddingX = 18
  const paddingY = 10
  context.font = `600 ${fontSize}px sans-serif`
  const width = Math.ceil(context.measureText(label).width + paddingX * 2)
  const height = fontSize + paddingY * 2
  canvas.width = width * pixelRatio
  canvas.height = height * pixelRatio
  context.scale(pixelRatio, pixelRatio)
  context.font = `600 ${fontSize}px sans-serif`
  context.textBaseline = 'middle'
  context.fillStyle = 'rgba(15, 23, 42, 0.88)'
  context.roundRect(0, 0, width, height, 7)
  context.fill()
  context.strokeStyle = color.getStyle()
  context.lineWidth = 2
  context.roundRect(1, 1, width - 2, height - 2, 6)
  context.stroke()
  context.fillStyle = '#ffffff'
  context.fillText(label, paddingX, height / 2)

  const texture = new THREE.CanvasTexture(canvas)
  texture.colorSpace = THREE.SRGBColorSpace
  texture.minFilter = THREE.LinearFilter
  const material = new THREE.SpriteMaterial({ map: texture, depthTest: false, depthWrite: false, transparent: true })
  const sprite = new THREE.Sprite(material)
  sprite.scale.set(Math.max(12, Math.min(44, width / 12)), 4.5, 1)
  return sprite
}

function finitePosition(value: { x: number, y: number, z: number }): boolean {
  return Number.isFinite(value.x) && Number.isFinite(value.y) && Number.isFinite(value.z)
}
