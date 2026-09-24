import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/common'
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
 * Highlight a fenced block's body with highlight.js (common-language bundle, synchronous so
 * streaming keeps its single-pass render). Unknown or unregistered languages fall back to the
 * plain escaped body — never a throw, never auto-detection (a wrong guess is worse than none).
 */
function highlightCode(text: string, lang: string): string {
  if (lang && hljs.getLanguage(lang)) {
    return hljs.highlight(text, { language: lang, ignoreIllegals: true }).value
  }
  return escapeHtml(text)
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
      const highlighted = highlightCode(text, lang)
      const langLabel = lang ? escapeHtml(lang) : ''
      const codeClass = lang ? ` class="language-${langLabel}"` : ''
      return (
        `<div class="cx-code">` +
        `<div class="cx-code__bar">` +
        `<span class="cx-code__lang">${langLabel}</span>` +
        `<span class="cx-code__copy" role="button" tabindex="0" aria-label="Copy code">` +
        `<i class="mdi mdi-content-copy"></i>copy</span>` +
        `</div>` +
        `<pre><code${codeClass}>${highlighted}</code></pre>` +
        `</div>\n`
      )
    },
  },
})

/**
 * DOMPurify needs a window; its default export degrades to a windowless factory under node
 * (vitest). The real shell always runs with a DOM, so the guard only ever routes tests past
 * the sanitizer — the browser/webview path is exactly the wired instance below.
 */
const purifier: Pick<typeof DOMPurify, 'sanitize' | 'addHook'> | null =
  typeof DOMPurify.sanitize === 'function' ? DOMPurify : null

/**
 * Open rendered links away from the shell: model-authored markdown must never navigate the
 * app window itself. In-page anchors (href^="#") keep their default in-document behavior.
 */
purifier?.addHook('afterSanitizeAttributes', (node) => {
  if (!(node instanceof HTMLAnchorElement)) return
  const href = node.getAttribute('href')
  if (!href || href.startsWith('#')) return
  node.setAttribute('target', '_blank')
  node.setAttribute('rel', 'noopener noreferrer')
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
  if (!purifier) return html // windowless node context (tests) — never a real render target
  return purifier.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['form', 'input', 'button', 'textarea', 'select', 'option', 'style'],
    FORBID_ATTR: ['style'],
  })
}
