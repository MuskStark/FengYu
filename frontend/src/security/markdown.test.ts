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

describe('localized code-copy labels', () => {
  it('injects the configured copy label and aria text into the copy control', async () => {
    const { setMarkdownCodeLabels } = await import('./markdown')
    setMarkdownCodeLabels({ copy: '复制', copyAria: '复制代码' })
    const html = renderMarkdown('```\nplain\n```')
    expect(html).toContain('aria-label="复制代码"')
    expect(html).toContain('>复制<')
    // restore defaults for any later test in this file
    setMarkdownCodeLabels({ copy: 'copy', copyAria: 'Copy code' })
  })

  it('escapes hostile label text (the label crosses into sanitized HTML)', async () => {
    const { setMarkdownCodeLabels } = await import('./markdown')
    setMarkdownCodeLabels({ copy: '<img src=x onerror=alert(1)>', copyAria: '"inject"' })
    const html = renderMarkdown('```\nplain\n```')
    expect(html).not.toContain('<img src=x')
    expect(html).toContain('&lt;img')
    expect(html).not.toContain('"inject"')
    setMarkdownCodeLabels({ copy: 'copy', copyAria: 'Copy code' })
  })
})
