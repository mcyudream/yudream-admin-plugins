import { describe, expect, it } from 'vitest'
import { createBlueMapMaterials, disposeBlueMapMaterials } from './BlueMapMaterials'

describe('createBlueMapMaterials', () => {
  it('keeps BlueMap half-transparent materials in the depth buffer', async () => {
    const materials = await createBlueMapMaterials([{ color: [1, 1, 1, 1], halfTransparent: true }])
    expect(materials[0]).toMatchObject({ transparent: true, depthWrite: true })
    disposeBlueMapMaterials(materials)
  })
})
