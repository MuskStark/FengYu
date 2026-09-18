import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'

test('settings and store components receive their Vuetify imports without global registration', async () => {
  const server = await createServer({
    server: { middlewareMode: true, warmup: { clientFiles: [] } },
    optimizeDeps: { noDiscovery: true, include: [] },
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
