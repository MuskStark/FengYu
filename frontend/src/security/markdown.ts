import DOMPurify from 'dompurify'
import { marked } from 'marked'

marked.setOptions({ breaks: true, gfm: true })

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
