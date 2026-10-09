/**
 * Pure classification + text helpers for the workspace file preview pane.
 * Kept side-effect free (and dependency free) so the branch decisions are
 * unit-testable without mounting shiki or the network service layer.
 */

/** Extensions the preview pane renders as raster images (mirrors the backend raw-image whitelist). */
export const IMAGE_RE = /\.(png|jpe?g|gif|webp|bmp)$/i

/** Files the pane can render as rich content before falling back to source view. */
export const MARKDOWN_RE = /\.(md|markdown|mdx)$/i
export const SVG_RE = /\.svg$/i

/**
 * Extension → shiki language id. Covers the grammar set bundled into the lazy
 * highlighter (see codeHighlight.ts) — anything missing here renders as plain
 * text, never auto-detected (a wrong guess is worse than none).
 */
export const EXT_SHIKI_LANGS: Record<string, string> = {
  ts: 'typescript', tsx: 'tsx', mts: 'typescript', cts: 'typescript',
  js: 'javascript', jsx: 'jsx', mjs: 'javascript', cjs: 'javascript',
  json: 'json', jsonc: 'json',
  java: 'java', py: 'python', pyi: 'python',
  md: 'markdown', mdx: 'markdown',
  css: 'css', scss: 'scss', less: 'less',
  html: 'html', htm: 'html', xml: 'xml', vue: 'vue', svg: 'xml',
  yml: 'yaml', yaml: 'yaml',
  sh: 'bash', bash: 'bash', zsh: 'bash', command: 'bash',
  sql: 'sql', go: 'go', rs: 'rust',
  c: 'c', h: 'c', cpp: 'cpp', hpp: 'cpp', cc: 'cpp', cxx: 'cpp',
  cs: 'csharp', php: 'php', rb: 'ruby', kt: 'kotlin', kts: 'kotlin',
  swift: 'swift', scala: 'scala',
  ini: 'ini', toml: 'toml', properties: 'properties', env: 'ini',
  diff: 'diff', patch: 'diff',
  dockerfile: 'dockerfile', makefile: 'makefile',
  graphql: 'graphql', gql: 'graphql',
  proto: 'proto',
}

/** How the preview pane should render one loaded file. */
export type FilePreviewKind =
  | 'image'      // raster via the raw-image endpoint
  | 'markdown'   // rendered prose with a source toggle
  | 'svg'        // sanitized inline render with a source toggle
  | 'code'       // syntax-highlighted source
  | 'text'       // plain escaped source (unknown extension)

/** Resolve the shiki language id for a path, or null when it should render plain. */
export function shikiLanguageFor(path: string): string | null {
  const ext = path.split('.').pop()?.toLowerCase() ?? ''
  return EXT_SHIKI_LANGS[ext] ?? null
}

/** Classify a loaded (non-binary, non-too-large) file by extension. */
export function classifyPreview(path: string): FilePreviewKind {
  if (IMAGE_RE.test(path)) return 'image'
  if (MARKDOWN_RE.test(path)) return 'markdown'
  if (SVG_RE.test(path)) return 'svg'
  return shikiLanguageFor(path) ? 'code' : 'text'
}

/** Escape text for safe interpolation into the line grid (shiki tokens carry the colors, not us). */
export function escapeHtml(input: string): string {
  return input
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/'/g, '&#39;')
    .replace(/"/g, '&#34;')
}

/**
 * Split preview content into lines for the numbered grid. A trailing newline
 * does not create a phantom final row; empty content is one empty line so the
 * pane keeps its height.
 */
export function splitLines(content: string): string[] {
  if (content === '') return ['']
  const lines = content.split('\n')
  if (lines.length > 1 && lines[lines.length - 1] === '') lines.pop()
  return lines
}

/** Human-readable size for the preview header (matches the tree/changes tone). */
export function formatSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
