import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'

// Keep this server hermetic. vite.config.ts warms six first-paint files, and an inline
// `warmup: { clientFiles: [] }` cannot unset that — Vite concatenates arrays when merging
// configs — so a warmup that compiles Sass spawns the sass-embedded dart child that
// vite.close() never reaps and the test process hangs after its last assertion. Clear the
// list from a config hook, which runs after the merge.
const noWarmup = () => ({
  name: 'test-no-warmup',
  config(config) {
    if (config.server?.warmup?.clientFiles) config.server.warmup.clientFiles = []
  },
})

test('settings and store components receive their Vuetify imports without global registration', async () => {
  const server = await createServer({
    // 'custom' keeps the SPA html pipeline — and its index.html dependency scan — out of
    // the server. On CI runners that scan dies with "The server is being restarted or
    // closed. Request is outdated" and leaves the awaits below pending forever. The
    // assertions only ever call transformRequest on the two components. 'ws: false' is
    // Vite 7's switch for the HMR websocket ('hmr: false' is not, in middleware mode):
    // the spec files run as parallel processes, and two middleware servers racing for
    // the default ws port 24678 is what wedged CI runs into their 25-minute job timeout.
    appType: 'custom',
    server: { middlewareMode: true, ws: false },
    optimizeDeps: { noDiscovery: true, include: [] },
    plugins: [noWarmup()],
  })
  try {
    for (const [file, components] of [
      ['/src/views/Settings.vue', ['VDialog', 'VBtn', 'VCard']],
      ['/src/components/store/UnifiedSourcesPanel.vue', ['VSelect', 'VNavigationDrawer', 'VChip']],
    ]) {
      const result = await server.transformRequest(file)
      assert.ok(result, `${file} should compile`)
      for (const component of components) {
        assert.match(result.code, new RegExp(`import[^\\n]+${component}[^\\n]+vuetify/`),
          `${file} should import ${component} through vite-plugin-vuetify`)
      }
    }
  } finally {
    await server.close()
  }
})
