import { applyToolActivity, type ToolActivity } from './toolActivity'

/**
 * The ordered timeline INSIDE an assistant turn. The buckets (turn.content /
 * turn.thinking / turn.activities) keep their meaning — flat text for model context,
 * find, copy — but they erase the order a multi-round agentic turn actually happened
 * in. Segments restore it: text, thinking, and tool references in arrival order,
 * maintained by the same stream callbacks that feed the buckets. The transcript
 * renders them interleaved (the mainstream "parts" pattern); turns without segments
 * (legacy persisted history) keep the old bucket layout.
 */
export type ChatSegment =
  | { kind: 'text'; text: string }
  | { kind: 'thinking'; text: string }
  | { kind: 'tool'; activityId: string }

/** Wire form of one persisted part — self-contained, opaque to the server. */
export interface PersistedChatPart {
  kind: 'text' | 'thinking' | 'tool'
  text?: string
  activity?: ToolActivity
}

/** Append a streamed text delta, merging into a trailing segment of the same kind. */
function appendDelta(segments: ChatSegment[], kind: 'text' | 'thinking', text: string): void {
  if (!text) return
  const last = segments[segments.length - 1]
  if (last && last.kind === kind) last.text += text
  else segments.push({ kind, text })
}

export function appendTextSegment(segments: ChatSegment[], text: string): void {
  appendDelta(segments, 'text', text)
}

export function appendThinkingSegment(segments: ChatSegment[], text: string): void {
  appendDelta(segments, 'thinking', text)
}

/**
 * applyToolActivity + segment bookkeeping: a tool segment is added when (and only
 * when) the activity is NEW — the result/approval phases update an existing row and
 * must not reorder the timeline.
 */
export function applyToolActivitySegmented(
  activities: ToolActivity[], segments: ChatSegment[], payload: Record<string, unknown>,
): ToolActivity | null {
  const before = activities.length
  const item = applyToolActivity(activities, payload)
  if (item && activities.length > before) segments.push({ kind: 'tool', activityId: item.id })
  return item
}

/** Serialize the timeline for persistence; null when there is nothing worth storing. */
export function segmentsToParts(segments: ChatSegment[], activities: ToolActivity[]): PersistedChatPart[] | null {
  const parts: PersistedChatPart[] = []
  for (const segment of segments) {
    if (segment.kind === 'tool') {
      const activity = activities.find(item => item.id === segment.activityId)
      if (activity) parts.push({ kind: 'tool', activity })
    } else if (segment.text) {
      parts.push({ kind: segment.kind, text: segment.text })
    }
  }
  return parts.length > 0 ? parts : null
}

/** Structural check so persisted garbage never renders as an activity row. */
function isToolActivity(value: unknown): value is ToolActivity {
  if (!value || typeof value !== 'object') return false
  const v = value as Record<string, unknown>
  return typeof v.id === 'string' && v.id !== ''
    && typeof v.name === 'string'
    && typeof v.status === 'string'
    && typeof v.output === 'string'
    && typeof v.startedAt === 'number'
    && !!v.args && typeof v.args === 'object'
}

/**
 * Rebuild the interleaved timeline from persisted parts. Malformed entries are
 * dropped; null (absent or wholly unusable parts) means the turn keeps the legacy
 * bucket layout. Tool activities come back on the activities array, referenced by id
 * from their segments.
 */
export function partsToSegments(parts: unknown): { segments: ChatSegment[]; activities: ToolActivity[] } | null {
  if (!Array.isArray(parts) || parts.length === 0) return null
  const segments: ChatSegment[] = []
  const activities: ToolActivity[] = []
  for (const raw of parts) {
    if (!raw || typeof raw !== 'object') continue
    const part = raw as Record<string, unknown>
    if (part.kind === 'text' && typeof part.text === 'string' && part.text) {
      segments.push({ kind: 'text', text: part.text })
    } else if (part.kind === 'thinking' && typeof part.text === 'string' && part.text) {
      segments.push({ kind: 'thinking', text: part.text })
    } else if (part.kind === 'tool' && isToolActivity(part.activity)) {
      activities.push(part.activity)
      segments.push({ kind: 'tool', activityId: part.activity.id })
    }
  }
  return segments.length > 0 ? { segments, activities } : null
}
