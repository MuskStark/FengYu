/**
 * Mention trigger detection for the chat composer, ported from ZCode's
 * `promptInputTriggers.ts` semantics (regex family + domain-like guard + fullwidth alias).
 *
 * Triggers: `@` opens the plugin/file panel, `$` opens the skills panel. The trigger must sit
 * at the start of the text or after whitespace (Han/fullwidth punctuation also counts for `@`,
 * so Chinese input right before the symbol still triggers); the query may not contain a
 * second trigger character.
 */

export type MentionTrigger = '@' | '$'

/** `@` trigger: whitespace OR Han/fullwidth punctuation may precede the symbol. */
const AT_TRIGGER_RE = /(^|[\s\p{Script=Han}\u3000-\u303f\uff00-\uffef])([@])([^\s/@$#¥￥]*)$/u
/** `$` trigger (with fullwidth ¥/￥ aliases — IMEs type those for `$`). */
const GENERIC_TRIGGER_RE = /(^|\s)([$¥￥])([^\s/@$#¥￥]*)$/
/** A query shaped like `x.y` after a Han character reads as an email/domain — not a mention. */
const DOMAIN_LIKE_QUERY_RE = /\S\.\S/

export interface ActiveMention {
  trigger: MentionTrigger
  query: string
  /** Offset of the trigger character inside the full text (token spans [tokenStart, cursor)). */
  tokenStart: number
}

/**
 * Extract the active mention token from the text before the caret, or null. Mirrors ZCode's
 * `extractActivePromptInputTrigger`: try the permissive `@` regex first, then the generic one.
 */
export function extractActiveMention(textBeforeCursor: string): ActiveMention | null {
  const at = AT_TRIGGER_RE.exec(textBeforeCursor)
  if (at) {
    const query = at[3]
    const hanAdjacent = at[1] !== '' && /\p{Script=Han}/u.test(at[1])
    if (hanAdjacent && DOMAIN_LIKE_QUERY_RE.test(query)) return null
    return { trigger: '@', query, tokenStart: textBeforeCursor.length - query.length - 1 }
  }
  const generic = GENERIC_TRIGGER_RE.exec(textBeforeCursor)
  if (generic) {
    // ¥/￥ are IME aliases for $ — normalized here without rewriting the user's characters.
    return { trigger: '$', query: generic[3], tokenStart: textBeforeCursor.length - generic[3].length - 1 }
  }
  return null
}

/** Escape a label/destination for the markdown mention forms (ZCode mentionMarkdown escaping). */
export function escapeMentionPart(value: string): string {
  return value.replace(/([[\]<>\\])/g, '\\$1')
}

/**
 * Canonical send-time markdown per mention category, ported from ZCode's `mentionMarkdown.ts`:
 * files link relative (`./` prefix, trailing `/` for directories), skills carry the `$` sigil,
 * plugins put their identity in a `plugin://` destination.
 */
export function buildMentionMarkdown(
  category: 'file' | 'skill' | 'plugin',
  label: string,
  value: string,
  isDirectory = false,
): string {
  if (category === 'file') {
    const target = `./${value.replace(/^\.?\//, '')}${isDirectory ? '/' : ''}`
    return `[${escapeMentionPart(label)}](${escapeMentionPart(target)})`
  }
  if (category === 'skill') return `$${escapeMentionPart(label)}`
  return `[@${escapeMentionPart(label)}](plugin://${escapeMentionPart(value)})`
}
