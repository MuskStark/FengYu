/**
 * Mention candidate scoring/filtering, ported from ZCode's `mentionSearch.ts` rules:
 * prefix beats substring beats (ASCII-only) subsequence; CJK queries never fall through to
 * subsequence matching because it is far too loose for Han input. Empty queries keep a per-group
 * preview cap (files 10, other groups 3 — ZCode's MENTION_*_PREVIEW_LIMIT constants).
 */

import type { MentionTrigger } from './mentionTriggers'

export type MentionCategory = 'file' | 'skill' | 'plugin' | 'flow'

export interface MentionOption {
  id: string
  category: MentionCategory
  /** Primary label shown in the panel row and the chip. */
  label: string
  /** Weak trailing info in the row (relative path / description). */
  description: string
  /** Category-dependent identity used by the send-time markdown. */
  value: string
  /** Canonical markdown injected into the outgoing prompt (built per category). */
  markdown: string
  icon: string
  isDirectory?: boolean
}

export interface MentionSection {
  category: MentionCategory
  options: MentionOption[]
}

const FILES_PREVIEW_LIMIT = 10
const DEFAULT_PREVIEW_LIMIT = 3

function isAscii(query: string): boolean {
  return !/[^\u0000-\u007f]/.test(query)
}

/**
 * ZCode's fuzzy layers: prefix → len-diff (best), substring → 100+index,
 * subsequence → 200+misses (ASCII queries only). Returns null when nothing matches.
 */
export function scoreFuzzyMatch(query: string, candidate: string): number | null {
  if (!query) return 0
  const q = query.toLowerCase()
  const c = candidate.toLowerCase()
  if (c.startsWith(q)) return c.length - q.length
  const index = c.indexOf(q)
  if (index >= 0) return 100 + index
  if (!isAscii(q)) return null
  let cursor = 0
  let misses = 0
  for (const char of q) {
    const found = c.indexOf(char, cursor)
    if (found < 0) return null
    misses += found - cursor
    cursor = found + 1
  }
  return 200 + misses
}

/** Build the sections for one trigger from the flat option pools (already category-tagged). */
export function buildMentionSections(
  trigger: MentionTrigger,
  pools: Partial<Record<MentionCategory, MentionOption[]>>,
  query: string,
): MentionSection[] {
  const categories: MentionCategory[] = trigger === '@'
    ? ['plugin', 'file', 'flow']
    : ['skill']
  const sections: MentionSection[] = []
  for (const category of categories) {
    const pool = pools[category]
    if (!pool || pool.length === 0) continue
    const scored: { option: MentionOption; score: number; tie: number }[] = []
    for (const option of pool) {
      // ZCode weighs label strongest, then value; description participates except for plugins.
      const scores = [scoreFuzzyMatch(query, option.label), scoreFuzzyMatch(query, option.value)]
      if (category !== 'plugin') scores.push(scoreFuzzyMatch(query, option.description))
      const best = scores.reduce<number | null>(
        (min, score) => score !== null && (min === null || score < min) ? score : min, null)
      if (best !== null) scored.push({ option, score: best, tie: option.label.localeCompare('') })
    }
    scored.sort((a, b) => a.score - b.score || a.option.label.localeCompare(b.option.label))
    let options = scored.map(entry => entry.option)
    if (!query) {
      const cap = category === 'file' ? FILES_PREVIEW_LIMIT : DEFAULT_PREVIEW_LIMIT
      options = options.slice(0, cap)
    }
    if (options.length > 0) sections.push({ category, options })
  }
  return sections
}

/** Flatten sections into the keyboard-navigable list; index arithmetic happens on this order. */
export function flattenMentionSections(sections: MentionSection[]): MentionOption[] {
  return sections.flatMap(section => section.options)
}
