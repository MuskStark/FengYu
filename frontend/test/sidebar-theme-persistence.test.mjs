import test, { after } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createPinia, setActivePinia } from 'pinia'
import { createServer } from 'vite'

const source = await readFile(new URL('../src/shell/Sidebar.vue', import.meta.url), 'utf8')
const vite = await createServer({
  server: { middlewareMode: true },
  appType: 'custom',
  ssr: { noExternal: ['vuetify'] },
})

after(async () => {
  await vite.close()
})

test('persists sidebar theme changes through the settings store', () => {
  assert.match(
    source,
    /@click="settings\.setTheme\(theme\.theme === 'dark' \? 'light' : 'dark'\)"/,
  )
  assert.doesNotMatch(source, /@click="theme\.toggle\(\)"/)
})

test('keeps the light theme after persisting the collapsed sidebar state', async () => {
  setActivePinia(createPinia())
  const { api } = await vite.ssrLoadModule('/src/api/client.ts')
  const { useSettingsStore } = await vite.ssrLoadModule('/src/stores/settings.ts')
  const { useThemeStore } = await vite.ssrLoadModule('/src/stores/theme.ts')

  let persisted = { sidebarCollapsed: false, theme: 'dark', language: 'en' }
  api.putSettings = async (partial) => {
    persisted = { ...persisted, ...partial }
    return persisted
  }

  const originalWarn = console.warn
  console.warn = () => {}
  try {
    const settings = useSettingsStore()
    await settings.setTheme('light')
    await settings.setSidebarCollapsed(true)

    assert.equal(persisted.theme, 'light')
    assert.equal(settings.theme, 'light')
    assert.equal(useThemeStore().theme, 'light')
    assert.equal(settings.sidebarCollapsed, true)
  } finally {
    console.warn = originalWarn
  }
})
