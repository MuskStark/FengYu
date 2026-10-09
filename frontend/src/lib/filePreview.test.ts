import { describe, expect, it } from 'vitest'
import {
  classifyPreview, escapeHtml, formatSize, shikiLanguageFor, splitLines,
} from './filePreview'

describe('classifyPreview', () => {
  it('routes rasters, markdown, svg, known source, and unknown extensions', () => {
    expect(classifyPreview('screens/shot.png')).toBe('image')
    expect(classifyPreview('docs/IMG_1.jpeg')).toBe('image')
    expect(classifyPreview('README.md')).toBe('markdown')
    expect(classifyPreview('NOTES.markdown')).toBe('markdown')
    expect(classifyPreview('assets/logo.svg')).toBe('svg')
    expect(classifyPreview('src/main.ts')).toBe('code')
    expect(classifyPreview('pkg/PKG.GO')).toBe('code') // case-insensitive extension
    expect(classifyPreview('data.bin')).toBe('text')
    expect(classifyPreview('no-extension')).toBe('text')
  })
})

describe('shikiLanguageFor', () => {
  it('maps extensions onto bundled grammars and leaves the rest plain', () => {
    expect(shikiLanguageFor('a.tsx')).toBe('tsx')
    expect(shikiLanguageFor('a.yml')).toBe('yaml')
    expect(shikiLanguageFor('a.properties')).toBe('properties')
    // Whole-name files like Dockerfile have no dot — their "extension" IS the lowercase name.
    expect(shikiLanguageFor('Dockerfile')).toBe('dockerfile')
    expect(shikiLanguageFor('archive.tar.gz')).toBeNull()
    expect(shikiLanguageFor('a.unknownext')).toBeNull()
  })
})

describe('splitLines', () => {
  it('drops the phantom row after a trailing newline', () => {
    expect(splitLines('one\ntwo\n')).toEqual(['one', 'two'])
    expect(splitLines('one\ntwo')).toEqual(['one', 'two'])
    expect(splitLines('')).toEqual([''])
    expect(splitLines('a\r\nb')).toEqual(['a\r', 'b']) // backend normalizes; guard documents the contract
  })
})

describe('escapeHtml', () => {
  it('neutralizes the five HTML-significant characters', () => {
    expect(escapeHtml('<a href="x">&\'</a>')).toBe('&lt;a href=&#34;x&#34;&gt;&amp;&#39;&lt;/a&gt;')
  })
})

describe('formatSize', () => {
  it('formats bytes at the three magnitudes the pane shows', () => {
    expect(formatSize(512)).toBe('512 B')
    expect(formatSize(2048)).toBe('2.0 KB')
    expect(formatSize(3 * 1024 * 1024)).toBe('3.0 MB')
    expect(formatSize(-1)).toBe('')
  })
})
