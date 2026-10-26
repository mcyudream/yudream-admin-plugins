import { Matrix4, Quaternion, Vector3 } from 'three'
import { buildCubeMeshData, computeBoneLocalPos, eulerToQuaternion } from '../vendor/ysm-model-manager/cube-mesh'
import { evaluateClip } from '../vendor/ysm-model-manager/animation-evaluator'
import { parseAnimation } from './animation'
import type { Bundle, Vector } from '../types'

export interface CoverPixels { width: number; height: number; pixels: Uint8ClampedArray }
interface Vertex { x: number; y: number; z: number; u: number; v: number }
interface Triangle { vertices: Vertex[]; light: number }
const vec = (v: Vector) => ({ x: v[0], y: v[1], z: v[2] })

/** The same mesh and idle pose as the 3D viewer, projected into a CPU depth buffer. */
function triangles(bundle: Bundle): Triangle[] {
  if (bundle.schema !== 1 || !bundle.textures.length) throw new Error('不支持的模型格式')
  const definitions = new Map(bundle.bones.map(b => [b.name, b]))
  const matrices = new Map<string, Matrix4>()
  const visiting = new Set<string>()
  const idle = bundle.animations.idle
  const poses = idle ? evaluateClip(parseAnimation('idle', idle), 0) : new Map()
  function boneMatrix(name: string): Matrix4 {
    const cached = matrices.get(name)
    if (cached) return cached
    if (visiting.has(name)) throw new Error('模型骨骼存在循环')
    const bone = definitions.get(name)
    if (!bone) throw new Error('模型缺少骨骼')
    visiting.add(name)
    const parent = bone.parent ? definitions.get(bone.parent) : undefined
    const position = new Vector3(...computeBoneLocalPos(vec(bone.pivot ?? [0, 0, 0]), parent ? vec(parent.pivot ?? [0, 0, 0]) : null))
    const pose = poses.get(name)
    if (pose?.position) position.add(new Vector3(-pose.position[0], pose.position[1], pose.position[2]))
    const base = bone.rotation ?? [0, 0, 0], rotation = pose?.rotation ?? [0, 0, 0]
    const matrix = new Matrix4().compose(position,
      new Quaternion(...eulerToQuaternion(-base[0] - rotation[0], -base[1] - rotation[1], base[2] + rotation[2])),
      new Vector3(...(pose?.scale ?? [1, 1, 1])))
    if (bone.parent) matrix.premultiply(boneMatrix(bone.parent))
    visiting.delete(name)
    matrices.set(name, matrix)
    return matrix
  }
  const result: Triangle[] = [], light = new Vector3(-.3, .8, -.7).normalize()
  for (const part of bundle.parts) {
    const bone = definitions.get(part.bone)
    if (!bone) throw new Error('模型缺少骨骼')
    for (const [index, cube] of part.cubes.entries()) {
      const mesh = buildCubeMeshData({ ...cube, pivot: cube.pivot ?? bone.pivot ?? [0, 0, 0], pivotSet: true,
        rotation: cube.rotation ?? [0, 0, 0], inflate: cube.inflate ?? 0, mirror: cube.mirror ?? false,
        faceUV: '', texSlot: 0, cubeTexW: 0, cubeTexH: 0 }, vec(bone.pivot ?? [0, 0, 0]),
      bundle.textures[0].width, bundle.textures[0].height, bone.name, index)
      if (!mesh) throw new Error('模型方块数据无效')
      const transform = boneMatrix(bone.name).clone().multiply(new Matrix4().compose(
        new Vector3(...mesh.localPosition), new Quaternion(...mesh.localRotation), new Vector3(1, 1, 1)))
      const vertices: Vertex[] = []
      for (let i = 0; i < mesh.positions.length / 3; i++) {
        const p = new Vector3(mesh.positions[i * 3], mesh.positions[i * 3 + 1], mesh.positions[i * 3 + 2]).applyMatrix4(transform)
        vertices.push({ x: p.x, y: p.y, z: p.z, u: mesh.uvs[i * 2], v: mesh.uvs[i * 2 + 1] })
      }
      for (let i = 0; i < mesh.indices.length; i += 3) {
        const points = mesh.indices.slice(i, i + 3).map(n => vertices[n])
        const a = points[0], b = points[1], c = points[2]
        const normal = new Vector3(b.x - a.x, b.y - a.y, b.z - a.z).cross(new Vector3(c.x - a.x, c.y - a.y, c.z - a.z)).normalize()
        result.push({ vertices: points, light: .76 + .24 * Math.abs(normal.dot(light)) })
      }
    }
  }
  if (!result.length) throw new Error('模型没有可显示的方块')
  return result
}

/** Front/back orthographic views. Transparent texels never write depth. No WebGL dependency. */
export function rasterizeCover(bundle: Bundle, texture: CoverPixels, width = 480, height = 360): CoverPixels {
  const mesh = triangles(bundle), pixels = new Uint8ClampedArray(width * height * 4)
  const depths = new Float64Array(width * height).fill(-Infinity)
  for (let view = 0; view < 2; view++) {
    const yaw = view * Math.PI + .18
    const direction = new Vector3(Math.sin(yaw), .08, -Math.cos(yaw)).normalize()
    const right = new Vector3(0, 1, 0).cross(direction).normalize(), up = direction.clone().cross(right)
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity
    const projected = mesh.map(t => ({ light: t.light, vertices: t.vertices.map(v => {
      const p = new Vector3(v.x, v.y, v.z)
      const x = p.dot(right), y = p.dot(up)
      minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y)
      return { x, y, z: p.dot(direction), u: v.u, v: v.v }
    }) }))
    const pane = width / 2, padding = 24
    const scale = Math.min((pane - padding * 2) / Math.max(maxX - minX, .001), (height - padding * 2) / Math.max(maxY - minY, .001))
    for (const triangle of projected) for (const v of triangle.vertices) {
      v.x = (v.x - (minX + maxX) / 2) * scale + pane * (view + .5)
      v.y = height / 2 - (v.y - (minY + maxY) / 2) * scale
    }
    // Far to near also preserves the alpha of translucent overlay textures.
    projected.sort((a, b) => a.vertices.reduce((s, v) => s + v.z, 0) - b.vertices.reduce((s, v) => s + v.z, 0))
    for (const { vertices: [a, b, c], light } of projected) {
      const area = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y)
      if (Math.abs(area) < 1e-8) continue
      const left = Math.max(view * pane, Math.floor(Math.min(a.x, b.x, c.x))), top = Math.max(0, Math.floor(Math.min(a.y, b.y, c.y)))
      const endX = Math.min((view + 1) * pane - 1, Math.ceil(Math.max(a.x, b.x, c.x))), endY = Math.min(height - 1, Math.ceil(Math.max(a.y, b.y, c.y)))
      for (let y = top; y <= endY; y++) for (let x = left; x <= endX; x++) {
        const wa = ((b.y - c.y) * (x + .5 - c.x) + (c.x - b.x) * (y + .5 - c.y)) / area
        const wb = ((c.y - a.y) * (x + .5 - c.x) + (a.x - c.x) * (y + .5 - c.y)) / area, wc = 1 - wa - wb
        if (wa < -1e-8 || wb < -1e-8 || wc < -1e-8) continue
        const depth = wa * a.z + wb * b.z + wc * c.z, index = y * width + x
        if (depth <= depths[index] + 1e-7) continue
        const u = Math.max(0, Math.min(texture.width - 1, Math.floor((wa * a.u + wb * b.u + wc * c.u) * texture.width)))
        const v = Math.max(0, Math.min(texture.height - 1, Math.floor((wa * a.v + wb * b.v + wc * c.v) * texture.height)))
        const source = (v * texture.width + u) * 4, alpha = texture.pixels[source + 3] / 255
        if (alpha < .05) continue
        const target = index * 4, behind = pixels[target + 3] / 255, combined = alpha + behind * (1 - alpha)
        for (let channel = 0; channel < 3; channel++) pixels[target + channel] = (texture.pixels[source + channel] * light * alpha + pixels[target + channel] * behind * (1 - alpha)) / combined
        pixels[target + 3] = combined * 255
        depths[index] = depth
      }
    }
  }
  return { width, height, pixels }
}
