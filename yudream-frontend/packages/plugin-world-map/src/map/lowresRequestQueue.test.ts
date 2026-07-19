import { describe, expect, it } from 'vitest'
import { enqueueLowresRequest } from './lowresRequestQueue'

describe('enqueueLowresRequest', () => {
  it('keeps nearest overview requests and rejects a more distant request after reaching capacity', () => {
    const queue: Array<{ key: string, priority: number, value: string }> = []
    expect(enqueueLowresRequest(queue, { key: 'far', priority: 9, value: 'far' }, 2)).toBe(true)
    expect(enqueueLowresRequest(queue, { key: 'near', priority: 1, value: 'near' }, 2)).toBe(true)
    expect(enqueueLowresRequest(queue, { key: 'mid', priority: 4, value: 'mid' }, 2)).toBe(true)
    expect(queue.map(request => request.key)).toEqual(['near', 'mid'])
    expect(enqueueLowresRequest(queue, { key: 'very-far', priority: 10, value: 'very-far' }, 2)).toBe(false)
  })
})
