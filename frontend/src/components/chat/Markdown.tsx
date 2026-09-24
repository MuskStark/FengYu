import type { KeyboardEvent, MouseEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { renderMarkdown } from '@/security/markdown'

/**
 * Sanitized markdown renderer for chat turns (React twin of the Vue md() helper).
 *
 * Memoization: streaming re-renders every turn on each token delta, and the
 * marked + DOMPurify pipeline is far too costly to repeat for unchanged content.
 * Insertion-order LRU keyed by the source string (identical content dedupes
 * across turns), capped so long conversations don't accumulate every
 * intermediate streaming snapshot.
 */
const MD_CACHE_LIMIT = 64
const mdCache = new Map<string, string>()

function md(source: string): string {
  const cached = mdCache.get(source)
  if (cached !== undefined) {
    mdCache.delete(source)
    mdCache.set(source, cached)
    return cached
  }
  const html = renderMarkdown(source)
  mdCache.set(source, html)
  if (mdCache.size > MD_CACHE_LIMIT) {
    const oldest = mdCache.keys().next().value
    if (oldest !== undefined) mdCache.delete(oldest)
  }
  return html
}

/**
 * Copy a rendered code block via event delegation: the copy control lives inside
 * sanitized HTML (a plain span — DOMPurify forbids buttons), so the container's
 * click/keydown handlers resolve it with closest() and write the block's text.
 * A successful copy flashes the localized "Copied" label through data attributes
 * (styled in chat.css) for 1.5s.
 */
async function copyCode(control: HTMLElement, copiedLabel: string): Promise<void> {
  const pre = control.closest('.cx-code')?.querySelector('pre')
  if (!pre) return
  try {
    await navigator.clipboard.writeText(pre.textContent ?? '')
  } catch {
    return // clipboard unavailable — leave the label unchanged
  }
  control.dataset.copied = '1'
  control.dataset.copiedText = copiedLabel
  window.setTimeout(() => {
    delete control.dataset.copied
    delete control.dataset.copiedText
  }, 1500)
}

export function Markdown({ source }: { source: string }): React.JSX.Element {
  const { t } = useTranslation()

  const onClick = (event: MouseEvent<HTMLDivElement>) => {
    const control = (event.target as HTMLElement).closest('.cx-code__copy') as HTMLElement | null
    if (!control) return
    event.preventDefault()
    void copyCode(control, t('aichat.copied'))
  }

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const control = (event.target as HTMLElement).closest('.cx-code__copy') as HTMLElement | null
    if (!control || (event.key !== 'Enter' && event.key !== ' ')) return
    event.preventDefault()
    void copyCode(control, t('aichat.copied'))
  }

  return (
    <div
      className="cx-md"
      onClick={onClick}
      onKeyDown={onKeyDown}
      dangerouslySetInnerHTML={{ __html: md(source) }}
    />
  )
}

export default Markdown
