import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, describe, expect, it } from 'vitest'

const script = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  'scripts',
  'verify-frontend-dist.mjs',
)

const created: string[] = []

afterEach(() => {
  for (const dir of created.splice(0)) rmSync(dir, { recursive: true, force: true })
})

// Minimal dist-shaped index.html: relative assets + inline import map + baked CSP.
function page(scriptBody: string, cspHash: string, withCsp = true): string {
  const meta = withCsp
    ? `    <meta http-equiv="Content-Security-Policy" content="default-src 'self'; script-src 'self' 'sha256-${cspHash}'">\n`
    : ''
  return `<!doctype html>
<html lang="en">
  <head>
${meta}    <script type="importmap">${scriptBody}</script>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="./assets/index-abc123.js"></script>
  </body>
</html>
`
}

const IMPORT_MAP = `
      {
        "imports": {
          "vue": "./vendor/vue.esm-browser.prod.js"
        }
      }
    `

const sha256 = (s: string) => createHash('sha256').update(s).digest('base64')

function verify(html: string) {
  const dir = mkdtempSync(join(tmpdir(), 'fengyu-verify-dist-'))
  created.push(dir)
  const file = join(dir, 'index.html')
  writeFileSync(file, html)
  return spawnSync(process.execPath, [script, file], { encoding: 'utf8' })
}

describe('verify-frontend-dist CSP hash gate', () => {
  it('accepts an LF build whose token hashes the import map', () => {
    const body = IMPORT_MAP
    const res = verify(page(body, sha256(body)))
    expect(res.status).toBe(0)
    expect(res.stdout).toContain('verified')
  })

  it('accepts a CRLF build whose token hashes LF-normalized content (fixed builder)', () => {
    const body = IMPORT_MAP.replace(/\n/g, '\r\n')
    const res = verify(page(body, sha256(body.replace(/\r\n/g, '\n'))))
    expect(res.status).toBe(0)
  })

  it('rejects the rc.1 Windows defect: token hashed over raw CRLF bytes', () => {
    const body = IMPORT_MAP.replace(/\n/g, '\r\n')
    const res = verify(page(body, sha256(body)))
    expect(res.status).not.toBe(0)
    expect(res.stderr).toContain('no CSP')
  })

  it('rejects a page without a baked CSP meta tag', () => {
    const res = verify(page(IMPORT_MAP, 'irrelevant', false))
    expect(res.status).not.toBe(0)
    expect(res.stderr).toContain('Content-Security-Policy')
  })
})
