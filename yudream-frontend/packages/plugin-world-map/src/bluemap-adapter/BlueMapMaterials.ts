import * as THREE from 'three'

interface BlueMapTexture {
  color?: number[]
  halfTransparent?: boolean
  texture?: string
}

const MAX_MATERIALS = 4096

/** Builds the same material-indexed texture set used by BlueMap PRBM groups. */
export async function createBlueMapMaterials(payload: unknown): Promise<THREE.ShaderMaterial[]> {
  if (!Array.isArray(payload) || payload.length === 0 || payload.length > MAX_MATERIALS) {
    throw new Error('Invalid BlueMap textures metadata')
  }
  return Promise.all(payload.map(async (entry) => createMaterial(entry as BlueMapTexture)))
}

async function createMaterial(entry: BlueMapTexture): Promise<THREE.ShaderMaterial> {
  const color = Array.isArray(entry.color) && entry.color.length >= 4 ? entry.color : [1, 0, 1, 1]
  const texture = await loadTexture(entry.texture, color)
  const uniforms = THREE.UniformsUtils.merge([
    THREE.UniformsLib.fog,
    {
      textureImage: { value: texture },
      sunlightStrength: { value: 1 },
      ambientLight: { value: 0.25 },
    },
  ])
  return new THREE.ShaderMaterial({
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
}

async function loadTexture(source: string | undefined, color: number[]): Promise<THREE.Texture> {
  if (source) {
    const texture = await new THREE.TextureLoader().loadAsync(source)
    texture.colorSpace = THREE.SRGBColorSpace
    texture.magFilter = THREE.NearestFilter
    texture.minFilter = THREE.NearestMipMapLinearFilter
    texture.generateMipmaps = true
    texture.flipY = false
    return texture
  }
  const data = new Uint8Array(color.slice(0, 4).map(value => Math.round(Math.min(1, Math.max(0, value)) * 255)))
  const texture = new THREE.DataTexture(data, 1, 1)
  texture.colorSpace = THREE.SRGBColorSpace
  texture.needsUpdate = true
  return texture
}

export function applyBlueMapDayFactor(materials: readonly THREE.ShaderMaterial[], dayFactor: number): void {
  for (const material of materials) {
    material.uniforms.sunlightStrength.value = dayFactor
    material.uniforms.ambientLight.value = 0.18 + 0.14 * dayFactor
  }
}

export function disposeBlueMapMaterials(materials: readonly THREE.ShaderMaterial[]): void {
  for (const material of materials) {
    const texture = material.uniforms.textureImage.value as THREE.Texture | null
    texture?.dispose()
    material.dispose()
  }
}
