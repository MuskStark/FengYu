import fs from 'node:fs/promises'
import path from 'node:path'
import { createHash, createPrivateKey, sign } from 'node:crypto'

/** Sign exact .fyp bytes with Ed25519 and atomically write a catalog-ready sidecar. */
export async function signPlugin(file, { key, keyId, out } = {}) {
  const archive = path.resolve(file)
  if (!archive.toLowerCase().endsWith('.fyp')) throw new Error('sign expects a .fyp archive')
  if (!key) throw new Error('--key is required')
  if (!keyId) throw new Error('--key-id is required')
  const bytes = await fs.readFile(archive)
  const privateKey = createPrivateKey(await fs.readFile(path.resolve(key)))
  if (privateKey.asymmetricKeyType !== 'ed25519') throw new Error('signing key must be Ed25519')
  const result = {
    algorithm: 'Ed25519',
    keyId,
    sha256: createHash('sha256').update(bytes).digest('hex'),
    signature: sign(null, bytes, privateKey).toString('base64'),
  }
  const output = path.resolve(out ?? `${archive}.sig.json`)
  const temporary = `${output}.tmp-${process.pid}`
  try {
    await fs.writeFile(temporary, JSON.stringify(result, null, 2) + '\n', { mode: 0o600 })
    await fs.rename(temporary, output)
  } finally {
    await fs.rm(temporary, { force: true }).catch(() => {})
  }
  return { output, ...result }
}
