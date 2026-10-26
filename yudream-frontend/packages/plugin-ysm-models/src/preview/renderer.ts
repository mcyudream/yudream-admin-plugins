import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { buildCubeMeshData, computeBoneLocalPos, eulerToQuaternion } from '../vendor/ysm-model-manager/cube-mesh'
import { evaluateClip } from '../vendor/ysm-model-manager/animation-evaluator'
import { parseAnimation } from './animation'
import type { Bundle, Vector } from '../types'

export interface PreviewHandle {
  texture(name: string): void
  animation(name: string): void
  playing(value: boolean): void
  resetCamera(): void
  dispose(): void
}

export async function mountPreview(container: HTMLElement, bundle: Bundle): Promise<PreviewHandle> {
  if (bundle.schema !== 1) throw new Error('不支持的模型格式版本')
  const textures = new Map<string, THREE.Texture>()
  const geometries: THREE.BufferGeometry[] = []
  let renderer: THREE.WebGLRenderer | undefined
  let controls: OrbitControls | undefined
  let resize: ResizeObserver | undefined
  let raf = 0
  const material = new THREE.MeshLambertMaterial({ alphaTest: 0.05, side: THREE.DoubleSide, transparent: true })
  const dispose = () => {
    cancelAnimationFrame(raf)
    resize?.disconnect()
    controls?.dispose()
    geometries.forEach(g => g.dispose())
    textures.forEach(t => t.dispose())
    material.dispose()
    renderer?.dispose()
    renderer?.forceContextLoss()
    renderer?.domElement.remove()
  }
  try {
    // Sequential decoding bounds peak memory and allows full cleanup after any failed texture.
    for (const texture of bundle.textures) {
      const t = await new THREE.TextureLoader().loadAsync(`data:image/png;base64,${texture.png}`)
      t.colorSpace = THREE.SRGBColorSpace
      t.magFilter = THREE.NearestFilter
      t.minFilter = THREE.NearestFilter
      t.generateMipmaps = false
      t.flipY = false
      textures.set(texture.name, t)
    }
    material.map = textures.values().next().value ?? null
    const scene = new THREE.Scene()
    const root = new THREE.Group()
    root.scale.setScalar(1 / 16)
    scene.add(root, new THREE.AmbientLight(0xffffff, 2))
    const sun = new THREE.DirectionalLight(0xffffff, 2)
    sun.position.set(-3, 5, -4)
    scene.add(sun)
    const nodes = new Map(bundle.bones.map(b => [b.name, new THREE.Group()]))
    const definitions = new Map(bundle.bones.map(b => [b.name, b]))
    const toVec = (v: Vector): { x: number; y: number; z: number } => ({ x: v[0], y: v[1], z: v[2] })
    for (const bone of bundle.bones) {
      const node = nodes.get(bone.name)!
      const parent = bone.parent ? definitions.get(bone.parent) : undefined
      node.position.fromArray(computeBoneLocalPos(toVec(bone.pivot ?? [0, 0, 0]), parent ? toVec(parent.pivot ?? [0, 0, 0]) : null))
      const r = bone.rotation ?? [0, 0, 0]
      node.quaternion.fromArray(eulerToQuaternion(-r[0], -r[1], r[2]))
      ;(bone.parent ? nodes.get(bone.parent)! : root).add(node)
    }
    for (const part of bundle.parts) {
      const bone = definitions.get(part.bone)!
      for (const [index, cube] of part.cubes.entries()) {
        const data = buildCubeMeshData({ ...cube, pivot: cube.pivot ?? bone.pivot ?? [0, 0, 0], pivotSet: true,
          rotation: cube.rotation ?? [0, 0, 0], inflate: cube.inflate ?? 0, mirror: cube.mirror ?? false,
          faceUV: '', texSlot: 0, cubeTexW: 0, cubeTexH: 0 }, toVec(bone.pivot ?? [0, 0, 0]), bundle.textures[0].width, bundle.textures[0].height, bone.name, index)
        if (!data) throw new Error('模型方块数据无效')
        const geometry = new THREE.BufferGeometry()
        geometries.push(geometry)
        geometry.setAttribute('position', new THREE.Float32BufferAttribute(data.positions, 3))
        geometry.setAttribute('normal', new THREE.Float32BufferAttribute(data.normals, 3))
        geometry.setAttribute('uv', new THREE.Float32BufferAttribute(data.uvs, 2))
        geometry.setIndex(data.indices)
        const mesh = new THREE.Mesh(geometry, material)
        mesh.position.fromArray(data.localPosition)
        mesh.quaternion.fromArray(data.localRotation)
        nodes.get(part.bone)!.add(mesh)
      }
    }
    const clips = new Map(Object.entries(bundle.animations).map(([name, a]) => [name, parseAnimation(name, a)]))
    const bases = new Map([...nodes].map(([name, n]) => [name, n.position.clone()]))
    const camera = new THREE.PerspectiveCamera(40, 1, 0.01, 1000)
    const box = new THREE.Box3().setFromObject(root)
    const center = box.getCenter(new THREE.Vector3())
    const radius = Math.max(box.getSize(new THREE.Vector3()).length(), 1)
    try { renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true }) }
    catch { throw new Error('当前浏览器无法创建三维预览，请检查图形加速设置或更换支持 WebGL 的浏览器。') }
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
    renderer.domElement.setAttribute('aria-label', `${bundle.name} 三维模型预览，可拖动旋转与缩放`)
    renderer.domElement.setAttribute('role', 'img')
    container.append(renderer.domElement)
    controls = new OrbitControls(camera, renderer.domElement)
    controls.enableDamping = true
    controls.target.copy(center)
    const resetCamera = () => { camera.position.copy(center).add(new THREE.Vector3(radius * .45, radius * .15, -radius * 1.5)); controls!.target.copy(center); controls!.update() }
    resetCamera()
    resize = new ResizeObserver(() => {
      const w = Math.max(container.clientWidth, 1), h = Math.max(container.clientHeight, 1)
      camera.aspect = w / h
      camera.updateProjectionMatrix()
      renderer!.setSize(w, h, false)
    })
    resize.observe(container)
    let selected = clips.has('idle') ? 'idle' : clips.keys().next().value ?? ''
    let elapsed = 0, playing = true, previous = performance.now()
    let transition: Map<string, { position: THREE.Vector3; rotation: THREE.Quaternion; scale: THREE.Vector3 }> | undefined
    let blendTime = 0
    const render = (now: number) => {
      const dt = Math.min((now - previous) / 1000, .1)
      previous = now
      if (playing) elapsed += dt
      if (playing) blendTime += dt
      const alpha = Math.min(blendTime / .3, 1)
      const blend = alpha * alpha * (3 - 2 * alpha)
      const clip = clips.get(selected)
      const poses = clip ? evaluateClip(clip, elapsed) : new Map()
      for (const [name, node] of nodes) {
        const pose = poses.get(name)
        node.position.copy(bases.get(name)!)
        if (pose?.position) node.position.add(new THREE.Vector3(-pose.position[0], pose.position[1], pose.position[2]))
        const base = definitions.get(name)!.rotation ?? [0, 0, 0]
        const r = pose?.rotation ?? [0, 0, 0]
        node.quaternion.fromArray(eulerToQuaternion(-base[0] - r[0], -base[1] - r[1], base[2] + r[2]))
        node.scale.fromArray(pose?.scale ?? [1, 1, 1])
        const from = transition?.get(name)
        if (from) {
          node.position.lerpVectors(from.position, node.position, blend)
          node.quaternion.slerpQuaternions(from.rotation, node.quaternion.clone(), blend)
          node.scale.lerpVectors(from.scale, node.scale, blend)
        }
      }
      if (alpha >= 1) transition = undefined
      controls!.update()
      renderer!.render(scene, camera)
      raf = requestAnimationFrame(render)
    }
    raf = requestAnimationFrame(render)
    return { texture(name) { const t = textures.get(name); if (t) { material.map = t; material.needsUpdate = true } },
      animation(name) {
        transition = new Map([...nodes].map(([bone, node]) => [bone, { position: node.position.clone(), rotation: node.quaternion.clone(), scale: node.scale.clone() }]))
        selected = name; elapsed = 0; blendTime = 0
      }, playing(value) { playing = value }, resetCamera, dispose }
  }
  catch (error) { dispose(); throw error }
}
