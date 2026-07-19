import type { ShaderMaterial } from 'three'

/** Tracks the BlueMap materials referenced by terrain meshes that are still in the scene. */
export class BlueMapVisibleMaterials {
  private readonly references = new Map<ShaderMaterial, number>()
  private readonly materials: ShaderMaterial[] = []

  retain(materials: readonly ShaderMaterial[]): void {
    for (const material of materials) {
      const count = this.references.get(material) ?? 0
      if (count === 0) {
        this.materials.push(material)
      }
      this.references.set(material, count + 1)
    }
  }

  release(materials: readonly ShaderMaterial[]): void {
    for (const material of materials) {
      const remaining = (this.references.get(material) ?? 1) - 1
      if (remaining > 0) {
        this.references.set(material, remaining)
        continue
      }
      this.references.delete(material)
      const index = this.materials.indexOf(material)
      if (index >= 0) {
        this.materials.splice(index, 1)
      }
    }
  }

  get values(): readonly ShaderMaterial[] {
    return this.materials
  }

  clear(): void {
    this.references.clear()
    this.materials.length = 0
  }
}
