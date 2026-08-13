import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const source = await readFile(new URL('../src/views/PluginView.vue', import.meta.url), 'utf8')

test('installs the host message listener before rendering the plugin iframe', () => {
  assert.match(source, /onBeforeMount\(\(\) => \{[\s\S]*addEventListener\('message', onMessage\)[\s\S]*bridgeListening\.value = true/)
  assert.match(source, /<iframe[\s\S]*v-if="bridgeListening"/)
})

test('captures the iframe window before navigating to the plugin entrypoint', () => {
  assert.match(source, /const frameUrl = ref\('about:blank'\)/)
  assert.match(source, /let activeFrameWindow: Window \| null = null/)
  assert.match(source, /await nextTick\(\)[\s\S]*activeFrameWindow = frame\.value\?\.contentWindow \?\? null[\s\S]*frameUrl\.value = targetUrl/)
  assert.match(source, /request\.method !== HOST_METHODS\.ready/)
  assert.match(source, /activeFrameWindow = event\.source as Window/)
  assert.match(source, /respond\(request\.id,[\s\S]*event\.source as Window/)
  assert.match(source, /:src="frameUrl"/)
  assert.doesNotMatch(source, /:src="pluginUrl\(\)"/)
})

test('keeps loading state until the plugin completes its ready handshake', () => {
  assert.match(source, /request\.method === HOST_METHODS\.ready[\s\S]*bridgeReady\.value = true/)
  assert.match(source, /function onFrameLoad\(\)[\s\S]*loading\.value = false/)
  assert.doesNotMatch(source, /pluginHandshakeTimeout/)
  assert.match(source, /retryPlugin/)
})

test('loads same-origin sandbox scripts from an isolated plugin origin', () => {
  assert.match(source, /import \{ pluginAssetUrl \} from '@\/api\/config'/)
  assert.match(source, /sandbox="allow-scripts allow-same-origin allow-forms allow-downloads"/)
  assert.match(source, /return entry \? pluginAssetUrl\(entry\) : undefined/)
})

test('advertises and handles the official input-directory capability', () => {
  assert.match(source, /capabilities: HOST_CAPABILITIES/)
  assert.match(source, /request\.method === HOST_METHODS\.filesInputDirectory/)
  assert.match(source, /webkitdirectory/)
})

test('propagates the configured host locale through handshake and environment updates', () => {
  assert.match(source, /import \{ useSettingsStore \} from '@\/stores\/settings'/)
  assert.match(source, /const settings = useSettingsStore\(\)/)
  assert.match(source, /request\.method === HOST_METHODS\.ready[\s\S]*locale: settings\.language/)
  assert.match(source, /event: 'environment'[\s\S]*data: \{ theme: theme\.theme, locale: settings\.language \}/)
  assert.match(source, /watch\(\(\) => settings\.language, sendEnvironment\)/)
})

test('uses the shared plugin notification center when the host has no toast surface', () => {
  assert.match(
    source,
    /request\.method === HOST_METHODS\.notify[\s\S]*respond\(request\.id, false\)/,
  )
})
