import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const source = await readFile(new URL('../src/views/PluginView.vue', import.meta.url), 'utf8')

test('installs the host message listener before rendering the plugin iframe', () => {
  assert.match(source, /onBeforeMount\(\(\) => \{[\s\S]*addEventListener\('message', onMessage\)[\s\S]*bridgeListening\.value = true/)
  assert.match(source, /<iframe[\s\S]*v-if="bridgeListening"/)
})

test('keeps loading state until the plugin completes its ready handshake', () => {
  assert.match(source, /request\.method === 'host\.ready'[\s\S]*bridgeReady\.value = true/)
  assert.doesNotMatch(source, /@load="[^"]*loading = false/)
  assert.match(source, /pluginHandshakeTimeout/)
  assert.match(source, /retryPlugin/)
})

test('loads same-origin sandbox scripts from an isolated plugin origin', () => {
  assert.match(source, /import \{ pluginAssetUrl \} from '@\/api\/config'/)
  assert.match(source, /sandbox="allow-scripts allow-same-origin allow-forms allow-downloads"/)
  assert.match(source, /return entry \? pluginAssetUrl\(entry\) : undefined/)
})

test('advertises and handles the official input-directory capability', () => {
  assert.match(source, /capabilities: \[[^\]]*'files\.inputDirectory'/s)
  assert.match(source, /request\.method === 'files\.inputDirectory'/)
  assert.match(source, /webkitdirectory/)
})
