# Sidebar Theme Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the selected light theme active when the sidebar is collapsed or expanded.

**Architecture:** Preserve `useThemeStore` as the live Vuetify theme applicator and route the sidebar's user-initiated theme change through `useSettingsStore.setTheme`, the existing persistence boundary. Add a focused Node test that locks down the sidebar wiring and loads the real Pinia stores through Vite SSR to reproduce the theme-update/sidebar-update sequence.

**Tech Stack:** Vue 3.5, TypeScript, Pinia, Vuetify 3, Node.js test runner

---

### Task 1: Add the Sidebar Persistence Regression Test

**Files:**
- Create: `frontend/test/sidebar-theme-persistence.test.mjs`
- Test: `frontend/test/sidebar-theme-persistence.test.mjs`

- [ ] **Step 1: Write the failing test**

```javascript
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
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `cd frontend && node --test test/sidebar-theme-persistence.test.mjs`

Expected: one FAIL because `Sidebar.vue` still contains `@click="theme.toggle()"` and does not call `settings.setTheme(...)`; the real-store persistence sequence passes.

### Task 2: Route the Theme Action Through Persisted Settings

**Files:**
- Modify: `frontend/src/shell/Sidebar.vue:109-115`
- Test: `frontend/test/sidebar-theme-persistence.test.mjs`

- [ ] **Step 1: Implement the minimal production change**

Replace the theme row's click handler with:

```vue
@click="settings.setTheme(theme.theme === 'dark' ? 'light' : 'dark')"
```

Keep `useThemeStore` because the template still reads `theme.theme` to calculate both the next theme and the displayed icon.

- [ ] **Step 2: Run the focused test and verify GREEN**

Run: `cd frontend && node --test test/sidebar-theme-persistence.test.mjs`

Expected: PASS with two passing tests and no failures.

- [ ] **Step 3: Run the complete frontend test suite**

Run: `cd frontend && npm test`

Expected: all `frontend/test/*.test.mjs` tests pass with no failures.

- [ ] **Step 4: Run the frontend type-check and production build**

Run: `cd frontend && npm run build`

Expected: `vue-tsc --noEmit` and `vite build` both exit successfully.

- [ ] **Step 5: Review the final diff**

Run: `git diff --check && git diff -- frontend/src/shell/Sidebar.vue frontend/test/sidebar-theme-persistence.test.mjs`

Expected: no whitespace errors; the diff contains only the persisted theme click handler and its regression test.

- [ ] **Step 6: Commit the bug fix**

```bash
git add frontend/src/shell/Sidebar.vue frontend/test/sidebar-theme-persistence.test.mjs docs/superpowers/plans/2026-07-13-sidebar-theme-persistence.md
git commit -m "🐛 fix(frontend): persist sidebar theme changes"
```
