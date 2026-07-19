import { describe, expect, it } from 'vitest'
import { createTerrainMaterial } from './material'
import { createBlueMapMaterials, disposeBlueMapMaterials } from '../bluemap-adapter/BlueMapMaterials'
import * as THREE from 'three'

describe('map shader color space', () => {
  it('decodes sRGB terrain texels before lighting and encodes the final output', () => {
    const atlas = new THREE.DataTexture(new Uint8Array([255, 255, 255, 255]), 1, 1)
    const material = createTerrainMaterial(atlas)

    expect(material.fragmentShader).toContain('sRGBTransferEOTF(texel)')
    expect(material.fragmentShader).toContain('#include <colorspace_fragment>')
    material.dispose()
    atlas.dispose()
  })

  it('uses the same color-space pipeline for BlueMap PRBM materials', async () => {
    const materials = await createBlueMapMaterials([{ color: [1, 1, 1, 1] }])

    expect(materials[0]!.fragmentShader).toContain('sRGBTransferEOTF(color)')
    expect(materials[0]!.fragmentShader).toContain('#include <colorspace_fragment>')
    disposeBlueMapMaterials(materials)
  })
})
