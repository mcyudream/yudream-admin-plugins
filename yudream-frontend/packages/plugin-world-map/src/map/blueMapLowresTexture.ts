import * as THREE from 'three'

/**
 * BlueMap lowres PNGs put height and light bits beside the color pixels. They must stay in
 * byte space: an sRGB conversion would corrupt those packed metadata values in the shader.
 */
export function configureBlueMapLowresTexture(texture: THREE.Texture): void {
  texture.colorSpace = THREE.NoColorSpace
  texture.magFilter = THREE.NearestFilter
  texture.minFilter = THREE.NearestFilter
  texture.generateMipmaps = false
  texture.flipY = false
}
