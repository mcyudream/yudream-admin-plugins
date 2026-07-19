import * as THREE from 'three'
import { describe, expect, it } from 'vitest'
import { BlueMapVisibleMaterials } from './blueMapVisibleMaterials'

describe('BlueMapVisibleMaterials', () => {
  it('keeps each visible material once until the last terrain tile releases it', () => {
    const visible = new BlueMapVisibleMaterials()
    const shared = new THREE.ShaderMaterial()
    const unique = new THREE.ShaderMaterial()

    visible.retain([shared, unique])
    visible.retain([shared])
    expect(visible.values).toEqual([shared, unique])

    visible.release([shared, unique])
    expect(visible.values).toEqual([shared])

    visible.release([shared])
    expect(visible.values).toEqual([])
    shared.dispose()
    unique.dispose()
  })

  it('clears its references when the scene is disposed', () => {
    const visible = new BlueMapVisibleMaterials()
    const material = new THREE.ShaderMaterial()
    visible.retain([material])
    visible.clear()
    expect(visible.values).toEqual([])
    material.dispose()
  })
})
