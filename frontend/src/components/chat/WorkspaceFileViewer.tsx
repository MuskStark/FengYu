import { useEffect, useMemo, useRef, useState } from 'react'
import type { JSX } from 'react'
import DOMPurify from 'dompurify'
import { useTranslation } from 'react-i18next'
import {
  Check, Copy, Eye, FileCode, FileImage, FileQuestion, FileText, FileWarning, WrapText,
} from 'lucide-react'
import { services } from '@/services'
import type { WorkspaceFilePreview } from '@/services/types'
import { classifyPreview, escapeHtml, formatSize, IMAGE_RE, splitLines } from '@/lib/filePreview'
import { highlightLines } from '@/lib/codeHighlight'
import { cn } from '@/lib/utils'
import { Markdown } from './Markdown'
import { ImageLightbox } from './ImageAttachmentCard'
import '@/styles/file-viewer.css'

/** Wrap preference survives viewer unmounts within the session (panel open/close, tab hops). */
let sessionWrap = false

/** Sanitized rich-render branch kinds; everything else shows source lines. */
type ViewMode = 'preview' | 'source'

/**
 * The workspace panel's file preview body — the FengYu port of ZCode's PreviewPane,
 * scoped to what this app's backend serves: shiki-highlighted source with a line
 * grid, rendered markdown, sanitized inline SVG, raster images through the raw-image
 * endpoint, and the binary/too-large/error statuses. The toolbar carries the ZCode
 * affordances that make sense at this width: path + size, copy path, wrap toggle,
 * and preview/source switching for markdown and SVG.
 */
export default function WorkspaceFileViewer({ conversationId, preview, onOpenFile }: {
  conversationId: number
  preview: WorkspaceFilePreview
  /** Markdown relative-link open gesture, wired to the panel's file loader. */
  onOpenFile: (path: string) => void
}): JSX.Element {
  const { t } = useTranslation()
  const kind = classifyPreview(preview.path)
  const rich = kind === 'markdown' || kind === 'svg'

  const [modeState, setModeState] = useState<{ path: string; mode: ViewMode }>({ path: '', mode: 'preview' })
  const [wrap, setWrap] = useState(sessionWrap)
  const [highlightState, setHighlightState] = useState<{ path: string; lines: string[] } | null>(null)
  const [copied, setCopied] = useState(false)

  // Per-file view state DERIVES from the current path during render (no reset
  // effect): a file switch can never paint one frame of the previous file's
  // mode or highlighted rows.
  const viewMode = modeState.path === preview.path ? modeState.mode : 'preview'

  // Source view: plain escaped lines land immediately, shiki upgrades them when ready.
  const plainLines = useMemo(
    () => splitLines(preview.content ?? '').map(escapeHtml),
    [preview.content])
  const showSource = !rich || viewMode === 'source'
  const highlighted = showSource && highlightState?.path === preview.path ? highlightState.lines : null
  useEffect(() => {
    if (!showSource || preview.content === undefined) return
    const path = preview.path
    let cancelled = false
    void highlightLines(path, preview.content).then(html => {
      if (!cancelled && html) setHighlightState({ path, lines: html })
    })
    return () => { cancelled = true }
  }, [preview.path, preview.content, showSource])

  // Raster previews ride the raw-image endpoint as object URLs. One URL lives at a
  // time; transitions revoke the previous, unmount revokes the last.
  const imageWanted = preview.binary && IMAGE_RE.test(preview.path)
  const [imageUrl, setImageUrl] = useState<string | null>(null)
  const [imageFailed, setImageFailed] = useState(false)
  const [imageZoom, setImageZoom] = useState(false)
  const imageUrlRef = useRef<string | null>(null)
  useEffect(() => {
    setImageFailed(false)
    setImageZoom(false)
    if (imageUrlRef.current) URL.revokeObjectURL(imageUrlRef.current)
    imageUrlRef.current = null
    setImageUrl(null)
    if (!imageWanted) return
    let cancelled = false
    services.workspace.rawImage(conversationId, preview.path)
      .then(url => {
        if (cancelled) URL.revokeObjectURL(url)
        else {
          imageUrlRef.current = url
          setImageUrl(url)
        }
      })
      .catch(() => { if (!cancelled) setImageFailed(true) })
    return () => { cancelled = true }
  }, [conversationId, preview.path, imageWanted])
  useEffect(() => () => {
    if (imageUrlRef.current) URL.revokeObjectURL(imageUrlRef.current)
  }, [])

  async function copyPath(): Promise<void> {
    try {
      await navigator.clipboard.writeText(preview.path)
    } catch {
      return // clipboard unavailable — leave the icon unchanged
    }
    setCopied(true)
    window.setTimeout(() => setCopied(false), 1500)
  }

  const toggleWrap = () => {
    sessionWrap = !sessionWrap
    setWrap(sessionWrap)
  }

  // The grid is one injected HTML string (not per-line components): a 2 MB preview can
  // carry tens of thousands of rows, and string assembly keeps that off React's reconciler.
  const gridHtml = useMemo(() => {
    const rows = highlighted ?? plainLines
    let out = ''
    for (let index = 0; index < rows.length; index++) {
      out += `<div class="fv-line"><span class="fv-no">${index + 1}</span><code class="fv-src">${rows[index]}</code></div>`
    }
    return out
  }, [highlighted, plainLines])

  const sanitizedSvg = useMemo(
    () => (kind === 'svg'
      ? DOMPurify.sanitize(preview.content ?? '', { USE_PROFILES: { svg: true, svgFilters: true } })
      : ''),
    [kind, preview.content])

  const PathIcon = imageWanted
    ? FileImage
    : preview.binary ? FileQuestion
      : (kind === 'markdown' && viewMode === 'preview') ? FileText
        : FileCode

  return (
    <div className="fv">
      <div className="fv-bar">
        <PathIcon size={13} className="fv-bar__icon" />
        <span className="fv-bar__path" title={`${preview.path}${preview.size ? ` · ${formatSize(preview.size)}` : ''}`}>
          {preview.path}
        </span>
        <div className="fv-bar__actions">
          {rich && (
            <div className="fv-seg" role="group">
              <button
                type="button"
                className={cn('fv-seg__btn', viewMode === 'preview' && 'fv-seg__btn--active')}
                title={t('aichat.workspacePreviewView')}
                onClick={() => setModeState({ path: preview.path, mode: 'preview' })}
              >
                <Eye size={13} />
              </button>
              <button
                type="button"
                className={cn('fv-seg__btn', viewMode === 'source' && 'fv-seg__btn--active')}
                title={t('aichat.workspaceSourceView')}
                onClick={() => setModeState({ path: preview.path, mode: 'source' })}
              >
                <FileCode size={13} />
              </button>
            </div>
          )}
          {showSource && (
            <button
              type="button"
              className={cn('cx-iconbtn cx-iconbtn--sm', wrap && 'fv-btn--on')}
              title={t('aichat.workspaceWrapLines')}
              aria-pressed={wrap}
              onClick={toggleWrap}
            >
              <WrapText size={14} />
            </button>
          )}
          <button
            type="button"
            className="cx-iconbtn cx-iconbtn--sm"
            title={t('aichat.workspaceCopyPath')}
            onClick={() => { void copyPath() }}
          >
            {copied ? <Check size={14} className="fv-copied" /> : <Copy size={14} />}
          </button>
        </div>
      </div>

      <div className="fv-body">
        {preview.tooLarge ? (
          <div className="cx-muted ws-preview__status">
            <FileWarning size={16} /> {t('aichat.workspaceLargeFile')}
          </div>
        ) : preview.binary ? (
          imageWanted ? (
            <>
              {imageUrl ? (
                <img
                  className="fv-image"
                  src={imageUrl}
                  alt={preview.path}
                  draggable={false}
                  onClick={() => setImageZoom(true)}
                />
              ) : imageFailed ? (
                <div className="cx-muted ws-preview__status">
                  <FileQuestion size={16} /> {t('aichat.workspaceImageFailed')}
                </div>
              ) : (
                <div className="cx-muted ws-preview__status"><span className="cx-spin" /></div>
              )}
              {imageZoom && imageUrl && (
                <ImageLightbox src={imageUrl} name={preview.path} onClose={() => setImageZoom(false)} />
              )}
            </>
          ) : (
            <div className="cx-muted ws-preview__status">
              <FileQuestion size={16} /> {t('aichat.workspaceBinaryFile')}
            </div>
          )
        ) : kind === 'markdown' && viewMode === 'preview' ? (
          <div className="fv-doc">
            <Markdown source={preview.content ?? ''} onOpenFile={onOpenFile} />
          </div>
        ) : kind === 'svg' && viewMode === 'preview' ? (
          <div className="fv-svg" dangerouslySetInnerHTML={{ __html: sanitizedSvg }} />
        ) : (
          <div className={cn('fv-code', wrap && 'fv-code--wrap')}>
            <div dangerouslySetInnerHTML={{ __html: gridHtml }} />
          </div>
        )}
      </div>
    </div>
  )
}
