---
title: UI Components
description: "The @infinia/plugin-ui kit — the Infinia React 19 template kit for FengYu plugins. Focused-workbench shell, pages, states, pickers, select/combobox, step wizard, notifications, i18n, and live theme/locale binding."
lang: en
---

# UI Components

`@infinia/plugin-ui` (2.1) is the official React 19 template kit for FengYu plugin UIs. The project scaffolded by `fengyu init` depends on it, and its `src/main.tsx` already hands the complete bootstrap and teardown lifecycle to `mountFengYuApp` — so you only compose components. Import the pieces you use from a single entry:

```tsx
import {
  FilePicker,
  Page,
  PageHeader,
  PluginBar,
  PluginShell,
  StepWizard,
  useFengYuClient,
  mountFengYuApp,
} from '@infinia/plugin-ui'
import '@infinia/plugin-ui/style.css'
```

One stylesheet import covers all styling: the kit is built as library-mode Tailwind (`source(none)` scanning only its own sources) and ships one compiled `dist/plugin-ui.css` — plugin projects never run Tailwind themselves.

The kit deliberately ships **no Aceternity UI components** (their license forbids redistributing source files). Fetch those per project with `fengyu add` — see [Aceternity UI Components](/en/plugins/aceternity-ui).

## Design tokens: the Infinia language

The kit shares one design language with the host app and the store (values mirrored verbatim from the host `zai.css`): a warm-white canvas, white panels, `#e5e2db` hairline borders, and gold `#eab04b` as the single loud interaction color — a gold fill always pairs dark ink text. All tokens are `--c-*` CSS variables on `:root`, with a dark set under `.dark` on `<html>` (toggled by `bindFengYuEnvironment`):

| Token | Light | Dark | Role |
| --- | --- | --- | --- |
| `--c-canvas` | `#faf9f6` | `#09090b` | App canvas |
| `--c-panel` | `#ffffff` | `#141416` | Panels, bars, inputs |
| `--c-raised` | `#ffffff` | `#1c1c20` | Raised surfaces (menus, popovers) |
| `--c-muted-surface` | `#f3f1ec` | `#101013` | Muted fills, skeleton rows |
| `--c-ink` / `--c-ink-2` / `--c-ink-3` | ink tiers | ink tiers | Primary / secondary / faint text |
| `--c-line` / `--c-line-strong` | `#e5e2db` / `#d8d3c8` | `#29292d` / `#38383e` | Hairline borders |
| `--c-hover` | ink 4.5% | white 5% | Hover wash |
| `--c-gold` / `--c-gold-hover` | `#eab04b` / `#d99e33` | `#f6bd60` / `#ffd28a` | The single loud interaction color |
| `--c-gold-ink` | `#18181b` | `#18181b` | Text on a gold fill (gold always pairs dark ink) |
| `--c-accent` | `#885400` | `#f6bd60` | Focus outline (2px `:focus-visible`) |
| `--c-tag` | `#e9e5dc` | `#26262c` | Neutral tag/chip background |
| `--c-success(-bg)` / `--c-warning(-bg)` / `--c-danger(-bg)` | semantic pairs | semantic pairs | Status colors and their tinted backgrounds |
| `--c-input` / `--c-input-border` | input fill / border | input fill / border | Form controls (`--c-input-border-focused` on focus) |

The tokens are also mapped into Tailwind theme utilities inside the kit (`bg-canvas`, `bg-panel`, `bg-muted-surface`, `text-ink`, `text-ink-2`, `border-line`, `bg-gold`, `text-gold-ink`, `bg-hover`, …), which is how the components themselves are styled. Two signature rules ship with the stylesheet: `.infinia-hex` (the hexagon clip-path behind `HexMark`) and the 2px accent `:focus-visible` outline. The `cn(...inputs)` helper (clsx + tailwind-merge) is exported for composing class lists the same way the kit does, and the common SDK types (`FengYuClient`, `Environment`, `Theme`, `FileRef`, `FileFilter`) are re-exported so plugin code has stable imports. All components carry stable `data-*` attributes (`data-plugin-shell`, `data-wizard-step`, `data-action`, …) for end-to-end tests.

## Bootstrap: `mountFengYuApp` and the client hook

The scaffolded `src/main.tsx` is the whole bootstrap (this is the `react-java` template, verbatim):

```tsx
import { fengyu } from '@infinia/plugin-sdk'
import { mountFengYuApp } from '@infinia/plugin-ui'
import '@infinia/plugin-ui/style.css'
import App from './App'

if (!fengyu) throw new Error('FengYu SDK requires a browser environment')
const client = fengyu
await mountFengYuApp({ root: App, client })
```

`mountFengYuApp` owns the complete iframe UI lifecycle: it binds the environment, wraps your root in `FengYuClientProvider` + `NotifyProvider` (and `FengYuI18nProvider` when `messages` is passed), creates the React root, and unmounts/unsubscribes/disposes everything on `pagehide`. It returns an idempotent disposer. Options (`MountFengYuAppOptions`):

| Option | Type | Notes |
| --- | --- | --- |
| `root` | `ComponentType \| ReactNode` | Your root component (or a ready-made element). |
| `client` | `FengYuClient` | The SDK client (the `fengyu` singleton in an iframe). |
| `target` | `string \| Element` | Mount target; defaults to `#app`. |
| `messages` | `FengYuMessageTables` | Flat-key i18n tables (`{ en: {...}, zh: {...} }`); wired to the host locale. |
| `wrap` | `(tree) => ReactNode` | Escape hatch for plugin-specific providers; receives the default tree. |
| `onEnvironment` | `(environment) => void` | Fires on every host environment update (theme/locale already applied). |
| `onReadyError` | `(error) => void` | The host ready handshake failed or timed out (UI still renders with defaults). |

Inside the tree, retrieve the client with `useFengYuClient()` — it throws early when no client is in scope instead of failing on the first host round-trip. The lower-level exports, for custom bootstraps, are `FengYuClientProvider` / `FengYuClientContext` and:

| Export | Purpose |
| --- | --- |
| `bindFengYuEnvironment(client, options?)` | Applies the current host environment once, then reacts to `environment` events. Flips the `.dark` class on `<html>` and pushes the locale into the i18n runtime. Subscribes **before** the ready handshake and falls back to defaults after a 3s timeout (standalone `vite dev` without a host still renders). Returns the unsubscribe function. |
| `themeClass(value?)` | Maps an `Environment.theme` to `'light' \| 'dark'` (the class set on `<html>`). |
| `localeName(value?)` | Maps a BCP-47-ish `Environment.locale` to `'en' \| 'zh'`. |

## Template chrome

The 2.x shell is a **focused workbench without a sidebar**: the host panel already provides back navigation and plugin identity, so the plugin keeps only task-level chrome — a full-bleed content area, an optional top focus bar, and an optional bottom status bar.

| Component | Purpose |
| --- | --- |
| `PluginShell` | The workbench frame: a full-height `h-dvh` column (canvas background) that stacks `PluginBar` / header / content / `StatusBar`. |
| `PluginBar` | Top focus bar. `tabs?: PluginBarTab[]` renders the view switcher as Infinia capsules (white pill + gold active pill); controlled via `active` + `onNavigate`, or self-held with `defaultActive`. Optional `title` (left) and `right` slot (context actions/status). Omit `tabs` entirely for a pure action bar; omit the bar for single-purpose plugins. |
| `PluginHeader` | Content-area header row: `HexMark` icon + `name` + `category` chip + mono `version` + `right` actions. |
| `StatusBar` | Bottom status bar: hairline top border, mono 11px, `left` and `right` slots — isomorphic to the host app's status bar. |
| `HexMark` | The hexagon plugin mark (gold fill + dark-ink icon): `children` icon, `size` (default 26). |
| `Chip` | Neutral tag capsule (tag background) for category/version/secondary info. |
| `StatusChip` | Semantic status capsule with a real status dot: `tone: 'success' \| 'warning' \| 'danger' \| 'idle'`. |
| `GoldButton` | Primary button: gold fill + dark-ink text, press feedback, disabled state. Spreads native button props. |
| `GhostButton` | Secondary button: panel fill + hairline border. Spreads native button props. |

`PluginBarTab` is `{ value, title, icon? }`. This is the complete shell of the scaffolded app:

```tsx
import {
  GoldButton, Page, PageHeader, PluginBar, PluginShell, StatusBar, useFengYuClient,
} from '@infinia/plugin-ui'

export default function App() {
  const client = useFengYuClient()
  return (
    <PluginShell>
      <PluginBar
        active={view}
        onNavigate={setView}
        tabs={[{ value: 'home', title: 'Home' }]}
      />
      <Page>
        <PageHeader
          title="Hello, worker"
          description="UI → host RPC → worker, end to end."
          right={<GoldButton onClick={sayHello}>Call hello</GoldButton>}
        />
      </Page>
      <StatusBar left={<span>worker 127.0.0.1:24057</span>} right={<span>{pluginId}</span>} />
    </PluginShell>
  )
}
```

## Pages and headers

| Component | Purpose |
| --- | --- |
| `Page` | Content container: centered column with `maxWidth` (default 980px). `fluid` removes the cap for editor/canvas workspaces; `fullHeight` lets such pages fill the viewport (flex column, scroll inside). |
| `PageHeader` | Section heading row: `title`, optional `description`, trailing `right` action slot. |

## State components

| Component | Purpose |
| --- | --- |
| `EmptyState` | Composed empty state — `icon`, `title`, `message`, `action`: one line and one way out. |
| `LoadingState` | Skeleton rows shaped like the content they stand in for: `label`, `rows` (default 3). Live-region semantics. |
| `ErrorState` | Inline error panel (danger tint): `title`, `message`, `onRetry` + `retryLabel`, `role="alert"`. |
| `PermissionNotice` | Permission-denial notice (warning tint) — pickers surface these instead of errors. |
| `Progress` | Hairline progress bar: `value` is a 0–1 fraction, `status: 'determinate' \| 'indeterminate'`, optional `label` (determinate mode shows the percentage when no label is given). |

## Pickers

| Component | Purpose |
| --- | --- |
| `FilePicker` | SDK-backed file picker around `client.files.open`. `value: FileRef \| null`, `onChange`, `extensions`, `filters`, `label`, `onCancel`, `onError`. |
| `DirectoryPicker` | SDK-backed directory picker around `client.files.inputDirectory`. `value: string \| null` (the path), `onChange`, `label`, `onCancel`, `onError`. |
| `isPermissionError(error)` | Detects a permission/access denial from a rejected pick. |

Both pickers keep the Vue 2.x kit's behavioral contract: concurrent clicks are guarded while a pick is in flight, a `null` host result is a **normal cancellation** (`onChange(null)` + `onCancel`, no alert), permission denials render a `PermissionNotice`, and other errors render an `ErrorState` whose retry re-runs the pick. Default labels are Simplified Chinese in this release — pass `label` (and your own surrounding strings) for other UI languages.

```tsx
import { useState } from 'react'
import { FilePicker, useFengYuNotify } from '@infinia/plugin-ui'
import type { FileRef } from '@infinia/plugin-sdk'

function SourcePicker() {
  const { notify } = useFengYuNotify()
  const [file, setFile] = useState<FileRef | null>(null)
  return (
    <FilePicker
      label="Choose spreadsheet"
      extensions={['xlsx', 'csv']}
      value={file}
      onChange={async (next) => {
        setFile(next)
        if (next) await notify(`Selected ${next.name}`, { tone: 'success' })
      }}
    />
  )
}
```

## Select and Combobox

Native `<select>` / `<datalist>` popovers cannot be reskinned, so the kit ships self-drawn equivalents (hairline rounded menu on a raised surface, gold check on the selected option, full keyboard loop ↑↓ / Enter / Esc, click-outside and Escape dismissal):

| Component | Purpose |
| --- | --- |
| `Select` | Controlled dropdown, the `<select>` equivalent: `value: string`, `options: SelectOption[]`, `onChange`, `placeholder`, `size: 'sm' \| 'md'`, `disabled`. |
| `Combobox` | Free-text input + suggestion popover, the `<datalist>` equivalent: typing filters the options and commits immediately (values outside the list are allowed); picking a suggestion and free input both go through `onCommit(value)`. Same `options`/`size`/`disabled` shape. |

`SelectOption` is `{ value: string, label: ReactNode, disabled?: boolean }`.

## ConfirmDialog

A confirmation-first dialog with exactly two verbs: `open`, `title`, `message`, `confirmLabel` / `cancelLabel` (defaults 确认 / 取消), `destructive` (danger-toned confirm), `busy`, `onConfirm`, `onCancel`, and an optional `children` slot between message and buttons. Focus starts on the confirm button, Escape cancels, and a backdrop click cancels.

## Step wizard

`StepWizard` renders the Infinia step rail over a framework-neutral state machine (`src/wizard.ts`). **The snapshot format is identical to the Vue 2.x kit** (`FY_WIZARD_SNAPSHOT_VERSION = 1`), so wizard progress persisted by a Vue-era plugin survives the React migration untouched.

| Prop | Type | Notes |
| --- | --- | --- |
| `steps` | `StepWizardStep<T>[]` | Ordered `{ value, title, description?, optional?, validate?, render }` entries. `validate(context, step)` runs before advancing (sync or async, returns `{ valid, message? }`; a throw becomes `{ valid: false, message }`); `render({ step, state, context, actions })` returns the step body. |
| `context` | `T` | Caller-owned wizard context (form state); passed to every `validate`/`render`. The wizard never mutates it. |
| `completed` | `boolean` | External completion flag (result screen); freezes navigation. |
| `snapshot` | `FyWizardSnapshot` | Restore seed — normalize with `normalizeWizardSnapshot` upstream (e.g. from localStorage). |
| `onSnapshot` | `(snapshot) => void` | Emitted after every transition with the fresh snapshot; persist it here. |
| `labels` | `StepWizardLabels` | `next` / `back` / `finish` buttons plus the `status` map and formatter overrides (defaults in `FY_WIZARD_DEFAULT_LABELS`); every visible string is replaceable. |
| `footer` | `(props) => ReactNode` | Optional custom footer; receives `busy`, `canBack`, `nextLabel`, `actions`, `completed`. Defaults to back/next + compact progress. |

The six step statuses are `pending`, `active`, `validating`, `complete`, `error`, and `skipped`; an `error` state may carry an `error` message (rendered as an inline `ErrorState`). The shared `actions` object exposes `next(): Promise<void>`, `back()`, `goTo(step)`, and `invalidate(changedStep)` — invalidation resets every step **after** `changedStep` in declaration order back to `pending`.

```tsx
import { useState } from 'react'
import { EmptyState, GoldButton, Progress, StepWizard, type StepWizardStep } from '@infinia/plugin-ui'

interface Ctx { sourceFile?: string }

export function SplitWizard() {
  const [context, setContext] = useState<Ctx>({})
  const steps: StepWizardStep<Ctx>[] = [
    {
      value: 'source',
      title: 'Source file',
      validate: async () => context.sourceFile
        ? { valid: true }
        : { valid: false, message: 'Choose a workbook first' },
      render: ({ actions }) => (
        <div className="grid gap-3">
          <GoldButton onClick={() => { setContext((c) => ({ ...c, sourceFile: 'ledger.xlsx' })); actions.invalidate('source') }}>
            Choose workbook
          </GoldButton>
          {context.sourceFile
            ? <Progress value={1} label="ready" />
            : <EmptyState title="No workbook yet" message="Pick an .xlsx file to continue" />}
        </div>
      ),
    },
    { value: 'mode', title: 'Import mode', optional: true, render: () => <p>Sheet, column, or complex rules…</p> },
  ]
  return (
    <StepWizard
      steps={steps}
      context={context}
      labels={{ next: 'Next', back: 'Back', finish: 'Finish' }}
      onSnapshot={(s) => localStorage.setItem('wizard', JSON.stringify(s))}
    />
  )
}
```

The state machine is exported for direct use: `createWizardStates`, `invalidateWizardStates`, `buildWizardSnapshot`, `normalizeWizardSnapshot` (rejects an unsupported version or invalid step list, drops unknown/duplicate visited ids, repairs an invalid active step, and makes a non-completed active step `active`), `guardWizardStepDefinitions` (duplicate or blank `steps[].value` definitions fail fast), plus the `FyWizard*` types and `FY_WIZARD_DEFAULT_LABELS`. `StepWizard` contains no storage API — persistence and domain-data restoration belong to the consuming plugin.

## Notifications

`mountFengYuApp` mounts a `NotifyProvider` for you. `useFengYuNotify()` returns `{ notify, dismiss, messages }`: `notify(message, { tone, timeout })` forwards to the host's unified surface (in-app toast + native OS notification + notification center; no manifest permission), and when the host rejects or throws, the message is mirrored into a local fallback queue rendered bottom-right by `NotifyHost`. `tone` is `info` (default) / `success` / `warning` / `error`; `timeout` defaults to 5s, `-1` keeps the notice until dismissed. Notifiers bound to the same client share one queue, so a notification raised anywhere in the tree reaches the single host. `sendFengYuNotification(client, message, options?)` is the non-component equivalent.

## i18n

Pass flat-key message tables to `mountFengYuApp` and read them through the hook — the host locale drives everything, a plugin never ships a language switcher:

```tsx
import { mountFengYuApp, useFengYuI18n } from '@infinia/plugin-ui'

await mountFengYuApp({
  root: App,
  client,
  messages: {
    en: { title: 'Split complete', pick: 'Choose a file' },
    zh: { title: '拆分完成', pick: '选择文件' },
  },
})

function Header() {
  const { t, locale } = useFengYuI18n()
  return <h2>{t('title')}</h2>   // positional {0} placeholders interpolate into t(key, ...args)
}
```

`createFengYuI18n(tables, fallback = 'en')` builds the runtime (same catalog shape and `t()` semantics as the Vue 2.x kit), `FengYuI18nProvider` installs it, `useFengYuI18n()` subscribes and re-renders on locale change, and `normalizeFengYuLocale` maps host locales onto your tables. Details on [Internationalization](/en/plugins/i18n).

## Legacy static plugins

A static plugin (plain `ui/index.html` + `ui/app.js`, no build step) does **not** use this package — it imports the SDK directly from `./sdk.js`. `check` and `build` accept both styles (the `ui/` tree is packaged as-is); the `dev` simulator requires the Vite `ui-src` tree. Migrating an existing static plugin to the React kit is optional; see [Getting Started](/en/plugins/getting-started) for the scaffolded layout.

## Next steps

- [Getting Started](/en/plugins/getting-started) — the create + dev + build loop.
- [UI Micro-frontend](/en/plugins/ui-microfrontend) — the `FengYuClient` API the pickers and shell wrap.
- [SDK & CLI](/en/plugins/sdk-cli) — the full SDK + CLI reference.
- [Aceternity UI Components](/en/plugins/aceternity-ui) — fetching licensed Aceternity components with `fengyu add`.
