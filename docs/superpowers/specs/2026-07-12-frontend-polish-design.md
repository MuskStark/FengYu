# Frontend Polish — Setup Defaults, Global Status Bar, Button Alignment, Sidebar Logo

- **Date:** 2026-07-12
- **Branch:** `4.0.0-FengYu`
- **Status:** Approved (brainstorm complete, pending implementation plan)
- **Owner:** Frontend

## Summary

Four targeted frontend polish tasks on the FengYu 4.0 MD3/Vuetify shell:

1. **Setup wizard local-DB defaults + button layout** — show the program-default data-file path for embedded (H2/SQLite) databases, and make the Test/Initialize buttons centered and stacked vertically.
2. **Global bottom status bar** — a single bottom info bar (version + backend status: offline / connected / restarting) shown on **every** screen, including the setup wizard.
3. **Button alignment audit** — every button sits on the same center line as the component next to it.
4. **Sidebar logo + rail-mode polish** — replace the `ZF` text avatar with an MDI logo icon and align/spacing-collapse icons in the 64px rail.

## Decisions (locked during brainstorm)

| Decision | Choice |
|---|---|
| Local-DB default path format | **Program absolute path** — backend resolves `<user.dir>/.fengyu/database/fengyu` and ships it in `types()` so the real path is visible. |
| Status bar scope | **Global, including the wizard** — `StatusBar` lifts to root `App.vue`; the wizard's restart loop drives a `restarting` state. |
| Input-bar button alignment | **Vertical center** (`align-center`) — send/plan buttons center on the input box, not bottom-aligned. |
| Sidebar icon task | **Logo + rail collapse polish** — brand area becomes an MDI logo; rail mode (64px) center-aligns all icons. |

## Context (current state)

- `SetupController.types()` (`FengYu/.../setup/SetupController.java:91-101`) emits the embedded `filePath` field **without a `default`**; the program-default path is only resolved at save time inside `DataSourceConfigService.buildFromWizard()` (`:135-137`). The frontend store already auto-fills any `f.default` it receives (`frontend/src/stores/setup.ts:36-39`), so a backend-supplied default needs **zero** frontend store change.
- `StatusBar.vue` already polls `/api/health` every 5s and shows a connection chip + a **hardcoded** `"FengYu 4.0.0"`. It only renders inside `AppShell` (app mode) — `App.vue:11-14` renders the wizard full-screen **without** the bar, yet the wizard is exactly where the restart state matters.
- `package.json` carries `"version": "4.0.0"`; it is not currently wired into the app (only a literal string in `StatusBar.vue:47`).
- Icon audit: the frontend is already 100% MDI — `plugins/vuetify.ts:24-27` installs `mdi` + `@mdi/font`; a regex sweep for SVG / FontAwesome / Lucide / heroicons / tabler across `frontend/src` returns **zero** matches. Task 4 (sidebar logo) will keep that invariant.
- Known button misalignments (input-box siblings using `align-end`): `AiChat.vue:106`, `AiAgent.vue:257`.

## Architecture

No new dependencies, no new components beyond reusing the existing `StatusBar.vue`. All changes are edits to existing files plus i18n keys.

### File impact map

| File | Change |
|---|---|
| `FengYu/.../setup/SetupController.java` | `types()` adds `default` = resolved embedded data path for `filePath`. |
| `frontend/src/views/SetupWizard.vue` | Step-2 action block: center + vertical stack; localized labels. |
| `frontend/src/App.vue` | Always render `<StatusBar />` (remove wizard-only conditional). |
| `frontend/src/shell/StatusBar.vue` | Add `offline`/`restarting` states + MDI icons; version from `__APP_VERSION__`; pin to bottom. |
| `frontend/src/views/SetupWizard.vue` | `onInitialize`→`waitForRestart` drives the `restarting` state (shared via a tiny reactive flag / store). |
| `frontend/vite.config.ts` | `define: { __APP_VERSION__: JSON.stringify(pkg.version) }`. |
| `frontend/src/env.d.ts` | Declare `__APP_VERSION__: string`. |
| `frontend/src/views/AiChat.vue`, `AiAgent.vue` | `align-end` → `align-center` on input-bar button rows. |
| `frontend/src/shell/Sidebar.vue` | `v-avatar` `ZF` text → MDI logo icon; rail-mode centering pass. |
| `frontend/src/i18n/{en,zh}.json` | New keys: `setup.*`, `status.offline`, `status.restarting`. |

## Task 1 — Setup wizard local-DB defaults + button layout

### 1a. Default data-file path (backend-driven)

`SetupController.types()` gains access to the same base dir `DataSourceConfigService` uses, so the default it advertises **exactly matches** what `buildFromWizard()` would compute when `filePath` is blank.

- Refactor: expose the resolved default path from `DataSourceConfigService` (e.g. a `defaultEmbeddedPath()` method returning `Path.of(baseDir, "database", "fengyu")`), and have `buildFromWizard()` call it instead of duplicating the literal. `SetupController` injects `DataSourceConfigService` (it already does) and reads `defaultEmbeddedPath()` to populate the field's `default`.
- The `filePath` field for embedded types becomes:
  ```
  { name: "filePath", label: "Data file location", required: true, secret: false,
    default: <resolved absolute path> }
  ```
- Remote (MySQL/PostgreSQL) types keep their existing per-field `default` (port only).
- Frontend store needs no change — `selectType()` already copies `f.default` into `params`. The wizard's `v-text-field` binds to `setup.params[f.name]`, so the resolved path appears as the field's value on type selection. User can still override.

**Why backend-driven:** the frontend cannot know the backend's `user.dir`; only the backend can produce the true on-disk path. This also keeps a single source of truth for the path (no drift between the shown default and the actual save location).

### 1b. Test / Initialize button layout

In `SetupWizard.vue` step 2, wrap the Test row **and** the Initialize button in one column container:

```
v-container/fluid  (no gutters) or div.d-flex.flex-column.align-center
 ├─ Test connection   (v-btn, tonal, :loading)
 ├─ result message    (text-center, success/error, centered under button)
 └─ Initialize        (v-btn, color=primary, :disabled=!canInitialize)  ← below Test
```

- Test result (success chip / error text) renders **centered below** the Test button instead of inline to its right.
- Both buttons are full-width-optional but visually centered (`align="center"`); keep the existing `block?` off so they don't stretch edge-to-edge — a modest fixed width or natural width, centered, is the target.

## Task 2 — Global bottom status bar (version + backend status)

### 2a. Lift `StatusBar` to the root

`App.vue` currently branches: wizard route → full-screen `<router-view/>` (no bar); else → `<AppShell/>` (which renders the bar). Replace with an unconditional bottom bar for **both**:

```
<v-app>
  <router-view ... />          (wizard renders full-screen; app renders inside AppShell)
  <StatusBar />                (always present)
</v-app>
```

Because the wizard centers its card vertically, the persistent bottom bar sits beneath it; the wizard's outer container keeps its centering (`h-100`) and the bar overlays the bottom edge. (The wizard card uses `pa-6` and `max-width: 560`; the bar is a `v-system-bar` height — no layout clash.)

### 2b. State machine

Extend the current 3-state poller to 5:

| State | Meaning | Detected by | MDI icon | chip color |
|---|---|---|---|---|
| `connecting` | initial, never yet ok | mounted, before first success | `mdi-circle-medium` (or pulse) | `default` |
| `connected` | `/api/health` ok | poll success → `status === 'ok'` | `mdi-check-circle-outline` | `success` |
| `reconnecting` | was connected, transient failure | poll fail after a prior success | `mdi-autorenew` | `warning` |
| `offline` | never connected / repeated failure at startup | poll fail with **no** prior success | `mdi-lan-disconnect` | `error` |
| `restarting` | backend process is mid-restart (setup initialize) | wizard signals it (2c) | `mdi-restart` | `info` |

`restarting` is set externally (wizard) and supersedes poll results while active; it clears when the poller sees a healthy, initialized backend again.

### 2c. Restart detection (wizard → status bar)

`SetupWizard.onInitialize()` already calls `waitForRestart()` which polls `api.health()` + `api.getSetupStatus()`. While that loop runs and health is failing, set a shared `restarting` flag the `StatusBar` reads.

**Implementation (chosen):** a small Pinia store `frontend/src/stores/connection.ts` exporting `useConnectionStore()` with `state` (`ConnState` ref) and `setRestarting(bool)`. The `StatusBar` owns the polling loop and writes `connecting/connected/reconnecting/offline` into the store; the wizard calls `setRestarting(true)` at the start of `waitForRestart` and `setRestarting(false)` when it detects a healthy, initialized backend (then navigates to `/`). Pinia is already an app dependency, so this avoids a bespoke module-level singleton and keeps state inspectable in devtools. While `restarting` is true, the `StatusBar` renders the restarting chip and does not let transient poll failures flip it back to `offline`/`reconnecting`.

### 2d. Version injection

- `vite.config.ts`: read `package.json` `version` and `define: { __APP_VERSION__: JSON.stringify(version) }`.
- `env.d.ts`: `declare const __APP_VERSION__: string` (global).
- `StatusBar.vue`: replace literal `"FengYu 4.0.0"` with `` `FengYu ${__APP_VERSION__}` ``.

## Task 3 — Button alignment audit

Every `v-btn` that sits beside another component in a horizontal row must vertically center on it. Audit list and fix:

| Location | Current | Fix |
|---|---|---|
| `AiChat.vue:106` input row | `d-flex ga-2 align-end` | `align-center` |
| `AiAgent.vue:257` goal row | `d-flex ga-2 align-end` | `align-center` |
| `Settings.vue` test/save rows | already `align-center` | verify, no change |
| `SetupWizard.vue` action block | (rewritten in Task 1) | center-stacked |
| `ToolGrid.vue` card actions | `v-spacer` + icon btn | already aligned |
| `PluginView.vue` back/retry rows | `align-center` | verify |

No `align-end` button siblings should remain.

## Task 4 — Sidebar logo + rail-mode polish

### 4a. Brand-area logo

`Sidebar.vue:40-53` brand block currently:

```
<v-avatar color="primary" size="32" rounded="lg"><span>ZF</span></v-avatar>
<span v-if="!rail">FengYu</span>
<v-spacer />
<v-btn :icon="rail ? chevron-right : chevron-left" .../>
```

Replace the `ZF` text avatar with an MDI logo glyph:

```
<v-avatar color="primary" size="32" rounded="lg">
  <v-icon icon="mdi-hexagon-multiple-outline" size="large" />
</v-avatar>
```

`mdi-hexagon-multiple-outline` reads as a multi-part hex stack (visual nod to a tool/flow suite). If it proves visually heavy at 32px, the equally-valid fallback is `mdi-flask-outline`; decision deferred to the visual check during implementation, not a runtime toggle.

Keep the textual `FengYu` label next to it in expanded mode (unchanged). The icon is MDI → consistent with Task-4's "use UI framework built-in icons" intent.

### 4b. Rail-mode (64px) centering pass

When `rail` is true, verify each row is horizontally centered:

- Brand logo avatar centers (no left `px-3` offset that misaligns the avatar at 64px) — switch the brand row to `justify-center` when `rail`, keeping `px-3` only expanded.
- Collapse/expand button: centered when rail.
- `v-list-item` nav icons: `v-navigation-drawer rail` already centers list-item prepend icons — verify no custom padding breaks it; leave default Vuetify rail behavior intact.

All changes are utility-class flips driven by `:class="{ 'justify-center': rail }"` etc.; no SCSS.

## Testing

- **Backend:** update/add assertions in `SetupControllerTest` that `GET /api/setup/types` returns a `default` on the embedded `filePath` field equal to the service's resolved path (mock/inject base dir as today). `DataSourceConfigServiceTest` gains an assertion that `defaultEmbeddedPath()` matches the blank-`filePath` branch of `buildFromWizard`.
- **Frontend:** no unit-test harness exists for the Vue shell today; verify visually (dev server) — wizard default path shows, buttons stacked/centered, status bar present on wizard + app, version reflects package.json, send/plan buttons center-aligned, sidebar logo renders and rail mode centers.
- Manual matrix: dark/light × en/zh (new i18n keys present in both).

## Out of scope

- No change to the AI config / agent / plugin views beyond the alignment fixes listed.
- No new Vuetify components or dependencies.
- No backend health-endpoint changes (still `{status:"ok"}`); restart detection is client-side.
- Theme/palette unchanged (MD3 baseline stays).
