# Vuetify MD3 UI Component Library Adoption — Design Spec

- **Date:** 2026-07-10
- **Branch:** `4.0.0-FengYu`
- **Status:** Approved (brainstorm complete, pending implementation plan)
- **Owner:** Frontend

## Summary

Adopt [Vuetify 3](https://vuetifyjs.com) with its **Material Design 3 (MD3) blueprint** as the standard UI component library for both the host SPA (`frontend/`) and the plugin micro-frontend (`plugin-markdown/ui-src`). The existing hand-written `--sk-*` IntelliJ-"New UI" token system is retired in favor of the Material Design 3 visual language (Google default baseline palette, tonal surfaces, MD3 shape/elevation).

This is a full visual-language switch: all existing views are rewritten to MD3, `--sk-*` tokens are deleted at the end of migration, and the canonical design docs (`docs/ui-design/`) retain the IntelliJ-token spec only for the JavaFX host.

## Decisions (locked during brainstorm)

| Decision | Choice |
|---|---|
| Visual strategy | **全面采用 MD3 观感** — full MD3 adoption; rewrite all views; retire `--sk-*` |
| Scope this round | **一次性迁移所有视图** — migrate all 7 views + 3 shell components in one effort |
| Plugin micro-frontend | **宿主 + 插件微前端都上** — both host SPA and plugin MF adopt MD3 |
| MD3 palette seed | **Google 默认 MD3 调色板** — fixed M3 baseline (purple-toned), no seed-derived tonal generation |
| Integration architecture | **方案 A — 宿主配 + MF 共享** — host installs Vuetify; plugin MF receives the instance via `PluginContext` (no import-map, no plugin build changes) |

## Architecture

### Dependencies

Added to `frontend/package.json`:

| Package | Type | Purpose |
|---|---|---|
| `vuetify` `^3.7.x` | dependency | Component library + MD3 blueprint |
| `@mdi/font` `^7.4.x` | dependency | MD3 icon set (Material Design Icons) |
| `vite-plugin-vuetify` `^2.0.x` | devDependency | Lazy component import, tree-shake, Sass var integration |
| `sass-embedded` `^1.x` | devDependency | Required to compile Vuetify's Sass |

**Nothing** is added to `plugin-markdown/ui-src/package.json` — the plugin consumes the host's Vuetify via `PluginContext`.

### New host module: `frontend/src/plugins/vuetify.ts`

Builds the single shared Vuetify app plugin: MD3 blueprint + Google-default MD3 palette + dual theme (dark/light).

```ts
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import { md3 } from 'vuetify/blueprints'
import { aliases, mdi } from 'vuetify/iconsets/mdi'
import '@mdi/font/css/materialdesignicons.css'

export const vuetify = createVuetify({
  blueprint: md3,
  components,
  directives,
  icons: { defaultSet: 'mdi', aliases, sets: { mdi } },
  theme: {
    defaultTheme: 'dark',
    themes: { dark: md3Dark, light: md3Light },
  },
})
```

### Registration

`frontend/src/main.ts`: `app.use(vuetify)` after pinia/router/i18n. `vuetify` is also injected into the MF `PluginContext` (see Micro-frontend section).

### Vite config

`frontend/vite.config.ts`: add `vite-plugin-vuetify`'s `vite()` to `plugins`, with `styles: { configFile: 'src/plugins/vuetify-settings.scss' }`. Host keeps `external: ['vue']` (Vuetify is bundled into the host, not externalized).

## Theme & MD3 Palette

Google's default M3 baseline palette (purple primary). Replaces `--sk-*` as the source of truth for color.

### Light theme (`md3Light`)

| Role | Value |
|---|---|
| primary | `#6750A4` |
| on-primary | `#FFFFFF` |
| primary-container | `#EADDFF` |
| secondary | `#625B71` |
| tertiary | `#7D5260` |
| error | `#B3261E` |
| background | `#FEF7FF` |
| surface | `#FEF7FF` |
| on-surface | `#1D1B20` |
| surface-variant | `#E7E0EC` |
| outline | `#79747E` |

### Dark theme (`md3Dark`)

| Role | Value |
|---|---|
| primary | `#D0BCFF` |
| on-primary | `#381E72` |
| primary-container | `#4F378B` |
| secondary | `#CCC2DC` |
| tertiary | `#EFB8C8` |
| error | `#F2B8B5` |
| background | `#141218` |
| surface | `#141218` |
| on-surface | `#E6E0E9` |
| surface-variant | `#49454F` |
| outline | `#938F99` |

These are the canonical M3 baseline values. They live in `plugins/vuetify.ts` under `theme.themes.{light,dark}`.

### Theme switching — bridge to existing Pinia store

`useThemeStore` stays the single source of truth for the *user's choice*, but now drives **Vuetify's** theme instead of the root class. The store imports the exported `vuetify` singleton directly (Vuetify's `useTheme()` composable requires a component setup context via `inject()`, but this store action runs in `main.ts` outside any component):

```ts
// stores/theme.ts — setTheme() now also flips Vuetify
import { vuetify } from '@/plugins/vuetify'
function setTheme(next: ThemeName) {
  theme.value = next
  vuetify.theme.global.name.value = next   // Vuetify MD3 theme switch (singleton)
  listeners.forEach((cb) => cb(next))      // plugins MF still notified via ctx
}
```

> Note: `useTheme()` (the composition-API form) is still used **inside components** (e.g. a view that needs the current theme reactively). The store uses the `vuetify.theme.global` singleton because it runs outside a component.

- `App.vue`'s `watchEffect` that adds `.theme-dark/.theme-light` classes is **removed** — Vuetify applies theme via `<v-app>` + `data-v-app`.
- `tokens.css` `--sk-*` definitions are **kept during migration**, **deleted at the end** (see Retirement).
- `PluginContext.onThemeChange` is preserved so MFs that read `ctx.theme` still work.

### `v-app` wrapper

`App.vue` template wraps content in `<v-app>` (required by Vuetify for theme/layout). Existing `<router-view>`/`<AppShell>` logic stays inside.

## Component Migration Mapping

Each of the 7 views and 3 shell components (~1688 lines) mapped to Vuetify MD3 components. Custom `.sk-*` classes replaced by Vuetify components + MD3 density/roundness.

### Shell layer (`shell/`)

| Current | → Vuetify MD3 |
|---|---|
| `AppShell.vue` | Wraps in `<v-app>`; content area is `<v-main>`. Sidebar + status bar as slots. |
| `Sidebar.vue` (192 lines) | `<v-navigation-drawer>` (`rail` collapsible mode), `<v-list>` / `<v-list-item prepend-icon>` for nav. Active route highlighted via `:active` / `to`. |
| `StatusBar.vue` (77 lines) | `<v-system-bar>` or slim `<v-app-bar density="compact">`, `<v-chip>` for status badges (connected/disconnected), `<v-spacer>`. |

### Views (`views/`)

| View | Key MD3 components |
|---|---|
| `ToolGrid.vue` (185) | `<v-container>` + `<v-row>`/`<v-col>` grid; each tool = `<v-card>` with `prepend-avatar`/icon + title + subtitle. Category headers = `<v-list-subheader>` or `<div class="text-h6">`. |
| `AiChat.vue` (196) | `<v-sheet>` chat scroll; messages = `<v-card variant="tonal">` bubbles; input = `<v-textarea auto-grow>` + `<v-btn icon="mdi-send">`. `v-virtual-scroll` for autoscroll. |
| `AiAgent.vue` (498, largest) | Split layout `<v-row>` — left plan/steps (`<v-timeline>` or `<v-list>`, each step with status icon), right `<v-card>` detail + `<v-expansion-panels>` for tool calls. Top `<v-app-bar>` with action buttons. |
| `Settings.vue` (67) | `<v-list>` + `<v-list-item>`; theme toggle = `<v-switch>`; pickers = `<v-select>`. Section dividers = `<v-list-subheader>`. |
| `SetupWizard.vue` (288) | `<v-stepper>` (MD3-style step bar); each step = `<v-card>` form with `<v-text-field>`/`<v-select>`/`<v-btn>`. Bottom `<v-card-actions>` for navigation. |
| `PluginView.vue` (150) | `<v-card>` with `<v-card-title>` + `<v-card-subtitle>`; MF mount target = `<div ref>` inside card. `<v-progress-circular indeterminate>` loading, `<v-alert type="error">` on failure. |

### Migration rules (apply to every file)

1. **Remove all `.sk-*` class usage** — replace with Vuetify component props (`variant="tonal|outlined|flat|elevated"`, `density="compact|comfortable"`, `color="primary|surface"`).
2. **Remove per-component `<style scoped>` color rules** that depend on `--sk-*`; keep minimal scoped style only for layout Vuetify can't express. Inline `style="color: var(--sk-accent)"` → `color="primary"`.
3. **Icons** — use `@mdi/font` natively: `<v-icon icon="mdi-cog">` or `prepend-icon="mdi-cog"`. Existing `MdiIconUtil` references map to the same `mdi-*` symbol names.
4. **MD3 shape** — cards default to `rounded` (MD3 specifies medium/large radii); buttons `rounded="lg"`. Set globally via `vuetify-settings.scss`.
5. **No Element/Quasar/other library** — Vuetify only.

### Migration order (smallest-first to de-risk)

1. `Settings.vue` (smallest, establishes pattern)
2. `StatusBar.vue` + `Sidebar.vue` (shell foundation) — **in parallel with step 2a**
2a. `AppShell.vue` + `App.vue` (`<v-app>`/`<v-main>` wrap) — must land before any view renders under Vuetify; done with the shell step
3. `ToolGrid.vue` (grid pattern)
4. `AiChat.vue`
5. `PluginView.vue` (wires `ctx.vuetify` into the MF mount)
6. `SetupWizard.vue`
7. `AiAgent.vue` (largest, last)
8. `plugin-markdown/ui-src/src/MarkdownEditor.vue` (verify MF sharing E2E)

## Micro-frontend Plugin Sharing

**Pattern: host passes the Vuetify app plugin through `PluginContext`; the plugin does not install Vuetify.** No import-map entry, no plugin build config, one theme everywhere.

### `PluginContext` extension (`mf/loader.ts`)

```ts
import type { Plugin } from 'vue'

export interface PluginContext {
  // ...existing: api, theme, onThemeChange, locale, t, onLocaleChange, notify
  /** Vuetify plugin instance (MD3, from host). Call app.use(ctx.vuetify) in mount(). */
  vuetify: Plugin
}
```

### Host provides it (`PluginView.vue` mount call)

```ts
const ctx: PluginContext = {
  // ...existing fields
  vuetify,        // ← new: the single shared instance
}
```

### Plugin mount() uses it (`plugin-markdown/ui-src/src/main.ts`)

```ts
const module: PluginUiModule = {
  mount(el, ctx) {
    const app = createApp(MarkdownEditor)
    app.provide('pluginCtx', ctx)
    app.use(ctx.vuetify)        // ← new: register host's MD3 Vuetify
    app.mount(el)
    return () => app.unmount()
  },
}
```

- **No new dependency** in `plugin-markdown/ui-src/package.json`.
- **No import-map update** in the plugin build (`vuetify` never appears as a bare specifier).
- **No `external` change** in `plugin-markdown/ui-src/vite.config.ts`.

### Why this satisfies single-instance

Exactly one Vuetify on the wire: the host builds it in `plugins/vuetify.ts` and injects it into each plugin's `createApp()`. Vuetify's internals (reactive theme, globally-registered components) attach to the app instance, so every app gets the same MD3 config + theme switching (the theme store is the shared `useTheme()` singleton in the host — same module instance resolved via import map).

### Theme propagation (already correct)

Existing `PluginContext.onThemeChange` still fires the plugin's callback. With Vuetify registered via `ctx.vuetify`, any `useTheme()` read inside the plugin reflects the host's switch. Plugins keep backward compat (`ctx.theme` string) AND gain native MD3 reactivity.

### Plugin demo update (`MarkdownEditor.vue`)

Migrated to Vuetify components (e.g. wrapped in `<v-card variant="outlined">`, `<v-btn color="primary">`) to validate the sharing path works E2E. Part of migration order step 9.

### Docs updates for plugins

- `fengyu-plugin-dev` skill: document `ctx.vuetify` — plugins MUST call `app.use(ctx.vuetify)` in `mount()` and MUST use MD3 components.
- `CLAUDE.md` frontend section: note Vuetify MD3 as the standard + the MF-sharing convention.

## Retirement of `--sk-*`

- `frontend/src/theme/tokens.css`: after all views migrated, the `--sk-*` custom-property blocks (`.theme-dark`/`.theme-light`) are **deleted**. Until then they coexist harmlessly (Vuetify doesn't read them; migrated code stops using them).
- `frontend/src/stores/theme.ts`: `applyThemeClass()` no longer toggles root classes. Keep `onChange` listener notification for MF backward-compat.
- `frontend/src/App.vue`: remove the `theme-dark`/`theme-light` class-toggle `watchEffect`.
- `index.html`: remove the hardcoded `class="theme-dark"` from `<html>` (Vuetify owns theme now).
- Any `--sk-*` references in migrated `.vue` `<style>` blocks removed (migration rule #1/#2).

## Docs Updates

- **`CLAUDE.md`** (frontend stack section): replace "Vue 3.5.39 + TS + Pinia + `--sk-*` tokens ported from `fengyu-common.css`" with **"Vue 3.5.39 + TS + Pinia + Vuetify 3 (MD3 blueprint)"**. Add the MF-sharing convention (`ctx.vuetify`).
- **`docs/ui-design/`** (EN) + **`docs/zh/ui-design/`** (ZH): the 8 design docs describe the IntelliJ "New UI" token system. Add a pointer note stating the **web frontend now implements MD3 via Vuetify**; the IntelliJ-token spec remains authoritative for the **JavaFX** host only. Full doc rewrite is out of scope (a `docs-updater` run can reconcile later).
- **`.zcode/skills/create-builtin-tool/SKILL.md`** + **`.agents/skills/fengyu-plugin-dev/SKILL.md`**: note that built-in-tool web UI and FengYu plugins use Vuetify MD3 + `ctx.vuetify`.
- **CHANGELOG**: entry under `4.0.0-FengYu` — "feat(frontend): adopt Vuetify 3 MD3 as UI component library".

## Acceptance Criteria

1. `cd frontend && npm install` succeeds; `npm run build` (`vue-tsc --noEmit && vite build`) passes with **zero new TS errors**.
2. `cd plugin-markdown/ui-src && npm run build` passes (no dependency changes, but re-verified).
3. Dev server (`npm run dev`) shows all routes in MD3 styling: `/`, `/ai`, `/agent`, `/settings`, `/setup`, `/plugin/:id`.
4. Theme toggle in Settings flips **both** host and (when a plugin view is open) the plugin MF — verified by eye.
5. No `.sk-*` classes remain in any `.vue` file under `frontend/src/` (grep returns nothing).
6. No `--sk-*` var references in migrated `.vue` `<style>` blocks.
7. `ctx.vuetify` documented in the plugin-dev skill; a plugin calling `app.use(ctx.vuetify)` renders MD3 components.

## Out of Scope

- Rewriting the 8 `docs/ui-design/` docs to MD3 (pointer note only).
- Migrating the JavaFX host (`FengYu-Api/.../fengyu-common.css`) — stays IntelliJ-token-based.
- Auto-generating a tonal palette from a seed (using Google's fixed baseline instead).
- Icon redesign (reusing existing `mdi-*` symbol names).

## Rollback

Changes are isolated to `frontend/` (new dep + `plugins/vuetify.ts` + view rewrites) and `plugin-markdown/ui-src/src/main.ts` (one line). `git revert` of the feature-branch commits restores the IntelliJ-token UI. No backend/data changes.
