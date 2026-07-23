# Plugin UI Host Consistency Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every icon form emitted by the official CLI render correctly through `@infinia/plugin-ui`, keep plugin theme tokens value-identical to the host, and encode the contract in the repository skills.

**Architecture:** Keep the iframe and package boundaries unchanged. Centralize icon classification inside `plugin-ui`, retain duplicated publishable theme definitions but enforce value equality with a repository test, and add the invariant to plugin development and release workflows.

**Tech Stack:** Vue 3, Vuetify 3, TypeScript, Vitest, Vite, Markdown skills.

---

### Task 1: Lock the CLI icon contract with failing component tests

**Files:**
- Modify: `plugin-ui/vue/test/layout-and-states.test.ts`

- [ ] **Step 1: Add a failing `FyPluginShell` test**

Import `FyEmptyState` alongside the existing components. Mount `FyPluginShell` with the exact CLI
input `mdi-home-outline`, then assert:

```ts
const item = wrapper.get('[data-nav="home"]')
expect(item.find('svg.fy-icon').exists()).toBe(false)
expect(item.find('.v-icon').exists()).toBe(true)
expect(item.find('.v-icon').classes()).toContain('mdi-home-outline')
```

- [ ] **Step 2: Add a failing `FyEmptyState` test**

Mount `FyEmptyState` with `icon: 'mdi-inbox-outline'` and assert the same `v-icon` versus `FyIcon`
contract.

- [ ] **Step 3: Run the focused tests and verify RED**

Run:

```bash
cd plugin-ui/vue
npm test -- --run test/layout-and-states.test.ts
```

Expected: both new tests fail because `mdi-*` currently renders through `svg.fy-icon`.

### Task 2: Implement one correct icon classifier

**Files:**
- Create: `plugin-ui/vue/src/icon.ts`
- Modify: `plugin-ui/vue/src/components/FyPluginShell.vue`
- Modify: `plugin-ui/vue/src/components/FyEmptyState.vue`

- [ ] **Step 1: Add the internal classifier**

Create:

```ts
/** Distinguish inline SVG path data from Vuetify `mdi-*` icon names. */
export function isSvgPathIcon(icon: string | undefined): boolean {
  return !!icon && !/^mdi-/i.test(icon) && /^m/i.test(icon)
}
```

- [ ] **Step 2: Replace both local classifiers**

Import `isSvgPathIcon` in both components, delete their duplicated `isIconPath` functions, and use
the shared helper in their templates.

- [ ] **Step 3: Correct public comments**

Document both icon forms as supported. Remove claims that the bundled MDI font is unreliable,
because `createFengYuVuetify` explicitly imports `@mdi/font/css/materialdesignicons.css`.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run:

```bash
cd plugin-ui/vue
npm test -- --run test/layout-and-states.test.ts test/icon.test.ts
```

Expected: all focused icon tests pass; path data still renders through `FyIcon`.

### Task 3: Lock host/plugin theme equality with a failing test

**Files:**
- Modify: `plugin-ui/vue/test/theme.test.ts`

- [ ] **Step 1: Import the host themes**

Import `md3Dark` and `md3Light` from
`../../../frontend/src/plugins/md3-themes`.

- [ ] **Step 2: Add exact theme-contract assertions**

Add a test that asserts:

```ts
expect(fengyuCodexLight.colors).toEqual(md3Light.colors)
expect(fengyuCodexLight.variables).toEqual(md3Light.variables)
expect(fengyuCodexDark.colors).toEqual(md3Dark.colors)
expect(fengyuCodexDark.variables).toEqual(md3Dark.variables)
```

- [ ] **Step 3: Run the focused test and verify RED**

Run:

```bash
cd plugin-ui/vue
npm test -- --run test/theme.test.ts
```

Expected: the equality test fails and reports the existing palette drift.

### Task 4: Align the theme contract without changing existing host colors

**Files:**
- Modify: `frontend/src/plugins/md3-themes.ts`
- Modify: `plugin-ui/vue/src/theme.ts`

- [ ] **Step 1: Complete the host semantic token set**

Add the plugin-required `surface-container-low` and warning tokens to both host themes. Use the
existing plugin values for warning semantics because they do not replace any host token.

- [ ] **Step 2: Copy the authoritative host values into the plugin themes**

Align every existing plugin `colors` and `variables` value with `md3Light`/`md3Dark`, while retaining
the plugin-specific exported theme names.

- [ ] **Step 3: Run the focused test and verify GREEN**

Run:

```bash
cd plugin-ui/vue
npm test -- --run test/theme.test.ts
```

Expected: the exact light/dark theme-contract assertions pass.

### Task 5: Update the plugin development skill

**Files:**
- Modify: `.agents/skills/fengyu-plugin-dev/SKILL.md`

- [ ] **Step 1: Add the UI ownership invariant**

Add a concise section stating:

- The host frontend is the visual source of truth.
- `@infinia/plugin-ui` must provide host-consistent components and tokens inside the iframe.
- Official CLI templates are executable compatibility contracts, not illustrative snippets.
- If a legal template input fails in SDK/UI, fix the toolchain rather than rewriting plugin business
  UI to avoid it.
- `mdi-*` and `@mdi/js` paths are both supported where the component API accepts icons.

- [ ] **Step 2: Add focused verification requirements**

Require changes to CLI UI templates, theme definitions, icon handling, or public UI components to
run the relevant CLI template/component contract tests, plugin-ui unit tests, typecheck, build, and
visual tests when presentation changes.

### Task 6: Update the plugin-tooling release skill

**Files:**
- Modify: `.agents/skills/plugin-tooling-release/SKILL.md`

- [ ] **Step 1: Add the compatibility release gate**

Require release verification to prove:

- CLI scaffolded icon/component inputs render through the released UI kit.
- Host and plugin theme contract tests pass.
- `npm run prepack` and `npm run test:visual` pass for `plugin-ui`.

- [ ] **Step 2: Keep commands consistent with the actual CLI**

Do not introduce a standalone `fengyu plugin validate` command. Use `fengyu plugin build`, whose
validation stage is unconditional, for official-plugin checks.

### Task 7: Full verification and diff audit

**Files:**
- Verify all files modified by Tasks 1–6.

- [ ] **Step 1: Run the full UI unit suite**

```bash
cd plugin-ui/vue
npm test
```

- [ ] **Step 2: Run type checking**

```bash
cd plugin-ui/vue
npm run typecheck
```

- [ ] **Step 3: Build the publishable UI package**

```bash
cd plugin-ui/vue
npm run build
```

- [ ] **Step 4: Validate skill structure**

Run the available skill validator against both `.agents/skills/fengyu-plugin-dev` and
`.agents/skills/plugin-tooling-release`. If the generic validator is incompatible with repository
skills, validate frontmatter, line count, and internal command references directly.

- [ ] **Step 5: Audit only intended changes**

```bash
git diff --check
git status --short
git diff -- plugin-ui/vue frontend/src/plugins/md3-themes.ts .agents/skills/fengyu-plugin-dev/SKILL.md .agents/skills/plugin-tooling-release/SKILL.md docs/superpowers
```

Confirm that existing `plugin-dev` and unrelated documentation modifications remain untouched.
Do not commit, push, tag, or publish.
