import { describe, expect, it } from 'vitest'
import { plainTextFromHtml, sanitizeEmailHtml } from './richText'

describe('email-safe rich text', () => {
  it('normalizes Word HTML without losing tables or text', () => {
    const clean = sanitizeEmailHtml(`<p class="MsoNormal"><b>Hello</b><o:p>&nbsp;</o:p></p>
      <table><tr><td>Q1</td></tr></table><img src="javascript:alert(1)">`)
    expect(clean).toContain('<b>Hello</b>')
    expect(clean).toContain('<table>')
    expect(clean).not.toContain('MsoNormal')
    expect(clean).not.toContain('javascript:')
  })

  it('keeps only email-safe styles and derives plain text', () => {
    const clean = sanitizeEmailHtml('<p style="text-align:center;position:absolute;color:#336699">Report</p>')
    expect(clean).toContain('text-align: center')
    expect(clean).toContain('color: #336699')
    expect(clean).not.toContain('position')
    expect(plainTextFromHtml(clean)).toBe('Report')
  })
})
