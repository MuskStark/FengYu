import fs from 'node:fs/promises'
import path from 'node:path'
import zlib from 'node:zlib'

// Compression methods used in the Local / Central headers.
const METHOD_STORED = 0
const METHOD_DEFLATE = 8

// CRC32 lookup table (IEEE polynomial 0xedb88320).
const table = (() => {
  const t = []
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = (c & 1) ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c >>> 0
  }
  return t
})()

function crc32(data) {
  let c = 0xffffffff
  for (const b of data) c = table[(c ^ b) & 255] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

function u16(n) {
  const b = Buffer.alloc(2)
  b.writeUInt16LE(n)
  return b
}

function u32(n) {
  const b = Buffer.alloc(4)
  b.writeUInt32LE(n >>> 0)
  return b
}

/**
 * Collect every regular file under `dir` (recursively), skipping build output and
 * source directories that must never ship inside a `.fyp`. Entry names use POSIX
 * separators so the archive is portable across platforms.
 */
export async function collect(root, dir = root, out = []) {
  for (const e of await fs.readdir(dir, { withFileTypes: true })) {
    if (['.git', 'node_modules', 'target', 'dist', 'src'].includes(e.name)) continue
    const p = path.join(dir, e.name)
    if (e.isDirectory()) {
      await collect(root, p, out)
    } else {
      out.push({ name: path.relative(root, p).split(path.sep).join('/'), data: await fs.readFile(p) })
    }
  }
  return out
}

/**
 * Write a ZIP archive of `root` to `output`. Each entry is deflate-compressed, with a
 * per-entry fallback to STORED when compression would not shrink the data (already-
 * compressed binaries like the worker JAR, PNGs, etc.). The result is a standards-
 * compliant ZIP readable by `yauzl`, `unzip`, and JDK `JarFile`.
 *
 * @param {string} root directory to archive
 * @param {string} output absolute path to the archive being written
 * @returns {Promise<{ output: string, files: number }>}
 */
export async function writeZip(root, output) {
  const files = await collect(root)
  const locals = []
  const centrals = []
  let offset = 0

  for (const f of files) {
    const name = Buffer.from(f.name)
    const data = Buffer.from(f.data)
    const crc = crc32(data)

    // Try deflate; keep it only if it actually shrinks the entry. JARs, PNGs, and other
    // pre-compressed payloads would otherwise INCREASE on disk for zero gain. NOTE: ZIP
    // method 8 requires RAW deflate (no zlib wrapper) — `deflateRawSync`, not `deflateSync`.
    const deflated = zlib.deflateRawSync(data, { level: 9 })
    let method, payload
    if (deflated.length < data.length) {
      method = METHOD_DEFLATE
      payload = deflated
    } else {
      method = METHOD_STORED
      payload = data
    }

    // Local File Header: signature, version (2.0), flags, method, modTime (0), modDate (0),
    // CRC, compressed size, uncompressed size, name length, extra length, name, payload.
    const local = Buffer.concat([
      u32(0x04034b50), u16(20), u16(0), u16(method), u16(0), u16(0),
      u32(crc), u32(payload.length), u32(data.length),
      u16(name.length), u16(0), name, payload,
    ])
    locals.push(local)

    // Central Directory File Header: signature, version-made-by, version-needed, flags,
    // method, modTime, modDate, CRC, compressed size, uncompressed size, name length,
    // extra length, comment length, disk number start, internal attrs, external attrs,
    // local-header offset, name. (46 fixed bytes before the name.)
    centrals.push(Buffer.concat([
      u32(0x02014b50), u16(20), u16(20), u16(0), u16(method), u16(0), u16(0),
      u32(crc), u32(payload.length), u32(data.length),
      u16(name.length), u16(0), u16(0), u16(0), u16(0), u32(0), u32(offset), name,
    ]))
    offset += local.length
  }

  const central = Buffer.concat(centrals)
  // End Of Central Directory Record: signature, disk #, disk with CD start, entries on this
  // disk, total entries, CD size, CD offset, comment length (0).
  const end = Buffer.concat([
    u32(0x06054b50), u16(0), u16(0), u16(files.length), u16(files.length),
    u32(central.length), u32(offset), u16(0),
  ])

  await fs.mkdir(path.dirname(output), { recursive: true })
  await fs.writeFile(output, Buffer.concat([...locals, central, end]))
  return { output, files: files.length }
}
