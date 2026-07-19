import * as THREE from 'three'
import { describe, expect, it } from 'vitest'
import { configureBlueMapLowresTexture } from './blueMapLowresTexture'

describe('configureBlueMapLowresTexture', () => {
  it('preserves BlueMap packed height and light bytes without sRGB conversion', () => {
    const texture = new THREE.Texture()
    texture.colorSpace = THREE.SRGBColorSpace

    configureBlueMapLowresTexture(texture)

    expect(texture.colorSpace).toBe(THREE.NoColorSpace)
    expect(texture.magFilter).toBe(THREE.NearestFilter)
    expect(texture.minFilter).toBe(THREE.NearestFilter)
    expect(texture.generateMipmaps).toBe(false)
    expect(texture.flipY).toBe(false)
  })
})
