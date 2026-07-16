import fs from 'node:fs/promises'
import path from 'node:path'
import { inspectArchive } from './archive.mjs'
import { validateManifestObject } from './manifest.mjs'

/**
 * Install a `.fyp` plugin package into a running FengYu host.
 *
 * Offline-first: the archive is inspected and its manifest validated BEFORE any
 * network access. Only after local validation succeeds is the package uploaded
 * to the host's `/api/plugin-market/upload` endpoint.
 *
 * @param {string} file - path to the `.fyp` archive
 * @param {{ host?: string, token?: string, fetchImpl?: typeof fetch }} [options]
 * @returns {Promise<object|string>} parsed JSON response, or response text when not JSON
 */
export async function installPlugin(file, options = {}) {
  if (!file.toLowerCase().endsWith('.fyp')) {
    throw new Error('install requires a .fyp plugin package')
  }
  // 1. Inspect archive limits and paths (no extraction).
  const { entries } = await inspectArchive(file)
  const names = new Set(entries.map((e) => e.name))

  // 2. Validate the archived manifest object.
  const manifestEntry = entries.find((e) => e.name === 'manifest.json')
  if (!manifestEntry) throw new Error('package is missing manifest.json')
  const manifest = JSON.parse(await readEntry(file, 'manifest.json'))
  const errors = validateManifestObject(manifest)
  // ui.entry must exist as an archive entry.
  if (manifest.ui?.entry && !names.has(manifest.ui.entry)) {
    errors.push(`package is missing UI entry: ${manifest.ui.entry}`)
  }
  if (errors.length) throw new Error(errors.join('\n'))

  // 3. Upload only after validation succeeds.
  const host = options.host ?? 'http://127.0.0.1:24056'
  const token = options.token ?? process.env.FENGYU_TOKEN ?? ''
  const fetchImpl = options.fetchImpl ?? fetch
  const body = new FormData()
  body.append('file', new Blob([await fs.readFile(file)]), path.basename(file))
  const headers = token ? { 'X-FengYu-Token': token } : {}
  const res = await fetchImpl(host + '/api/plugin-market/upload', { method: 'POST', headers, body })
  if (!res.ok) {
    throw new Error(`install failed: ${res.status} ${await res.text()}`)
  }
  const text = await res.text()
  const contentType = res.headers.get('content-type') ?? ''
  if (contentType.includes('application/json')) {
    try { return JSON.parse(text) } catch { return text }
  }
  return text
}

/** Read a single entry's bytes from the archive (used for the manifest only). */
async function readEntry(file, name) {
  const yauzl = (await import('yauzl')).default
  return new Promise((resolve, reject) => {
    const open = Buffer.isBuffer(file)
      ? (cb) => yauzl.fromBuffer(file, { lazyEntries: true }, cb)
      : (cb) => yauzl.open(file, { lazyEntries: true }, cb)
    open((err, zip) => {
      if (err) return reject(err)
      zip.on('entry', (entry) => {
        if (entry.fileName === name) {
          zip.openReadStream(entry, (streamErr, stream) => {
            if (streamErr) return reject(streamErr)
            const chunks = []
            stream.on('data', (c) => chunks.push(c))
            stream.on('end', () => { zip.close(); resolve(Buffer.concat(chunks).toString('utf8')) })
          })
        } else {
          zip.readEntry()
        }
      })
      zip.on('end', () => { try { zip.close() } catch { /* ignore */ } reject(new Error(`entry not found: ${name}`)) })
      zip.readEntry()
    })
  })
}
