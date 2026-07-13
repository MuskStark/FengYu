import test from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { loadConfigFromFile } from 'vite'

test('dev server proxies installed plugin UI assets to the backend', async () => {
  const configFile = fileURLToPath(new URL('../vite.config.ts', import.meta.url))
  const loaded = await loadConfigFromFile({ command: 'serve', mode: 'test' }, configFile)

  assert.ok(loaded, 'vite.config.ts should load')
  const proxy = loaded.config.server?.proxy
  assert.equal(proxy?.['/plugin-runtime']?.target, proxy?.['/api']?.target)
})
