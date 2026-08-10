import { describe, it, expect } from 'vitest'
import { formatA11yTree } from '../src/browser/a11y'

describe('formatA11yTree', () => {
  it('renders a single root node', () => {
    const tree = {
      nodes: [
        { nodeId: '0', role: { type: 'rootWebArea' }, name: { value: 'Home' }, childIds: ['1'] },
        { nodeId: '1', role: { type: 'button' }, name: { value: 'Submit' }, childIds: [] },
      ],
    }
    const out = formatA11yTree(tree)
    expect(out).toContain('rootWebArea "Home":')
    expect(out).toContain('button "Submit"')
    // child indented deeper than root
    const rootLine = out.split('\n').find((l) => l.includes('rootWebArea'))!
    const childLine = out.split('\n').find((l) => l.includes('button "Submit"'))!
    expect(childLine.indexOf('button')).toBeGreaterThan(rootLine.indexOf('rootWebArea'))
  })

  it('ignores nodes not reachable from the root', () => {
    const tree = {
      nodes: [
        { nodeId: '0', role: { type: 'rootWebArea' }, name: { value: '' }, childIds: [] },
        { nodeId: 'orphan', role: { type: 'link' }, name: { value: 'nope' }, childIds: [] },
      ],
    }
    expect(formatA11yTree(tree)).not.toContain('nope')
  })

  it('handles empty tree', () => {
    expect(formatA11yTree({ nodes: [] })).toBe('')
  })
})
