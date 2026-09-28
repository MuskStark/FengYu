import { useEffect, useMemo, useRef, useState } from 'react'
import {
  CardSpotlight,
  FloatingDock,
  GoldButton,
  PluginHeader,
  StatusBar,
  StatusChip,
  TextGenerateEffect,
  useFengYuClient,
  type FengYuClient,
} from '@infinia/plugin-ui'
import {
  IconBold,
  IconCode,
  IconDownload,
  IconFileText,
  IconH2,
  IconItalic,
  IconLink,
  IconList,
  IconQuote,
  IconTable,
} from '@tabler/icons-react'
import manifest from '../../manifest.base.json'
import { useFengYuEnvironment } from './env'
import { createPluginRpc, type PluginRpc } from './generated/fengyu-rpc'

const SAMPLE = '# Hello FengYu\n\nType **markdown** here.'

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** First `# heading` line of the source, as the plain preview title. */
function firstHeading(source: string): string {
  for (const line of source.split('\n')) {
    const match = /^#\s+(.+?)\s*$/.exec(line)
    if (match) return match[1]
  }
  return ''
}

/** Drop a leading <h1> from the worker html — the title is shown by TextGenerateEffect. */
function stripLeadingH1(html: string): string {
  return html.replace(/^\s*<h1[^>]*>[\s\S]*?<\/h1>\s*/, '')
}

type Format = 'bold' | 'italic' | 'h2' | 'list' | 'quote' | 'code' | 'link' | 'table'

export default function MarkdownEditor({ initialMarkdown = SAMPLE }: { initialMarkdown?: string }) {
  let client: FengYuClient | undefined
  try {
    client = useFengYuClient()
  } catch {
    client = undefined
  }
  const { t } = useFengYuEnvironment()
  // Typed RPC client generated from manifest rpc.methods. null when there is no host.
  const rpc: PluginRpc | undefined = useMemo(
    () => (client ? createPluginRpc(client) : undefined),
    [client],
  )

  const [markdown, setMarkdown] = useState(initialMarkdown)
  const [html, setHtml] = useState('')
  const [isError, setIsError] = useState(false)
  const [rendering, setRendering] = useState(false)
  const [renderMs, setRenderMs] = useState(0)

  // Latest source for the debounced render — the timer fires long after this render's closure.
  const markdownRef = useRef(initialMarkdown)
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const gutterRef = useRef<HTMLDivElement>(null)

  const lineCount = markdown.split('\n').length
  const wordCount = markdown.split(/\s+/).filter(Boolean).length
  // Title of the document, revealed once per title by TextGenerateEffect (0.03 s).
  const heading = useMemo(() => firstHeading(markdown), [markdown])

  async function render(): Promise<void> {
    const source = markdownRef.current
    const started = performance.now()
    setRendering(true)
    if (!rpc) {
      // No host wiring (standalone) — show the raw source so the pane isn't blank.
      setIsError(false)
      setHtml('<pre>' + escapeHtml(source) + '</pre>')
      setRendering(false)
      setRenderMs(Math.round(performance.now() - started))
      return
    }
    try {
      const res = await rpc.render({ markdown: source })
      if (res.success) {
        setIsError(false)
        setHtml(typeof res.html === 'string' ? stripLeadingH1(res.html) : '')
      } else {
        setIsError(true)
        setHtml(escapeHtml(res.summary || t('mde.renderFailed')))
      }
    } catch (err) {
      setIsError(true)
      setHtml(escapeHtml(err instanceof Error ? err.message : String(err)))
    } finally {
      setRenderMs(Math.round(performance.now() - started))
      setRendering(false)
    }
  }

  function scheduleRender(): void {
    if (debounceTimer.current !== null) clearTimeout(debounceTimer.current)
    debounceTimer.current = setTimeout(() => {
      debounceTimer.current = null
      void render()
    }, 250)
  }

  // First render immediately; re-render if the client appears later. render()
  // reads the source through markdownRef, so the closure staying stale is fine.
  useEffect(() => {
    void render()
  }, [rpc])

  // Cancel a pending debounce when the editor goes away.
  useEffect(() => () => {
    if (debounceTimer.current !== null) {
      clearTimeout(debounceTimer.current)
      debounceTimer.current = null
    }
  }, [])

  function handleChange(event: React.ChangeEvent<HTMLTextAreaElement>): void {
    markdownRef.current = event.target.value
    setMarkdown(event.target.value)
    scheduleRender()
  }

  function syncGutter(event: React.UIEvent<HTMLTextAreaElement>): void {
    if (gutterRef.current) gutterRef.current.scrollTop = event.currentTarget.scrollTop
  }

  /** Replace the source, re-render (debounced), and restore caret + focus next frame. */
  function commit(next: string, selectionStart: number, selectionEnd: number): void {
    markdownRef.current = next
    setMarkdown(next)
    scheduleRender()
    requestAnimationFrame(() => {
      const el = textareaRef.current
      if (!el) return
      el.focus()
      el.setSelectionRange(selectionStart, selectionEnd)
    })
  }

  /** Wrap the selection (or a placeholder) with inline markers, e.g. **bold**. */
  function surround(before: string, after: string, placeholder: string): void {
    const el = textareaRef.current
    const value = markdownRef.current
    const start = el?.selectionStart ?? value.length
    const end = el?.selectionEnd ?? start
    const selected = value.slice(start, end) || placeholder
    commit(
      value.slice(0, start) + before + selected + after + value.slice(end),
      start + before.length,
      start + before.length + selected.length,
    )
  }

  /** Prefix (or un-prefix) every selected line with a block marker, e.g. `## `. */
  function prefixLines(prefix: string): void {
    const el = textareaRef.current
    const value = markdownRef.current
    const start = el?.selectionStart ?? value.length
    const end = el?.selectionEnd ?? start
    const lineStart = value.lastIndexOf('\n', start - 1) + 1
    let lineEnd = value.indexOf('\n', end)
    if (lineEnd === -1) lineEnd = value.length
    const lines = value.slice(lineStart, lineEnd).split('\n')
    const remove = lines.every((line) => line.startsWith(prefix))
    const replaced = lines
      .map((line) => (remove ? line.slice(prefix.length) : prefix + line))
      .join('\n')
    commit(
      value.slice(0, lineStart) + replaced + value.slice(lineEnd),
      lineStart,
      lineStart + replaced.length,
    )
  }

  /** Insert a block template at the caret. */
  function insertBlock(block: string): void {
    const el = textareaRef.current
    const value = markdownRef.current
    const at = el?.selectionStart ?? value.length
    const lead = at > 0 && value[at - 1] !== '\n' ? '\n\n' : ''
    const insertion = lead + block
    commit(
      value.slice(0, at) + insertion + value.slice(at),
      at + insertion.length,
      at + insertion.length,
    )
  }

  function applyFormat(format: Format): void {
    const sample = t('mde.sampleText')
    switch (format) {
      case 'bold':
        surround('**', '**', sample)
        break
      case 'italic':
        surround('*', '*', sample)
        break
      case 'code':
        surround('`', '`', sample)
        break
      case 'link':
        surround('[', '](url)', sample)
        break
      case 'h2':
        prefixLines('## ')
        break
      case 'list':
        prefixLines('- ')
        break
      case 'quote':
        prefixLines('> ')
        break
      case 'table':
        insertBlock('| heading | heading |\n| --- | --- |\n|  |  |')
        break
    }
  }

  // FloatingDock items are anchors and the kit exposes no onClick hook, so each icon
  // carries a data-format marker and the wrapper intercepts the click (capture phase).
  const dockItems = (['bold', 'italic', 'h2', 'list', 'quote', 'code', 'link', 'table'] as const).map(
    (format) => {
      const icons = {
        bold: <IconBold size={18} stroke={1.6} />,
        italic: <IconItalic size={18} stroke={1.6} />,
        h2: <IconH2 size={18} stroke={1.6} />,
        list: <IconList size={18} stroke={1.6} />,
        quote: <IconQuote size={18} stroke={1.6} />,
        code: <IconCode size={18} stroke={1.6} />,
        link: <IconLink size={18} stroke={1.6} />,
        table: <IconTable size={18} stroke={1.6} />,
      }
      return {
        title: t(`mde.${format === 'h2' ? 'heading2' : format}`),
        icon: <span data-format={format}>{icons[format]}</span>,
        href: '#',
      }
    },
  )

  function handleDockClick(event: React.MouseEvent<HTMLDivElement>): void {
    const hit = (event.target as HTMLElement).closest<HTMLElement>('[data-format]')
    if (!hit) return
    event.preventDefault()
    event.stopPropagation()
    applyFormat(hit.dataset.format as Format)
  }

  return (
    <main className="relative flex min-w-0 flex-1 flex-col">
      <PluginHeader
        icon={<IconFileText size={14} stroke={1.8} />}
        name={t('mde.cardTitle')}
        category={manifest.category}
        version={`v${manifest.version as string}`}
        right={
          <>
            <GoldButton>
              <IconDownload size={15} stroke={1.7} />
              {t('mde.navExport')}
            </GoldButton>
            <StatusChip tone={isError ? 'danger' : rpc ? 'success' : 'idle'}>
              {rendering ? t('mde.rendering') : rpc ? t('mde.workerReady') : t('mde.workerOffline')}
            </StatusChip>
          </>
        }
      />

      {/* 编辑 / 预览 双栏 */}
      <div className="relative flex min-h-0 flex-1">
        {/* Editor pane: controlled textarea + synced line-number gutter */}
        <div className="flex min-w-0 flex-1 flex-col border-r border-line bg-panel">
          <div className="editor-scroll flex min-h-0 flex-1 overflow-hidden font-mono text-[12.5px] leading-[1.75]">
            <div
              ref={gutterRef}
              aria-hidden="true"
              className="editor-gutter w-10 shrink-0 select-none overflow-hidden py-5 pl-5 pr-4 text-right text-ink-3"
            >
              {Array.from({ length: lineCount }, (_, i) => (
                <div key={i}>{i + 1}</div>
              ))}
            </div>
            <textarea
              ref={textareaRef}
              value={markdown}
              onChange={handleChange}
              onScroll={syncGutter}
              aria-label={t('mde.editor')}
              spellCheck={false}
              className="mde-textarea min-h-0 flex-1 resize-none overflow-auto border-none bg-transparent px-5 py-5 font-mono text-[12.5px] leading-[1.75] text-ink outline-none"
            />
          </div>
        </div>

        {/* Preview: official CardSpotlight（金色追光） */}
        <CardSpotlight
          radius={280}
          color="rgba(234,176,75,0.18)"
          className="m-4 flex min-w-0 flex-1 flex-col overflow-hidden rounded-xl border border-line bg-panel"
        >
          <div className="flex h-full min-h-0 flex-col">
            <div className="flex h-10 shrink-0 items-center gap-2 border-b border-line px-5 font-mono text-[11px] text-ink-3">
              {t('mde.preview')}
              <span className="ml-auto">{rendering ? t('mde.rendering') : `${renderMs} ms`}</span>
            </div>
            <article
              data-preview-body=""
              aria-busy={rendering}
              className={
                'preview-scroll mde-preview-body min-h-0 flex-1 overflow-auto px-7 py-6' +
                (isError ? ' mde-error' : '')
              }
            >
              {heading ? (
                <TextGenerateEffect
                  key={heading}
                  words={heading}
                  className="text-left text-xl font-semibold tracking-tight text-ink"
                  duration={0.03}
                />
              ) : null}
              {/* Worker-rendered html, escaped raw source, or escaped error text. */}
              <div className="mde-html" dangerouslySetInnerHTML={{ __html: html }} />
            </article>
          </div>
        </CardSpotlight>

        {/* Official FloatingDock: bottom format dock, spanning both panes */}
        <div className="pointer-events-none absolute inset-x-0 bottom-5 z-30 flex justify-center">
          <div className="pointer-events-auto" onClickCapture={handleDockClick}>
            <FloatingDock
              items={dockItems}
              desktopClassName="bg-panel border border-line shadow-[0_8px_28px_rgba(24,24,27,0.10)]"
            />
          </div>
        </div>
      </div>

      <StatusBar
        left={
          <>
            <span>{rpc ? t('mde.workerReady') : t('mde.workerOffline')}</span>
            <span>{t('mde.renderMs', renderMs)}</span>
            <span>{t('mde.wordCount', wordCount)}</span>
          </>
        }
        right={
          <span>
            {manifest.id} · v{manifest.version as string}
          </span>
        }
      />
    </main>
  )
}
