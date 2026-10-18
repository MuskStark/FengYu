/**
 * vite-plugin-content.mjs — wires build-content.mjs into the Vite lifecycle.
 *
 * buildStart runs the full content pipeline before module resolution, so
 * src/.content/*.json always exist when the app imports them (dev AND build).
 * In dev, edits under en/ zh/ rebuild the content and hard-reload the page —
 * HMR is not attempted on purpose: the JSON is import.meta.glob'd deep enough
 * that a clean reload is cheaper and always correct.
 */
import path from 'node:path'
import { buildContent, DOCS_ROOT } from './build-content.mjs'

export function fengyuDocsContent() {
  const root = DOCS_ROOT
  const isContentFile = (file) => {
    const rel = path.relative(root, file)
    return rel.startsWith('en/') || rel.startsWith('zh/') || rel.startsWith(`${path.sep}en${path.sep}`) || rel.startsWith(`${path.sep}zh${path.sep}`)
  }
  let rebuilding = null
  return {
    name: 'fengyu-docs-content',
    async buildStart() {
      rebuilding = buildContent(root)
      await rebuilding
    },
    configureServer(server) {
      server.watcher.add([path.join(root, 'en'), path.join(root, 'zh')])
      const onChange = async (file) => {
        if (!isContentFile(String(file))) return
        try {
          if (rebuilding) await rebuilding.catch(() => {})
          await buildContent(root, { quiet: true })
          server.ws.send({ type: 'full-reload' })
        } catch (error) {
          server.ws.send({ type: 'error', err: { message: `[content] ${error.message}`, stack: '' } })
        }
      }
      server.watcher.on('change', onChange)
      server.watcher.on('add', onChange)
      server.watcher.on('unlink', onChange)
    },
  }
}
