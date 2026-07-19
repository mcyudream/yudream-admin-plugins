import { describe, expect, it } from 'vitest'
import { decodePrbm } from './PrbmDecoder'

function tileFixture(): ArrayBuffer {
  const bytes: number[] = [1, 0b00000010, 3, 0, 0, 0, 0, 0]
  const writeAttribute = (name: string, flags: number, values: number[]) => {
    bytes.push(...new TextEncoder().encode(name), 0, flags)
    while (bytes.length % 4) bytes.push(0)
    const encoded = new Uint8Array(new Float32Array(values).buffer)
    bytes.push(...encoded)
  }
  // position vec3 and uv vec2, matching the PRBM v1 writer's little-endian layout.
  writeAttribute('position', 0b00100001, [0, 0, 0, 1, 0, 0, 0, 1, 0])
  writeAttribute('uv', 0b00010001, [0, 0, 1, 0, 0, 1])
  while (bytes.length % 4) bytes.push(0)
  bytes.push(255, 255, 255, 255)
  return new Uint8Array(bytes).buffer
}

describe('decodePrbm', () => {
  it('decodes BlueMap-compatible position and uv buffers', () => {
    const geometry = decodePrbm(tileFixture())

    expect(geometry.getAttribute('position').count).toBe(3)
    expect(geometry.getAttribute('uv').count).toBe(3)
    expect(geometry.getIndex()).toBeNull()
  })

  it('rejects an unsupported format version', () => {
    expect(() => decodePrbm(new Uint8Array([2, 0, 0, 0, 0, 0, 0, 0]).buffer)).toThrow('Unsupported PRBM')
  })
})
