import test, { after } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createPinia, setActivePinia } from 'pinia'
import { createServer } from 'vite'

const sidebarSource = await readFile(new URL('../src/shell/Sidebar.vue', import.meta.url), 'utf8')
const settingsSource = await readFile(new URL('../src/views/Settings.vue', import.meta.url), 'utf8')
const vite = await createServer({
  server: { middlewareMode: true },
  appType: 'custom',
  ssr: { noExternal: ['vuetify'] },
})

after(async () => {
  await vite.close()
})

test('keeps theme changes in the settings surface after the sidebar redesign', () => {
  assert.match(settingsSource, /@click="settings\.setTheme\(i\.value\)"/)
  assert.doesNotMatch(sidebarSource, /@click="theme\.toggle\(\)"/)
})

test('automatically collapses the host sidebar before plugin content becomes cramped', () => {
  assert.match(sidebarSource, /const \{ width \} = useDisplay\(\)/)
  assert.match(sidebarSource, /autoRail = computed\(\(\) => width\.value < 900\)/)
  assert.match(sidebarSource, /settings\.sidebarCollapsed \|\| autoRail\.value/)
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

test('notifies the desktop shell before theme persistence completes', async () => {
  setActivePinia(createPinia())
  const { api } = await vite.ssrLoadModule('/src/api/client.ts')
  const { useSettingsStore } = await vite.ssrLoadModule('/src/stores/settings.ts')

  let finishPersistence
  api.putSettings = () => new Promise((resolve) => {
    finishPersistence = () => resolve({ sidebarCollapsed: false, theme: 'light', language: 'en' })
  })

  const applied = []
  globalThis.window = {
    fengyu: {
      setTheme: (theme) => applied.push(theme),
    },
  }

  try {
    const pending = useSettingsStore().setTheme('light')
    assert.deepEqual(applied, ['light'])
    finishPersistence()
    await pending
    assert.deepEqual(applied, ['light'])
  } finally {
    delete globalThis.window
  }
})
