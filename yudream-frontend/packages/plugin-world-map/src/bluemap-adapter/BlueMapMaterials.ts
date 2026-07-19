import * as THREE from 'three'
import { createBlueMapAnimationState, stepBlueMapAnimation } from './BlueMapAnimation'
import type { BlueMapAnimationDefinition, BlueMapAnimationState } from './BlueMapAnimation'
import { decodeLowresImage, releaseLowresImage } from '../map/lowresImageDecode'

interface BlueMapTexture {
  color?: number[]
  halfTransparent?: boolean
  texture?: string
  animation?: BlueMapAnimationDefinition
}

const MAX_MATERIALS = 4096
const MAX_CONCURRENT_TEXTURE_LOADS = 8
const textureSources = new WeakMap<THREE.ShaderMaterial, string>()
const textureLoads = new WeakMap<THREE.ShaderMaterial, Promise<void>>()
const textureLoadControllers = new WeakMap<THREE.ShaderMaterial, AbortController>()
const disposedMaterials = new WeakSet<THREE.ShaderMaterial>()
const animations = new WeakMap<THREE.ShaderMaterial, BlueMapAnimationDefinition>()
const animationStates = new WeakMap<THREE.ShaderMaterial, BlueMapAnimationState>()
const queuedTextureLoads: Array<() => void> = []
let activeTextureLoads = 0

/** Builds the same material-indexed texture set used by BlueMap PRBM groups. */
export async function createBlueMapMaterials(payload: unknown): Promise<THREE.ShaderMaterial[]> {
  if (!Array.isArray(payload) || payload.length === 0 || payload.length > MAX_MATERIALS) {
    throw new Error('Invalid BlueMap textures metadata')
  }
  // BlueMap stores every texture as an embedded data URL. Decoding the whole material table before
  // the first frame can take seconds and blocks useful rendering, so textures are loaded on demand.
  return payload.map(entry => createMaterial(entry as BlueMapTexture))
}

/** Starts texture loads only for material groups referenced by a visible PRBM tile. */
export function ensureBlueMapMaterialTextures(materials: readonly THREE.ShaderMaterial[], geometry: THREE.BufferGeometry): Promise<void> {
  const indexes = new Set(geometry.groups.map(group => group.materialIndex).filter((index): index is number => index !== undefined))
  return Promise.all([...indexes].map(index => {
    const material = materials[index]
    return material ? loadMaterialTexture(material) : Promise.resolve()
  })).then(() => undefined)
}

function createMaterial(entry: BlueMapTexture): THREE.ShaderMaterial {
  const color = Array.isArray(entry.color) && entry.color.length >= 4 ? entry.color : [1, 0, 1, 1]
  const texture = colorTexture(color)
  const uniforms = THREE.UniformsUtils.merge([
    THREE.UniformsLib.fog,
    {
      textureImage: { value: texture },
      sunlightStrength: { value: 1 },
      ambientLight: { value: 0.25 },
      animationFrameHeight: { value: 1 },
      animationFrameIndex: { value: 0 },
      animationNextFrameIndex: { value: 0 },
      animationInterpolation: { value: 0 },
    },
  ])
  const material = new THREE.ShaderMaterial({
    uniforms,
    vertexColors: true,
    fog: true,
    transparent: Boolean(entry.halfTransparent),
    // BlueMap keeps depth writes for its half-transparent PRBM materials so foliage and
    // cutout-style blocks do not reveal geometry behind them through unstable sorting.
    depthWrite: true,
    vertexShader: /* glsl */ `
      attribute float ao;
      attribute float sunlight;
      attribute float blocklight;
      varying vec2 vUv;
      varying vec3 vColor;
      varying float vAo;
      varying float vSunlight;
      varying float vBlocklight;
      #include <fog_pars_vertex>
      void main() {
        vUv = uv; vColor = color; vAo = ao; vSunlight = sunlight; vBlocklight = blocklight;
        vec4 mvPosition = modelViewMatrix * vec4(position, 1.0);
        gl_Position = projectionMatrix * mvPosition;
        #include <fog_vertex>
      }
    `,
    fragmentShader: /* glsl */ `
      uniform sampler2D textureImage;
      uniform float sunlightStrength;
      uniform float ambientLight;
      uniform float animationFrameHeight;
      uniform float animationFrameIndex;
      uniform float animationNextFrameIndex;
      uniform float animationInterpolation;
      varying vec2 vUv;
      varying vec3 vColor;
      varying float vAo;
      varying float vSunlight;
      varying float vBlocklight;
      #include <fog_pars_fragment>
      void main() {
        vec2 frameUv = vec2(vUv.x, animationFrameHeight * (vUv.y + animationFrameIndex));
        vec4 color = texture2D(textureImage, frameUv);
        if (animationInterpolation > 0.0) {
          vec2 nextFrameUv = vec2(vUv.x, animationFrameHeight * (vUv.y + animationNextFrameIndex));
          color = mix(color, texture2D(textureImage, nextFrameUv), animationInterpolation);
        }
        if (color.a <= 0.01) discard;
        color = sRGBTransferEOTF(color);
        float light = mix(vBlocklight, max(vSunlight, vBlocklight), sunlightStrength);
        color.rgb *= vColor * vAo * mix(ambientLight, 1.0, light / 15.0);
        gl_FragColor = color;
        #include <fog_fragment>
        #include <colorspace_fragment>
      }
    `,
  })
  if (entry.texture) textureSources.set(material, entry.texture)
  if (entry.animation) animations.set(material, entry.animation)
  return material
}

function colorTexture(color: number[]): THREE.DataTexture {
  const data = new Uint8Array(color.slice(0, 4).map(value => Math.round(Math.min(1, Math.max(0, value)) * 255)))
  const texture = new THREE.DataTexture(data, 1, 1)
  texture.colorSpace = THREE.SRGBColorSpace
  texture.needsUpdate = true
  return texture
}

function loadMaterialTexture(material: THREE.ShaderMaterial): Promise<void> {
  const existing = textureLoads.get(material)
  if (existing) return existing
  const source = textureSources.get(material)
  if (!source || disposedMaterials.has(material)) return Promise.resolve()
  const controller = new AbortController()
  textureLoadControllers.set(material, controller)
  const load = scheduleTextureLoad(async () => {
    if (disposedMaterials.has(material)) throw new Error('BlueMap material was disposed')
    return loadTexture(source, controller.signal)
  }).then(texture => {
    if (disposedMaterials.has(material)) {
      disposeTexture(texture)
      return
    }
    texture.colorSpace = THREE.SRGBColorSpace
    texture.magFilter = THREE.NearestFilter
    texture.minFilter = THREE.NearestMipMapLinearFilter
    texture.generateMipmaps = true
    texture.flipY = false
    const fallback = material.uniforms.textureImage.value as THREE.Texture | null
    material.uniforms.textureImage.value = texture
    const animation = animations.get(material)
    if (animation && texture.image) {
      const width = Number(texture.image.width) || 1
      const height = Number(texture.image.height) || width
      const state = createBlueMapAnimationState(animation, Math.max(1, Math.round(height / width)))
      animationStates.set(material, state)
      material.uniforms.animationFrameHeight.value = 1 / state.frameCount
    }
    if (fallback) disposeTexture(fallback)
    material.needsUpdate = true
  }).catch(error => {
    textureLoads.delete(material)
    textureLoadControllers.delete(material)
    throw error
  })
  textureLoads.set(material, load)
  return load
}

/** Data URLs are fetched explicitly so map switches can cancel image decode instead of waiting for TextureLoader. */
async function loadTexture(source: string, signal: AbortSignal): Promise<THREE.Texture> {
  const response = await fetch(source, { signal })
  if (!response.ok) throw new Error(`BlueMap texture: HTTP ${response.status}`)
  const image = await decodeLowresImage(await response.blob())
  const texture = new THREE.Texture(image)
  texture.needsUpdate = true
  return texture
}

/** Keeps data-URL image decode bounded while the tile loader moves through a dense world. */
function scheduleTextureLoad(load: () => Promise<THREE.Texture>): Promise<THREE.Texture> {
  return new Promise((resolve, reject) => {
    const start = () => {
      activeTextureLoads += 1
      void load().then(resolve, reject).finally(() => {
        activeTextureLoads -= 1
        queuedTextureLoads.shift()?.()
      })
    }
    if (activeTextureLoads < MAX_CONCURRENT_TEXTURE_LOADS) start()
    else queuedTextureLoads.push(start)
  })
}

export function applyBlueMapDayFactor(materials: readonly THREE.ShaderMaterial[], dayFactor: number): void {
  for (const material of materials) {
    material.uniforms.sunlightStrength.value = dayFactor
    material.uniforms.ambientLight.value = 0.18 + 0.14 * dayFactor
  }
}

/** Advances only loaded animated textures, matching BlueMap's 20 ticks per second animation timing. */
export function stepBlueMapAnimations(materials: readonly THREE.ShaderMaterial[], deltaMs: number): void {
  for (const material of materials) {
    const state = animationStates.get(material)
    if (!state) continue
    const frame = stepBlueMapAnimation(state, deltaMs)
    material.uniforms.animationFrameIndex.value = frame.current
    material.uniforms.animationNextFrameIndex.value = frame.next
    material.uniforms.animationInterpolation.value = frame.interpolation
  }
}

/** Returns true only after a visible material has decoded an animated texture. */
export function hasActiveBlueMapAnimations(materials: readonly THREE.ShaderMaterial[]): boolean {
  return materials.some(material => animationStates.has(material))
}

export function disposeBlueMapMaterials(materials: readonly THREE.ShaderMaterial[]): void {
  for (const material of materials) {
    disposedMaterials.add(material)
    textureLoadControllers.get(material)?.abort()
    textureLoadControllers.delete(material)
    const texture = material.uniforms.textureImage.value as THREE.Texture | null
    if (texture) disposeTexture(texture)
    textureSources.delete(material)
    textureLoads.delete(material)
    animations.delete(material)
    animationStates.delete(material)
    material.dispose()
  }
}

function disposeTexture(texture: THREE.Texture): void {
  releaseLowresImage(texture.image as ImageBitmap | HTMLImageElement | null)
  texture.dispose()
}
