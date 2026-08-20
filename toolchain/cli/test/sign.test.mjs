import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { generateKeyPairSync, verify } from 'node:crypto'
import { signPlugin } from '../src/sign.mjs'

test('sign emits a verifiable Ed25519 catalog sidecar', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'fengyu-sign-'))
  try {
    const archive = path.join(root, 'demo.fyp')
    await fs.writeFile(archive, 'package bytes')
    const { privateKey, publicKey } = generateKeyPairSync('ed25519')
    const key = path.join(root, 'private.pem')
    await fs.writeFile(key, privateKey.export({ type: 'pkcs8', format: 'pem' }))
    const result = await signPlugin(archive, { key, keyId: 'acme-2026' })
    const sidecar = JSON.parse(await fs.readFile(result.output, 'utf8'))
    assert.equal(sidecar.keyId, 'acme-2026')
    assert.ok(verify(null, await fs.readFile(archive), publicKey, Buffer.from(sidecar.signature, 'base64')))
  } finally {
    await fs.rm(root, { recursive: true, force: true })
  }
})
