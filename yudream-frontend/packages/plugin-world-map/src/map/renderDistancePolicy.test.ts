import { describe, expect, it } from 'vitest'
import {
  DEFAULT_HIRES_RADIUS,
  DEFAULT_LOWRES_COVERAGE,
  MAX_HIRES_RADIUS,
  MAX_LOWRES_COVERAGE,
  MIN_HIRES_RADIUS,
  MIN_LOWRES_COVERAGE,
  normalizeHiresRadius,
  normalizeLowresCoverage,
} from './renderDistancePolicy'

describe('render distance policy', () => {
  it('keeps terrain detail inside the bounded tile budget', () => {
    expect(normalizeHiresRadius()).toBe(DEFAULT_HIRES_RADIUS)
    expect(normalizeHiresRadius(0)).toBe(MIN_HIRES_RADIUS)
    expect(normalizeHiresRadius(99)).toBe(MAX_HIRES_RADIUS)
    expect(normalizeHiresRadius(3.6)).toBe(4)
  })

  it('keeps overview coverage finite and predictable', () => {
    expect(normalizeLowresCoverage()).toBe(DEFAULT_LOWRES_COVERAGE)
    expect(normalizeLowresCoverage(0)).toBe(MIN_LOWRES_COVERAGE)
    expect(normalizeLowresCoverage(99)).toBe(MAX_LOWRES_COVERAGE)
    expect(normalizeLowresCoverage(2.25)).toBe(2.25)
  })
})
