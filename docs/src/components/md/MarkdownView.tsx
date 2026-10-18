import { useEffect, useRef } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import type { Locale } from '@/content/navigation'
import { t } from '@/i18n'

/**
 * Renders build-time HTML (scripts/build-content.mjs) and owns the two pieces
 * of behavior a static string cannot ship: per-code-figure copy buttons and
 * SPA interception of internal links (so markdown anchors ride the router
 * instead of full page loads). The HTML itself is trusted repo content — it
 * is generated from our own markdown, never user input.
 */
export function MarkdownView({ html, locale }: { html: string; locale: Locale }) {
  const ref = useRef<HTMLDivElement>(null)
  const navigate = useNavigate()
  const location = useLocation()
  const L = t(locale)

  // Copy buttons on every code figure; listeners die with the DOM they're
  // attached to (React replaces innerHTML on html change).
  useEffect(() => {
    const root = ref.current
    if (!root) return
    for (const figure of Array.from(root.querySelectorAll('figure.doc-code'))) {
      const head = figure.querySelector('.doc-code-head')
      const pre = figure.querySelector('pre')
      if (!head || !pre || head.querySelector('.doc-code-copy')) continue
      const button = document.createElement('button')
      button.type = 'button'
      button.className = 'doc-code-copy'
      button.textContent = L.copy
      button.addEventListener('click', async () => {
        try {
          await navigator.clipboard.writeText(pre.textContent ?? '')
          button.textContent = L.copied
          button.classList.add('copied')
          window.setTimeout(() => {
            button.textContent = L.copy
            button.classList.remove('copied')
          }, 1500)
        } catch {
          /* clipboard denied — the code stays selectable by hand */
        }
      })
      head.appendChild(button)
    }
  }, [html, L.copy, L.copied])

  const onClick = (event: React.MouseEvent<HTMLDivElement>) => {
    const anchor = (event.target as HTMLElement).closest('a')
    if (!anchor) return
    const href = anchor.getAttribute('href')
    if (!href || /^(https?:|mailto:)/i.test(href)) return
    event.preventDefault()
    if (href.startsWith('#')) {
      document.getElementById(decodeURIComponent(href.slice(1)))?.scrollIntoView({ behavior: 'smooth' })
      window.history.replaceState(null, '', href)
    } else if (href.startsWith('/')) {
      navigate(href)
    } else {
      // No relative asset links exist in the content tree; last-resort fallback.
      window.location.assign(new URL(href, window.location.href).toString())
    }
  }
  void location

  return <div ref={ref} className="doc-prose" onClick={onClick} dangerouslySetInnerHTML={{ __html: html }} />
}
