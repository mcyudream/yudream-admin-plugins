import { describe, expect, it } from 'vitest'
import { DEFAULT_STRUCTURED_LAYOUT, normalizeStructuredLayout, validateTemplateCode } from './template-editor'

describe('template editor state', () => {
  it('upgrades legacy default layouts to editable JSON', () => {
    expect(normalizeStructuredLayout('default')).toBe(DEFAULT_STRUCTURED_LAYOUT)
    expect(() => JSON.parse(normalizeStructuredLayout('default'))).not.toThrow()
  })

  it('formats valid layout JSON and preserves invalid input for correction', () => {
    expect(normalizeStructuredLayout('{"variant":"compact"}')).toContain('\n  "variant": "compact"\n')
    expect(normalizeStructuredLayout('{invalid')).toBe('{invalid')
  })

  it('validates structured JSON and advanced card roots', () => {
    expect(validateTemplateCode('STRUCTURED', '{invalid', '')).toBe('结构化布局 JSON 格式无效')
    expect(validateTemplateCode('ADVANCED', '{}', '<article>Missing root</article>')).toContain('id="web-card"')
    expect(validateTemplateCode('ADVANCED', '{}', '<article id="web-card"></article>')).toBe('')
  })
})
