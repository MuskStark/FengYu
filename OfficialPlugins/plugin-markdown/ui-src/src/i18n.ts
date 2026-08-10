/**
 * Client-side i18n for the Markdown Editor UI. The host pushes the active locale through
 * `environment` events; this module ships the `mde.*` message map for en/zh and picks the matching
 * table. Mirrors the offlinepython frontend i18n shape (flat keys + messagesFor/format).
 *
 * Both tables MUST keep identical key sets so neither locale ever renders a raw key.
 */
export type Messages = Record<string, string>

const en: Messages = {
  'mde.cardTitle': 'Markdown',
  'mde.editor': 'Markdown',
  'mde.preview': 'Preview',
  'mde.renderFailed': 'Render failed',
}

const zh: Messages = {
  'mde.cardTitle': 'Markdown',
  'mde.editor': 'Markdown',
  'mde.preview': '预览',
  'mde.renderFailed': '渲染失败',
}

const tables: Record<string, Messages> = { en, zh }

/** Resolve the active message table from a locale string (defaults to en). */
export function messagesFor(locale: string | undefined): Messages {
  if (!locale) return en
  return tables[locale.toLowerCase().startsWith('zh') ? 'zh' : 'en'] ?? en
}

/** Look up a key with positional {0}/{1}/… substitution. Falls back to the key itself. */
export function format(messages: Messages, key: string, ...args: (string | number)[]): string {
  let out = messages[key] ?? key
  args.forEach((a, i) => { out = out.replaceAll(`{${i}}`, String(a)) })
  return out
}
