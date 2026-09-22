import { ipcMain, shell } from 'electron'

/**
 * Saved-chat-artifact IPC: `artifact:reveal` (show in Finder/Explorer) and `artifact:open`
 * (open with the default app). The renderer passes ONLY the server-registered artifact id —
 * the main process resolves the real path from the loopback backend, so a compromised or
 * stale renderer can never hand the shell an arbitrary filesystem path, URL, or custom
 * scheme (task doc 7.4).
 */

/**
 * Open allowlist: the "Open" action hands the file to the OS default handler only for formats
 * that are passively rendered. Everything else — executables, scripts, source code (a Windows
 * default handler may be an interpreter), shortcut/launcher formats (.lnk/.url/.desktop/…),
 * installers, macro-capable office documents (legacy .doc/.xls/.ppt, .docm/.xlsm), archives,
 * and every unknown or missing extension — reveals in Finder/Explorer instead. A denylist can
 * never finish chasing OS-executable extensions; an allowlist makes "unknown" fall back to
 * reveal by construction.
 */
const OPEN_ALLOWED_EXTENSIONS = new Set([
  // Text and data rendered by viewers/editors without executing the content
  '.txt', '.md', '.markdown', '.csv', '.tsv', '.json', '.xml', '.yaml', '.yml', '.log',
  // PDF: viewers sandbox or prompt before running embedded JavaScript
  '.pdf',
  // Modern OOXML without macro embedding (.docm/.xlsm/.pptm are deliberately absent)
  '.docx', '.xlsx', '.pptx',
  // Images (SVG renders inside a viewer/browser sandbox)
  '.png', '.jpg', '.jpeg', '.gif', '.bmp', '.webp', '.tif', '.tiff', '.ico',
  '.heic', '.heif', '.avif', '.svg',
  // Markup opened in a browser: scripted content stays inside the browser sandbox
  '.html', '.htm',
  // Audio/video playback
  '.mp3', '.wav', '.ogg', '.flac', '.aac', '.m4a',
  '.mp4', '.webm', '.mov', '.mkv', '.avi', '.m4v',
])

export interface ArtifactPathResolver {
  /**
   * Resolves an artifact id to its confirmed saved path via
   * GET /api/ai/chat-resources/artifacts/{id}/path. Throws on unknown/unsaved ids.
   */
  (artifactId: string): Promise<{ path: string }>
}

export function isArtifactOpenAllowed(path: string): boolean {
  const name = path.toLowerCase()
  const dot = name.lastIndexOf('.')
  const extension = dot >= 0 ? name.slice(dot) : ''
  return OPEN_ALLOWED_EXTENSIONS.has(extension)
}

export function registerArtifactIpc(resolvePath: ArtifactPathResolver): void {
  ipcMain.handle(
    'artifact:reveal',
    async (_event, artifactId: unknown) => {
      const { path } = await resolve(artifactId, resolvePath)
      shell.showItemInFolder(path) // void on success; a missing item simply no-ops
    },
  )

  ipcMain.handle(
    'artifact:open',
    async (_event, artifactId: unknown) => {
      const { path } = await resolve(artifactId, resolvePath)
      // Scripts and executables only ever reveal — a chat artifact must not become a
      // "run this file I just generated" primitive.
      if (!isArtifactOpenAllowed(path)) {
        shell.showItemInFolder(path)
        return
      }
      // openPath resolves with '' on success; any other string is the OS error, and
      // reporting it as a failure (rather than success) is the whole point (7.4).
      const error = await shell.openPath(path)
      if (error) throw new Error(error)
    },
  )
}

async function resolve(
  artifactId: unknown,
  resolvePath: ArtifactPathResolver,
): Promise<{ path: string }> {
  if (typeof artifactId !== 'string' || artifactId.trim() === '') {
    throw new Error('Invalid artifact id')
  }
  try {
    return await resolvePath(artifactId)
  } catch (error) {
    throw new Error(
      error instanceof Error && error.message
        ? error.message
        : 'Could not resolve the saved artifact',
    )
  }
}
