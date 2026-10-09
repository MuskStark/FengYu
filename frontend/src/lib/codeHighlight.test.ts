import { describe, expect, it } from 'vitest'
import { highlightLines, MAX_HIGHLIGHT_BYTES } from './codeHighlight'

describe('highlightLines', () => {
  it('emits one HTML string per line carrying both theme palettes as CSS vars', async () => {
    const lines = await highlightLines('demo.ts', 'const answer: number = 42\n// done')
    expect(lines).not.toBeNull()
    expect(lines).toHaveLength(2)
    expect(lines![0]).toContain('--shiki-light:')
    expect(lines![0]).toContain('--shiki-dark:')
    expect(lines![0]).toContain('fv-t')
    expect(lines![0]).toContain('const')
    expect(lines![1]).toContain('// done')
  })

  it('keeps empty lines as zero-width rows so the grid holds its height', async () => {
    const lines = await highlightLines('empty.json', '{\n\n}')
    expect(lines).toHaveLength(3)
    expect(lines![1]).toBe('&#8203;')
  })

  it('falls back to null for unknown languages and over-budget content', async () => {
    expect(await highlightLines('data.bin', 'raw')).toBeNull()
    expect(await highlightLines('huge.ts', 'x'.repeat(MAX_HIGHLIGHT_BYTES + 1))).toBeNull()
  })
})
