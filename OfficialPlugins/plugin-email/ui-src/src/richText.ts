import DOMPurify from 'dompurify'

const ALLOWED_TAGS = ['p', 'br', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'strong', 'b', 'em', 'i', 'u',
  'ol', 'ul', 'li', 'a', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'blockquote', 'span']
const SAFE_STYLES = new Set(['text-align', 'color', 'background-color', 'font-size', 'font-weight',
  'font-style', 'text-decoration', 'border', 'border-color', 'border-style', 'border-width'])

export function sanitizeEmailHtml(input: string): string {
  const clean = DOMPurify.sanitize(input ?? '', {
    ALLOWED_TAGS,
    ALLOWED_ATTR: ['href', 'title', 'style'],
    ALLOWED_URI_REGEXP: /^(?:(?:https?|mailto):|[^a-z]|[a-z+.-]+(?:[^a-z+.-:]|$))/i,
  })
  const document = new DOMParser().parseFromString(`<body>${clean}</body>`, 'text/html')
  document.body.querySelectorAll<HTMLElement>('[style]').forEach(element => {
    const declarations = (element.getAttribute('style') ?? '').split(';').flatMap(item => {
      const colon = item.indexOf(':')
      if (colon < 1) return []
      const name = item.slice(0, colon).trim().toLowerCase()
      const value = item.slice(colon + 1).trim()
      return SAFE_STYLES.has(name) && value.length <= 80 && !/url\(|expression|javascript:/i.test(value)
        ? [`${name}: ${value}`]
        : []
    })
    if (declarations.length) element.setAttribute('style', declarations.join('; '))
    else element.removeAttribute('style')
  })
  return document.body.innerHTML
}

export function plainTextFromHtml(html: string): string {
  return new DOMParser().parseFromString(sanitizeEmailHtml(html), 'text/html').body.textContent?.trim() ?? ''
}

/**
 * Decide whether an external `modelValue` change should be written back into the
 * editor via `setContent`.
 *
 * Tiptap's `setContent` replaces the whole ProseMirror document. During a CJK IME
 * composition the browser is writing into that very document node, so rewriting it
 * mid-composition aborts the input — Chinese (and other IME) text becomes impossible
 * to type. We therefore:
 *   1. Never apply while a composition is in progress.
 *   2. Skip the echo of the HTML the editor itself just emitted (avoids an editor →
 *      parent → editor round-trip on every keystroke), which also keeps the caret
 *      stable.
 *
 * @param incoming  the external value arriving through v-model
 * @param emitted   the last HTML the editor emitted, or null if none yet
 * @param composing whether an IME composition is currently in progress
 */
export function shouldApplyExternalContent(incoming: string, emitted: string | null, composing: boolean): boolean {
  if (composing) return false
  if (emitted !== null && sanitizeEmailHtml(incoming ?? '') === sanitizeEmailHtml(emitted)) return false
  return true
}
