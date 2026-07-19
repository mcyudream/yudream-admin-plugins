import { describe, expect, it } from 'vitest'
import { releaseLowresImage } from './lowresImageDecode'

describe('releaseLowresImage', () => {
  it('closes ImageBitmap-like decoded images but leaves DOM image elements alone', () => {
    let closed = 0
    releaseLowresImage({ close: () => { closed += 1 } } as unknown as ImageBitmap)
    releaseLowresImage({} as HTMLImageElement)
    releaseLowresImage(null)
    expect(closed).toBe(1)
  })
})
