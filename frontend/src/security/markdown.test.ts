import { describe, expect, it } from 'vitest'
import { renderMarkdown } from './markdown'

// The sanitizer itself needs a DOM; assertions below hold for both the sanitized
// output and the node passthrough, so the pipeline stays testable headless.
describe('renderMarkdown fenced code blocks', () => {
  it('wraps fenced blocks in the labelled shell with a copy control', () => {
    const html = renderMarkdown('```ts\nconst x = 1\n```')
    expect(html).toContain('class="cx-code"')
    expect(html).toContain('cx-code__lang">ts<')
    expect(html).toContain('cx-code__copy')
    expect(html).toContain('language-ts')
  })

  it('highlights registered languages with highlight.js token spans', () => {
    const json = renderMarkdown('```json\n{"answer": 42}\n```')
    expect(json).toContain('hljs-attr')
    expect(json).toContain('hljs-number')
    const ts = renderMarkdown('```ts\nconst greeting: string = "hi"\n```')
    expect(ts).toContain('hljs-keyword')
    expect(ts).toContain('hljs-string')
  })

  it('falls back to escaped plain text for unregistered languages', () => {
    const html = renderMarkdown('```notalanguage\nconst <oops>\n```')
    expect(html).not.toContain('hljs-')
    expect(html).toContain('&lt;oops&gt;')
  })

  it('never auto-detects a language for bare fences', () => {
    const html = renderMarkdown('```\nSELECT * FROM t\n```')
    expect(html).not.toContain('hljs-')
  })
})
