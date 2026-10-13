import { describe, expect, it } from 'vitest'
import {
  appendTextSegment, appendThinkingSegment, applyToolActivitySegmented,
  partsToSegments, segmentsToParts, type ChatSegment,
} from './chatSegments'

describe('chatSegments streaming appends', () => {
  it('merges same-kind deltas into the trailing segment, splits across interruptions', () => {
    const segments: ChatSegment[] = []
    appendTextSegment(segments, 'Hello ')
    appendTextSegment(segments, 'world')
    appendThinkingSegment(segments, 'reasoning…')
    appendTextSegment(segments, 'After the tool ran')
    expect(segments).toEqual([
      { kind: 'text', text: 'Hello world' },
      { kind: 'thinking', text: 'reasoning…' },
      { kind: 'text', text: 'After the tool ran' },
    ])
  })

  it('adds a segment for a NEW tool call and none for its result/approval phases', () => {
    const segments: ChatSegment[] = []
    const activities: import('./toolActivity').ToolActivity[] = []
    const call = applyToolActivitySegmented(activities, segments,
      { phase: 'call', id: 't1', name: 'read_file', arguments: { path: 'a.txt' } })
    expect(call).toBeDefined()
    expect(segments).toEqual([{ kind: 'tool', activityId: 't1' }])
    expect(segments).toHaveLength(1)
    const result = applyToolActivitySegmented(activities, segments,
      { phase: 'result', id: 't1', success: true, output: 'ok' })
    expect(result?.status).toBe('completed')
    // The result updates the existing activity — the timeline is not reordered.
    expect(segments).toHaveLength(1)
  })

  it('keeps tool segments between text deltas in true arrival order', () => {
    const segments: ChatSegment[] = []
    const activities: import('./toolActivity').ToolActivity[] = []
    appendTextSegment(segments, 'Let me check. ')
    applyToolActivitySegmented(activities, segments, { phase: 'call', id: 't1', name: 'read_file', arguments: {} })
    applyToolActivitySegmented(activities, segments, { phase: 'result', id: 't1', success: true, output: 'x' })
    appendTextSegment(segments, 'The file says 42.')
    expect(segments.map(segment => segment.kind)).toEqual(['text', 'tool', 'text'])
  })
})

describe('chatSegments persistence round-trip', () => {
  it('serializes tool segments with their activity inline and rebuilds them', () => {
    const segments: ChatSegment[] = []
    const activities: import('./toolActivity').ToolActivity[] = []
    appendThinkingSegment(segments, 'round 1')
    applyToolActivitySegmented(activities, segments, { phase: 'call', id: 't1', name: 'read_file', arguments: { path: 'a' } })
    applyToolActivitySegmented(activities, segments, { phase: 'result', id: 't1', success: true, output: 'o' })
    appendTextSegment(segments, 'Answer.')

    const parts = segmentsToParts(segments, activities)
    expect(parts).not.toBeNull()
    const rebuilt = partsToSegments(parts)
    expect(rebuilt?.segments).toEqual(segments)
    expect(rebuilt?.activities).toEqual(activities)
  })

  it('returns null for empty or nothing-worth-storing timelines', () => {
    expect(segmentsToParts([], [])).toBeNull()
    const blank: ChatSegment[] = [{ kind: 'text', text: '' }]
    expect(segmentsToParts(blank, [])).toBeNull()
  })

  it('drops dangling tool references and malformed parts on rebuild', () => {
    const segments: ChatSegment[] = [
      { kind: 'text', text: 'before' },
      { kind: 'tool', activityId: 'gone' },
    ]
    // A tool segment whose activity vanished persists as nothing — rebuild keeps the text.
    const rebuilt = partsToSegments(segmentsToParts(segments, []))
    expect(rebuilt?.segments).toEqual([{ kind: 'text', text: 'before' }])

    expect(partsToSegments('nope')).toBeNull()
    expect(partsToSegments([])).toBeNull()
    expect(partsToSegments([{ kind: 'text', text: 'ok' }, { kind: 'tool' }, 42, null,
      { kind: 'tool', activity: { id: 'x' } }])).toEqual({
      segments: [{ kind: 'text', text: 'ok' }],
      activities: [],
    })
    expect(partsToSegments([{ kind: 'text' }])).toBeNull()
  })
})
