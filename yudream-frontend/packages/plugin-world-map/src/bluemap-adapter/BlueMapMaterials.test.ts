import { afterEach, describe, expect, it, vi } from 'vitest'
import { blueMapTextureUsesMipmaps, createBlueMapMaterials, disposeBlueMapMaterials, ensureBlueMapMaterialTextures, hasActiveBlueMapAnimations } from './BlueMapMaterials'
import * as THREE from 'three'

describe('createBlueMapMaterials', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })
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

  it('matches BlueMap mipmap policy for transparent texture sheets', () => {
    expect(blueMapTextureUsesMipmaps(1, false)).toBe(true)
    expect(blueMapTextureUsesMipmaps(0.5, true)).toBe(true)
    expect(blueMapTextureUsesMipmaps(0.5, false)).toBe(false)
  })

  it('aborts an in-flight material texture when its map generation is disposed', async () => {
    let signal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) => {
      signal = init?.signal ?? undefined
      return new Promise(() => {})
    }))
    const materials = await createBlueMapMaterials([{ texture: 'data:image/png;base64,AA==' }])
    const geometry = new THREE.BufferGeometry()
    geometry.addGroup(0, 3, 0)

    void ensureBlueMapMaterialTextures(materials, geometry)
    await Promise.resolve()
    disposeBlueMapMaterials(materials)

    expect(signal?.aborted).toBe(true)
    geometry.dispose()
  })
})
