import * as THREE from 'three'

interface BlueMapTexture {
  color?: number[]
  halfTransparent?: boolean
  texture?: string
}

const MAX_MATERIALS = 4096
const MAX_CONCURRENT_TEXTURE_LOADS = 8
const textureSources = new WeakMap<THREE.ShaderMaterial, string>()
const textureLoads = new WeakMap<THREE.ShaderMaterial, Promise<void>>()
const disposedMaterials = new WeakSet<THREE.ShaderMaterial>()
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
    },
  ])
  const material = new THREE.ShaderMaterial({
    uniforms,
    vertexColors: true,
    fog: true,
    transparent: Boolean(entry.halfTransparent),
    depthWrite: !entry.halfTransparent,
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
      varying vec2 vUv;
      varying vec3 vColor;
      varying float vAo;
      varying float vSunlight;
      varying float vBlocklight;
      #include <fog_pars_fragment>
      void main() {
        vec4 color = texture2D(textureImage, vUv);
        if (color.a <= 0.01) discard;
        float light = mix(vBlocklight, max(vSunlight, vBlocklight), sunlightStrength);
        color.rgb *= vColor * vAo * mix(ambientLight, 1.0, light / 15.0);
        gl_FragColor = color;
        #include <fog_fragment>
      }
    `,
  })
  if (entry.texture) textureSources.set(material, entry.texture)
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
  const load = scheduleTextureLoad(async () => {
    if (disposedMaterials.has(material)) throw new Error('BlueMap material was disposed')
    return new THREE.TextureLoader().loadAsync(source)
  }).then(texture => {
    if (disposedMaterials.has(material)) {
      texture.dispose()
      return
    }
    texture.colorSpace = THREE.SRGBColorSpace
    texture.magFilter = THREE.NearestFilter
    texture.minFilter = THREE.NearestMipMapLinearFilter
    texture.generateMipmaps = true
    texture.flipY = false
    const fallback = material.uniforms.textureImage.value as THREE.Texture | null
    material.uniforms.textureImage.value = texture
    fallback?.dispose()
    material.needsUpdate = true
  }).catch(error => {
    textureLoads.delete(material)
    throw error
  })
  textureLoads.set(material, load)
  return load
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

export function disposeBlueMapMaterials(materials: readonly THREE.ShaderMaterial[]): void {
  for (const material of materials) {
    disposedMaterials.add(material)
    const texture = material.uniforms.textureImage.value as THREE.Texture | null
    texture?.dispose()
    textureSources.delete(material)
    textureLoads.delete(material)
    material.dispose()
  }
}
