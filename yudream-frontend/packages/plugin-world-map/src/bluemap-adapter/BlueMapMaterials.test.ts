import { describe, expect, it } from 'vitest'
import { createBlueMapMaterials, disposeBlueMapMaterials, hasActiveBlueMapAnimations } from './BlueMapMaterials'

describe('createBlueMapMaterials', () => {
  it('keeps BlueMap half-transparent materials in the depth buffer', async () => {
    const materials = await createBlueMapMaterials([{ color: [1, 1, 1, 1], halfTransparent: true }])
    expect(materials[0]).toMatchObject({ transparent: true, depthWrite: true })
    disposeBlueMapMaterials(materials)
  })

  it('does not keep an animation frame loop alive before a texture has decoded', async () => {
    const materials = await createBlueMapMaterials([{ animation: { frames: [0, 1], frametime: 1 } }])
    expect(hasActiveBlueMapAnimations(materials)).toBe(false)
    disposeBlueMapMaterials(materials)
  })
})
