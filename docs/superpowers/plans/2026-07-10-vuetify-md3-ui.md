# Vuetify MD3 UI Component Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adopt Vuetify 3 with the Material Design 3 (MD3) blueprint as the UI component library for the host SPA (`frontend/`) and the plugin micro-frontend (`plugin-markdown/ui-src`), migrating all existing views from hand-written `--sk-*` CSS tokens to MD3 components.

**Architecture:** The host installs Vuetify, builds a single MD3-configured Vuetify app-plugin instance (`plugins/vuetify.ts`), and shares it with the plugin micro-frontend via `PluginContext.vuetify`. Theme switching stays in the existing Pinia `useThemeStore` but now drives Vuetify's global theme singleton. `--sk-*` tokens are deleted at the end once all views are migrated.

**Tech Stack:** Vue 3.5.39, Vuetify `^3.7.x` (MD3 blueprint), `@mdi/font ^7.4.x`, `vite-plugin-vuetify ^2.0.x`, `sass-embedded ^1.x`, Vite 6, Pinia, vue-router 4, vue-i18n 10.

## Global Constraints

- **Library:** Vuetify only. No Element/Quasar/Naive/Ant. MD3 components.
- **Palette:** Google default M3 baseline (exact values in Task 2) — do not seed-generate.
- **MD3 blueprint:** `import { md3 } from 'vuetify/blueprints'` passed as `blueprint: md3`.
- **Icons:** `@mdi/font`; usage `<v-icon icon="mdi-cog">`. Reuse existing `mdi-*` symbol names.
- **No import-map / plugin-build changes:** Vuetify is shared via `ctx.vuetify`, never externalized in the plugin build.
- **Migration rules:** remove every `.sk-*` class and every `var(--sk-*)` usage in migrated `.vue` files; keep only minimal layout-scoped `<style>` that Vuetify can't express.
- **Builds must pass:** `cd frontend && npm run build` and `cd plugin-markdown/ui-src && npm run build` — zero new TS errors at every task boundary.
- **Commits:** one commit per task, conventional-commit format, Chinese emoji prefix consistent with repo (`✨ feat(frontend): ...`).
- **Branch:** work on `4.0.0-ZhiFlow` (current branch).
- **Spec:** `docs/superpowers/specs/2026-07-10-vuetify-md3-ui-design.md`.

---

## File Structure

**Created:**
- `frontend/src/plugins/vuetify.ts` — single Vuetify instance (MD3 blueprint + palette + theme). Responsibility: be the one source of MD3 config, consumed by `main.ts` and the MF `PluginContext`.
- `frontend/src/plugins/vuetify-settings.scss` — global Sass overrides for MD3 shape (card/button roundness, density). Wired via `vite-plugin-vuetify` `styles.configFile`.
- `frontend/src/plugins/md3-themes.ts` — the two theme objects (`md3Dark`, `md3Light`) as plain exported consts, imported by `vuetify.ts`. Split out so the palette is reviewable in isolation.

**Modified (host):**
- `frontend/package.json` — add deps.
- `frontend/vite.config.ts` — add `vite-plugin-vuetify`.
- `frontend/index.html` — remove `class="theme-dark"` from `<html>`.
- `frontend/src/main.ts` — `app.use(vuetify)`.
- `frontend/src/stores/theme.ts` — drive Vuetify singleton instead of root class.
- `frontend/src/App.vue` — wrap in `<v-app>`, remove class-toggle `watchEffect`.
- `frontend/src/shell/AppShell.vue` — use `<v-main>` + `<v-navigation-drawer>` + `<v-system-bar>` layout.
- `frontend/src/shell/Sidebar.vue` — `<v-navigation-drawer>` + `<v-list>`.
- `frontend/src/shell/StatusBar.vue` — `<v-system-bar>` + `<v-chip>`.
- `frontend/src/mf/loader.ts` — add `vuetify: Plugin` to `PluginContext`.
- `frontend/src/views/*.vue` (7 files) — migrate to MD3.
- `frontend/src/theme/tokens.css` — delete `--sk-*` blocks (final task).

**Modified (plugin MF):**
- `plugin-markdown/ui-src/src/main.ts` — `app.use(ctx.vuetify)`.
- `plugin-markdown/ui-src/src/MarkdownEditor.vue` — migrate demo to MD3.

**Modified (docs):**
- `CLAUDE.md`, `.agents/skills/zhiflow-plugin-dev/SKILL.md`, `.zcode/skills/create-builtin-tool/SKILL.md`, `docs/ui-design/README.md`, `docs/zh/ui-design/README.md`, `CHANGELOG.md`.

---

## Task 1: Add Vuetify dependencies + Vite plugin

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/vite.config.ts`
- Create: `frontend/src/plugins/vuetify-settings.scss` (empty placeholder so the configFile path resolves)

**Interfaces:**
- Produces: Vite config that loads `vite-plugin-vuetify` with `styles.configFile` pointing at `src/plugins/vuetify-settings.scss`. Later tasks import from `vuetify` knowing the plugin is wired.

- [ ] **Step 1: Add dependencies**

Run:
```bash
cd frontend
npm install vuetify@^3.7.0 @mdi/font@^7.4.47
npm install -D vite-plugin-vuetify@^2.0.4 sass-embedded@^1.83.0
```

Expected: `package.json` now lists `vuetify`, `@mdi/font` under `dependencies`, and `vite-plugin-vuetify`, `sass-embedded` under `devDependencies`. `package-lock.json` updated.

- [ ] **Step 2: Create the Sass settings placeholder**

Create `frontend/src/plugins/vuetify-settings.scss`:
```scss
// Global Vuetify Sass overrides for the MD3 look.
// Loaded via vite-plugin-vuetify `styles.configFile`.
// Values are intentionally minimal — MD3 roundness/density is achieved
// through Vuetify defaults + component props, not heavy Sass overrides.
@use 'vuetify/settings' with (
  // MD3 specifies medium (12px) and large (16px) corner radii.
  $rounded: (
    's': 8px,
    'm': 12px,
    'l': 16px,
  ),
);
```

- [ ] **Step 3: Wire the Vite plugin**

Modify `frontend/vite.config.ts`. Replace the `import` block and `plugins:` line:

Old:
```ts
import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
```
New:
```ts
import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'
```

Old:
```ts
  plugins: [vendorVue(), vue()],
```
New:
```ts
  plugins: [
    vendorVue(),
    vue(),
    // MD3 Vuetify: auto tree-shake components, wire Sass overrides.
    vuetify({ styles: { configFile: 'src/plugins/vuetify-settings.scss' } }),
  ],
```

- [ ] **Step 4: Verify the dev server boots**

Run:
```bash
cd frontend && npm run dev -- --port 5180 &
sleep 6
curl -s http://localhost:5180/ | grep -q "ZhiFlow" && echo "DEV_OK" || echo "DEV_FAIL"
kill %1 2>/dev/null
```
Expected: `DEV_OK` (the server starts; no component imports yet so Vuetify isn't actually rendered — this only confirms the Vite plugin didn't break the build).

- [ ] **Step 5: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/vite.config.ts frontend/src/plugins/vuetify-settings.scss
git commit -m "✨ feat(frontend): add Vuetify 3 (MD3) dependencies + Vite plugin"
```

---

## Task 2: MD3 themes + Vuetify instance

**Files:**
- Create: `frontend/src/plugins/md3-themes.ts`
- Create: `frontend/src/plugins/vuetify.ts`
- Modify: `frontend/src/main.ts`

**Interfaces:**
- Produces:
  - `export const md3Dark: ThemeDefinition` and `export const md3Light: ThemeDefinition` (from `md3-themes.ts`).
  - `export const vuetify` — the single `VuetifyOptions`-derived app plugin (from `vuetify.ts`). Consumed by `main.ts` (`app.use(vuetify)`), `stores/theme.ts` (`vuetify.theme.global.name.value`), and `views/PluginView.vue` (`ctx.vuetify`).

- [ ] **Step 1: Write the MD3 theme definitions**

Create `frontend/src/plugins/md3-themes.ts`:
```ts
import type { ThemeDefinition } from 'vuetify'

/**
 * Google default Material Design 3 baseline palette (purple-toned).
 * Source: M3 baseline (the set Material Theme Builder emits for a purple
 * seed). These replace the hand-written --sk-* tokens as the source of
 * truth for color in the web frontend.
 */

export const md3Light: ThemeDefinition = {
  dark: false,
  colors: {
    background: '#FEF7FF',
    surface: '#FEF7FF',
    'surface-variant': '#E7E0EC',
    'on-surface': '#1D1B20',
    'surface-bright': '#FEF7FF',
    'surface-container': '#F3EDF7',
    'surface-container-high': '#ECE6F0',
    'surface-container-highest': '#E6E0E9',
    primary: '#6750A4',
    'on-primary': '#FFFFFF',
    'primary-container': '#EADDFF',
    'on-primary-container': '#21005D',
    secondary: '#625B71',
    'on-secondary': '#FFFFFF',
    'secondary-container': '#E8DEF8',
    tertiary: '#7D5260',
    'on-tertiary': '#FFFFFF',
    'tertiary-container': '#FFD8E4',
    error: '#B3261E',
    'on-error': '#FFFFFF',
    'error-container': '#F9DEDC',
    outline: '#79747E',
    'outline-variant': '#CAC4D0',
  },
  variables: {
    'border-color': '#79747E',
    'border-opacity': 1,
  },
}

export const md3Dark: ThemeDefinition = {
  dark: true,
  colors: {
    background: '#141218',
    surface: '#141218',
    'surface-variant': '#49454F',
    'on-surface': '#E6E0E9',
    'surface-bright': '#3B383E',
    'surface-container': '#211F26',
    'surface-container-high': '#2B2930',
    'surface-container-highest': '#36343B',
    primary: '#D0BCFF',
    'on-primary': '#381E72',
    'primary-container': '#4F378B',
    'on-primary-container': '#EADDFF',
    secondary: '#CCC2DC',
    'on-secondary': '#332D41',
    'secondary-container': '#4A4458',
    tertiary: '#EFB8C8',
    'on-tertiary': '#492532',
    'tertiary-container': '#633B48',
    error: '#F2B8B5',
    'on-error': '#601410',
    'error-container': '#8C1D18',
    outline: '#938F99',
    'outline-variant': '#49454F',
  },
  variables: {
    'border-color': '#938F99',
    'border-opacity': 1,
  },
}
```

- [ ] **Step 2: Write the Vuetify instance**

Create `frontend/src/plugins/vuetify.ts`:
```ts
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import { md3 } from 'vuetify/blueprints'
import { aliases, mdi } from 'vuetify/iconsets/mdi'
import '@mdi/font/css/materialdesignicons.css'
import 'vuetify/styles'
import { md3Dark, md3Light } from './md3-themes'

/**
 * The single shared Vuetify app-plugin instance for the whole web shell.
 * - MD3 blueprint (Material Design 3 component defaults).
 * - Google-default MD3 baseline palette (purple primary).
 * - Dual theme (dark default); flipped via `vuetify.theme.global.name.value`
 *   from stores/theme.ts (NOT useTheme(), which needs a component context).
 *
 * Also injected into the micro-frontend PluginContext so plugin apps call
 * `app.use(ctx.vuetify)` and share this exact instance + theme.
 */
export const vuetify = createVuetify({
  blueprint: md3,
  components,
  directives,
  icons: {
    defaultSet: 'mdi',
    aliases,
    sets: { mdi },
  },
  theme: {
    defaultTheme: 'dark',
    themes: {
      dark: { name: 'dark', ...md3Dark },
      light: { name: 'light', ...md3Light },
    },
  },
})
```

- [ ] **Step 3: Register in main.ts**

Modify `frontend/src/main.ts`. After the `import './theme/tokens.css'` line add:
```ts
import { vuetify } from './plugins/vuetify'
```

Then after `app.use(i18n)` add:
```ts
app.use(vuetify)
```

The relevant section becomes:
```ts
const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(i18n)
app.use(vuetify)
```

- [ ] **Step 4: Verify build**

Run:
```bash
cd frontend && npm run build 2>&1 | tail -20
```
Expected: build succeeds (exits 0). Vuetify is registered but not yet rendered (no `<v-app>` until Task 3), which is fine — this task only verifies the instance compiles and imports resolve.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/plugins/md3-themes.ts frontend/src/plugins/vuetify.ts frontend/src/main.ts
git commit -m "✨ feat(frontend): wire MD3 Vuetify instance + Google baseline palette"
```

---

## Task 3: Drive Vuetify theme from the store + `<v-app>` wrapper

**Files:**
- Modify: `frontend/src/stores/theme.ts`
- Modify: `frontend/src/App.vue`
- Modify: `frontend/index.html`

**Interfaces:**
- Consumes: `vuetify` singleton from `plugins/vuetify.ts`.
- Produces: `useThemeStore.setTheme(next)` now flips `vuetify.theme.global.name.value`; `App.vue` wraps in `<v-app>`. Later view tasks render inside `<v-app>` and inherit MD3 theme.

- [ ] **Step 1: Rewrite the theme store**

Replace the full contents of `frontend/src/stores/theme.ts`:
```ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ThemeName } from '@/api/types'
import { vuetify } from '@/plugins/vuetify'

const listeners = new Set<(t: ThemeName) => void>()

/**
 * Drive Vuetify's global theme singleton directly. We do NOT use the
 * useTheme() composable here because this store action runs in main.ts
 * (outside any component/setup context); useTheme() relies on inject().
 * The `vuetify` export is the same singleton main.ts registered.
 */
function applyVuetifyTheme(theme: ThemeName) {
  vuetify.theme.global.name.value = theme
}

export const useThemeStore = defineStore('theme', () => {
  const theme = ref<ThemeName>('dark')

  function setTheme(next: ThemeName) {
    theme.value = next
    applyVuetifyTheme(next)
    listeners.forEach((cb) => cb(next))
  }

  function toggle() {
    setTheme(theme.value === 'dark' ? 'light' : 'dark')
  }

  /** Subscribe to theme changes (used by the MF plugin host ctx). */
  function onChange(cb: (t: ThemeName) => void): () => void {
    listeners.add(cb)
    return () => listeners.delete(cb)
  }

  return { theme, setTheme, toggle, onChange }
})
```

- [ ] **Step 2: Wrap App.vue in `<v-app>`**

Replace the full contents of `frontend/src/App.vue`:
```vue
<script setup lang="ts">
import { useRoute } from 'vue-router'
import AppShell from './shell/AppShell.vue'

const route = useRoute()
</script>

<template>
  <!-- v-app is required by Vuetify for theme/layout (data-v-app). -->
  <v-app>
    <!-- Setup wizard renders full-screen without the app shell -->
    <router-view v-if="route.name === 'setup'" />
    <AppShell v-else />
  </v-app>
</template>
```

(Removed: the `useThemeStore` import, the `watchEffect` that toggled `.theme-dark`/`.theme-light` on `<html>`. Vuetify now owns the theme via `v-app`.)

- [ ] **Step 3: Remove the hardcoded theme class from index.html**

In `frontend/index.html`, change the `<html>` opening tag:
Old:
```html
<html lang="en" class="theme-dark">
```
New:
```html
<html lang="en">
```

- [ ] **Step 4: Verify dev server renders with Vuetify root**

Run:
```bash
cd frontend && npm run dev -- --port 5181 &
sleep 6
curl -s http://localhost:5181/ | grep -q 'data-v-app\|<div id="app"' && echo "RENDER_OK" || echo "RENDER_FAIL"
# Open in browser mentally: the page should load without console errors about v-app.
kill %1 2>/dev/null
```
Expected: `RENDER_OK`. The shell still uses old `.sk-*` layout (AppShell not yet migrated — Task 4) but the app boots inside `<v-app>`.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/stores/theme.ts frontend/src/App.vue frontend/index.html
git commit -m "✨ feat(frontend): drive MD3 theme from store + wrap app in <v-app>"
```

---

## Task 4: Migrate AppShell + Sidebar + StatusBar (shell foundation)

**Files:**
- Modify: `frontend/src/shell/AppShell.vue`
- Modify: `frontend/src/shell/Sidebar.vue`
- Modify: `frontend/src/shell/StatusBar.vue`

**Interfaces:**
- Consumes: `useSettingsStore.sidebarCollapsed` / `setSidebarCollapsed`, `useNavStore`, `useCategoriesStore`, `useThemeStore.toggle`, `usePluginsStore`.
- Produces: a Vuetify-based shell (`<v-app>` is in App.vue; here `<v-main>`, `<v-navigation-drawer>`, `<v-system-bar>`). Views render inside `<v-main>`.

- [ ] **Step 1: Rewrite AppShell.vue**

Replace the full contents of `frontend/src/shell/AppShell.vue`:
```vue
<script setup lang="ts">
import Sidebar from './Sidebar.vue'
import StatusBar from './StatusBar.vue'
</script>

<template>
  <Sidebar />
  <v-main>
    <router-view v-slot="{ Component }">
      <component :is="Component" />
    </router-view>
  </v-main>
  <StatusBar />
</template>
```

(Removed: the `<style scoped>` grid + `--sk-bg` usage. `<v-main>` handles the content area; the drawer and system-bar are direct siblings inside `<v-app>`.)

- [ ] **Step 2: Rewrite Sidebar.vue**

Replace the full contents of `frontend/src/shell/Sidebar.vue`:
```vue
<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import { useThemeStore } from '@/stores/theme'
import { useNavStore } from '@/stores/nav'
import { useCategoriesStore } from '@/stores/categories'

const settings = useSettingsStore()
const theme = useThemeStore()
const nav = useNavStore()
const cats = useCategoriesStore()
const router = useRouter()

const rail = computed(() => settings.sidebarCollapsed)

interface NavItem {
  key: string
  labelKey: string
  icon: string
}
const navItems = computed<NavItem[]>(() => [
  { key: 'all', labelKey: 'sidebar.all', icon: 'mdi-view-grid' },
  ...cats.categories.map((c) => ({ key: c.id, labelKey: c.labelKey, icon: c.icon || 'mdi-folder' })),
  { key: 'favorites', labelKey: 'sidebar.favorites', icon: 'mdi-star' },
])

onMounted(() => {
  if (!cats.loaded) void cats.load()
})

function pickCategory(key: string) {
  nav.setCategory(key)
  router.push('/')
}
</script>

<template>
  <v-navigation-drawer :rail="rail" permanent width="220" rail-width="64">
    <div class="d-flex align-center px-3 py-3">
      <v-avatar color="primary" size="32" rounded="lg">
        <span class="font-weight-bold text-body-2">ZF</span>
      </v-avatar>
      <span v-if="!rail" class="ml-3 font-weight-medium">ZhiFlow</span>
      <v-spacer />
      <v-btn
        :icon="rail ? 'mdi-chevron-right' : 'mdi-chevron-left'"
        size="small"
        variant="text"
        :title="rail ? $t('sidebar.expand') : $t('sidebar.collapse')"
        @click="settings.setSidebarCollapsed(!rail)"
      />
    </div>

    <v-list density="compact" nav>
      <v-list-subheader v-if="!rail">{{ $t('sidebar.categories') }}</v-list-subheader>
      <v-list-item
        v-for="item in navItems"
        :key="item.key"
        :active="nav.category === item.key"
        :prepend-icon="item.icon"
        :title="$t(item.labelKey)"
        :value="item.key"
        @click="pickCategory(item.key)"
      />
    </v-list>

    <template #append>
      <v-list density="compact" nav>
        <v-list-item prepend-icon="mdi-chat-outline" :title="$t('sidebar.aiChat')" @click="router.push('/ai')" />
        <v-list-item prepend-icon="mdi-robot-outline" :title="$t('sidebar.agent')" @click="router.push('/agent')" />
        <v-list-item prepend-icon="mdi-cog-outline" :title="$t('sidebar.settings')" @click="router.push('/settings')" />
        <v-list-item
          :prepend-icon="theme.theme === 'dark' ? 'mdi-weather-night' : 'mdi-weather-sunny'"
          :title="$t('sidebar.theme')"
          @click="theme.toggle()"
        />
      </v-list>
    </template>
  </v-navigation-drawer>
</template>
```

(Note: backend category icons `c.icon` may be a `mdi-*` name or a legacy glyph. The `|| 'mdi-folder'` fallback handles missing/empty values. If a category icon is a non-`mdi-*` legacy glyph, it will simply not render an icon — acceptable; a follow-up can normalize. This keeps the migration unblocked.)

- [ ] **Step 3: Rewrite StatusBar.vue**

Replace the full contents of `frontend/src/shell/StatusBar.vue`:
```vue
<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { api } from '@/api/client'

type ConnState = 'connecting' | 'connected' | 'reconnecting'

const state = ref<ConnState>('connecting')
let timer: number | undefined

async function poll() {
  try {
    const r = await api.health()
    state.value = r.status === 'ok' ? 'connected' : 'reconnecting'
  } catch {
    state.value = 'reconnecting'
  }
}

onMounted(() => {
  void poll()
  timer = window.setInterval(poll, 5000)
})
onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})

const statusKey: Record<ConnState, string> = {
  connecting: 'status.connecting',
  connected: 'status.connected',
  reconnecting: 'status.reconnecting',
}

const chipColor: Record<ConnState, string> = {
  connecting: 'default',
  connected: 'success',
  reconnecting: 'warning',
}
</script>

<template>
  <v-system-bar>
    <v-chip :color="chipColor[state]" size="x-small" variant="flat">
      {{ $t(statusKey[state]) }}
    </v-chip>
    <v-spacer />
    <span class="text-medium-emphasis text-caption">ZhiFlow 4.0.0</span>
  </v-system-bar>
</template>
```

- [ ] **Step 4: Verify dev server renders the shell**

Run:
```bash
cd frontend && npm run dev -- --port 5182 &
sleep 6
curl -s http://localhost:5182/ | grep -q "id=\"app\"" && echo "SHELL_OK" || echo "SHELL_FAIL"
kill %1 2>/dev/null
```
Expected: `SHELL_OK`. Open in browser: the MD3 drawer (collapsible rail), main content area (views still `.sk-*` styled until later tasks), and a system-bar at the bottom. No console errors about missing Vuetify components.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/shell/AppShell.vue frontend/src/shell/Sidebar.vue frontend/src/shell/StatusBar.vue
git commit -m "✨ feat(frontend): migrate shell to MD3 (v-navigation-drawer/v-main/v-system-bar)"
```

---

## Task 5: Migrate Settings.vue

**Files:**
- Modify: `frontend/src/views/Settings.vue`

**Interfaces:**
- Consumes: `useSettingsStore` (`theme`, `setTheme`, `language`, `setLanguage`, `load`, `loaded`), i18n keys `settings.*`, `LanguageName`, `ThemeName`.
- Produces: the first migrated view; establishes the pattern (Vuetify list + select + switch) for subsequent view tasks.

- [ ] **Step 1: Rewrite Settings.vue**

Replace the full contents of `frontend/src/views/Settings.vue`:
```vue
<script setup lang="ts">
import { onMounted } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import type { LanguageName, ThemeName } from '@/api/types'

const settings = useSettingsStore()

onMounted(() => {
  if (!settings.loaded) void settings.load().catch(() => {})
})

const themes: ThemeName[] = ['dark', 'light']
const languages: LanguageName[] = ['en', 'zh']
</script>

<template>
  <v-container max-width="640">
    <h1 class="text-h5 mb-4">{{ $t('settings.title') }}</h1>

    <v-list lines="two">
      <v-list-item>
        <template #prepend><v-icon icon="mdi-palette-outline" /></template>
        <v-list-item-title>{{ $t('settings.theme') }}</v-list-item-title>
        <template #append>
          <v-select
            :model-value="settings.theme"
            :items="themes"
            density="compact"
            variant="outlined"
            hide-details
            style="max-width: 160px"
            @update:model-value="(v: ThemeName) => settings.setTheme(v)"
          />
        </template>
      </v-list-item>

      <v-list-item>
        <template #prepend><v-icon icon="mdi-translate" /></template>
        <v-list-item-title>{{ $t('settings.language') }}</v-list-item-title>
        <template #append>
          <v-select
            :model-value="settings.language"
            :items="languages"
            density="compact"
            variant="outlined"
            hide-details
            style="max-width: 160px"
            @update:model-value="(v: LanguageName) => settings.setLanguage(v)"
          />
        </template>
      </v-list-item>
    </v-list>
  </v-container>
</template>
```

(Removed: all `<style scoped>`, the `.sk-combo`, `var(--sk-*)` usages, the manual `onTheme`/`onLanguage` event handlers — `v-select` uses `@update:model-value` instead.)

- [ ] **Step 2: Verify build + route**

Run:
```bash
cd frontend && npm run build 2>&1 | tail -5
```
Expected: build succeeds (exits 0).

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/Settings.vue
git commit -m "✨ feat(frontend): migrate Settings view to MD3 (v-list + v-select)"
```

---

## Task 6: Migrate ToolGrid.vue

**Files:**
- Modify: `frontend/src/views/ToolGrid.vue`

**Interfaces:**
- Consumes: `usePluginsStore` (`plugins`, `loading`, `error`, `favorites`, `toggleFavorite`), `useNavStore.category`, types `PluginDescriptor`, `PluginSource`, i18n keys `grid.*`, `source.*`, `badge.ai`.

- [ ] **Step 1: Rewrite ToolGrid.vue**

Replace the full contents of `frontend/src/views/ToolGrid.vue`:
```vue
<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { usePluginsStore } from '@/stores/plugins'
import { useNavStore } from '@/stores/nav'
import type { PluginDescriptor, PluginSource } from '@/api/types'

const store = usePluginsStore()
const nav = useNavStore()
const router = useRouter()

onMounted(() => {
  if (store.plugins.length === 0) void store.load()
})

const filtered = computed<PluginDescriptor[]>(() => {
  const cat = nav.category
  if (cat === 'all') return store.plugins
  if (cat === 'favorites') return store.plugins.filter((p) => store.favorites.has(p.id))
  return store.plugins.filter((p) => p.category.toLowerCase() === cat)
})

function open(p: PluginDescriptor) {
  void router.push(`/plugin/${encodeURIComponent(p.id)}`)
}

function initials(name: string): string {
  return name.trim().charAt(0).toUpperCase() || '?'
}

function sourceLabelKey(source: PluginSource): string {
  return source === 'OFFICIAL' ? 'source.official' : 'source.third_party'
}
</script>

<template>
  <v-container>
    <h1 class="text-h5 mb-4">{{ $t('grid.title') }}</h1>

    <div v-if="store.loading" class="text-medium-emphasis">{{ $t('grid.loading') }}</div>
    <v-alert v-else-if="store.error" type="error" variant="tonal" class="mb-4">{{ store.error }}</v-alert>
    <div v-else-if="filtered.length === 0" class="text-medium-emphasis">{{ $t('grid.empty') }}</div>

    <v-row>
      <v-col v-for="p in filtered" :key="p.id" cols="12" sm="6" md="4" lg="3">
        <v-card variant="tonal" rounded="lg" class="h-100" @click="open(p)">
          <v-card-item>
            <template #prepend>
              <v-avatar color="primary" rounded="md" size="40">
                <span class="font-weight-bold">{{ initials(p.name) }}</span>
              </v-avatar>
            </template>
            <v-card-title class="text-body-1 d-flex align-center flex-wrap ga-2">
              {{ p.name }}
              <v-chip
                size="x-small"
                :color="p.source === 'OFFICIAL' ? 'primary' : 'default'"
                variant="outlined"
              >{{ $t(sourceLabelKey(p.source)) }}</v-chip>
              <v-chip v-if="p.supportsAi" size="x-small" color="success" variant="outlined">
                {{ $t('badge.ai') }}
              </v-chip>
            </v-card-title>
            <v-card-subtitle class="text-wrap">{{ p.description }}</v-card-subtitle>
          </v-card-item>

          <v-card-actions>
            <v-spacer />
            <v-btn
              :icon="store.favorites.has(p.id) ? 'mdi-star' : 'mdi-star-outline'"
              :color="store.favorites.has(p.id) ? 'warning' : 'default'"
              variant="text"
              size="small"
              :title="$t('grid.toggleFavorite')"
              @click.stop="store.toggleFavorite(p.id)"
            />
          </v-card-actions>
        </v-card>
      </v-col>
    </v-row>
  </v-container>
</template>
```

(Removed: all `<style scoped>` and `.sk-*`/`--sk-*` usages; the chip/badge classes replaced by `<v-chip>`.)

- [ ] **Step 2: Verify build**

Run:
```bash
cd frontend && npm run build 2>&1 | tail -5
```
Expected: build succeeds (exits 0).

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/ToolGrid.vue
git commit -m "✨ feat(frontend): migrate ToolGrid view to MD3 (v-card grid)"
```

---

## Task 7: Migrate AiChat.vue

**Files:**
- Modify: `frontend/src/views/AiChat.vue`

**Interfaces:**
- Consumes: `useAiSessionStore` (`turns`, `busy`, `error`, `send`, `stop`, `clear`), each turn `{ id, role, content, thinking, streaming }`, `marked`, i18n keys `aichat.*`.

- [ ] **Step 1: Rewrite AiChat.vue**

Replace the full contents of `frontend/src/views/AiChat.vue`:
```vue
<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { marked } from 'marked'
import { useI18n } from 'vue-i18n'
import { useAiSessionStore } from '@/stores/aiSession'

const { t } = useI18n()
const ai = useAiSessionStore()
const draft = ref('')
// Plain div ref (NOT a Vuetify component ref) so scroll logic stays simple
// — component-instance $el indirection is unreliable. Same pattern as the
// original hand-written version.
const scroller = ref<HTMLElement | null>(null)

marked.setOptions({ breaks: true, gfm: true })

function md(src: string): string {
  return marked.parse(src) as string
}

function submit() {
  const text = draft.value
  if (!text.trim() || ai.busy) return
  draft.value = ''
  void ai.send(text)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    submit()
  }
}

const hasError = computed(() => ai.error !== null)

watch(
  () => ai.turns.map((turn) => turn.content + turn.thinking).join('|'),
  async () => {
    await nextTick()
    const el = scroller.value
    if (el) el.scrollTop = el.scrollHeight
  },
)
</script>

<template>
  <div class="d-flex flex-column h-100 pa-4">
    <div class="d-flex align-center justify-space-between mb-2">
      <h1 class="text-h5">{{ $t('aichat.title') }}</h1>
      <v-btn variant="outlined" prepend-icon="mdi-broom" @click="ai.clear()">
        {{ $t('aichat.clear') }}
      </v-btn>
    </div>

    <v-alert
      v-if="hasError"
      type="error"
      variant="tonal"
      class="mb-2"
      closable
      @click:close="ai.error = null"
    >{{ ai.error }}</v-alert>

    <div ref="scroller" class="flex-grow-1 overflow-y-auto d-flex flex-column ga-4 pa-2">
      <div v-if="ai.turns.length === 0" class="text-medium-emphasis text-center mt-10">
        {{ $t('aichat.empty') }}
      </div>

      <div
        v-for="turn in ai.turns"
        :key="turn.id"
        class="d-flex flex-column ga-1"
        :class="turn.role === 'user' ? 'align-self-end align-end' : 'align-self-start align-start'"
        style="max-width: 80%"
      >
        <div class="text-caption text-medium-emphasis">
          {{ turn.role === 'user' ? t('aichat.you') : t('aichat.assistant') }}
        </div>

        <v-expansion-panels v-if="turn.thinking" variant="accordion">
          <v-expansion-panel>
            <v-expansion-panel-title class="text-caption">{{ $t('aichat.thinking') }}</v-expansion-panel-title>
            <v-expansion-panel-text>
              <div v-html="md(turn.thinking)" />
            </v-expansion-panel-text>
          </v-expansion-panel>
        </v-expansion-panels>

        <v-card
          v-if="turn.role === 'assistant'"
          variant="tonal"
          rounded="lg"
          class="pa-3"
        >
          <div v-html="md(turn.content)" />
        </v-card>
        <v-card v-else color="primary" variant="tonal" rounded="lg" class="pa-3">
          <div class="text-body-2" style="white-space: pre-wrap">{{ turn.content }}</div>
        </v-card>

        <div v-if="turn.streaming && !turn.content" class="text-medium-emphasis">…</div>
      </div>
    </div>

    <div class="d-flex ga-2 align-end mt-2">
      <v-textarea
        v-model="draft"
        :placeholder="$t('aichat.placeholder')"
        auto-grow
        rows="2"
        variant="outlined"
        hide-details
        class="flex-grow-1"
        @keydown="onKeydown"
      />
      <v-btn
        v-if="ai.busy"
        variant="outlined"
        prepend-icon="mdi-stop"
        @click="ai.stop()"
      >{{ $t('aichat.stop') }}</v-btn>
      <v-btn
        v-else
        color="primary"
        prepend-icon="mdi-send"
        :disabled="!draft.trim()"
        @click="submit"
      >{{ $t('aichat.send') }}</v-btn>
    </div>
  </div>
</template>
```

(Removed: all `<style scoped>`, the `.bubble`/`.thinking`/`.banner`/`--sk-*` classes; the native `<details>` replaced by `<v-expansion-panels>`; `<textarea>` by `<v-textarea>`.)

- [ ] **Step 2: Verify build**

Run:
```bash
cd frontend && npm run build 2>&1 | tail -5
```
Expected: build succeeds (exits 0).

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/AiChat.vue
git commit -m "✨ feat(frontend): migrate AiChat view to MD3 (v-textarea + message cards)"
```

---

## Task 8: Migrate PluginView.vue + wire `ctx.vuetify` into the MF

**Files:**
- Modify: `frontend/src/mf/loader.ts`
- Modify: `frontend/src/views/PluginView.vue`
- Modify: `plugin-markdown/ui-src/src/main.ts`

**Interfaces:**
- Consumes: `vuetify` singleton from `plugins/vuetify.ts`, `loadPlugin`/`PluginContext` from `mf/loader.ts`.
- Produces: `PluginContext.vuetify: Plugin` — the contract addition that plugin MFs consume. Plugin `mount()` now calls `app.use(ctx.vuetify)`.

- [ ] **Step 1: Extend PluginContext with `vuetify`**

Modify `frontend/src/mf/loader.ts`. Add the type import and the field. Replace the import block and interface:

Old:
```ts
import { backendUrl } from '@/api/config'
```
New:
```ts
import type { Plugin } from 'vue'
import { backendUrl } from '@/api/config'
```

Old (inside `PluginContext`):
```ts
  notify: (msg: string) => void
}
```
New:
```ts
  notify: (msg: string) => void
  /**
   * The host's Vuetify (MD3) app-plugin instance. Plugins MUST call
   * `app.use(ctx.vuetify)` in mount() so they share the same MD3 theme +
   * components as the shell, without bundling Vuetify themselves.
   */
  vuetify: Plugin
}
```

- [ ] **Step 2: Pass `vuetify` into the context in PluginView.vue + migrate the view**

Replace the full contents of `frontend/src/views/PluginView.vue`:
```vue
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { usePluginsStore } from '@/stores/plugins'
import { useThemeStore } from '@/stores/theme'
import { api } from '@/api/client'
import { i18n } from '@/i18n'
import { loadPlugin, type PluginContext } from '@/mf/loader'
import { vuetify } from '@/plugins/vuetify'

const props = defineProps<{ id: string }>()

const { t } = useI18n()
const plugins = usePluginsStore()
const theme = useThemeStore()
const router = useRouter()

const host = ref<HTMLElement | null>(null)
const error = ref<string | null>(null)
const loading = ref(false)
let unmount: (() => void) | null = null

function teardown() {
  if (unmount) {
    try {
      unmount()
    } catch {
      /* ignore plugin teardown errors */
    }
    unmount = null
  }
  if (host.value) host.value.innerHTML = ''
}

async function mountPlugin() {
  teardown()
  error.value = null

  const descriptor = plugins.byId(props.id)
  if (!descriptor) {
    error.value = t('plugin.unknown', { id: props.id })
    return
  }
  const el = host.value
  if (!el) return

  loading.value = true
  try {
    const mod = await loadPlugin(descriptor.uiEntry)
    const ctx: PluginContext = {
      api: {
        invoke: (action, args = {}) => api.pluginInvoke(descriptor.id, action, args),
      },
      theme: theme.theme,
      onThemeChange: (cb) => theme.onChange(cb),
      locale: i18n.global.locale.value,
      t: (key: string) => i18n.global.t(key),
      onLocaleChange: (cb: (locale: string) => void) => {
        const unwatch = watch(() => i18n.global.locale.value, (l) => cb(l as string))
        return unwatch
      },
      notify: (msg) => console.info(`[${descriptor.name}]`, msg),
      vuetify, // shared MD3 instance — plugins call app.use(ctx.vuetify)
    }
    unmount = mod.mount(el, ctx)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  if (plugins.plugins.length === 0) await plugins.load()
  await mountPlugin()
})

watch(
  () => props.id,
  () => {
    void mountPlugin()
  },
)

onBeforeUnmount(teardown)
</script>

<template>
  <div class="d-flex flex-column h-100">
    <div class="d-flex align-center ga-3 px-4 py-2 border-b">
      <v-btn variant="text" prepend-icon="mdi-arrow-left" @click="router.push('/')">
        {{ $t('common.back') }}
      </v-btn>
      <span class="font-weight-medium">
        {{ plugins.byId(props.id)?.name ?? props.id }}
      </span>
    </div>

    <v-alert
      v-if="error"
      type="error"
      variant="tonal"
      class="ma-4"
      :title="$t('plugin.failedTitle')"
    >
      <div class="text-body-2" style="font-family: monospace; overflow-wrap: anywhere">{{ error }}</div>
      <template #append>
        <v-btn color="error" variant="outlined" @click="mountPlugin()">{{ $t('common.retry') }}</v-btn>
      </template>
    </v-alert>

    <div v-show="!error" ref="host" class="flex-grow-1 overflow-auto" />
    <div v-if="loading" class="d-flex justify-center pa-6">
      <v-progress-circular indeterminate color="primary" />
    </div>
  </div>
</template>
```

- [ ] **Step 3: Plugin mount() uses ctx.vuetify**

Modify `plugin-markdown/ui-src/src/main.ts`. Add the one line inside `mount`:

Old:
```ts
    const app = createApp(MarkdownEditor);
    // Hand the host context to the component tree.
    app.provide('pluginCtx', ctx);
    app.mount(el);
```
New:
```ts
    const app = createApp(MarkdownEditor);
    // Hand the host context to the component tree.
    app.provide('pluginCtx', ctx);
    // Register the host's shared MD3 Vuetify instance (no bundling).
    app.use(ctx.vuetify);
    app.mount(el);
```

- [ ] **Step 4: Verify both builds**

Run:
```bash
cd frontend && npm run build 2>&1 | tail -5
cd ../plugin-markdown/ui-src && npm run build 2>&1 | tail -5
```
Expected: both builds succeed (exit 0).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/mf/loader.ts frontend/src/views/PluginView.vue plugin-markdown/ui-src/src/main.ts
git commit -m "✨ feat(mf): share MD3 Vuetify via PluginContext; migrate PluginView"
```

---

## Task 9: Migrate SetupWizard.vue

**Files:**
- Modify: `frontend/src/views/SetupWizard.vue`

**Interfaces:**
- Consumes: `useSetupStore` (`types`, `selectedType`, `selectType`, `params`, `testing`, `testResult`, `testConnection`, `initialize`, `loadTypes`), `api.health`/`api.getSetupStatus`, router.

- [ ] **Step 1: Rewrite SetupWizard.vue**

Replace the full contents of `frontend/src/views/SetupWizard.vue`:
```vue
<script setup lang="ts">
import { onMounted, computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSetupStore } from '@/stores/setup'
import { api } from '@/api/client'

const router = useRouter()
const setup = useSetupStore()

const step = ref<1 | 2 | 3>(1)
const restartMessage = ref('')
const restartFailed = ref(false)

const selectedMeta = computed(
  () => setup.types.find((t) => t.type === setup.selectedType) ?? null,
)
const canInitialize = computed(() => setup.testResult?.success === true)

onMounted(async () => {
  await setup.loadTypes()
  const h2 = setup.types.find((t) => t.type === 'h2')
  if (h2) setup.selectType('h2')
})

function chooseType(t: string) {
  setup.selectType(t)
  step.value = 2
}

function backToSelect() {
  step.value = 1
}

async function onTest() {
  await setup.testConnection()
}

async function onInitialize() {
  const ok = await setup.initialize()
  if (!ok) return
  step.value = 3
  restartMessage.value = 'Configuration complete. Restarting backend…'
  await waitForRestart()
}

async function waitForRestart() {
  const deadline = Date.now() + 30_000
  let back = false
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 500))
    try {
      await api.health()
      const status = await api.getSetupStatus()
      if (status.initialized) {
        back = true
        break
      }
    } catch {
      // Backend still down — keep polling.
    }
  }
  if (back) {
    router.replace('/')
  } else {
    restartFailed.value = true
    restartMessage.value = 'Restart timed out. Please manually restart the application.'
  }
}
</script>

<template>
  <div class="d-flex align-center justify-center h-100 pa-6">
    <v-card max-width="560" width="100%" rounded="lg" class="pa-6">
      <v-card-title class="text-h5">ZhiFlow Setup</v-card-title>
      <v-card-subtitle class="mb-4">Choose how to store your data.</v-card-subtitle>

      <!-- Step 1: choose type -->
      <div v-if="step === 1">
        <v-row>
          <v-col v-for="t in setup.types" :key="t.type" cols="12" sm="6">
            <v-card
              variant="outlined"
              rounded="lg"
              class="pa-4 h-100"
              :color="setup.selectedType === t.type ? 'primary' : undefined"
              :class="{ 'border-primary': setup.selectedType === t.type }"
              @click="chooseType(t.type)"
            >
              <div class="text-body-1 font-weight-bold">{{ t.label }}</div>
              <div class="text-caption text-uppercase text-medium-emphasis">
                {{ t.embedded ? 'local' : 'remote' }}
              </div>
            </v-card>
          </v-col>
        </v-row>
      </div>

      <!-- Step 2: configure + test -->
      <div v-else-if="step === 2">
        <v-btn variant="text" prepend-icon="mdi-arrow-left" size="small" @click="backToSelect">
          Back
        </v-btn>
        <h2 class="text-h6 mt-2 mb-4">{{ selectedMeta?.label }} configuration</h2>

        <div v-for="f in selectedMeta?.fields ?? []" :key="f.name" class="mb-3">
          <v-text-field
            :label="f.label ?? f.name"
            :type="f.secret ? 'password' : 'text'"
            :placeholder="f.name"
            variant="outlined"
            density="compact"
            hide-details
            :model-value="(setup.params as Record<string, unknown>)[f.name] as string"
            @update:model-value="(v: string) => ((setup.params as Record<string, unknown>)[f.name] = v)"
          />
        </div>

        <div class="d-flex align-center ga-3 my-4">
          <v-btn variant="tonal" :loading="setup.testing" @click="onTest">
            Test connection
          </v-btn>
          <span
            v-if="setup.testResult"
            :class="setup.testResult.success ? 'text-success' : 'text-error'"
            class="text-body-2"
          >
            <v-icon size="small" :icon="setup.testResult.success ? 'mdi-check' : 'mdi-close'" />
            {{ setup.testResult.success
              ? `Connected (${setup.testResult.serverVersion})`
              : setup.testResult.error }}
          </span>
        </div>

        <v-btn color="primary" :disabled="!canInitialize" @click="onInitialize">
          Initialize
        </v-btn>
      </div>

      <!-- Step 3: restart overlay -->
      <div v-else class="text-center pa-8">
        <v-progress-circular indeterminate color="primary" size="40" class="mb-4" />
        <p class="text-body-1">{{ restartMessage }}</p>
        <v-alert v-if="restartFailed" type="warning" variant="tonal" class="mt-3">
          {{ restartMessage }}
        </v-alert>
      </div>
    </v-card>
  </div>
</template>
```

(Removed: all `<style scoped>`, the `.sk-input`/`.sk-btn`/`.type-card`/`--sk-*` usages, the custom `.spinner` keyframe replaced by `<v-progress-circular>`.)

- [ ] **Step 2: Verify build**

Run:
```bash
cd frontend && npm run build 2>&1 | tail -5
```
Expected: build succeeds (exits 0).

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/SetupWizard.vue
git commit -m "✨ feat(frontend): migrate SetupWizard view to MD3 (v-card + v-text-field)"
```

---

## Task 10: Migrate AiAgent.vue

**Files:**
- Modify: `frontend/src/views/AiAgent.vue` (498 lines — the largest view)

**Interfaces:**
- Consumes: whatever the current `AiAgent.vue` store/composables are (read the file before editing to capture exact imports/state). This is a faithful MD3 re-skin: preserve all script logic (state, functions, store calls) verbatim; replace only `<template>` + remove `<style scoped>` `--sk-*` rules.

> **Implementer note:** Read `frontend/src/views/AiAgent.vue` in full before starting. This task is intentionally less prescriptive at the line level because the file is large and its exact internal state may have drifted. The rule: **keep the `<script setup>` block unchanged** (imports, refs, functions, store calls), and rewrite `<template>` to MD3 components following the same patterns as Tasks 4–9 (`v-container`, `v-row`/`v-col`, `v-card`, `v-timeline` or `v-list` for steps, `v-expansion-panels` for tool calls, `v-btn`, `v-text-field`, `v-app-bar`). Remove all `.sk-*` classes and `var(--sk-*)` references; delete any `<style scoped>` rules that only set `--sk-*` colors.

- [ ] **Step 1: Read the current file**

Run: read `frontend/src/views/AiAgent.vue` (all 498 lines). Catalogue:
- every store/composable import,
- every reactive ref/computed,
- every function,
- every `--sk-*` / `.sk-*` usage in `<style>` (these get removed),
- every i18n key used.

- [ ] **Step 2: Rewrite the `<template>` to MD3**

Apply this component mapping to the existing template, preserving all bindings (`v-for`, `v-if`, `@click`, `:value`, i18n `$t(...)` calls exactly):

| Existing construct | MD3 component |
|---|---|
| Outer page wrapper `<div class="...-page">` | `<v-container fluid class="d-flex flex-column h-100">` |
| Top bar / header with title + actions | `<v-app-bar density="compact" flat>` + `<v-app-bar-title>` + `<v-spacer>` + `<v-btn>` actions |
| Plan/steps list | `<v-timeline>` (one `<v-timeline-item>` per step, `:dot="..."` for status color) OR `<v-list>` with `<v-list-item>` if a vertical timeline doesn't fit the existing layout |
| Step detail panel | `<v-card variant="tonal" rounded="lg">` with `<v-card-title>`/`<v-card-text>` |
| Tool calls (collapsible) | `<v-expansion-panels variant="accordion">` + `<v-expansion-panel>` |
| Input/composer | `<v-text-field variant="outlined" append-inner-icon="mdi-send">` or `<v-textarea>` + `<v-btn color="primary">` |
| Loading indicator | `<v-progress-circular indeterminate>` |
| Error banner | `<v-alert type="error" variant="tonal" closable>` |
| Status chips/badges | `<v-chip size="x-small" :color="...">` |

Keep every `@click`/`:disabled`/`v-model` bound to the existing script functions/refs — do NOT rename or move any logic.

- [ ] **Step 3: Remove `--sk-*` styles**

Delete the entire `<style scoped>` block if it contains only `.sk-*`/`--sk-*` rules. If any rules are pure layout (e.g., `display: grid` for a split pane), keep those few rules but replace `var(--sk-*)` color references with Vuetify utility classes (`text-medium-emphasis`, `bg-surface`, etc.) or remove them (Vuetify handles color). After editing, grep to confirm zero `sk-` references remain in the file.

- [ ] **Step 4: Verify no sk- leakage**

Run:
```bash
cd frontend && grep -nE 'sk-|--sk-' src/views/AiAgent.vue || echo "CLEAN"
```
Expected: `CLEAN`.

- [ ] **Step 5: Verify build**

Run:
```bash
cd frontend && npm run build 2>&1 | tail -5
```
Expected: build succeeds (exits 0).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/AiAgent.vue
git commit -m "✨ feat(frontend): migrate AiAgent view to MD3"
```

---

## Task 11: Migrate plugin MF demo + delete `--sk-*` tokens

**Files:**
- Modify: `plugin-markdown/ui-src/src/MarkdownEditor.vue`
- Modify: `frontend/src/theme/tokens.css`
- Delete: (none — `tokens.css` stays as a file but the `--sk-*` blocks are removed; if the file becomes empty of rules, delete the file and its `import` in `main.ts`)

**Interfaces:**
- Consumes: `ctx.vuetify` (Task 8) registered on the plugin app.

- [ ] **Step 1: Migrate MarkdownEditor.vue to MD3**

Read `plugin-markdown/ui-src/src/MarkdownEditor.vue`. Replace any `.sk-*`/`--sk-*` markup with MD3 components. Minimal faithful migration (adapt to the component's actual elements):

```vue
<script setup lang="ts">
import { inject, ref } from 'vue'
const ctx = inject<{ t: (k: string) => string; theme: string }>('pluginCtx')!
// ...keep existing script logic verbatim
</script>

<template>
  <v-card variant="outlined" rounded="lg" class="ma-2">
    <v-card-item>
      <v-card-title>Markdown</v-card-title>
      <v-card-text>
        <!-- existing editor element, now styled with Vuetify utilities -->
      </v-card-text>
    </v-card-item>
  </v-card>
</template>
```

(Keep all existing editor logic; only swap the wrapper/styling. If the component uses a `<textarea>` or third-party editor, leave that element intact and wrap it in `<v-card>`.)

- [ ] **Step 2: Verify plugin build**

Run:
```bash
cd plugin-markdown/ui-src && npm run build 2>&1 | tail -5
```
Expected: build succeeds (exit 0).

- [ ] **Step 3: Delete `--sk-*` token blocks**

In `frontend/src/theme/tokens.css`, remove the `.theme-dark { ... }` and `.theme-light { ... }` blocks (lines defining `--sk-*`). Keep the base resets (`* { box-sizing }`, `html, body, #app { ... }`) only if they're still needed and don't reference `--sk-*`. If the entire file's only purpose was tokens, delete the file and remove its import from `main.ts` (`import './theme/tokens.css'`).

Decide at edit time: if after removing `--sk-*` the file still has base resets, keep it; else delete it + the import.

- [ ] **Step 4: Grep the whole frontend for any remaining sk- references**

Run:
```bash
cd frontend && grep -rnE '\.sk-|--sk-' src/ || echo "ALL_CLEAN"
```
Expected: `ALL_CLEAN` (no `.sk-*` classes, no `--sk-*` var references anywhere under `frontend/src/`).

- [ ] **Step 5: Verify full build**

Run:
```bash
cd frontend && npm run build 2>&1 | tail -5
```
Expected: build succeeds (exit 0).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/theme/tokens.css frontend/src/main.ts plugin-markdown/ui-src/src/MarkdownEditor.vue
git commit -m "✨ feat(frontend): migrate plugin MF demo to MD3; retire --sk-* tokens"
```

---

## Task 12: Documentation updates

**Files:**
- Modify: `CLAUDE.md` (frontend stack section)
- Modify: `.agents/skills/zhiflow-plugin-dev/SKILL.md`
- Modify: `.zcode/skills/create-builtin-tool/SKILL.md`
- Modify: `docs/ui-design/README.md`
- Modify: `docs/zh/ui-design/README.md`
- Modify: `CHANGELOG.md`

**Interfaces:** none (docs only).

- [ ] **Step 1: Update CLAUDE.md frontend stack**

In `CLAUDE.md`, find the frontend stack description (~lines 30-31, 99-105). Replace references to "`--sk-*` tokens ported from `zhiflow-common.css`" with:

```
Vue 3.5.39 + TypeScript + Pinia + vue-router 4 + vue-i18n 10, UI via
Vuetify 3 (Material Design 3 blueprint; Google default M3 baseline palette).
Theme is driven by Vuetify's global theme singleton from the Pinia
useThemeStore. Micro-frontend plugins share the host's Vuetify instance
via PluginContext.vuetify (app.use(ctx.vuetify) in mount()).
```

- [ ] **Step 2: Update plugin-dev skill**

In `.agents/skills/zhiflow-plugin-dev/SKILL.md`, add a section noting:
- Plugin web UI uses Vuetify MD3.
- The host injects `vuetify` via `ctx.vuetify`; plugins MUST call `app.use(ctx.vuetify)` in `mount()` before `app.mount(el)`.
- Plugins must use MD3 components; no separate UI library.

- [ ] **Step 3: Update create-builtin-tool skill**

In `.zcode/skills/create-builtin-tool/SKILL.md`, add a note that the web/UI side of built-in tools uses Vuetify MD3 components.

- [ ] **Step 4: Add pointer notes to UI-design docs**

In both `docs/ui-design/README.md` and `docs/zh/ui-design/README.md`, add a note at the top (or in a "Scope" section):

EN:
```
> **Web frontend note:** As of 4.0.0 the web shell (frontend/) implements
> Material Design 3 via Vuetify 3, not the IntelliJ token system below.
> The --sk-* token spec in these docs remains authoritative for the
> **JavaFX host** only.
```

ZH:
```
> **Web 前端说明:** 自 4.0.0 起 Web 前端 (frontend/) 通过 Vuetify 3 实现
> Material Design 3,不再使用下文的 IntelliJ token 体系。下文 --sk-*
> token 规范仅对 **JavaFX 宿主** 继续有效。
```

- [ ] **Step 5: Add CHANGELOG entry**

In `CHANGELOG.md`, under the `4.0.0-ZhiFlow` section, add:
```
### UI
- feat(frontend): adopt Vuetify 3 (Material Design 3 blueprint) as the UI
  component library for the host SPA and plugin micro-frontend. Full
  visual-language switch from the --sk-* IntelliJ-token system to MD3
  (Google default baseline palette). Theme driven by Vuetify's global
  singleton from useThemeStore; plugins share the host's Vuetify instance
  via PluginContext.vuetify.
```

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md .agents/skills/zhiflow-plugin-dev/SKILL.md .zcode/skills/create-builtin-tool/SKILL.md docs/ui-design/README.md docs/zh/ui-design/README.md CHANGELOG.md
git commit -m "📝 docs(ui): document Vuetify MD3 adoption (CLAUDE, skills, design docs, changelog)"
```

---

## Final Verification

- [ ] **Run:** `cd frontend && npm run build` — exits 0, zero new TS errors.
- [ ] **Run:** `cd plugin-markdown/ui-src && npm run build` — exits 0.
- [ ] **Run:** `cd frontend && grep -rnE '\.sk-|--sk-' src/ && echo FOUND || echo CLEAN` — prints `CLEAN`.
- [ ] **Manual (dev server):** `cd frontend && npm run dev` → visit `/`, `/ai`, `/agent`, `/settings`, `/setup`, `/plugin/markdown`. All render in MD3; theme toggle in Settings flips host and (on `/plugin/...`) the plugin MF.
