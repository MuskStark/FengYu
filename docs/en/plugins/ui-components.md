---
title: UI Components
description: "The @infinia/plugin-ui kit — a Codex-style Vuetify 3 foundation for FengYu plugins. Shell, file/directory pickers, step wizard, task table, notification center, and the theme/locale bindings the scaffolded main.ts wires up."
lang: en
---

# UI Components

`@infinia/plugin-ui` is the official Vuetify 3 (Material Design 3) component library for FengYu plugins. The project scaffolded by `fengyu plugin create` depends on it, and its `src/main.ts` already wires up the Vuetify instance, theme/locale binding, and client injection — so you only compose components. Import the pieces you use from a single entry:

```ts
import {
  FyFilePicker,
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
| `FyPageHeader` | A page title, optional `description`, and a trailing `#actions` slot for toolbar buttons. |
| `FyToolbar` | A horizontal button row for `#actions`. |
| `FyFilePicker` | SDK-backed file button — wraps `client.files.open`. v-model is the `FileRef` or `null`. |
| `FyDirectoryPicker` | SDK-backed directory button — `mode: 'input' \| 'workspace' \| 'output'` selects a readable input, writable project workspace, or fresh output directory. |
| `FyStepWizard` | Linear, value-keyed multi-step controller with a `canContinue` gate. |
| `FyTaskTable` | Read-only task list (`tasks: FyTaskRow[]`) rendered with `v-data-table`; status shown as icon + label. |
| `FyNotificationCenter` | Fallback snackbar stack. Call its `notify(msg)` (via template ref) to surface a message; forwards to the host and falls back locally if the host rejects. |
| `FyConfirmDialog` | v-model dialog with `destructive` styling; emits `confirm` / `cancel`. |
| `FyEmptyState` / `FyLoadingState` / `FyErrorState` / `FyPermissionNotice` | Standardized empty / loading / error / permission-denied panels with `title`, `message`, and `icon`. `FyErrorState` emits `retry`. |

The notification composable `useFengYuNotify(client)` and `sendFengYuNotification(client, message)` are also exported for non-component use.

## Example: a file picker

`FyFilePicker` wraps `client.files.open` so a plugin can request a host file through one button. Cancellation — the host resolving `null` — is a normal empty result: it emits `update:modelValue(null)` and `cancel`, and renders **no** error. Permission denials render `FyPermissionNotice`; other errors render `FyErrorState` with a retry. This is the complete pattern the scaffolded `App.vue` uses:

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { FyFilePicker, FyNotificationCenter, useFengYuClient } from '@infinia/plugin-ui'
import type { FileRef } from '@infinia/plugin-sdk'

const client = useFengYuClient()
const selectedFile = ref<FileRef | null>(null)
const notifications = ref<InstanceType<typeof FyNotificationCenter> | null>(null)

async function onFile(file: FileRef | null): Promise<void> {
  selectedFile.value = file
  if (file) await notifications.value?.notify(`Selected ${file.name}`)
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
  <FyNotificationCenter ref="notifications" />
</template>
```

| Prop | Type | Notes |
| --- | --- | --- |
| `modelValue` | `FileRef \| null` | v-model: the selected file, or `null`. |
| `extensions` | `string[]` | Extension allowlist forwarded to the host file dialog. |
| `filters` | `FileFilter[]` | Named extension filters forwarded to the host. |
| `label` | `string` | Button label (default `Choose file`). |

Emits: `update:modelValue`, `cancel`, `error`. `FyDirectoryPicker` has the same shape plus `mode: 'input' | 'workspace' | 'output'` (default `input`). Workspace mode calls `client.files.workspaceDirectory()` and requires `files.write`.

## Example: a step wizard

`FyStepWizard` is a linear, value-keyed multi-step controller. `steps` is an ordered list of `{ value, title, optional? }`; the current step is a v-model keyed by `value`; each step's content comes from a named slot. `canContinue(from, to)` may be sync or async and gates each forward transition — returning `false` emits `blocked` and stays put. Forward navigation from the last step emits `complete`.

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { FyStepWizard } from '@infinia/plugin-ui'
import type { FyWizardStep } from '@infinia/plugin-ui'

const steps: FyWizardStep[] = [
  { value: 'source', title: 'Source' },
  { value: 'options', title: 'Options' },
  { value: 'review', title: 'Review' },
]
const current = ref(steps[0].value)

// Gate the Source → Options transition on a real (async) check.
async function canContinue(from: string, _to: string): Promise<boolean> {
  if (from === 'source') {
    // e.g. validate that a file was picked before proceeding
    return true
  }
  return true
}
</script>

<template>
  <FyStepWizard
    v-model="current"
    :steps="steps"
    :can-continue="canContinue"
    @complete="console.log('wizard complete')"
  >
    <template #source>
      <p class="pa-4">Pick a source file.</p>
    </template>
    <template #options>
      <p class="pa-4">Tune import options.</p>
    </template>
    <template #review>
      <p class="pa-4">Review and finish.</p>
    </template>
  </FyStepWizard>
</template>
```

| Prop | Type | Notes |
| --- | --- | --- |
| `steps` | `FyWizardStep[]` | Ordered `{ value, title, optional? }` entries. |
| `modelValue` | `string` | Current step value (v-model). Defaults to the first step. |
| `canContinue` | `(from, to) => boolean \| Promise<boolean>` | Gates forward transitions. |

Emits: `update:modelValue`, `blocked(reason?)`, `complete`. Back navigation ignores `canContinue` and is hidden on the first step.

## Legacy static plugins

A static plugin (plain `ui/index.html` + `ui/app.js`, no build step) does **not** use this package — it imports the SDK directly from `./sdk.js`. Both styles are fully supported by `dev` and `build`. Migrating an existing static plugin to the Vue/Codex kit is optional; see [Getting Started](/en/plugins/getting-started) for the scaffolded layout.

## Next steps

- [Getting Started](/en/plugins/getting-started) — the create + dev + build loop.
- [UI Micro-frontend](/en/plugins/ui-microfrontend) — the `FengYuClient` API the pickers and shell wrap.
- [SDK & CLI](/en/plugins/sdk-cli) — the full SDK + CLI reference.
