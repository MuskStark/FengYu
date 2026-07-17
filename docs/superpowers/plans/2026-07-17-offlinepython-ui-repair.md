# Offline Python Builder UI Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore Offline Python Builder interaction, keep the official FengYu icon-rail shell, and move project selection into the default Build & Verify workflow.

**Architecture:** Fix the breakpoint mismatch once in the shared `FyPluginShell`, then keep project ownership in `App.vue` while moving the visible directory picker into `BuildVerifyPanel.vue`. Preserve the official SDK bridge and Vuetify theme, and use small source-contract tests plus component tests to lock down the composition.

**Tech Stack:** Vue 3.5, TypeScript, Vuetify 3, Vitest, Vue Test Utils, FengYu `@infinia/plugin-ui`, FengYu `@infinia/plugin-sdk`, Vite.

---

## File Map

- Modify `plugin-ui/vue/src/components/FyPluginShell.vue`: align Vuetify's mobile breakpoint with the shell's `railBreakpoint`.
- Modify `plugin-ui/vue/test/layout-and-states.test.ts`: reproduce the desktop iframe scrim regression and cover narrow-screen behavior.
- Create `OfficialPlugins/plugin-offlinepython/ui-src/src/uiComposition.test.ts`: assert default navigation, official icons, and project-picker ownership.
- Modify `OfficialPlugins/plugin-offlinepython/ui-src/src/App.vue`: default to Build & Verify, add navigation icons, and retain shared project state without rendering a page-level picker.
- Modify `OfficialPlugins/plugin-offlinepython/ui-src/src/panels/BuildVerifyPanel.vue`: render the project picker, emit project changes, reset project-scoped state, and clean up polling.
- Modify `OfficialPlugins/plugin-offlinepython/ui-src/src/panels/ConfigPanel.vue`: use the shared project and official empty/error states.
- Modify `OfficialPlugins/plugin-offlinepython/ui-src/src/panels/DeployPanel.vue`: use translated labels and semantic log-surface styling.
- Modify `OfficialPlugins/plugin-offlinepython/ui-src/src/panels/DoctorPanel.vue`: use translated labels and mounted lifecycle loading.
- Modify `OfficialPlugins/plugin-offlinepython/ui-src/src/i18n.ts`: add every visible English and Simplified Chinese string required by the four panels.
- Create `OfficialPlugins/plugin-offlinepython/ui-src/src/i18n.test.ts`: lock down the new translation keys and fallback behavior.
- Preserve the user's existing uncommitted files: `.github/workflows/plugin-tooling.yml`, `OfficialPlugins/plugin-offlinepython/src/main/java/fan/summer/fengyu/plugin/offlinepython/domain/PlatformMatcher.java`, and `OfficialPlugins/plugin-offlinepython/ui-src/src/officialSdk.test.ts`.

### Task 1: Fix the Shared Official Shell Breakpoint

**Files:**
- Modify: `plugin-ui/vue/test/layout-and-states.test.ts`
- Modify: `plugin-ui/vue/src/components/FyPluginShell.vue`

- [ ] **Step 1: Add the failing desktop iframe regression test**

Append a test that creates Vuetify after setting a 1014 px viewport, mounts `FyPluginShell`, and asserts that the drawer is a rail but not temporary/mobile:

```ts
import { nextTick } from 'vue'

it('keeps the desktop rail permanent at plugin iframe widths', async () => {
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1014 })
  window.dispatchEvent(new Event('resize'))
  const wrapper = mount(FyPluginShell, {
    global: { plugins: [createFengYuVuetify()] },
    props: {
      title: 'Workbench',
      modelValue: 'build',
      railBreakpoint: 720,
      items: [{ value: 'build', title: 'Build', icon: 'mdi-hammer-wrench' }],
    },
    slots: { default: '<button data-content-action>Run</button>' },
  })
  await nextTick()

  const classes = wrapper.get('.v-navigation-drawer').classes()
  expect(classes).toContain('v-navigation-drawer--rail')
  expect(classes).not.toContain('v-navigation-drawer--temporary')
  expect(classes).not.toContain('v-navigation-drawer--mobile')
})
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd plugin-ui/vue
npm test -- test/layout-and-states.test.ts
```

Expected: FAIL because the drawer contains `v-navigation-drawer--temporary` and `v-navigation-drawer--mobile` at 1014 px.

- [ ] **Step 3: Align Vuetify's drawer breakpoint with `railBreakpoint`**

Add the explicit mobile breakpoint to the existing drawer:

```vue
<v-navigation-drawer
  :rail="!temporary"
  :temporary="temporary"
  :mobile-breakpoint="railBreakpoint"
  :model-value="temporary ? drawerOpen : true"
  @update:model-value="drawerOpen = $event"
>
```

Do not hide the scrim with CSS; the drawer must stop entering the contradictory mobile/temporary state.

- [ ] **Step 4: Add and verify the narrow-screen behavior**

Add this second test:

```ts
it('uses a temporary labeled drawer below the shell breakpoint', async () => {
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: 640 })
  window.dispatchEvent(new Event('resize'))
  const wrapper = mount(FyPluginShell, {
    global: { plugins: [createFengYuVuetify()] },
    props: {
      title: 'Workbench',
      modelValue: 'build',
      railBreakpoint: 720,
      items: [{ value: 'build', title: 'Build', icon: 'mdi-hammer-wrench' }],
    },
  })
  await nextTick()

  expect(wrapper.get('.v-navigation-drawer').classes()).toContain('v-navigation-drawer--temporary')
  expect(wrapper.find('.v-app-bar-nav-icon').exists()).toBe(true)
})
```

Run the same focused command and expect all tests in the file to PASS.

- [ ] **Step 5: Run the complete plugin-ui test suite**

Run:

```bash
cd plugin-ui/vue
npm test
```

Expected: PASS with no warnings or unhandled errors.

- [ ] **Step 6: Commit the shared root-cause fix**

```bash
git add plugin-ui/vue/src/components/FyPluginShell.vue plugin-ui/vue/test/layout-and-states.test.ts
git commit -m "🐛 fix(plugin-ui): align shell drawer breakpoint"
```

### Task 2: Move Project Selection into the Default Build Workflow

**Files:**
- Create: `OfficialPlugins/plugin-offlinepython/ui-src/src/uiComposition.test.ts`
- Modify: `OfficialPlugins/plugin-offlinepython/ui-src/src/App.vue`
- Modify: `OfficialPlugins/plugin-offlinepython/ui-src/src/panels/BuildVerifyPanel.vue`

- [ ] **Step 1: Write the failing composition contract test**

Create a source-contract test that asserts the intended ownership without mocking the SDK bridge:

```ts
import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const source = (file: string) => fs.readFileSync(path.resolve('src', file), 'utf8')

describe('Offline Python UI composition', () => {
  it('opens on Build & Verify with an official icon rail', () => {
    const app = source('App.vue')
    expect(app).toContain("const active = ref('build')")
    expect(app).toContain("icon: 'mdi-hammer-wrench'")
    expect(app).toContain("icon: 'mdi-tune-variant'")
    expect(app).toContain("icon: 'mdi-package-variant-closed'")
    expect(app).toContain("icon: 'mdi-stethoscope'")
  })

  it('owns the visible project picker inside Build & Verify', () => {
    const app = source('App.vue')
    const build = source('panels/BuildVerifyPanel.vue')
    expect(app).not.toContain('FyDirectoryPicker')
    expect(build).toContain('FyDirectoryPicker')
    expect(build).toContain("(e: 'update:project', project: FileRef): void")
    expect(app).toContain('@update:project="project = $event"')
  })
})
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
cd OfficialPlugins/plugin-offlinepython/ui-src
npm test -- src/uiComposition.test.ts
```

Expected: FAIL because Config is the default, navigation icons are absent, and the picker is still rendered by `App.vue`.

- [ ] **Step 3: Update `App.vue` to own only shared state and routing**

Make these concrete changes:

```ts
import { computed, inject, ref } from 'vue'
import { FENGYU_CLIENT_KEY, FyPluginShell, useFengYuNotify } from '@infinia/plugin-ui'

const nav = computed(() => [
  { value: 'build', title: t('opb.nav.build'), icon: 'mdi-hammer-wrench' },
  { value: 'config', title: t('opb.nav.config'), icon: 'mdi-tune-variant' },
  { value: 'deploy', title: t('opb.nav.deploy'), icon: 'mdi-package-variant-closed' },
  { value: 'doctor', title: t('opb.nav.doctor'), icon: 'mdi-stethoscope' },
])
const active = ref('build')
```

Remove `FyPageHeader` and `FyDirectoryPicker` from `App.vue`. Render panels directly inside the container, and pass the shared project update through Build & Verify:

```vue
<v-container fluid class="pa-4">
  <BuildVerifyPanel
    v-if="active === 'build'"
    :client="client"
    :project="project"
    :t="t"
    @update:project="project = $event"
    @toast="toast"
  />
  <ConfigPanel v-else-if="active === 'config'" :client="client" :project="project" :t="t" @toast="toast" />
  <DeployPanel v-else-if="active === 'deploy'" :client="client" :t="t" @toast="toast" />
  <DoctorPanel v-else :client="client" :t="t" @toast="toast" />
</v-container>
```

- [ ] **Step 4: Implement project selection and cleanup in `BuildVerifyPanel.vue`**

Import `onUnmounted`, `FyDirectoryPicker`, `FyEmptyState`, `FyPageHeader`, and define the translator and project update contracts:

```ts
type Translate = (key: string, ...args: (string | number)[]) => string

const props = defineProps<{
  client: FengYuClient
  project: FileRef | null
  t: Translate
}>()
const emit = defineEmits<{
  (e: 'update:project', project: FileRef): void
  (e: 'toast', msg: string): void
}>()
```

Use one cleanup helper for polling and project-scoped state:

```ts
function stopPolling() {
  if (poll) clearInterval(poll)
  poll = null
}

function selectProject(next: FileRef | null) {
  if (!next) return
  stopPolling()
  logs.value = []
  status.value = 'idle'
  building.value = false
  jobId.value = null
  emit('update:project', next)
}

onUnmounted(stopPolling)
```

Render the official empty state and its action slot when no project is selected:

```vue
<FyEmptyState
  v-if="!project"
  :title="t('opb.project.empty')"
  :message="t('opb.build.openPrompt')"
  icon="mdi-folder-open-outline"
>
  <template #action>
    <FyDirectoryPicker :label="t('opb.project.open')" @update:model-value="selectProject" />
  </template>
</FyEmptyState>
```

When a project exists, render this header before the build card. Hiding the picker while `building` prevents project changes during a running job:

```vue
<FyPageHeader :title="project.name" :description="t('opb.build.title')">
  <template #actions>
    <FyDirectoryPicker
      v-if="!building"
      :label="t('opb.project.change')"
      :model-value="project"
      @update:model-value="selectProject"
    />
  </template>
</FyPageHeader>
```

- [ ] **Step 5: Run the composition test and verify GREEN**

Run:

```bash
cd OfficialPlugins/plugin-offlinepython/ui-src
npm test -- src/uiComposition.test.ts
```

Expected: PASS.

- [ ] **Step 6: Run type checking before committing**

Run:

```bash
cd OfficialPlugins/plugin-offlinepython/ui-src
npm run typecheck
```

Expected: PASS with no unused props or event-signature errors.

- [ ] **Step 7: Commit the project-flow change**

```bash
git add OfficialPlugins/plugin-offlinepython/ui-src/src/App.vue \
  OfficialPlugins/plugin-offlinepython/ui-src/src/panels/BuildVerifyPanel.vue \
  OfficialPlugins/plugin-offlinepython/ui-src/src/uiComposition.test.ts
git commit -m "✨ feat(offlinepython): move project selection into build"
```

### Task 3: Finish Official States, Translation, and Semantic Styling

**Files:**
- Create: `OfficialPlugins/plugin-offlinepython/ui-src/src/i18n.test.ts`
- Modify: `OfficialPlugins/plugin-offlinepython/ui-src/src/i18n.ts`
- Modify: `OfficialPlugins/plugin-offlinepython/ui-src/src/panels/BuildVerifyPanel.vue`
- Modify: `OfficialPlugins/plugin-offlinepython/ui-src/src/panels/ConfigPanel.vue`
- Modify: `OfficialPlugins/plugin-offlinepython/ui-src/src/panels/DeployPanel.vue`
- Modify: `OfficialPlugins/plugin-offlinepython/ui-src/src/panels/DoctorPanel.vue`

- [ ] **Step 1: Write the failing translation contract test**

Create:

```ts
import { describe, expect, it } from 'vitest'
import { messagesFor } from './i18n'

const required = [
  'opb.project.change',
  'opb.build.openPrompt',
  'opb.build.logEmpty',
  'opb.build.completed',
  'opb.config.openPrompt',
  'opb.deploy.logEmpty',
  'opb.doctor.check',
  'opb.doctor.value',
  'opb.doctor.status',
  'opb.doctor.noChecks',
]

describe.each(['en', 'zh-CN'])('Offline Python messages for %s', (locale) => {
  it('contains every visible workflow string', () => {
    const messages = messagesFor(locale)
    for (const key of required) expect(messages[key], key).toBeTruthy()
  })
})
```

- [ ] **Step 2: Run the translation test and verify RED**

Run:

```bash
cd OfficialPlugins/plugin-offlinepython/ui-src
npm test -- src/i18n.test.ts
```

Expected: FAIL with missing required keys.

- [ ] **Step 3: Add matching English and Chinese message keys**

Add these exact entries:

```ts
// en
'opb.project.change': 'Change project',
'opb.build.openPrompt': 'Choose a project folder to build, verify, or package.',
'opb.build.logEmpty': 'Build log will appear here…',
'opb.build.completed': 'Build {0}',
'opb.build.failed': 'Build failed',
'opb.build.status.idle': 'Idle',
'opb.build.status.starting': 'Starting',
'opb.build.status.cancelled': 'Cancelled',
'opb.build.verifyOk': 'Verification passed',
'opb.build.packaged': 'Packaged: {0}',
'opb.config.openPrompt': 'Open a project from Build & Verify before editing configuration.',
'opb.config.requirementsPlaceholder': '# numpy==1.26.4',
'opb.config.pythonHint': 'For example: 3.12.10',
'opb.config.platformsHint': 'For example: win_amd64, manylinux2014_x86_64',
'opb.config.saved': 'Configuration saved',
'opb.deploy.bundleRequired': 'Select a bundle ZIP first',
'opb.deploy.venvHint': 'Where to create the virtual environment',
'opb.deploy.logEmpty': 'Install log will appear here…',
'opb.deploy.completed': 'Deploy {0}',
'opb.deploy.failed': 'Deploy failed',
'opb.doctor.check': 'Check',
'opb.doctor.value': 'Value',
'opb.doctor.status': 'Status',
'opb.doctor.noChecks': 'No checks yet',
'opb.common.ok': 'OK',
'opb.common.fail': 'FAIL',
'opb.common.error': 'Operation failed',

// zh
'opb.project.change': '更换项目',
'opb.build.openPrompt': '选择项目目录后即可构建、校验或打包。',
'opb.build.logEmpty': '构建日志将在此显示…',
'opb.build.completed': '构建状态：{0}',
'opb.build.failed': '构建失败',
'opb.build.status.idle': '空闲',
'opb.build.status.starting': '正在启动',
'opb.build.status.cancelled': '已取消',
'opb.build.verifyOk': '校验通过',
'opb.build.packaged': '已打包：{0}',
'opb.config.openPrompt': '请先在“构建 & 校验”中打开项目，再编辑配置。',
'opb.config.requirementsPlaceholder': '# numpy==1.26.4',
'opb.config.pythonHint': '例如：3.12.10',
'opb.config.platformsHint': '例如：win_amd64, manylinux2014_x86_64',
'opb.config.saved': '配置已保存',
'opb.deploy.bundleRequired': '请先选择 bundle ZIP',
'opb.deploy.venvHint': '虚拟环境的创建位置',
'opb.deploy.logEmpty': '安装日志将在此显示…',
'opb.deploy.completed': '部署状态：{0}',
'opb.deploy.failed': '部署失败',
'opb.doctor.check': '检查项',
'opb.doctor.value': '值',
'opb.doctor.status': '状态',
'opb.doctor.noChecks': '暂无检查结果',
'opb.common.ok': '正常',
'opb.common.fail': '失败',
'opb.common.error': '操作失败',
```

Keep identical key sets in both tables so neither locale renders raw keys.

- [ ] **Step 4: Pass `t` into every panel and remove hard-coded UI text**

Use the same translator type in all panels:

```ts
type Translate = (key: string, ...args: (string | number)[]) => string
const props = defineProps<{ client: FengYuClient; t: Translate }>()
```

For Config and Build & Verify, extend that type with `project: FileRef | null`. Do not retain the old `locale` prop in any panel.

Apply the keys exhaustively:

- `BuildVerifyPanel.vue`: build/verify/package/cancel buttons, status text, empty prompt, project change, log-empty text, success/failure toasts.
- `ConfigPanel.vue`: empty prompt, requirements heading/placeholder, field labels/hints, switches, and save toast/button.
- `DeployPanel.vue`: file picker, radio labels, venv label/hint, install/cancel buttons, log-empty text, and validation/completion toasts.
- `DoctorPanel.vue`: detected/missing Python chip, refresh button, table headers, OK/FAIL chips, and empty row.

Use `props.t(...)` in script and `t(...)` in templates. Remove the unused `locale` props.

In `ConfigPanel.vue`, correct the official empty-state prop from `description` to `message` and tell users to open a project from Build & Verify.

- [ ] **Step 5: Replace fixed RGB log sheets with semantic theme styling**

In Build & Verify and Deploy, replace:

```vue
<v-sheet class="pa-3" color="rgb(30,30,46)" rounded>
```

with:

```vue
<v-sheet class="pa-3 bg-surface-variant" rounded border>
```

Keep the global mono font through the official plugin-ui stylesheet; do not add a hard-coded font family or color.

- [ ] **Step 6: Make async loading and polling states exception-safe**

Add this local helper to each panel that reports errors:

```ts
function errorText(error: unknown): string {
  return error instanceof Error && error.message ? error.message : props.t('opb.common.error')
}
```

Use this concrete structure for one-shot operations such as config save, verify, package, and doctor refresh:

```ts
loading.value = true
try {
  const result = await call(props.client, method, params)
  emit('toast', result.success ? successMessage : result.summary)
} catch (error) {
  emit('toast', errorText(error))
} finally {
  loading.value = false
}
```

For `startBuild` and `startInstall`, set the running flag before the RPC, catch rejected promises, set status to `error`, emit `errorText(error)`, and clear the running flag unless a valid `jobId` started polling. For both polling functions, call `stopPolling()` and clear the running flag when `done` is true or when polling throws. Call `stopPolling()` from cancel and `onUnmounted`.

- [ ] **Step 7: Run the focused tests and verify GREEN**

Run:

```bash
cd OfficialPlugins/plugin-offlinepython/ui-src
npm test -- src/i18n.test.ts src/uiComposition.test.ts src/officialSdk.test.ts
```

Expected: PASS, including the user's existing official SDK compliance test.

- [ ] **Step 8: Run all Offline Python UI checks**

Run:

```bash
cd OfficialPlugins/plugin-offlinepython/ui-src
npm test
npm run typecheck
npm run build
```

Expected: all tests PASS, type checking PASS, and Vite emits `dist/index.html` plus hashed JS/CSS assets.

- [ ] **Step 9: Commit the UI compliance cleanup**

Stage only files changed by this task. Do not stage the user's unrelated workflow or Java changes.

```bash
git add OfficialPlugins/plugin-offlinepython/ui-src/src/i18n.ts \
  OfficialPlugins/plugin-offlinepython/ui-src/src/i18n.test.ts \
  OfficialPlugins/plugin-offlinepython/ui-src/src/panels/BuildVerifyPanel.vue \
  OfficialPlugins/plugin-offlinepython/ui-src/src/panels/ConfigPanel.vue \
  OfficialPlugins/plugin-offlinepython/ui-src/src/panels/DeployPanel.vue \
  OfficialPlugins/plugin-offlinepython/ui-src/src/panels/DoctorPanel.vue
git commit -m "🐛 fix(offlinepython): restore official interactive UI"
```

### Task 4: Build and Verify in the Real FengYu Host

**Files:**
- Verify generated output: `plugin-ui/vue/dist/`
- Verify generated output: `OfficialPlugins/plugin-offlinepython/ui-src/dist/`
- Verify packaged plugin: `OfficialPlugins/plugin-offlinepython/dist-package/*.fyp`

- [ ] **Step 1: Build the shared official UI package**

Run:

```bash
cd plugin-ui/vue
npm run typecheck
npm run build
```

Expected: PASS and refreshed `dist/` output.

- [ ] **Step 2: Reinstall the rebuilt local UI package and rebuild Offline Python**

Run:

```bash
cd OfficialPlugins/plugin-offlinepython/ui-src
npm install
npm run build
```

Expected: PASS and the plugin bundle includes the updated shell behavior.

- [ ] **Step 3: Package the plugin through the repository tooling**

Run from the repository root:

```bash
node plugin-cli/bin/fengyu.mjs plugin build OfficialPlugins/plugin-offlinepython
```

Expected: PASS and `OfficialPlugins/plugin-offlinepython/dist-package/fan.summer.offlinepython-4.0.0.fyp` is replaced with a package containing the rebuilt UI and worker.

Use the FengYu Plugins page's **Install from local** action to select that exact `.fyp` file. This is an in-scope local development install; do not select any other user file.

- [ ] **Step 4: Verify desktop interaction at the reproduced width**

At a 1280×720 outer viewport (roughly 1014 px plugin iframe):

- Open Tools → Offline Python Builder.
- Confirm Build & Verify is selected.
- Confirm the rail shows four official MDI icons and no clipped text fragments.
- Confirm no `.v-navigation-drawer__scrim` receives pointer events over `v-main`.
- Click the Build project picker and confirm the host SDK picker is invoked.
- After selection, confirm Config sees the same project.

- [ ] **Step 5: Verify narrow-screen navigation**

Set the plugin viewport below 720 px. Confirm the app-bar menu opens a temporary labeled drawer, selecting an item closes it, and content controls remain usable after the drawer closes.

- [ ] **Step 6: Run final regression commands**

Run:

```bash
cd plugin-ui/vue && npm test && npm run typecheck
cd ../../../OfficialPlugins/plugin-offlinepython/ui-src && npm test && npm run typecheck && npm run build
git diff --check
git status --short
```

Expected: all tests and builds PASS; `git diff --check` is clean; only the user's pre-existing uncommitted files remain outside the committed repair.

- [ ] **Step 7: Record browser evidence and final status**

Capture the corrected desktop page and report the exact commands run, test counts, remaining user-owned changes, and commits created. Do not claim success unless the real host click is no longer intercepted.
