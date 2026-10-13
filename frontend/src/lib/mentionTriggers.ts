/**
 * Mention trigger detection for the chat composer (regex family + domain-like guard +
 * fullwidth alias).
 *
 * Triggers: `@` opens the unified invocation panel (plugins/skills/flows + files),
 * `/` opens the custom slash-commands panel. `@` is the single invocation surface since
 * 4.1.0: every pick inserts a `[@label](scheme://id)` chip the backend resolves
 * deterministically at turn start (the retired `$` skills trigger and the flow hand-off
 * both converged here). The trigger must sit at the start of the text or after whitespace
 * (Han/fullwidth punctuation also counts for `@`, so Chinese input right before the
 * symbol still triggers); the query may not contain a second trigger character.
 */

export type MentionTrigger = '@' | '/'

/** `@` trigger: whitespace OR Han/fullwidth punctuation may precede the symbol. */
const AT_TRIGGER_RE = /(^|[\s\p{Script=Han}\u3000-\u303f\uff00-\uffef])([@])([^\s/@$#¥￥/]*)$/u
/** `/` trigger for custom slash commands; fires only at message start or after whitespace. */
const SLASH_TRIGGER_RE = /(^|\s)(\/)([^\s/@$#¥￥/]*)$/
/** A query shaped like `x.y` after a Han character reads as an email/domain — not a mention. */
const DOMAIN_LIKE_QUERY_RE = /\S\.\S/

export interface ActiveMention {
  trigger: MentionTrigger
  query: string
  /** Offset of the trigger character inside the full text (token spans [tokenStart, cursor)). */
  tokenStart: number
}

/**
 * Extract the active mention token from the text before the caret, or null:
 * try the permissive `@` regex first, then the slash one.
 */
export function extractActiveMention(textBeforeCursor: string): ActiveMention | null {
  const at = AT_TRIGGER_RE.exec(textBeforeCursor)
  if (at) {
    const query = at[3]
    const hanAdjacent = at[1] !== '' && /\p{Script=Han}/u.test(at[1])
    if (hanAdjacent && DOMAIN_LIKE_QUERY_RE.test(query)) return null
    return { trigger: '@', query, tokenStart: textBeforeCursor.length - query.length - 1 }
  }
  const slash = SLASH_TRIGGER_RE.exec(textBeforeCursor)
  if (slash) {
    return { trigger: '/', query: slash[3], tokenStart: textBeforeCursor.length - slash[3].length - 1 }
  }
  return null
}

/** Escape a label/destination for the markdown mention forms. */
export function escapeMentionPart(value: string): string {
  return value.replace(/([[\]<>\\])/g, '\\$1')
}

/**
 * Canonical send-time markdown per mention category:
 * files link relatively (`./` prefix, trailing `/` for directories); the three invocation
 * categories — plugins, skills, flows — share one `[@label](scheme://id)` chip grammar the
 * backend host-parses at turn start (skill body injection, plugin tool attach, flow run
 * tool attach).
 */
export function buildMentionMarkdown(
  category: 'file' | 'skill' | 'plugin' | 'flow',
  label: string,
  value: string,
  isDirectory = false,
): string {
  if (category === 'file') {
    const target = `./${value.replace(/^\.?\//, '')}${isDirectory ? '/' : ''}`
    return `[${escapeMentionPart(label)}](${escapeMentionPart(target)})`
  }
  return `[@${escapeMentionPart(label)}](${category}://${escapeMentionPart(value)})`
}
