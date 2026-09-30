import { describe, expect, it } from 'vitest'
import { plainTextFromHtml, sanitizeEmailHtml, shouldApplyExternalContent } from './richText'

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

describe('shouldApplyExternalContent (IME composition guard)', () => {
  // Regression: while a CJK IME is composing, the parent echoes our emitted
  // HTML back through v-model. Applying it via setContent tears down the
  // ProseMirror node the IME is writing into and aborts Chinese input.
  it('never overwrites while an IME composition is in progress', () => {
    expect(shouldApplyExternalContent('<p>你好</p>', null, true)).toBe(false)
    // Even an unrelated external change must wait until composition ends.
    expect(shouldApplyExternalContent('<p>other</p>', '<p>你好</p>', true)).toBe(false)
  })

  it('ignores the echo of the HTML the editor just emitted', () => {
    const emitted = '<p>你好</p>'
    expect(shouldApplyExternalContent(emitted, emitted, false)).toBe(false)
  })

  it('applies a genuine external change once composition has ended', () => {
    expect(shouldApplyExternalContent('<p>reset</p>', '<p>你好</p>', false)).toBe(true)
    expect(shouldApplyExternalContent('<p>reset</p>', null, false)).toBe(true)
  })
})
