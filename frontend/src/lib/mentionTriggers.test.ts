import { describe, expect, it } from 'vitest'
import { buildMentionMarkdown, extractActiveMention } from './mentionTriggers'
import { buildMentionSections, flattenMentionSections, scoreFuzzyMatch, type MentionOption } from './mentionSearch'

function file(path: string, name = path.split('/').pop()!): MentionOption {
  return { id: 'file:' + path, category: 'file', label: name, description: path, value: path,
    markdown: `[${name}](./${path})`, icon: 'mdi-file-outline' }
}

describe('mention trigger detection (ZCode promptInputTriggers semantics)', () => {
  it('triggers @ after whitespace with the query extracted', () => {
    expect(extractActiveMention('look at src/')).toBeNull()
    expect(extractActiveMention('look at @src')).toEqual({ trigger: '@', query: 'src', tokenStart: 8 })
    expect(extractActiveMention('@')).toEqual({ trigger: '@', query: '', tokenStart: 0 })
  })

  it('triggers @ directly after Han characters (Chinese input adjacency)', () => {
    expect(extractActiveMention('看一下@文件')).toEqual({ trigger: '@', query: '文件', tokenStart: 3 })
  })

  it('does not trigger for email-like text after Han characters', () => {
    expect(extractActiveMention('发到 user@example.com')).toBeNull()
  })

  it('does not trigger when the symbol is glued to a word without space', () => {
    expect(extractActiveMention('user@example')).toBeNull()
    expect(extractActiveMention('a$b')).toBeNull()
  })

  it('triggers $ after whitespace and normalizes fullwidth aliases', () => {
    expect(extractActiveMention('use $skill')).toEqual({ trigger: '$', query: 'skill', tokenStart: 4 })
    expect(extractActiveMention('用 ¥技能')).toEqual({ trigger: '$', query: '技能', tokenStart: 2 })
    // Unlike @, the $ trigger keeps ZCode's stricter gap rule — glued Han does not fire it.
    expect(extractActiveMention('用¥技能')).toBeNull()
  })
})

describe('mention markdown serialization (ZCode mentionMarkdown forms)', () => {
  it('links files relatively with a ./ prefix and directory slash', () => {
    expect(buildMentionMarkdown('file', 'a.txt', 'src/a.txt')).toBe('[a.txt](./src/a.txt)')
    expect(buildMentionMarkdown('file', 'src', 'src', true)).toBe('[src](./src/)')
    expect(buildMentionMarkdown('file', 'b', '/b')).toBe('[b](./b)')
  })

  it('sigils skills and links plugin identities', () => {
    expect(buildMentionMarkdown('skill', 'code-review', 'code-review')).toBe('$code-review')
    expect(buildMentionMarkdown('plugin', 'Excel', 'excel')).toBe('[@Excel](plugin://excel)')
  })

  it('escapes markdown-breaking characters', () => {
    expect(buildMentionMarkdown('skill', 'a[b]', 'x')).toBe('$a\\[b\\]')
    expect(buildMentionMarkdown('plugin', 'X>', 'x')).toBe('[@X\\>](plugin://x)')
  })
})

describe('mention scoring and sections (ZCode mentionSearch rules)', () => {
  it('layers prefix over substring over subsequence', () => {
    expect(scoreFuzzyMatch('ap', 'app')).toBe(1)
    expect(scoreFuzzyMatch('pp', 'app')).toBeGreaterThanOrEqual(100)
    expect(scoreFuzzyMatch('ap', 'a-x-p')).toBeGreaterThanOrEqual(200)
    expect(scoreFuzzyMatch('zz', 'app')).toBeNull()
  })

  it('never falls through to subsequence for CJK queries', () => {
    expect(scoreFuzzyMatch('文件', 'x文件y')).toBeGreaterThanOrEqual(100) // substring works
    expect(scoreFuzzyMatch('件文', 'a文b件c')).toBeNull() // no subsequence fallback for Han
  })

  it('caps empty-query previews at 10 files / 3 others and orders by score', () => {
    const files = ['a1', 'a2', 'a3', 'a4', 'a5', 'a6', 'a7', 'a8', 'a9', 'a10', 'a11', 'b1']
      .map(name => file(name))
    const plugins: MentionOption[] = ['p1', 'p2', 'p3', 'p4'].map(name => ({
      id: 'plugin:' + name, category: 'plugin' as const, label: name, description: '', value: name,
      markdown: '[@' + name + '](plugin://' + name + ')', icon: 'mdi-puzzle-outline' }))
    const flows: MentionOption[] = ['f1', 'f2'].map(name => ({
      id: 'flow:' + name, category: 'flow' as const, label: name, description: '', value: name,
      markdown: '[@' + name + '](flow://' + name + ')', icon: 'mdi-vector-polyline' }))

    const sections = buildMentionSections('@', { file: files, plugin: plugins, flow: flows }, '')
    // ZCode's @ group order: plugins → files (+ flows in the whiteboards slot).
    expect(sections.map(s => s.category)).toEqual(['plugin', 'file', 'flow'])
    expect(sections[0].options).toHaveLength(3) // plugin preview cap
    expect(sections[1].options).toHaveLength(10) // file preview cap
    expect(sections[2].options).toHaveLength(2) // flow group below its cap
    expect(sections[1].options[0].label).toBe('a1') // prefix matches first

    const queried = buildMentionSections('@', { file: files, plugin: plugins, flow: flows }, 'b1')
    expect(queried.map(s => s.category)).toEqual(['file'])
    expect(queried[0].options.map(o => o.label)).toEqual(['b1'])
  })

  it('flattens sections in keyboard-navigation order', () => {
    const flat = flattenMentionSections([
      { category: 'plugin', options: [{ ...file('x'), category: 'plugin', id: 'p' }] },
      { category: 'file', options: [file('a'), file('b')] },
    ])
    expect(flat.map(o => o.id)).toEqual(['p', 'file:a', 'file:b'])
  })
})
