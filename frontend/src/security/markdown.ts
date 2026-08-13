import DOMPurify from 'dompurify'
import { marked } from 'marked'

marked.setOptions({ breaks: true, gfm: true })

function escapeHtml(input: string): string {
  return input
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * Wrap fenced code blocks in a labelled shell with a copy control so the
 * chat transcript renders language + one-click copy (cherry-studio style).
 *
 * The copy affordance is a plain <span role="button"> (DOMPurify forbids
 * <button>, and the actual clipboard write is delegated to a click handler
 * on the scroll container in AiChat.vue — no inline JS, no duplicated code
 * payload). Marked v12+ passes a token object to renderer.code; the legacy
 * positional signature is handled too for safety.
 */
marked.use({
  renderer: {
    code(code: unknown, infostring?: string): string {
      const token = code as { text?: string; lang?: string }
      const text = typeof code === 'string' ? code : (token?.text ?? '')
      const rawLang = typeof code === 'string' ? (infostring ?? '') : (token?.lang ?? '')
      const lang = String(rawLang).trim().split(/\s+/)[0]
      const escaped = escapeHtml(text)
      const langLabel = lang ? escapeHtml(lang) : ''
      const codeClass = lang ? ` class="language-${langLabel}"` : ''
      return (
        `<div class="cx-code">` +
        `<div class="cx-code__bar">` +
        `<span class="cx-code__lang">${langLabel}</span>` +
        `<span class="cx-code__copy" role="button" tabindex="0" aria-label="Copy code">` +
        `<i class="mdi mdi-content-copy"></i>copy</span>` +
        `</div>` +
        `<pre><code${codeClass}>${escaped}</code></pre>` +
        `</div>\n`
      )
    },
  },
})

/**
 * Render untrusted Markdown from models and marketplace packages.
 *
 * Marked intentionally preserves raw HTML, so its output must never reach v-html directly.
 * The HTML-only profile also excludes SVG/MathML attack surfaces; interactive form elements and
 * inline styles are unnecessary for rendered Markdown and are removed as defense in depth.
 */
export function renderMarkdown(source: string): string {
  const html = marked.parse(source ?? '') as string
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['form', 'input', 'button', 'textarea', 'select', 'option', 'style'],
    FORBID_ATTR: ['style'],
  })
}
