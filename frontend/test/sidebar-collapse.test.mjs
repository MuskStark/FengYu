import test, { after } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createServer } from 'vite'

const sidebarSource = await readFile(new URL('../src/shell/Sidebar.vue', import.meta.url), 'utf8')
const appShellSource = await readFile(new URL('../src/shell/AppShell.vue', import.meta.url), 'utf8')
const codexSource = await readFile(new URL('../src/theme/codex.css', import.meta.url), 'utf8')
const createWindowSource = await readFile(
  new URL('../../desktop/electron/src/window/create-window.ts', import.meta.url), 'utf8',
)
const vite = await createServer({
  server: { middlewareMode: true },
  appType: 'custom',
  ssr: { noExternal: ['vuetify'] },
})

after(async () => {
  await vite.close()
})

test('collapses fully (ZCode-style) instead of switching to an icon rail', () => {
  assert.match(codexSource, /\.cx-sidebar\.collapsed \{ width: 0; flex-basis: 0; opacity: 0;/)
  assert.doesNotMatch(codexSource, /\.cx-sidebar\.rail/)
  // The shell owns the collapse state; the sidebar only renders it.
  assert.match(sidebarSource, /collapsed\?: boolean/)
  assert.match(appShellSource, /:collapsed="sidebarCollapsed"/)
})

test('toggles the sidebar with ⌘B / Ctrl+B from anywhere outside the settings surface', async () => {
  const { isToggleSidebarShortcut } = await vite.ssrLoadModule('/src/shell/sidebar-layout.ts')
  const event = (overrides = {}) => ({
    metaKey: false, ctrlKey: false, shiftKey: false, altKey: false, key: 'b', code: 'KeyB', ...overrides,
  })
  assert.equal(isToggleSidebarShortcut(event({ metaKey: true })), true)
  assert.equal(isToggleSidebarShortcut(event({ ctrlKey: true })), true)
  assert.equal(isToggleSidebarShortcut(event({ metaKey: true, ctrlKey: true })), false)
  assert.equal(isToggleSidebarShortcut(event({ metaKey: true, shiftKey: true })), false)
  assert.equal(isToggleSidebarShortcut(event({ ctrlKey: true, altKey: true })), false)
  assert.equal(isToggleSidebarShortcut(event({ metaKey: true, key: 'B' })), true)
  // Non-Latin layouts report key 'ф' but still expose code 'KeyB'.
  assert.equal(isToggleSidebarShortcut(event({ metaKey: true, key: 'ф' })), true)
  assert.equal(isToggleSidebarShortcut(event({ metaKey: true, key: 'x', code: 'KeyX' })), false)

  assert.match(appShellSource, /window\.addEventListener\('keydown', onKeydown\)/)
  assert.match(appShellSource, /if \(settingsRoute\.value \|\| event\.repeat \|\| event\.isComposing\) return/)
})

test('clamps the remembered sidebar width between its floor and half the viewport', async () => {
  const { clampSidebarWidth, SIDEBAR_MIN_WIDTH } = await vite.ssrLoadModule('/src/shell/sidebar-layout.ts')
  assert.equal(SIDEBAR_MIN_WIDTH, 264)
  assert.equal(clampSidebarWidth(100, 1200), 264)
  assert.equal(clampSidebarWidth(300.6, 1200), 301)
  assert.equal(clampSidebarWidth(900, 1200), 600)
  // Narrow viewport: the max falls back to the floor, never below it.
  assert.equal(clampSidebarWidth(400, 400), 264)
})

test('persists the sidebar width locally and survives storage failures', async () => {
  const { persistSidebarWidth, readPersistedSidebarWidth, SIDEBAR_WIDTH_STORAGE_KEY } =
    await vite.ssrLoadModule('/src/shell/sidebar-layout.ts')
  const store = new Map()
  const originalWindow = globalThis.window
  globalThis.window = {
    innerWidth: 1200,
    localStorage: {
      getItem: key => (store.has(key) ? store.get(key) : null),
      setItem: (key, value) => store.set(key, String(value)),
    },
  }
  try {
    assert.equal(readPersistedSidebarWidth(), null)
    persistSidebarWidth(320)
    assert.equal(readPersistedSidebarWidth(), 320)
    // Corrupted entries fall back to null instead of throwing.
    store.set(SIDEBAR_WIDTH_STORAGE_KEY, 'not-a-number')
    assert.equal(readPersistedSidebarWidth(), null)
  } finally {
    globalThis.window = originalWindow
  }
  assert.equal(readPersistedSidebarWidth(), null)
})

test('keeps the resize separator keyboard-operable and snapping to collapse', () => {
  assert.match(appShellSource, /role="separator"/)
  assert.match(appShellSource, /aria-orientation="vertical"/)
  assert.match(appShellSource, /:aria-label="\$t\('sidebar\.resize'\)"/)
  assert.match(appShellSource, /if \(sidebarWidth\.value < SIDEBAR_MIN_WIDTH\) \{/)
  assert.match(appShellSource, /const step = event\.key === 'ArrowLeft' \? -16 : 16/)
  // While dragging, the sidebar follows the pointer without a width transition.
  assert.match(sidebarSource, /:class="\{ collapsed, resizing \}"/)
  assert.match(codexSource, /\.cx-sidebar\.resizing \{ transition: opacity/)
})

test('keeps collapse reversible without the sidebar: corner handle off macOS, title-bar button on it', () => {
  assert.match(appShellSource, /v-if="sidebarCollapsed && !macTitleBar && !settingsRoute"/)
  assert.match(appShellSource, /class="cx-iconbtn cx-iconbtn--sm shell-sidebar-handle"/)
  assert.match(appShellSource, /v-if="macTitleBar && !settingsRoute" class="shell-window-controls"/)
})

test('route headers dodge the floating corner controls while the sidebar is collapsed', () => {
  // mac: traffic lights + toggle strip (112px); elsewhere: the 28px corner handle.
  assert.match(appShellSource, /\.cx-shell\.mac-titlebar\.sidebar-collapsed :deep\(\.cx-topbar\),/)
  assert.match(appShellSource, /\.cx-shell\.mac-titlebar\.sidebar-collapsed :deep\(\.flow-toolbar\) \{/)
  assert.match(appShellSource, /\.cx-shell\.sidebar-collapsed:not\(\.mac-titlebar\) :deep\(\.cx-topbar\),/)
  assert.match(appShellSource, /padding-left: 116px;/)
  assert.match(appShellSource, /padding-left: 52px;/)
  // Centered pages drop their title below the strip instead of insetting it.
  assert.match(appShellSource, /\.cx-shell\.sidebar-collapsed :deep\(\.cx-page\) \{\n  padding-top: 60px;/)
  // The mac controls strip stays above route headers and doubles as the window drag area
  // once the sidebar (which owned the drag bars) is gone.
  assert.match(appShellSource, /z-index: 30;\n  inset: 0 auto auto 0;/)
  assert.match(appShellSource, /app-region: drag;\n  -webkit-app-region: drag;\n\}\n\.shell-sidebar-toggle/)
})

test('traffic lights, collapse toggle, and route headers share the window-bar centerline', () => {
  // The 48px bar puts the centerline at y24: native lights at y18+6, the 28px toggle at 10+14.
  // Positioning must happen after restoring visibility because that native call resets the frame.
  const showButtonsAt = createWindowSource.indexOf('win.setWindowButtonVisibility(true)')
  const positionButtonsAt = createWindowSource.indexOf('win.setWindowButtonPosition({ x: 14, y: 18 })')
  assert.ok(showButtonsAt >= 0 && positionButtonsAt > showButtonsAt)
  assert.match(appShellSource, /\.shell-sidebar-toggle \{\n  position: absolute;\n  top: 10px;/)
  assert.match(codexSource, /\.cx-topbar \{[^}]*min-height: var\(--cx-window-bar-height\);/)
})
