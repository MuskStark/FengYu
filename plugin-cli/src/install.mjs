import fs from 'node:fs/promises'
import path from 'node:path'
import { validatePluginArchive } from './manifest.mjs'

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
  const { errors } = await validatePluginArchive(file)
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
