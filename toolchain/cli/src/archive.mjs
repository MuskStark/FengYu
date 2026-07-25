import yauzl from 'yauzl'
import { createRequire } from 'node:module'
import fs from 'node:fs/promises'
import path from 'node:path'

const require = createRequire(import.meta.url)

const MAX_PACKAGE_BYTES = 100 * 1024 * 1024
const MAX_EXPANDED_BYTES = 300 * 1024 * 1024

/**
 * Inspect a `.fyp` / zip archive WITHOUT extracting it. Enforces safety limits
 * (zip-slip paths, duplicate entries, size caps) and returns entry metadata.
 *
 * @param {string|Buffer} file - path to the archive, or an in-memory Buffer
 * @returns {Promise<{ entries: Array<{ name: string, compressedSize: number, uncompressedSize: number, isDirectory: boolean }>, totalExpandedBytes: number }>}
 */
export async function inspectArchive(file) {
  const size = Buffer.isBuffer(file) ? file.length : (await fs.stat(file)).size
  if (size > MAX_PACKAGE_BYTES) throw new Error('package exceeds 100 MB')
  return inspectArchiveUnchecked(file)
}

function inspectArchiveUnchecked(file) {
  return new Promise((resolve, reject) => {
    const entries = []
    const seen = new Set()
    let totalExpanded = 0
    const open = Buffer.isBuffer(file)
      ? (cb) => yauzl.fromBuffer(file, { lazyEntries: true, autoClose: false }, cb)
      : (cb) => yauzl.open(file, { lazyEntries: true, autoClose: false }, cb)
    open((err, zip) => {
      if (err) return reject(new Error(`archive could not be opened: ${err.message}`))
      zip.on('entry', (entry) => {
        const name = entry.fileName
        // Normalize and reject path traversal.
        if (/^[\\/]/.test(name) || /^[A-Za-z]:/.test(name)) {
          cleanup(reject, new Error(`unsafe archive path: ${name}`), zip)
          return
        }
        if (name.includes('\\')) {
          cleanup(reject, new Error(`unsafe archive path: ${name}`), zip)
          return
        }
        const normalized = path.posix.normalize(name)
        if (normalized !== name) {
          cleanup(reject, new Error(`non-canonical archive path: ${name}`), zip)
          return
        }
        if (normalizeForCheck(normalized).startsWith('../') || normalizeForCheck(normalized).includes('/../')) {
          cleanup(reject, new Error(`unsafe archive path: ${name}`), zip)
          return
        }
        if (seen.has(normalized)) {
          cleanup(reject, new Error(`duplicate archive entry: ${name}`), zip)
          return
        }
        seen.add(normalized)
        totalExpanded += entry.uncompressedSize
        if (entry.uncompressedSize > MAX_EXPANDED_BYTES || totalExpanded > MAX_EXPANDED_BYTES) {
          cleanup(reject, new Error('expanded package exceeds 300 MB'), zip)
          return
        }
        entries.push({
          name,
          compressedSize: entry.compressedSize,
          uncompressedSize: entry.uncompressedSize,
          isDirectory: /\/$/.test(name),
        })
        // Drain the (possibly compressed) stream so the next entry is read; we
        // never keep the bytes, only the metadata.
        zip.openReadStream(entry, (streamErr, stream) => {
          if (streamErr) return cleanup(reject, streamErr, zip)
          stream.on('data', () => {})
          stream.on('end', () => zip.readEntry())
          stream.resume()
        })
      })
      zip.on('end', () => {
        const total = entries.reduce((sum, e) => sum + e.uncompressedSize, 0)
        if (total > MAX_EXPANDED_BYTES) {
          cleanup(reject, new Error('expanded package exceeds 300 MB'), zip)
          return
        }
        cleanupDone(zip)
        resolve({ entries, totalExpandedBytes: total })
      })
      zip.on('error', (e) => {
        // yauzl rejects traversal entries itself with "invalid relative path";
        // surface those as the same unsafe-path error callers expect.
        const message = /invalid relative path|absolute path/i.test(e.message) ? `unsafe archive path: ${e.message}`
          : `archive read error: ${e.message}`
        cleanup(reject, new Error(message), zip)
      })
      zip.readEntry()
    })
  })
}

export async function readArchiveEntry(file, expectedName, { maxBytes = 64 * 1024 * 1024 } = {}) {
  await inspectArchive(file)
  return new Promise((resolve, reject) => {
    const open = Buffer.isBuffer(file)
      ? (cb) => yauzl.fromBuffer(file, { lazyEntries: true, autoClose: false }, cb)
      : (cb) => yauzl.open(file, { lazyEntries: true, autoClose: false }, cb)
    open((err, zip) => {
      if (err) return reject(new Error(`archive could not be opened: ${err.message}`))
      let settled = false
      const fail = (error) => {
        if (settled) return
        settled = true
        try { zip.close() } catch { /* ignore */ }
        reject(error)
      }
      zip.on('entry', (entry) => {
        if (entry.fileName !== expectedName) { zip.readEntry(); return }
        if (entry.uncompressedSize > maxBytes) return fail(new Error(`archive entry exceeds ${maxBytes} bytes: ${expectedName}`))
        zip.openReadStream(entry, (streamErr, stream) => {
          if (streamErr) return fail(streamErr)
          const chunks = []
          let total = 0
          stream.on('data', (chunk) => {
            total += chunk.length
            if (total > maxBytes) stream.destroy(new Error(`archive entry exceeds ${maxBytes} bytes: ${expectedName}`))
            else chunks.push(chunk)
          })
          stream.on('error', fail)
          stream.on('end', () => {
            if (settled) return
            settled = true
            try { zip.close() } catch { /* ignore */ }
            resolve(Buffer.concat(chunks))
          })
        })
      })
      zip.on('end', () => fail(new Error(`archive entry not found: ${expectedName}`)))
      zip.on('error', fail)
      zip.readEntry()
    })
  })
}

function normalizeForCheck(p) {
  // Collapse redundant separators for a robust traversal check.
  return p.replace(/\/+/g, '/').replace(/^\.\//, '')
}

function cleanup(reject, err, zip) {
  try { zip.close() } catch { /* ignore */ }
  reject(err)
}

function cleanupDone(zip) {
  try { zip.close() } catch { /* ignore */ }
}

export { MAX_PACKAGE_BYTES, MAX_EXPANDED_BYTES }
