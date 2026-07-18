import * as THREE from 'three'

/**
 * 地形 ShaderMaterial，对应契约 §4 渲染约定：
 *   最终颜色 = texture(atlas, uv) * color * ao * light
 *   light = clamp(ambient + sun * skylight/15 + torch * blocklight/15, 0, 1)
 * 雾使用 three 内置 fog chunk（scene.fog 驱动）。
 */
export function createTerrainMaterial(atlas: THREE.Texture): THREE.ShaderMaterial {
  const uniforms = THREE.UniformsUtils.merge([
    THREE.UniformsLib.fog,
    {
      atlas: { value: null },
      sunIntensity: { value: 1.0 },
      ambient: { value: 0.25 },
      torchIntensity: { value: 0.7 },
    },
  ])
  // merge 会深拷贝 uniform 容器，纹理在 merge 后赋值，避免被 clone
  uniforms.atlas.value = atlas

  return new THREE.ShaderMaterial({
    uniforms,
    vertexColors: true,
    fog: true,
    vertexShader: /* glsl */ `
      attribute float ao;
      attribute float blocklight;
      attribute float skylight;
      varying vec2 vUv;
      varying vec3 vColor;
      varying float vAo;
      varying float vBlocklight;
      varying float vSkylight;
      #include <fog_pars_vertex>
      void main() {
        vUv = uv;
        vColor = color;
        vAo = ao;
        vBlocklight = blocklight;
        vSkylight = skylight;
        vec4 mvPosition = modelViewMatrix * vec4( position, 1.0 );
        gl_Position = projectionMatrix * mvPosition;
        #include <fog_vertex>
      }
    `,
    fragmentShader: /* glsl */ `
      uniform sampler2D atlas;
      uniform float sunIntensity;
      uniform float ambient;
      uniform float torchIntensity;
      varying vec2 vUv;
      varying vec3 vColor;
      varying float vAo;
      varying float vBlocklight;
      varying float vSkylight;
      #include <fog_pars_fragment>
      void main() {
        vec4 texel = texture2D( atlas, vUv );
        float light = clamp(
          ambient + sunIntensity * ( vSkylight / 15.0 ) + torchIntensity * ( vBlocklight / 15.0 ),
          0.0,
          1.0
        );
        gl_FragColor = vec4( texel.rgb * vColor * vAo * light, 1.0 );
        #include <fog_fragment>
      }
    `,
  })
}

/** 更新昼夜 uniform（t: 0 = 午夜, 0.5 = 正午），返回 [0,1] 的白天系数供天空/雾插值 */
export function computeDayFactor(timeOfDay: number): number {
  const t = THREE.MathUtils.clamp(timeOfDay, 0, 1)
  const elevation = Math.sin((t - 0.25) * Math.PI * 2)
  return THREE.MathUtils.clamp(elevation * 1.6 + 0.35, 0, 1)
}

export function applyDayFactor(material: THREE.ShaderMaterial, dayFactor: number): void {
  material.uniforms.sunIntensity.value = 0.75 * dayFactor
  material.uniforms.ambient.value = 0.22 + 0.1 * dayFactor
  material.uniforms.torchIntensity.value = 0.7
}

/**
 * 半透明地形材质（水面等，契约 §4 translucent 段）：
 * 与不透明地形同一 shader，输出 alpha=0.78，关闭深度写入避免半透明排序伪影。
 */
export function createTranslucentTerrainMaterial(atlas: THREE.Texture): THREE.ShaderMaterial {
  const material = createTerrainMaterial(atlas)
  material.transparent = true
  material.depthWrite = false
  material.fragmentShader = material.fragmentShader.replace(
    'gl_FragColor = vec4( texel.rgb * vColor * vAo * light, 1.0 );',
    'gl_FragColor = vec4( texel.rgb * vColor * vAo * light, 0.78 );',
  )
  return material
}
