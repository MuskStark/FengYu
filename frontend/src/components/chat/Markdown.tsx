import type { KeyboardEvent, MouseEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { marked, type Token } from 'marked'
import { renderMarkdown, setMarkdownCodeLabels } from '@/security/markdown'

/**
 * Sanitized markdown renderer for chat turns (React twin of the Vue md() helper).
 *
 * Streaming perf: marked.lexer splits the source into blocks; each block's HTML is cached
 * by its raw text (LRU), so a streaming token delta re-renders ONLY the last (still
 * changing) block — the settled prefix is an O(1) cache hit. Settled turns additionally
 * dedupe whole-source renders through the top-level cache (identical content across
 * turns/panes).
 */
const MD_CACHE_LIMIT = 64
const BLOCK_CACHE_LIMIT = 512
const mdCache = new Map<string, string>()
const blockCache = new Map<string, string>()
/** Last language the caches were populated under (module-level — see Markdown()). */
let lastCopyLanguageModule: string | null = null

function cacheGet(cache: Map<string, string>, key: string): string | undefined {
  const cached = cache.get(key)
  if (cached !== undefined) {
    cache.delete(key)
    cache.set(key, cached)
  }
  return cached
}

function cachePut(cache: Map<string, string>, key: string, value: string, limit: number): void {
  cache.set(key, value)
  if (cache.size > limit) {
    const oldest = cache.keys().next().value
    if (oldest !== undefined) cache.delete(oldest)
  }
}

/** Drop every cached render (locale change: the copy-control label lives in the HTML). */
export function clearMarkdownCaches(): void {
  mdCache.clear()
  blockCache.clear()
}

function md(source: string): string {
  const cached = cacheGet(mdCache, source)
  if (cached !== undefined) return cached
  const html = renderMarkdownBlocks(source)
  cachePut(mdCache, source, html, MD_CACHE_LIMIT)
  return html
}

/** Per-block render: only the block whose raw text changed misses the cache. */
function renderMarkdownBlocks(source: string): string {
  let html = ''
  let tokens: Token[] = []
  try {
    tokens = marked.lexer(source ?? '')
  } catch {
    return renderMarkdown(source ?? '')
  }
  for (const token of tokens) {
    const raw = token.raw ?? ''
    if (!raw) continue
    const cached = cacheGet(blockCache, raw)
    if (cached !== undefined) {
      html += cached
      continue
    }
    const blockHtml = renderMarkdown(raw)
    cachePut(blockCache, raw, blockHtml, BLOCK_CACHE_LIMIT)
    html += blockHtml
  }
  return html
}

/** True for hrefs that name a workspace-relative file rather than a navigable URL. */
function isFileHref(href: string): boolean {
  if (!href || href.startsWith('#') || href.startsWith('mailto:')) return false
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(href)) return false
  if (/^[a-z][a-z0-9+.-]*:/i.test(href)) return false // scheme without // (mailto:, tel:, …)
  return true
}

/** Strip the "./" the @-mention serializer emits; keep nested paths as-is. */
function normalizeFileHref(href: string): string {
  return href.startsWith('./') ? href.slice(2) : href
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

export function Markdown({ source, onOpenFile }: {
  source: string
  /** Workspace file open gesture for relative links (@-mention chips produce them). */
  onOpenFile?: (path: string) => void
}): React.JSX.Element {
  const { t, i18n } = useTranslation()

  // The copy affordance's label/aria text is baked into the cached block HTML — push the
  // localized labels in BEFORE md() runs and drop the caches when the language changes so
  // no block keeps the previous locale's label alive. The compare is MODULE-level: the
  // language can change while no Markdown is mounted (Settings route), and a per-instance
  // ref would initialize to the new language on the next mount and never fire.
  const copyLanguage = i18n.language
  if (lastCopyLanguageModule !== copyLanguage) {
    lastCopyLanguageModule = copyLanguage
    clearMarkdownCaches()
  }
  setMarkdownCodeLabels({ copy: t('aichat.copyCode'), copyAria: t('aichat.copyCodeAria') })

  const onClick = (event: MouseEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement
    const control = target.closest('.cx-code__copy') as HTMLElement | null
    if (control) {
      event.preventDefault()
      void copyCode(control, t('aichat.copied'))
      return
    }
    if (onOpenFile) {
      const anchor = target.closest('a') as HTMLAnchorElement | null
      const href = anchor?.getAttribute('href')
      if (anchor && href && isFileHref(href)) {
        event.preventDefault()
        onOpenFile(normalizeFileHref(href))
      }
    }
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
