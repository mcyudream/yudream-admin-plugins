import { describe, expect, it } from 'vitest'
import { shouldCreatePrbmDecoder } from './prbmDecodePolicy'

describe('shouldCreatePrbmDecoder', () => {
  it('starts workers only for BlueMap binary terrain', () => {
    expect(shouldCreatePrbmDecoder('BLUEMAP')).toBe(true)
    expect(shouldCreatePrbmDecoder('YUDREAM')).toBe(false)
    expect(shouldCreatePrbmDecoder(undefined)).toBe(false)
  })
})
