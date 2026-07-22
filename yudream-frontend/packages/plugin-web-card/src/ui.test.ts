import { describe, expect, it } from 'vitest'
import { dateTime } from './ui'

describe('dateTime', () => {
  it('formats numeric timestamps serialized as strings', () => {
    expect(dateTime('1753006303724')).not.toBe('Invalid Date')
  })

  it('keeps missing and invalid values readable', () => {
    expect(dateTime()).toBe('-')
    expect(dateTime('not-a-date')).toBe('-')
  })
})
