import { describe, expect, it } from 'vitest'
import { canvasPointerToNdc } from './canvasCoordinates'

describe('canvasPointerToNdc', () => {
  it('maps the canvas center to the origin and top-left to -1,+1', () => {
    const bounds = { left: 10, top: 20, width: 400, height: 200 }
    expect(canvasPointerToNdc(210, 120, bounds)).toEqual({ x: 0, y: 0 })
    expect(canvasPointerToNdc(10, 20, bounds)).toEqual({ x: -1, y: 1 })
  })
})
