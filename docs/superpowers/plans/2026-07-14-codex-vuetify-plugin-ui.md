# Codex Vuetify Plugin UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship an official `@fengyu/plugin-ui` Vue/Vuetify package and make it the pre-activated default UI produced by `fengyu plugin create`.

**Architecture:** A standalone Vue 3 library owns the Codex theme, Vuetify defaults, environment synchronization, and FengYu-specific compound components. The CLI scaffolds a Vite application that bundles Vue, Vuetify, the UI package, and the SDK into the plugin iframe; CLI dev/build detect this project shape while preserving the legacy static-HTML path.

**Tech Stack:** Node.js 20+, TypeScript 5.9, Vue 3.5.39, Vuetify 3.9.x, Vite 7.1.x, Vitest 3.2.x, Vue Test Utils 2.4.x, Playwright, `@fengyu/plugin-sdk` 1.0.0, Node test runner.

## Global Constraints

- Plugin UI remains self-contained inside the sandboxed iframe; do not inject host CSS or the host Vuetify instance.
- `vue`, `vuetify`, and `@fengyu/plugin-sdk` are peer dependencies of `@fengyu/plugin-ui`; generated applications install and bundle them.
- The public setup entry point is `createFengYuVuetify(options?)`; generated projects require no additional theme activation.
- Base controls remain Vuetify controls; do not add pass-through wrappers such as `FyButton` or `FyTextField`.
- Compound components must not require Vue Router or Pinia.
- Colors come from Vuetify semantic theme tokens; no hard-coded page background or body text colors.
- Unknown theme/locale falls back to dark/English; cancellation is not an error; permission and timeout states are explicit.
- Existing static HTML plugins must continue to pass `dev`, `validate`, and `build` unchanged.
- `fengyu plugin create` installs dependencies by default; `--no-install` skips installation and installation failure preserves the scaffold.
- `fengyu plugin build` runs the frontend build before validation and packaging, and never emits a partial `.fyp` after failure.

---

## File Map

### New UI package

- `plugin-ui/vue/package.json` — package metadata, peer/dev dependencies, scripts, exports.
- `plugin-ui/vue/tsconfig.json` — library and declaration compilation.
- `plugin-ui/vue/vite.config.ts` — library build and Vitest configuration.
- `plugin-ui/vue/playwright.config.ts` — light/dark and responsive visual tests.
- `plugin-ui/vue/src/theme.ts` — light/dark theme definitions.
- `plugin-ui/vue/src/defaults.ts` — Vuetify density, variants, radii, and elevation defaults.
- `plugin-ui/vue/src/createFengYuVuetify.ts` — Vuetify factory and environment binding.
- `plugin-ui/vue/src/client.ts` — injected SDK client and `useFengYuClient()`.
- `plugin-ui/vue/src/styles/codex.css` — token-based focus, typography, border, and responsive refinements.
- `plugin-ui/vue/src/components/*.vue` — compound components, one component per file.
- `plugin-ui/vue/src/index.ts` — stable public exports.
- `plugin-ui/vue/test/*.test.ts` — theme, component, SDK, and accessibility behavior.
- `plugin-ui/vue/e2e/Workbench.vue`, `plugin-ui/vue/e2e/main.ts`, `plugin-ui/vue/e2e/index.html` — deterministic visual fixture.
- `plugin-ui/vue/e2e/workbench.spec.ts` — Playwright visual and axe checks.

### CLI and template

- `plugin-cli/templates/vue-codex/*` — generated Vue/Vite project, including the workbench `App.vue`.
- `plugin-cli/src/create.mjs` — template rendering, install runner, recovery errors.
- `plugin-cli/src/commands.mjs` — child-process helper shared by create/dev/build.
- `plugin-cli/src/project.mjs` — detect `vue-vite` versus `static` projects.
- `plugin-cli/src/dev.mjs` — retain static server and add Vite/simulator orchestration.
- `plugin-cli/src/build.mjs` — run optional frontend build before validation/package.
- `plugin-cli/src/cli.mjs` — parse `--no-install` and call the new build path.
- `plugin-cli/test/create.test.mjs`, `plugin-cli/test/dev.test.mjs`, `plugin-cli/test/build.test.mjs` — focused CLI tests.
- `plugin-cli/test/fixtures/static-plugin/*` — legacy compatibility fixture.

### Docs and automation

- `docs/zh/plugins/getting-started.md`, `docs/en/plugins/getting-started.md` — default template flow.
- `docs/zh/plugins/sdk-cli.md`, `docs/en/plugins/sdk-cli.md` — flags and lifecycle.
- `docs/zh/plugins/ui-microfrontend.md`, `docs/en/plugins/ui-microfrontend.md` — Vuetify/CSP/theme model.
- `docs/zh/plugins/ui-components.md`, `docs/en/plugins/ui-components.md` — new component reference.
- `docs/.vitepress/config.ts` — navigation entries for the component pages.
- `.github/workflows/plugin-tooling.yml` — package, CLI, and visual checks.

---

### Task 1: UI Package, Codex Themes, and Host Environment Binding

**Files:**
- Create: `plugin-ui/vue/package.json`
- Create: `plugin-ui/vue/tsconfig.json`
- Create: `plugin-ui/vue/vite.config.ts`
- Create: `plugin-ui/vue/src/theme.ts`
- Create: `plugin-ui/vue/src/defaults.ts`
- Create: `plugin-ui/vue/src/client.ts`
- Create: `plugin-ui/vue/src/createFengYuVuetify.ts`
- Create: `plugin-ui/vue/src/styles/codex.css`
- Create: `plugin-ui/vue/src/index.ts`
- Test: `plugin-ui/vue/test/theme.test.ts`
- Test: `plugin-ui/vue/test/environment.test.ts`

**Interfaces:**
- Consumes: `FengYuClient`, `Environment`, and `Theme` from `@fengyu/plugin-sdk`.
- Produces: `createFengYuVuetify(options?: FengYuVuetifyOptions): Vuetify`, `bindFengYuEnvironment(vuetify, client): Promise<() => void>`, `provideFengYuClient(app, client): void`, and `useFengYuClient(): FengYuClient`.

- [ ] **Step 1: Add package metadata and build configuration**

Create `plugin-ui/vue/package.json` with package name `@fengyu/plugin-ui`, version `1.0.0`, ESM/type/style exports from `dist`, peer ranges `vue:^3.5.0`, `vuetify:^3.9.0`, and `@fengyu/plugin-sdk:^1.0.0`. Add scripts `build`, `typecheck`, `test`, `dev:e2e`, and `test:visual`; use the same Vite/Vitest/TypeScript versions as `OfficialPlugins/plugin-email/ui-src/package.json`, plus `@playwright/test` and `@axe-core/playwright` as dev dependencies. Configure Vite library entry as `src/index.ts`, externalize all three peer dependencies, emit declarations with `vue-tsc`, and use `jsdom` for Vitest.

- [ ] **Step 2: Write failing theme and environment tests**

```ts
// plugin-ui/vue/test/theme.test.ts
import { describe, expect, it } from 'vitest'
import { createFengYuVuetify, fengyuCodexDark, fengyuCodexLight } from '../src'

describe('Codex Vuetify theme', () => {
  it('registers dark and light themes with compact defaults', () => {
    const vuetify = createFengYuVuetify({ theme: 'light', locale: 'zh-CN' })
    expect(vuetify.theme.global.name.value).toBe('fengyuCodexLight')
    expect(fengyuCodexDark.dark).toBe(true)
    expect(fengyuCodexLight.dark).toBe(false)
    expect(vuetify.defaults.value.VBtn.density).toBe('comfortable')
    expect(vuetify.defaults.value.VCard.elevation).toBe(0)
  })
})

// plugin-ui/vue/test/environment.test.ts
import { describe, expect, it, vi } from 'vitest'
import { bindFengYuEnvironment, createFengYuVuetify } from '../src'

it('applies ready state, reacts to environment events, and unsubscribes', async () => {
  let environmentHandler: ((value: unknown) => void) | undefined
  const stop = vi.fn()
  const client = {
    ready: vi.fn().mockResolvedValue({ theme: 'light', locale: 'zh-CN' }),
    on: vi.fn((_event, handler) => { environmentHandler = handler; return stop }),
  }
  const vuetify = createFengYuVuetify()
  const dispose = await bindFengYuEnvironment(vuetify, client as never)
  expect(vuetify.theme.global.name.value).toBe('fengyuCodexLight')
  environmentHandler?.({ theme: 'dark', locale: 'en' })
  expect(vuetify.theme.global.name.value).toBe('fengyuCodexDark')
  dispose()
  expect(stop).toHaveBeenCalledOnce()
})
```

- [ ] **Step 3: Run tests and verify the missing-module failure**

Run: `cd plugin-ui/vue && npm install && npm test -- --run test/theme.test.ts test/environment.test.ts`

Expected: FAIL because `../src` and the public functions do not exist.

- [ ] **Step 4: Implement themes, defaults, client injection, and environment normalization**

Use the approved neutral palettes from `frontend/src/plugins/md3-themes.ts`, renamed to `fengyuCodexLight`/`fengyuCodexDark`. Implement exact normalization and binding behavior:

```ts
// plugin-ui/vue/src/createFengYuVuetify.ts
export type FengYuVuetifyOptions = { theme?: string; locale?: string }

export function themeName(value?: string) {
  return value === 'light' ? 'fengyuCodexLight' : 'fengyuCodexDark'
}

export function localeName(value?: string) {
  return value?.toLowerCase().startsWith('zh') ? 'zhHans' : 'en'
}

export function createFengYuVuetify(options: FengYuVuetifyOptions = {}) {
  return createVuetify({
    blueprint: md3,
    components,
    directives,
    defaults: fengyuDefaults,
    icons: { defaultSet: 'mdi', aliases, sets: { mdi } },
    locale: { locale: localeName(options.locale), fallback: 'en', messages: { en, zhHans } },
    theme: {
      defaultTheme: themeName(options.theme),
      themes: { fengyuCodexDark, fengyuCodexLight },
    },
  })
}

export async function bindFengYuEnvironment(vuetify: ReturnType<typeof createFengYuVuetify>, client: FengYuClient) {
  const apply = (environment: Partial<Environment>) => {
    vuetify.theme.global.name.value = themeName(environment.theme)
    vuetify.locale.current.value = localeName(environment.locale)
  }
  apply(await client.ready())
  return client.on('environment', value => apply(value as Partial<Environment>))
}
```

In `defaults.ts`, set `VBtn`/`VTextField`/`VSelect` to `comfortable`, `VCard` elevation to `0`, dialogs/menus to the smallest necessary elevation, and rounded values to `lg`/`xl`. In `codex.css`, style only semantic Vuetify variables, focus-visible outlines, global font stacks, narrow-screen shell behavior, and reduced motion. Export all public symbols from `src/index.ts`.

- [ ] **Step 5: Run package checks**

Run: `cd plugin-ui/vue && npm run typecheck && npm test && npm run build`

Expected: all tests PASS and `dist/index.js`, `dist/index.d.ts`, and `dist/style.css` exist.

- [ ] **Step 6: Commit**

```bash
git add plugin-ui/vue
git commit -m "✨ feat(plugin-ui): add Codex Vuetify foundation"
```

---

### Task 2: Responsive Shell and Standard State Components

**Files:**
- Create: `plugin-ui/vue/src/components/FyPluginShell.vue`
- Create: `plugin-ui/vue/src/components/FyPageHeader.vue`
- Create: `plugin-ui/vue/src/components/FyToolbar.vue`
- Create: `plugin-ui/vue/src/components/FyEmptyState.vue`
- Create: `plugin-ui/vue/src/components/FyLoadingState.vue`
- Create: `plugin-ui/vue/src/components/FyErrorState.vue`
- Create: `plugin-ui/vue/src/components/FyPermissionNotice.vue`
- Modify: `plugin-ui/vue/src/index.ts`
- Test: `plugin-ui/vue/test/layout-and-states.test.ts`

**Interfaces:**
- Consumes: Vuetify installed by `createFengYuVuetify()`.
- Produces: named Vue component exports listed above. `FyPluginShell` accepts `title`, `items`, `modelValue`, and `railBreakpoint`; state components expose action slots/events without SDK dependencies.

- [ ] **Step 1: Write failing shell and state tests**

```ts
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createFengYuVuetify, FyErrorState, FyPluginShell } from '../src'

const global = { plugins: [createFengYuVuetify()] }

it('renders navigation and emits the selected item', async () => {
  const wrapper = mount(FyPluginShell, {
    global,
    props: { title: 'Workbench', modelValue: 'tasks', items: [
      { value: 'overview', title: 'Overview', icon: 'mdi-view-dashboard-outline' },
      { value: 'tasks', title: 'Tasks', icon: 'mdi-format-list-checks' },
    ] },
    slots: { default: '<main>Content</main>' },
  })
  expect(wrapper.text()).toContain('Workbench')
  await wrapper.find('[data-nav="overview"]').trigger('click')
  expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['overview'])
})

it('exposes a retry action with readable error text', async () => {
  const wrapper = mount(FyErrorState, { global, props: { title: 'Failed', message: 'Timed out' } })
  await wrapper.get('[data-action="retry"]').trigger('click')
  expect(wrapper.text()).toContain('Timed out')
  expect(wrapper.emitted('retry')).toHaveLength(1)
})
```

- [ ] **Step 2: Run the test and verify missing component exports**

Run: `cd plugin-ui/vue && npm test -- --run test/layout-and-states.test.ts`

Expected: FAIL because `FyPluginShell` and `FyErrorState` are not exported.

- [ ] **Step 3: Implement the shell and state contracts**

Define shared public types in `FyPluginShell.vue`:

```ts
export interface FyNavItem { value: string; title: string; icon?: string; disabled?: boolean }
```

Use `v-navigation-drawer`, `v-list`, and `v-main`; emit `update:modelValue` only for enabled items. At widths below `railBreakpoint` (default `720`), use a temporary drawer controlled by the built-in menu action. `FyPageHeader` exposes `title`, optional `description`, and `actions` slot. `FyToolbar` wraps its default slot and collapses with flex wrapping. Empty/loading/error/permission components use `role="status"` or `role="alert"`, visible icon plus text, and named `action` slots. `FyErrorState` supplies a default retry button emitting `retry`.

- [ ] **Step 4: Run focused and package tests**

Run: `cd plugin-ui/vue && npm test -- --run test/layout-and-states.test.ts && npm run typecheck`

Expected: PASS with no Vue warnings.

- [ ] **Step 5: Commit**

```bash
git add plugin-ui/vue/src/components plugin-ui/vue/src/index.ts plugin-ui/vue/test/layout-and-states.test.ts
git commit -m "✨ feat(plugin-ui): add shell and state components"
```

---

### Task 3: SDK-Integrated File, Directory, and Notification Components

**Files:**
- Create: `plugin-ui/vue/src/components/FyFilePicker.vue`
- Create: `plugin-ui/vue/src/components/FyDirectoryPicker.vue`
- Create: `plugin-ui/vue/src/components/FyNotificationCenter.vue`
- Create: `plugin-ui/vue/src/composables/useFengYuNotify.ts`
- Modify: `plugin-ui/vue/src/index.ts`
- Test: `plugin-ui/vue/test/sdk-components.test.ts`

**Interfaces:**
- Consumes: `useFengYuClient()` and SDK `FileRef`.
- Produces: file/directory pickers with `v-model: FileRef | null`, `cancel`, and `error`; `useFengYuNotify(): { notify(message): Promise<void>; localMessages }`.

- [ ] **Step 1: Write failing SDK component tests**

```ts
it('selects a file and treats cancellation as a normal empty result', async () => {
  const open = vi.fn().mockResolvedValueOnce({ id: '1', name: 'a.xlsx', kind: 'file', access: 'read', size: 10 })
    .mockResolvedValueOnce(null)
  const client = fakeClient({ files: { open } })
  const wrapper = mountWithClient(FyFilePicker, client)
  await wrapper.get('[data-action="pick-file"]').trigger('click')
  await flushPromises()
  expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toMatchObject({ name: 'a.xlsx' })
  await wrapper.get('[data-action="pick-file"]').trigger('click')
  await flushPromises()
  expect(wrapper.emitted('cancel')).toHaveLength(1)
  expect(wrapper.find('[role="alert"]').exists()).toBe(false)
})

it('shows permission errors without automatically retrying', async () => {
  const open = vi.fn().mockRejectedValue(new Error('Permission denied'))
  const wrapper = mountWithClient(FyFilePicker, fakeClient({ files: { open } }))
  await wrapper.get('[data-action="pick-file"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('Permission denied')
  expect(open).toHaveBeenCalledOnce()
})
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `cd plugin-ui/vue && npm test -- --run test/sdk-components.test.ts`

Expected: FAIL with missing SDK component exports.

- [ ] **Step 3: Implement one-request-at-a-time SDK behavior**

Both pickers must guard concurrent clicks with `loading`, clear the previous error before a new request, emit `cancel` for `null`, and emit `error` plus render `FyPermissionNotice` for permission messages or `FyErrorState` otherwise. `FyFilePicker` forwards `extensions` and `filters`; `FyDirectoryPicker` accepts `mode: 'input' | 'output'` and calls the matching SDK method.

Implement notification fallback exactly as:

```ts
export async function sendFengYuNotification(client: FengYuClient, message: string, local: Ref<string[]>) {
  try {
    const accepted = await client.notify(message)
    if (!accepted) local.value.push(message)
  } catch {
    local.value.push(message)
  }
}
```

`FyNotificationCenter` renders local messages in a Vuetify snackbar queue with close buttons and `aria-live="polite"`.

- [ ] **Step 4: Run tests and typecheck**

Run: `cd plugin-ui/vue && npm test -- --run test/sdk-components.test.ts && npm run typecheck`

Expected: PASS; cancellation produces no alert and permission rejection performs no automatic retry.

- [ ] **Step 5: Commit**

```bash
git add plugin-ui/vue/src plugin-ui/vue/test/sdk-components.test.ts
git commit -m "✨ feat(plugin-ui): add SDK-integrated components"
```

---

### Task 4: Workflow Components, Workbench Fixture, Visual Regression, and Accessibility

**Files:**
- Create: `plugin-ui/vue/src/components/FyStepWizard.vue`
- Create: `plugin-ui/vue/src/components/FyConfirmDialog.vue`
- Create: `plugin-ui/vue/src/components/FyTaskTable.vue`
- Modify: `plugin-ui/vue/src/index.ts`
- Create: `plugin-ui/vue/e2e/index.html`
- Create: `plugin-ui/vue/e2e/main.ts`
- Create: `plugin-ui/vue/e2e/Workbench.vue`
- Create: `plugin-ui/vue/playwright.config.ts`
- Create: `plugin-ui/vue/e2e/workbench.spec.ts`
- Test: `plugin-ui/vue/test/workflow-components.test.ts`

**Interfaces:**
- Consumes: shell/state components from Task 2 and notification/file components from Task 3.
- Produces: `FyStepWizard`, `FyConfirmDialog`, `FyTaskTable`, and the exact workbench later copied into the CLI template.

- [ ] **Step 1: Write failing workflow tests**

```ts
it('blocks invalid wizard navigation and emits completion after the final valid step', async () => {
  const wrapper = mount(FyStepWizard, { global, props: {
    steps: [{ value: 'file', title: 'File' }, { value: 'run', title: 'Run' }],
    canContinue: vi.fn((_from, to) => to !== 'run'),
  } })
  await wrapper.get('[data-action="next"]').trigger('click')
  expect(wrapper.emitted('blocked')).toHaveLength(1)
  expect(wrapper.emitted('update:modelValue')).toBeUndefined()
})

it('requires an explicit confirm action', async () => {
  const wrapper = mount(FyConfirmDialog, { global, attachTo: document.body,
    props: { modelValue: true, title: 'Delete task?', destructive: true } })
  await wrapper.get('[data-action="confirm"]').trigger('click')
  expect(wrapper.emitted('confirm')).toHaveLength(1)
})
```

- [ ] **Step 2: Run the workflow tests and verify missing exports**

Run: `cd plugin-ui/vue && npm test -- --run test/workflow-components.test.ts`

Expected: FAIL because the workflow components do not exist.

- [ ] **Step 3: Implement workflow components**

Use these stable public types:

```ts
export interface FyWizardStep { value: string; title: string; optional?: boolean }
export interface FyTaskRow { id: string; name: string; status: 'queued'|'running'|'success'|'error'|'cancelled'; detail?: string }
```

`FyStepWizard` accepts `steps`, `modelValue`, and async/sync `canContinue(from, to)`; it exposes slots named by step value, emits `blocked(error?)`, `update:modelValue`, and `complete`. `FyConfirmDialog` uses `v-dialog`, returns focus to the opener, closes on Esc, and labels destructive action textually. `FyTaskTable` uses `v-data-table`, status icon plus label, controlled `page/itemsPerPage`, and standard loading/error/empty slots.

- [ ] **Step 4: Build the approved workbench and visual assertions**

Create `Workbench.vue` using `FyPluginShell`, `FyPageHeader`, Vuetify form controls, `FyFilePicker`, `FyTaskTable`, and `FyNotificationCenter`. Seed deterministic task rows `sales-2026.xlsx` and `contacts.xlsx`. Add Playwright checks:

```ts
for (const theme of ['dark', 'light'] as const) {
  test(`${theme} desktop workbench`, async ({ page }) => {
    await page.goto(`/?theme=${theme}`)
    await expect(page.locator('[data-workbench]')).toHaveScreenshot(`${theme}-desktop.png`)
  })

  test(`${theme} narrow workbench`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(`/?theme=${theme}`)
    await expect(page.locator('[data-workbench]')).toHaveScreenshot(`${theme}-narrow.png`)
  })
}

test('narrow workbench has no page overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/?theme=dark')
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
})
```

Run axe against `[data-workbench]` and fail on serious/critical violations. Store the reviewed baseline screenshots under `plugin-ui/vue/e2e/workbench.spec.ts-snapshots/`.

- [ ] **Step 5: Run unit, visual, and accessibility checks**

Run: `cd plugin-ui/vue && npm test && npx playwright install chromium && npm run test:visual`

Expected: unit tests PASS; four approved theme/viewport screenshots match; axe reports no serious or critical violations.

- [ ] **Step 6: Commit**

```bash
git add plugin-ui/vue
git commit -m "✨ feat(plugin-ui): add workflow components and workbench"
```

---

### Task 5: Make the Vue/Codex Template the CLI Default

**Files:**
- Create: `plugin-cli/templates/vue-codex/package.json.tpl`
- Create: `plugin-cli/templates/vue-codex/manifest.json.tpl`
- Create: `plugin-cli/templates/vue-codex/index.html`
- Create: `plugin-cli/templates/vue-codex/tsconfig.json`
- Create: `plugin-cli/templates/vue-codex/vite.config.ts`
- Create: `plugin-cli/templates/vue-codex/src/main.ts`
- Create: `plugin-cli/templates/vue-codex/src/App.vue`
- Create: `plugin-cli/src/commands.mjs`
- Modify: `plugin-cli/src/create.mjs`
- Modify: `plugin-cli/src/cli.mjs`
- Modify: `plugin-cli/package.json`
- Replace: `plugin-cli/test/cli.test.mjs` with focused create/build coverage
- Test: `plugin-cli/test/create.test.mjs`

**Interfaces:**
- Consumes: Task 4 workbench and Task 1 setup APIs.
- Produces: `createPlugin(directory, id, { install = true, run = runCommand } = {})` and `runCommand(command, args, options)`.

- [ ] **Step 1: Write failing default-template and installation tests**

```js
test('create renders the activated Vue Codex template', async () => {
  const calls=[]; const run=async(...args)=>calls.push(args)
  await createPlugin(root,'com.example.demo',{run})
  const pkg=JSON.parse(await fs.readFile(path.join(root,'package.json'),'utf8'))
  assert.equal(pkg.dependencies['@fengyu/plugin-ui'],'^1.0.0')
  assert.equal(pkg.dependencies.vuetify,'^3.9.3')
  assert.match(await fs.readFile(path.join(root,'src/main.ts'),'utf8'),/createFengYuVuetify/)
  assert.deepEqual(calls[0].slice(0,2),['npm',['install']])
})

test('--no-install keeps a complete scaffold without invoking npm', async () => {
  const run=async()=>assert.fail('runner must not execute')
  await createPlugin(root,'com.example.demo',{install:false,run})
  assert.equal((await validate(root)).length,1) // ui/index.html is produced by build
  assert.ok(await fs.stat(path.join(root,'src/App.vue')))
})

test('install failure preserves generated files', async () => {
  await assert.rejects(() => createPlugin(root,'com.example.demo',{run:async()=>{throw new Error('npm failed')}}),/Scaffold created.*npm install/s)
  assert.ok(await fs.stat(path.join(root,'manifest.json')))
})
```

- [ ] **Step 2: Run CLI tests and verify failure against the static scaffold**

Run: `cd plugin-cli && npm test -- test/create.test.mjs`

Expected: FAIL because `createPlugin` has no options, template, or install runner.

- [ ] **Step 3: Add template rendering and safe installation**

Copy the reviewed `Workbench.vue` into the template and replace fixed text with `{{pluginName}}` and `{{pluginId}}`. Generated `main.ts` must be:

```ts
import { createApp } from 'vue'
import { fengyu } from '@fengyu/plugin-sdk'
import { bindFengYuEnvironment, createFengYuVuetify, provideFengYuClient } from '@fengyu/plugin-ui'
import '@fengyu/plugin-ui/style.css'
import App from './App.vue'

if (!fengyu) throw new Error('FengYu SDK requires a browser environment')
const vuetify = createFengYuVuetify()
const disposeEnvironment = await bindFengYuEnvironment(vuetify, fengyu)
const app = createApp(App)
provideFengYuClient(app, fengyu)
app.use(vuetify)
app.mount('#app')
window.addEventListener('pagehide', () => { disposeEnvironment(); fengyu.dispose() }, { once: true })
```

Implement placeholder rendering by recursively copying template files and replacing only known placeholders. On install failure, throw `Scaffold created at <root>, but npm install failed. Run: cd <root> && npm install` with the original error as `cause`.

- [ ] **Step 4: Parse `--no-install` and preserve the public create command**

In `cli.mjs`, compute `install: !args.includes('--no-install')`. Update usage and README command examples. Do not add an interactive prompt and do not retain a `--ui` flag because Vue/Codex is the selected default.

- [ ] **Step 5: Run CLI tests**

Run: `cd plugin-cli && npm test`

Expected: all create tests PASS; generated project has no copied `ui/sdk.js`.

- [ ] **Step 6: Commit**

```bash
git add plugin-cli
git commit -m "✨ feat(plugin-cli): default to Codex Vue plugins"
```

---

### Task 6: Vite-Aware Dev Host and SDK Simulator

**Files:**
- Create: `plugin-cli/src/project.mjs`
- Modify: `plugin-cli/src/dev.mjs`
- Test: `plugin-cli/test/dev.test.mjs`
- Create: `plugin-cli/test/fixtures/static-plugin/manifest.json`
- Create: `plugin-cli/test/fixtures/static-plugin/ui/index.html`

**Interfaces:**
- Consumes: `runCommand()` and generated package scripts.
- Produces: `detectProject(root): Promise<'vue-vite'|'static'>`, `dev(root, port, options?)`, and simulator message handlers for ready, notify, file calls, theme, locale, and permission denial.

- [ ] **Step 1: Write failing project detection and simulator tests**

```js
test('detectProject recognizes generated Vite and legacy static projects', async () => {
  assert.equal(await detectProject(generatedRoot),'vue-vite')
  assert.equal(await detectProject(staticFixture),'static')
})

test('Vue dev starts the project dev script and points the simulator iframe at it', async () => {
  const calls=[]
  const server=await dev(generatedRoot,4173,{run:async(...args)=>calls.push(args),uiPort:5173,open:false})
  assert.deepEqual(calls[0][0], 'npm')
  assert.deepEqual(calls[0][1], ['run','dev','--','--host','127.0.0.1','--port','5173'])
  assert.match(await fetch('http://127.0.0.1:4173/__fengyu').then(r=>r.text()),/http:\/\/127\.0\.0\.1:5173/)
  server.close()
})
```

- [ ] **Step 2: Run dev tests and verify they fail**

Run: `cd plugin-cli && npm test -- test/dev.test.mjs`

Expected: FAIL because project detection and Vite orchestration are absent.

- [ ] **Step 3: Split the static server from simulator orchestration**

Keep the current static file/SSE implementation as `devStatic`. Add `devVue` that starts the generated `npm run dev` process, waits for its TCP port, then starts the simulator. The simulator iframe source is the Vite URL for Vue projects and `/<manifest.ui.entry>` for static projects. Closing the simulator must terminate the child process.

Implement production-shaped responses:

```js
const environment={sdkVersion:'1.0.0',theme:'dark',locale:'en',platform:'web',capabilities:['rpc.invoke','files.open','notify']}
// host.ready -> environment
// notify -> true and append to inspector
// files.open -> deterministic FileRef or null
// files.inputDirectory/files.outputDirectory -> deterministic directory FileRef or null
// UI controls post environment events for theme/locale changes
// permission-denied toggle returns an error response without retrying
```

- [ ] **Step 4: Verify both project modes**

Run: `cd plugin-cli && npm test -- test/dev.test.mjs`

Expected: PASS; tests close every server and child-process stub without open handles.

- [ ] **Step 5: Commit**

```bash
git add plugin-cli/src plugin-cli/test
git commit -m "✨ feat(plugin-cli): add Vite plugin dev simulator"
```

---

### Task 7: Frontend-Aware Build Pipeline and Backward Compatibility

**Files:**
- Create: `plugin-cli/src/build.mjs`
- Modify: `plugin-cli/src/cli.mjs`
- Test: `plugin-cli/test/build.test.mjs`
- Modify: `plugin-cli/README.md`

**Interfaces:**
- Consumes: `detectProject`, `runCommand`, `validate`, `readManifest`, and `writeZip`.
- Produces: `buildPlugin(root, options?: BuildOptions): Promise<{ output: string; files: number }>`, where `BuildOptions` contains optional `out`, `run`, and test-observation `hooks.onValidate`/`hooks.onPackage` callbacks.

- [ ] **Step 1: Write failing build-order, failure, and legacy tests**

```js
test('Vue build runs before validation and packaging', async () => {
  const order=[]
  const result=await buildPlugin(root,{run:async()=>{
    order.push('frontend')
    await fs.mkdir(path.join(root,'ui'),{recursive:true})
    await fs.writeFile(path.join(root,'ui/index.html'),'<div id="app"></div>')
  }, hooks:{onValidate:()=>order.push('validate'),onPackage:()=>order.push('package')}})
  assert.deepEqual(order,['frontend','validate','package'])
  assert.ok(result.output.endsWith('.fyp'))
})

test('failed frontend build emits no fyp', async () => {
  await assert.rejects(()=>buildPlugin(root,{run:async()=>{throw Object.assign(new Error('vite failed'),{code:2})}}),/vite failed/)
  assert.deepEqual((await fs.readdir(path.join(root,'dist-package')).catch(()=>[])).filter(x=>x.endsWith('.fyp')),[])
})

test('legacy static plugin skips npm build', async () => {
  await buildPlugin(staticFixture,{out,run:async()=>assert.fail('must not run npm')})
  assert.ok(await fs.stat(out))
})
```

- [ ] **Step 2: Run build tests and verify missing build orchestration**

Run: `cd plugin-cli && npm test -- test/build.test.mjs`

Expected: FAIL because `buildPlugin` does not exist.

- [ ] **Step 3: Implement atomic build orchestration**

For `vue-vite`, call `npm run build` first. Validate only after it succeeds. Write the archive to a temporary sibling named `<output>.tmp-<pid>` and rename to the final path after `writeZip` succeeds; remove the temporary file in `finally`. Static projects skip npm. Preserve child exit code/cause when rethrowing.

Update `cli.mjs` build branch to call only `buildPlugin`, then print `Built <output> (<files> files)`.

- [ ] **Step 4: Run all CLI checks and inspect a generated archive**

Run: `cd plugin-cli && npm test`

Then run a local-package smoke test by replacing generated dependency ranges with `file:` paths to `plugin-ui/vue` and `plugin-sdk/typescript`, followed by `npm install && node ../plugin-cli/bin/fengyu.mjs plugin build .`.

Expected: tests PASS; the `.fyp` contains `manifest.json`, `ui/index.html`, and hashed JS/CSS assets; it does not contain `src/` or `node_modules/`.

- [ ] **Step 5: Commit**

```bash
git add plugin-cli
git commit -m "✨ feat(plugin-cli): build Vue assets before packaging"
```

---

### Task 8: Documentation, CI, and Final End-to-End Verification

**Files:**
- Modify: `docs/zh/plugins/getting-started.md`
- Modify: `docs/en/plugins/getting-started.md`
- Modify: `docs/zh/plugins/sdk-cli.md`
- Modify: `docs/en/plugins/sdk-cli.md`
- Modify: `docs/zh/plugins/ui-microfrontend.md`
- Modify: `docs/en/plugins/ui-microfrontend.md`
- Create: `docs/zh/plugins/ui-components.md`
- Create: `docs/en/plugins/ui-components.md`
- Modify: `docs/.vitepress/config.ts`
- Create: `.github/workflows/plugin-tooling.yml`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: all public APIs and CLI behavior delivered by Tasks 1–7.
- Produces: published usage contract and CI gates; no new runtime API.

- [ ] **Step 1: Write the bilingual component and migration documentation**

Document this exact quick start in both languages:

```bash
fengyu plugin create ./my-plugin --id com.example.my-plugin
cd my-plugin
fengyu plugin dev .
fengyu plugin build .
```

Explain that create installs by default, `--no-install` skips it, `main.ts` already binds theme/locale, base controls are Vuetify controls, and FengYu components are imported from `@fengyu/plugin-ui`. Include one complete file-picker example and one `FyStepWizard` example. State that legacy static plugins remain supported and migration is optional.

- [ ] **Step 2: Add CI gates**

Create `plugin-tooling.yml` for pushes/PRs touching `plugin-ui/**`, `plugin-cli/**`, or `plugin-sdk/**`. Use Node 20 and run:

```yaml
- run: cd plugin-sdk/typescript && npm install && npm test
- run: cd plugin-ui/vue && npm install && npm run typecheck && npm test && npm run build
- run: cd plugin-cli && npm install && npm test
- run: npx playwright install --with-deps chromium
  working-directory: plugin-ui/vue
- run: npm run test:visual
  working-directory: plugin-ui/vue
```

Upload visual diffs only on failure. Cache npm directories by the three lockfiles.

- [ ] **Step 3: Add the changelog entry**

Under the current unreleased section, record the new official UI package, default CLI template, Vite-aware dev/build, theme/locale synchronization, and static-plugin compatibility. Do not change release versions in this task.

- [ ] **Step 4: Run complete verification from clean package installs**

Run:

```bash
cd plugin-sdk/typescript && npm install && npm test
cd ../../plugin-ui/vue && npm install && npm run typecheck && npm test && npm run build && npm run test:visual
cd ../../plugin-cli && npm install && npm test
cd .. && npm run docs:build
```

Expected: all commands exit `0`; no snapshot changes remain unreviewed.

- [ ] **Step 5: Run generated-plugin smoke test**

Create a temporary plugin with `--no-install`, point `@fengyu/plugin-ui` and `@fengyu/plugin-sdk` to the just-built local directories, install, build, validate, and inspect the archive. Start `fengyu plugin dev`, switch light/dark and English/Chinese in the simulator, pick/cancel a file, trigger a notification, and confirm no browser console errors.

Expected: workbench renders in both themes, locale changes propagate, cancellation is not shown as an error, the notification appears, and the final `.fyp` validates.

- [ ] **Step 6: Request code review**

Invoke the `requesting-code-review` skill and review the implementation against `docs/superpowers/specs/2026-07-14-codex-vuetify-plugin-ui-design.md`, with special attention to iframe isolation, peer dependency duplication, static plugin compatibility, focus behavior, and partial archive cleanup.

- [ ] **Step 7: Commit**

```bash
git add docs .github/workflows/plugin-tooling.yml CHANGELOG.md
git commit -m "📝 docs: document the official plugin UI kit"
```
