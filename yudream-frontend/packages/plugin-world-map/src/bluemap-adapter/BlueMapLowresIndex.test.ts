import { describe, expect, it } from 'vitest'
import { hasBlueMapLowresTile, parseBlueMapLowresIndex } from './BlueMapLowresIndex'

describe('BlueMap lowres coverage index', () => {
  it('uses sparse rows to skip nonexistent tiles without hiding published tiles', () => {
    const index = parseBlueMapLowresIndex({ levels: { 1: [[-1, [[-2, 1]]], [0, [[0, 0]]]] } })
    expect(hasBlueMapLowresTile(index, 1, -2, -1)).toBe(true)
    expect(hasBlueMapLowresTile(index, 1, 1, -1)).toBe(true)
    expect(hasBlueMapLowresTile(index, 1, 2, -1)).toBe(false)
    expect(hasBlueMapLowresTile(index, 1, 0, 1)).toBe(false)
  })

  it('keeps the old request behavior when metadata is absent or invalid', () => {
    expect(hasBlueMapLowresTile(undefined, 1, 99, 99)).toBe(true)
    expect(parseBlueMapLowresIndex({ levels: { 1: [[0, [[3, 2]]]] } })).toBeUndefined()
  })
})
