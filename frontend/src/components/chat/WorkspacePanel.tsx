import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { JSX } from 'react'
import { useTranslation } from 'react-i18next'
import hljs from 'highlight.js/lib/common'
import {
  ChevronDown, ChevronRight, Code2, FileCode, FileImage, FileQuestion, FileText, FileWarning,
  Folder, FolderOpen, Info, X,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { services } from '@/services'
import type { WorkspaceFilePreview, WorkspaceTree, WorkspaceTreeNode } from '@/services/types'
import { wsRowIcon, type WsTreeRow } from '@/lib/wsTree'
import { cn } from '@/lib/utils'
import '@/styles/workspace-panel.css'

/** Extension → highlight.js language for the preview pane. */
const EXT_LANGUAGES: Record<string, string> = {
  ts: 'typescript', tsx: 'typescript', mts: 'typescript',
  js: 'javascript', jsx: 'javascript', mjs: 'javascript',
  json: 'json', java: 'java', py: 'python', md: 'markdown',
  css: 'css', scss: 'scss', html: 'xml', xml: 'xml', vue: 'xml', svg: 'xml',
  yml: 'yaml', yaml: 'yaml', sh: 'bash', bash: 'bash', zsh: 'bash',
  sql: 'sql', go: 'go', rs: 'rust', c: 'c', h: 'c', cpp: 'cpp', hpp: 'cpp', cc: 'cpp',
  cs: 'csharp', php: 'php', rb: 'ruby', kt: 'kotlin', swift: 'swift',
  ini: 'ini', toml: 'ini', properties: 'ini',
}

/** mdi name returned by wsRowIcon → lucide component for the tree rows. */
const ROW_ICONS: Record<string, LucideIcon> = {
  'mdi-folder-open-outline': FolderOpen,
  'mdi-folder-outline': Folder,
  'mdi-file-code-outline': FileCode,
  'mdi-code-json': Code2,
  'mdi-file-image-outline': FileImage,
  'mdi-file-document-outline': FileText,
}

function parentPath(path: string): string {
  return path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : ''
}

/** Flat server list → nested rows (directories first, numeric-aware by name). */
function assembleTree(nodes: WorkspaceTreeNode[]): WsTreeRow[] {
  const byPath = new Map<string, WsTreeRow>()
  for (const node of nodes) byPath.set(node.path, { ...node, children: [] })
  for (const row of byPath.values()) {
    const parent = parentPath(row.path)
    const owner = parent ? byPath.get(parent) : undefined
    if (owner) owner.children.push(row)
  }
  const sortRows = (list: WsTreeRow[]) => {
    list.sort((a, b) => a.dir === b.dir
      ? a.name.localeCompare(b.name, undefined, { numeric: true })
      : (a.dir ? -1 : 1))
    for (const row of list) sortRows(row.children)
  }
  const roots = [...byPath.values()].filter((row) => {
    const parent = parentPath(row.path)
    return !parent || !byPath.has(parent)
  })
  sortRows(roots)
  return roots
}

/**
 * One recursive level of the workspace file tree. Toggling a directory and opening a file
 * bubble up as callbacks so WorkspacePanel stays the single owner of tree state.
 */
function WsNode({ row, depth, expanded, selected, onToggle, onOpen }: {
  row: WsTreeRow
  depth: number
  expanded: Set<string>
  selected: string | null
  onToggle: (row: WsTreeRow) => void
  onOpen: (path: string) => void
}): JSX.Element {
  const isOpen = expanded.has(row.path)
  const RowIcon = ROW_ICONS[wsRowIcon(row, isOpen)] ?? FileText
  return (
    <li>
      <button
        type="button"
        className={cn('ws-tree__row', selected === row.path && 'ws-tree__row--active')}
        style={{ paddingLeft: `${10 + depth * 14}px` }}
        onClick={() => (row.dir ? onToggle(row) : onOpen(row.path))}
      >
        {row.dir
          ? (isOpen
            ? <ChevronDown size={13} className="ws-tree__chevron" />
            : <ChevronRight size={13} className="ws-tree__chevron" />)
          : <span className="ws-tree__chevron ws-tree__chevron--blank" />}
        <RowIcon size={15} className="ws-tree__icon" />
        <span className="ws-tree__name">{row.name}</span>
      </button>
      {row.dir && isOpen && (
        <ul className="ws-tree__list">
          {row.children.map((child) => (
            <WsNode
              key={child.path}
              row={child}
              depth={depth + 1}
              expanded={expanded}
              selected={selected}
              onToggle={onToggle}
              onOpen={onOpen}
            />
          ))}
        </ul>
      )}
    </li>
  )
}

/**
 * Read-only workspace side panel: an expandable file tree (from the server's bounded,
 * traversal-excluded walk) and a syntax-highlighted single-file preview. It exists so
 * "which files did the AI touch" is one click away from the tool timeline — the open-file
 * gesture on an activity row lands here via the {@link focus} prop. React port of the
 * Vue WorkspacePanel; visuals ride the cx-* kit.
 */
export default function WorkspacePanel({ conversationId, root, focus, onClose }: {
  conversationId: number
  root: string
  /** External open request ({path, seq}); seq makes repeated requests for one path re-fire. */
  focus: { path: string; seq: number } | null
  onClose: () => void
}): JSX.Element {
  const { t } = useTranslation()

  const [tree, setTree] = useState<WorkspaceTree | null>(null)
  const [treeLoading, setTreeLoading] = useState(false)
  const [treeError, setTreeError] = useState<string | null>(null)
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set())
  const [selected, setSelected] = useState<string | null>(null)
  const [preview, setPreview] = useState<WorkspaceFilePreview | null>(null)
  const [fileLoading, setFileLoading] = useState(false)
  const [fileError, setFileError] = useState<string | null>(null)

  /** Latest selection without re-creating `open` — lets post-await code drop stale results. */
  const selectedRef = useRef<string | null>(null)
  const fileCache = useRef(new Map<string, WorkspaceFilePreview>())

  const select = useCallback((path: string | null) => {
    selectedRef.current = path
    setSelected(path)
  }, [])

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      setTreeLoading(true)
      setTreeError(null)
      try {
        const data = await services.workspace.tree(conversationId)
        if (cancelled) return
        setTree(data)
        // Root-level directories start expanded; deeper levels stay collapsed until asked for.
        setExpanded(new Set(assembleTree(data.nodes).filter((row) => row.dir).map((row) => row.path)))
        select(null)
        setPreview(null)
        fileCache.current.clear()
      } catch {
        if (!cancelled) setTreeError(t('aichat.workspaceTreeFailed'))
      } finally {
        if (!cancelled) setTreeLoading(false)
      }
    }
    void load()
    return () => { cancelled = true }
    // `t` is read at failure time on purpose (the Vue panel does the same): a locale switch
    // must not reload the tree and collapse the user's expansion state.
  }, [conversationId, select])

  const toggle = useCallback((row: WsTreeRow) => {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(row.path)) next.delete(row.path)
      else next.add(row.path)
      return next
    })
  }, [])

  const open = useCallback(async (path: string) => {
    select(path)
    setFileError(null)
    setExpanded((prev) => {
      const next = new Set(prev)
      let dir = parentPath(path)
      while (dir) {
        next.add(dir)
        dir = parentPath(dir)
      }
      return next
    })
    const cached = fileCache.current.get(path)
    if (cached) {
      setPreview(cached)
      return
    }
    setFileLoading(true)
    try {
      const loaded = await services.workspace.file(conversationId, path)
      fileCache.current.set(path, loaded)
      if (selectedRef.current === path) setPreview(loaded)
    } catch {
      if (selectedRef.current === path) setFileError(t('aichat.workspaceFileFailed'))
    } finally {
      setFileLoading(false)
    }
  }, [conversationId, select])

  // `focus` carries a bumping seq so repeated requests for one path re-fire; the seq guard
  // also keeps a re-created `open` (conversation switch) from double-opening the same request.
  const lastFocusSeq = useRef(-1)
  useEffect(() => {
    if (!focus || !focus.path || focus.seq === lastFocusSeq.current) return
    lastFocusSeq.current = focus.seq
    void open(focus.path)
  }, [focus, open])

  const roots = useMemo<WsTreeRow[]>(() => (tree ? assembleTree(tree.nodes) : []), [tree])

  /**
   * Highlighted preview body. highlight.js escapes its input, so the generated HTML is safe
   * to inject — the same guarantee the chat markdown pipeline relies on. Unknown languages
   * fall back to manual escaping of the raw text.
   */
  const highlightedContent = useMemo<string>(() => {
    const content = preview?.content
    if (content === undefined) return ''
    const ext = (selected ?? '').split('.').pop()?.toLowerCase() ?? ''
    const language = EXT_LANGUAGES[ext]
    if (language && hljs.getLanguage(language)) {
      return hljs.highlight(content, { language, ignoreIllegals: true }).value
    }
    return content
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/'/g, '&#39;')
      .replace(/"/g, '&#34;')
  }, [preview, selected])

  const rootName = root.replace(/[\\/]+$/, '').split(/[\\/]/).pop() ?? root

  return (
    <aside className="ws-panel">
      <header className="ws-panel__head">
        <Folder size={18} className="ws-panel__head-icon" />
        <span className="ws-panel__title">{t('aichat.workspacePanel')}</span>
        <span className="cx-muted ws-panel__root" title={root}>{rootName}</span>
        <button
          type="button"
          className="cx-iconbtn cx-iconbtn--sm"
          title={t('common.close')}
          onClick={onClose}
        >
          <X size={16} />
        </button>
      </header>

      <div className="ws-panel__body">
        <nav className="ws-tree">
          {treeLoading ? (
            <div className="cx-muted ws-tree__status">
              <span className="cx-spin" /> {t('aichat.workspaceTreeLoading')}
            </div>
          ) : treeError ? (
            <div className="cx-alert cx-alert--error ws-tree__status">{treeError}</div>
          ) : (
            <>
              {tree?.truncated ? (
                <div className="ws-tree__status ws-tree__status--warn">
                  <Info size={14} />
                  {t('aichat.workspaceTreeTruncated', { count: tree.nodes.length })}
                </div>
              ) : null}
              <ul className="ws-tree__list">
                {roots.map((row) => (
                  <WsNode
                    key={row.path}
                    row={row}
                    depth={0}
                    expanded={expanded}
                    selected={selected}
                    onToggle={toggle}
                    onOpen={(path) => { void open(path) }}
                  />
                ))}
              </ul>
            </>
          )}
        </nav>

        <div className="ws-preview">
          {fileLoading ? (
            <div className="cx-muted ws-preview__status"><span className="cx-spin" /></div>
          ) : fileError ? (
            <div className="cx-alert cx-alert--error ws-preview__status">{fileError}</div>
          ) : !preview ? (
            <div className="cx-muted ws-preview__status">{t('aichat.workspaceNoSelection')}</div>
          ) : preview.binary ? (
            <div className="cx-muted ws-preview__status">
              <FileQuestion size={16} /> {t('aichat.workspaceBinaryFile')}
            </div>
          ) : preview.tooLarge ? (
            <div className="cx-muted ws-preview__status">
              <FileWarning size={16} /> {t('aichat.workspaceLargeFile')}
            </div>
          ) : (
            <>
              <div className="ws-preview__path" title={preview.path}>
                <FileCode size={13} />
                <span>{preview.path}</span>
              </div>
              <pre className="ws-preview__code">
                <code dangerouslySetInnerHTML={{ __html: highlightedContent }} />
              </pre>
            </>
          )}
        </div>
      </div>
    </aside>
  )
}
