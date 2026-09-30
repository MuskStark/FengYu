---
title: UI Components
description: "The @infinia/plugin-ui kit — a Codex-style Vuetify 3 foundation for FengYu plugins. Responsive shell/page, progress, notifications, pickers, workflows, and live theme/locale bindings."
lang: en
---

# UI Components

`@infinia/plugin-ui` is the official Vuetify 3 (Material Design 3) component library for FengYu plugins. The project scaffolded by `fengyu init` depends on it, and its `src/main.ts` already wires up the Vuetify instance, theme/locale binding, and client injection — so you only compose components. Import the pieces you use from a single entry:

```ts
import {
  FyFilePicker,
  FyPluginPage,
  FyProgress,
  FyStepWizard,
  FyPluginShell,
  useFengYuClient,
  createFengYuVuetify,
  bindFengYuEnvironment,
  provideFengYuClient,
} from '@infinia/plugin-ui'
```

The base controls you compose with (`v-btn`, `v-card`, `v-list`, `v-data-table`, …) are ordinary Vuetify controls, registered globally by `createFengYuVuetify`. The `Fy*` components are the opinionated FengYu wrappers on top.

## Foundation: Vuetify + environment binding

The scaffolded `src/main.ts` calls three helpers from this package. You normally do not touch them, but it helps to know what they do:

| Export | Purpose |
| --- | --- |
| `createFengYuVuetify(options?)` | Builds a Vuetify instance with the MD3 blueprint, the `fengyuCodexDark` / `fengyuCodexLight` themes, MDI icons, and `en` + `zhHans` locales. |
| `bindFengYuEnvironment(vuetify, client)` | Calls `client.ready()` once, applies theme + locale to Vuetify, then subscribes to `environment` events. Returns an unsubscribe function. |
| `provideFengYuClient(app, client)` | Provides the `FengYuClient` via Vue `provide/inject` so every component can call `useFengYuClient()`. |
| `useFengYuClient()` | Inject the `FengYuClient` inside any `setup()`. Prefer this over importing the raw `fengyu` singleton. |

## Component catalog

| Component | Purpose |
| --- | --- |
| `FyPluginShell` | App chrome: a navigation drawer (title + `items[]`) + app bar + content slot. Collapses to a temporary drawer below `railBreakpoint` (default 720px). v-model is the active item value. |
| `FyPluginPage` | Responsive content frame with shared desktop/mobile gutters and a configurable maximum width. Use `fluid` for editors/canvases and `fullHeight` for full-height workspaces. |
| `FyPageHeader` | A page title, optional `description`, and a trailing `#actions` slot for toolbar buttons. |
| `FyToolbar` | A horizontal button row for `#actions`. |
| `FyFilePicker` | SDK-backed file button — wraps `client.files.open`. v-model is the `FileRef` or `null`. |
| `FyDirectoryPicker` | SDK-backed directory button — `mode: 'input' \| 'workspace' \| 'output'` selects a readable input, writable project workspace, or fresh output directory. |
| `FyStepWizard` | Stateful, value-keyed workflow controller with controlled progress, async validation, branching, invalidation, and JSON snapshots. |
| `FyTaskTable` | Read-only task list (`tasks: FyTaskRow[]`) rendered with `v-data-table`; status shown as icon + label. |
| `FyProgress` | Unified determinate/indeterminate progress panel with `label`, `detail`, `modelValue` (0–100), `status`, and an `#actions` slot. |
| `FyNotificationCenter` | Host-aware snackbar queue. `FyPluginShell` mounts it automatically; it renders the shared local fallback when host delivery of a notification fails (the host call threw or resolved `false`). |
| `FyConfirmDialog` | v-model dialog with `destructive` styling; emits `confirm` / `cancel`. |
| `FyEmptyState` / `FyLoadingState` / `FyErrorState` / `FyPermissionNotice` | Standardized empty / loading / error / permission-denied panels with `title`, `message`, and `icon`. `FyErrorState` emits `retry`. |

### Icon contract

Vuetify icon props accept regular `mdi-*` names such as `mdi-home-outline`. The UI package carries
the MDI font dependency and lets the plugin's Vite application emit it as same-origin hashed assets,
so generated plugins do not need a custom font import or Vite workaround. Components that accept
icon path data also support tree-shakeable SVG paths imported from `@mdi/js`; render those through
`FyIcon`.

`FyPluginShell` is also valid with no navigation items: it omits the drawer and app bar for a single-workspace plugin while retaining the standard app, feedback, and responsive behavior. Pair it with `FyPluginPage` instead of copying viewport padding into each plugin.

The notification composable `useFengYuNotify(client)` and `sendFengYuNotification(client, message)` are also exported for non-component use. `notify(message, { tone, timeout })` accepts `info`, `success`, `warning`, or `error`; the options style the local fallback, while a host-accepted notification uses the host's own unified notification surface (toast + native desktop notification + notification center; no manifest permission required). Composables bound to the same client share one queue, so a notification raised anywhere in the plugin reaches the center mounted by `FyPluginShell`.

## Example: a file picker

`FyFilePicker` wraps `client.files.open` so a plugin can request a host file through one button. Cancellation — the host resolving `null` — is a normal empty result: it emits `update:modelValue(null)` and `cancel`, and renders **no** error. Permission denials render `FyPermissionNotice`; other errors render `FyErrorState` with a retry. This is the complete pattern the scaffolded `App.vue` uses:

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { FyFilePicker, useFengYuClient, useFengYuNotify } from '@infinia/plugin-ui'
import type { FileRef } from '@infinia/plugin-sdk'

const client = useFengYuClient()
const selectedFile = ref<FileRef | null>(null)
const { notify } = useFengYuNotify(client)

async function onFile(file: FileRef | null): Promise<void> {
  selectedFile.value = file
  if (file) await notify(`Selected ${file.name}`, { tone: 'success' })
}
</script>

<template>
  <FyFilePicker
    label="Choose spreadsheet"
    :extensions="['xlsx', 'csv']"
    :model-value="selectedFile"
    @update:model-value="onFile"
    @cancel="onFile(null)"
  />
</template>
```

## Responsive layout and progress

All plugin-level layout should begin with `FyPluginShell` + `FyPluginPage`. Business content can use the shared CSS hooks `.fy-surface`, `.fy-surface__section`, `.fy-section-title`, `.fy-section-copy`, `.fy-actions`, `.fy-actions--split`, `.fy-status`, `.fy-log`, and `.fy-responsive-table`. These hooks use only theme tokens and include the shared narrow-screen behavior. `FyPluginPage` establishes the named `fy-plugin-page` inline-size container, so plugin-specific grids should add `@container fy-plugin-page (...)` rules when the iframe's available content width matters more than the browser window width.

Use `FyProgress` for explicit long-running state instead of mixing custom circular and linear indicators:

```vue
<FyProgress
  v-if="running"
  label="Building offline repository…"
  :model-value="percent"
  status="running"
>
  <template #actions><v-btn variant="text" @click="cancel">Cancel</v-btn></template>
</FyProgress>
```

Omit `modelValue` for indeterminate work. Set `status` to `success`, `warning`, or `error` for a terminal/result presentation. The component includes live-region semantics, responsive action wrapping, and the same theme-driven bar treatment used by official plugins.

| Prop | Type | Notes |
| --- | --- | --- |
| `modelValue` | `FileRef \| null` | v-model: the selected file, or `null`. |
| `extensions` | `string[]` | Extension allowlist forwarded to the host file dialog. |
| `filters` | `FileFilter[]` | Named extension filters forwarded to the host. |
| `label` | `string` | Button label (default `Choose file`). |

Emits: `update:modelValue`, `cancel`, `error`. `FyDirectoryPicker` has the same shape plus `mode: 'input' | 'workspace' | 'output'` (default `input`). Workspace mode calls `client.files.workspaceDirectory()` and requires `files.write`.

## Example: a step wizard

`FyStepWizard` is a stateful, value-keyed workflow controller. A controlled consumer owns the active step, per-step states, and completion flag through the three v-model bindings. The six step statuses are `pending`, `active`, `validating`, `complete`, `error`, and `skipped`; an `error` state may also carry an `error` message.

```vue
<FyStepWizard
  v-model="activeStep"
  v-model:states="states"
  v-model:completed="completed"
  :steps="steps"
  :validate-step="validateStep"
  :resolve-next="resolveNext"
  @snapshot="saveProgress"
>
  <template #source>Source form</template>
  <template #complete>Completed result</template>
</FyStepWizard>
```

| Prop | Type | Notes |
| --- | --- | --- |
| `steps` | `FyWizardStep[]` | Ordered `{ value, title, description?, optional? }` entries. |
| `modelValue` | `string` | Current step (`v-model`); defaults to the first declared step when omitted. |
| `states` | `Record<string, FyWizardStepState>` | Controlled state map (`v-model:states`). |
| `completed` | `boolean` | Controlled workflow completion (`v-model:completed`). |
| `context` | `T` | Consumer data passed to validation, routing, invalidation, and step slots. |
| `snapshot` | `FyWizardSnapshot` | Versioned snapshot to normalize and restore. |
| `validateStep` | `(step, context, signal) => boolean \| FyWizardValidationResult \| Promise<boolean \| FyWizardValidationResult>` | Validates the active step before moving. |
| `resolveNext` | `(step, context) => string \| null` | Selects the next branch; `null` completes the workflow. |
| `invalidateAfter` | `(changedStep, context) => string[]` | Returns step IDs reset to `pending` by `actions.invalidate`. |
| `backText` / `nextText` / `finishText` / `retryText` / `optionalText` | `string` | Visible labels, defaulting to `Back`, `Next`, `Finish`, `Retry`, and `optional`. |
| `labels` | `FyWizardLabelsInput` | Partial status, progress, step, error-history, current-status, and visited-path label overrides. |

`labels` is merged with the exported `FY_WIZARD_DEFAULT_LABELS`. Its nested `status` map can override any of `pending`, `active`, `validating`, `complete`, `error`, and `skipped`; `progress`, `errorHistory`, `showVisitedPath`, and `hideVisitedPath` are strings; and `step(index, total)`, `compactProgress(index, total)`, `errorStep(title, status)`, and `currentStatus(title, status)` format the remaining visible or ARIA text. Together with the five text props above, every generic label has an English default and can be localized.

Events are `update:modelValue(value)`, `update:states(states)`, `update:completed(completed)`, `transition(from, to)`, `validation-error(step, message?)`, `restore-error(message)`, `snapshot(snapshot)`, and `complete(snapshot)`. In the controlled API, `complete` carries the final snapshot after the final step validates and routing returns `null`; the deprecated uncontrolled final-step behavior described below emits it without a snapshot.

| Slot | Props / public type |
| --- | --- |
| Named step slot (`#source`, for example) | Runtime `{ step, state, context, actions }`; `FyWizardStepSlotProps<T>` is exported for reusable consumers, while arbitrary slot names are not inferred individually by Vue. |
| `#step-label` | `{ step, index, state, statusLabel, active, context, actions }` (`FyWizardStepLabelSlotProps<T>`) |
| `#error` | `{ step, state, message, context, actions }` (`FyWizardErrorSlotProps<T>`) |
| `#actions` | `{ step?, state?, context, completed, busy, canBack, nextLabel, actions }` (`FyWizardActionsSlotProps<T>`) |
| `#complete` | `{ actions }` |

All slots are optional and retain the default UI. The error wrapper supplies alert semantics to a custom error slot. The shared `actions` object exposes `next(): Promise<void>`, `back()`, `goTo(step)`, and `invalidate(changedStep)`. Back and direct navigation are allowed only across the actual `visitedPath`; unvisited future steps stay locked.

### Validation, branches, and invalidation

`validateStep` receives an `AbortSignal`. Observe it and pass it into cancellable SDK/RPC work: invalidation, unmounting, or an external controlled-state change can abort the transition, and stale async completions are ignored. Only one forward validation runs at a time. Validation must succeed before `resolveNext` runs, so routing observes context updates made during validation. Returning `false` or `{ valid: false, message? }`, or throwing a non-abort error, puts the current step in `error`; the inline forward action changes to **Retry** and runs validation again. A resolver exception or unknown destination is handled the same way and never publishes an invalid active step or snapshot. Returning `true` or `{ valid: true }` completes the step and follows the resolved route.

When `resolveNext` jumps over declared steps, unvisited steps between the source and destination become `skipped`; the snapshot records the real visited path, not a fabricated linear path. Call `actions.invalidate(changedStep)` when upstream data changes. `invalidateAfter` chooses the dependent step IDs; without it, the wizard invalidates steps after `changedStep` in the visited path. At the earliest invalidated visited step, history is pruned, every pruned future state returns to `pending`, and direct navigation to it is locked. If the active step was pruned, the wizard moves to the nearest retained predecessor (normally `changedStep`); if the whole path was pruned, the first declared step is restored as the active history entry. Model, states, completion, and snapshot updates remain coherent. Invalidation also cancels validation and clears completion, but the consumer remains responsible for clearing dependent domain data or results.

Snapshots are detached, JSON-only objects with `version`, `activeStep`, `visitedPath`, `states`, and `completed`. Restoration rejects an unsupported version or an empty step list, drops unknown and duplicate visited IDs, repairs an invalid active step, and makes a non-completed active step `active`. Duplicate `steps[].value` definitions fail fast in state creation/component setup and produce a controlled normalization error instead of silently collapsing. `FyStepWizard` contains no storage API: persistence, snapshot version migration, domain-data restoration, and revalidation belong to the consuming plugin.

### Responsive and accessible behavior

Desktop renders the complete horizontal path. At 720px and below, the compact view focuses the current step, retains every error step, and provides an expandable, localized visited-path control; the Back/Next actions remain sticky. Every status uses an icon and text label as well as color. A visually hidden `role="status"` live region remains mounted at every width, including desktop. The wizard reports controlled or internal validation as busy, locks unvisited controls, links the active error to its step, and moves focus to new content or validation errors; reduced-motion preferences disable status animation.

### Deprecated 1.x compatibility

For 1.x consumers only, `canContinue(from, to)` and the `blocked(reason?)` event remain as deprecated compatibility behavior. Omitting `modelValue`, `states`, and `completed` retains the old uncontrolled mode. A legacy consumer that supplies only `modelValue` starts with the declaration-order prefix through that step as its Back history; canonical consumers that supply `states` or `snapshot` retain only their actual recorded path. When `canContinue` is present and the stateful `states`, `completed`, `snapshot`, `validateStep`, and `resolveNext` props are absent, pressing Next on the final declared step emits `complete` immediately and once, with or without `modelValue`, and does **not** call `canContinue(from, from)`. New plugins should use the three controlled v-model bindings plus `validateStep`; compatibility mode does not provide the full stateful workflow contract.

## Legacy static plugins

A static plugin (plain `ui/index.html` + `ui/app.js`, no build step) does **not** use this package — it imports the SDK directly from `./sdk.js`. Both styles are fully supported by `dev` and `build`. Migrating an existing static plugin to the Vue/Codex kit is optional; see [Getting Started](/en/plugins/getting-started) for the scaffolded layout.

## Next steps

- [Getting Started](/en/plugins/getting-started) — the create + dev + build loop.
- [UI Micro-frontend](/en/plugins/ui-microfrontend) — the `FengYuClient` API the pickers and shell wrap.
- [SDK & CLI](/en/plugins/sdk-cli) — the full SDK + CLI reference.
