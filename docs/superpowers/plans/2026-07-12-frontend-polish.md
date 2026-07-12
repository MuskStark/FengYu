# Frontend Polish — Setup Defaults, Global Status Bar, Button Alignment, Sidebar Logo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Polish the ZhiFlow 4.0 web shell in four areas — show the program-default local-DB path in the setup wizard with centered/stacked buttons, add a global bottom status bar (version + backend state incl. restarting), make every button vertically centered with its sibling, and give the sidebar a logo icon plus rail-mode centering.

**Architecture:** Backend-first for the default path (single source of truth in `DataSourceConfigService`, surfaced by `SetupController.types()`); the rest is frontend Vue/Vuetify edits. A new small Pinia store (`connection.ts`) shares backend state between the global `StatusBar` and the setup wizard's restart loop. Version comes from `package.json` via Vite `define`.

**Tech Stack:** Java 21 + Spring (`ZhiFlow` module, JUnit 5), Vue 3.5 + Vuetify 3 (MD3 blueprint, MDI icons), Pinia, Vite 6, vue-i18n. Build: `mvn` (root `pom.xml` modules: `ZhiFlow-Api`, `plugin-markdown`, `ZhiFlow`) and `npm` in `frontend/`.

## Global Constraints

- **Icons:** Vuetify is configured with the `mdi` iconset + `@mdi/font` (`frontend/src/plugins/vuetify.ts:24-27`). ALL icons in this plan are MDI (`mdi-*`). Do not introduce SVG/emoji/FontAwesome.
- **Commit style:** match the repo's emoji-prefix convention seen in recent commits (e.g. `✨ feat(frontend): …`, `🐛 fix(ai): …`).
- **i18n:** every user-facing string added must exist in BOTH `frontend/src/i18n/en.json` and `frontend/src/i18n/zh.json`.
- **No new dependencies.** Pinia, Vuetify, axios already present.
- **Backend path source of truth:** the default embedded data path is `<baseDir>/database/zhiflow` where baseDir = `<user.dir>/.zhiflow` (`DataSourceConfigService.java:32-34`). Keep exactly one place that computes it.
- **MD3 themes** (`frontend/src/plugins/md3-themes.ts`) and `tokens.css` are NOT modified.
- Branch: `4.0.0-ZhiFlow`.

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/DataSourceConfigService.java` | owns the default embedded path; `defaultEmbeddedPath()` | Modify |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/SetupController.java` | `types()` advertises the resolved default | Modify |
| `ZhiFlow/src/test/java/fan/summer/zhiflow/setup/DataSourceConfigServiceTest.java` | test the new method | Modify |
| `ZhiFlow/src/test/java/fan/summer/zhiflow/setup/SetupControllerTest.java` | test `types()` returns the default | Modify |
| `frontend/vite.config.ts` | inject `__APP_VERSION__` | Modify |
| `frontend/src/env.d.ts` | declare `__APP_VERSION__` | Modify |
| `frontend/src/stores/connection.ts` | shared backend connection state | Create |
| `frontend/src/shell/StatusBar.vue` | read state from store; 5 states + MDI icons; version | Modify |
| `frontend/src/App.vue` | render `StatusBar` unconditionally | Modify |
| `frontend/src/views/SetupWizard.vue` | default-path display already auto; stack/center buttons; drive restarting state | Modify |
| `frontend/src/views/AiChat.vue` | `align-end` → `align-center` | Modify |
| `frontend/src/views/AiAgent.vue` | `align-end` → `align-center` | Modify |
| `frontend/src/shell/Sidebar.vue` | MDI logo icon; rail-mode centering | Modify |
| `frontend/src/i18n/en.json`, `frontend/src/i18n/zh.json` | new keys | Modify |

---

## Task 1: Backend default embedded path (single source of truth)

Expose the default embedded data-file path from `DataSourceConfigService` and have `buildFromWizard()` reuse it, so there is exactly one place computing `<baseDir>/database/zhiflow`.

**Files:**
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/DataSourceConfigService.java`
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/setup/DataSourceConfigServiceTest.java`

**Interfaces:**
- Produces: `public Path defaultEmbeddedPath()` on `DataSourceConfigService` — returns `Path.of(baseDir, "database", "zhiflow")`. Used by Task 2 (`SetupController.types()`) and by `buildFromWizard()` internally.

- [ ] **Step 1: Write the failing test**

Append to `DataSourceConfigServiceTest.java` (inside the class, after the last `@Test`):

```java
    @Test
    void defaultEmbeddedPath_pointsAtDatabaseFolderUnderBaseDir() {
        DataSourceConfigService svc = newService();
        Path expected = tempDir.resolve("database/zhiflow");
        assertEquals(expected, svc.defaultEmbeddedPath());
    }

    @Test
    void buildFromWizard_embedded_blankFilePathUsesDefaultEmbeddedPath() {
        DataSourceConfigService svc = newService();
        // Blank filePath → must fall back to defaultEmbeddedPath()
        WizardParams params = new WizardParams("  ", null, null, null, null, null);
        DataSourceConfig cfg = svc.buildFromWizard(DbType.H2, params);
        assertEquals(svc.defaultEmbeddedPath().toString().replace("\\", "/"), cfg.filePath());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl ZhiFlow test -Dtest=DataSourceConfigServiceTest`
Expected: FAIL — `defaultEmbeddedPath()` does not exist (compile error).

- [ ] **Step 3: Implement `defaultEmbeddedPath()` and refactor `buildFromWizard()`**

In `DataSourceConfigService.java`, add the method near the other helpers (e.g. after `configFile()`):

```java
    /** The program-default embedded data-file path: {@code <baseDir>/database/zhiflow}. */
    public Path defaultEmbeddedPath() {
        return Path.of(baseDir, "database", "zhiflow");
    }
```

Then in `buildFromWizard(...)`, replace the inline literal with a call to it. Find the block:

```java
            String rawPath = (params.filePath() == null || params.filePath().isBlank())
                    ? Path.of(baseDir, "database", "zhiflow").toString()
                    : params.filePath();
```

Replace with:

```java
            String rawPath = (params.filePath() == null || params.filePath().isBlank())
                    ? defaultEmbeddedPath().toString()
                    : params.filePath();
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl ZhiFlow test -Dtest=DataSourceConfigServiceTest`
Expected: PASS (all tests green, including the two new ones).

- [ ] **Step 5: Commit**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/setup/DataSourceConfigService.java \
        ZhiFlow/src/test/java/fan/summer/zhiflow/setup/DataSourceConfigServiceTest.java
git commit -m "✨ feat(setup): expose default embedded DB path (single source of truth)"
```

---

## Task 2: Surface the default path via `GET /api/setup/types`

Make `SetupController.types()` include the resolved `default` on the embedded `filePath` field so the frontend wizard shows the real program path.

**Files:**
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/SetupController.java`
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/setup/SetupControllerTest.java`

**Interfaces:**
- Consumes: `DataSourceConfigService.defaultEmbeddedPath()` from Task 1 (already injected — `configService` field, `SetupController.java:39`).
- Produces: `GET /api/setup/types` JSON now contains, for embedded types only:
  ```json
  { "name": "filePath", "label": "Data file location", "required": true, "secret": false,
    "default": "<resolved absolute path>" }
  ```

- [ ] **Step 1: Write the failing test**

Append to `SetupControllerTest.java` (inside the class):

```java
    @Test
    void types_embedded_filePathCarriesResolvedDefault() {
        DataSourceConfigService svc = newService();
        SetupController controller = newController(svc, new java.util.concurrent.atomic.AtomicBoolean(false));

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> types = (java.util.List<Map<String, Object>>) controller.types();

        Map<String, Object> h2 = types.stream()
                .filter(t -> "h2".equals(t.get("type")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> fields = (java.util.List<Map<String, Object>>) h2.get("fields");
        Map<String, Object> filePath = fields.stream()
                .filter(f -> "filePath".equals(f.get("name")))
                .findFirst().orElseThrow();

        assertEquals(svc.defaultEmbeddedPath().toString(),
                filePath.get("default"),
                "embedded filePath must advertise the resolved program default");
    }

    @Test
    void types_remoteFieldsDoNotCarryFilePathDefault() {
        DataSourceConfigService svc = newService();
        SetupController controller = newController(svc, new java.util.concurrent.atomic.AtomicBoolean(false));

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> types = (java.util.List<Map<String, Object>>) controller.types();

        Map<String, Object> mysql = types.stream()
                .filter(t -> "mysql".equals(t.get("type")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> fields = (java.util.List<Map<String, Object>>) mysql.get("fields");
        boolean anyFilePath = fields.stream().anyMatch(f -> "filePath".equals(f.get("name")));
        assertFalse(anyFilePath, "remote types must not expose a filePath field");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl ZhiFlow test -Dtest=SetupControllerTest`
Expected: FAIL — the embedded `filePath` field has no `default` key (`filePath.get("default")` is null).

- [ ] **Step 3: Implement**

In `SetupController.java`, in `types()`, replace the embedded branch (currently at lines 91-93):

```java
            if (t.embedded) {
                fields.add(Map.of("name", "filePath", "label", "Data file location",
                        "required", true, "secret", false));
            } else {
```

with a mutable map that carries the resolved default:

```java
            if (t.embedded) {
                Map<String, Object> filePath = new LinkedHashMap<>();
                filePath.put("name", "filePath");
                filePath.put("label", "Data file location");
                filePath.put("required", true);
                filePath.put("secret", false);
                filePath.put("default", configService.defaultEmbeddedPath().toString());
                fields.add(filePath);
            } else {
```

(`LinkedHashMap` is already imported at `SetupController.java:18`; `Map.of(...)` is immutable so it cannot take the extra key — hence the mutable map.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl ZhiFlow test -Dtest=SetupControllerTest`
Expected: PASS.

- [ ] **Step 5: Run the full ZhiFlow test module to confirm no regressions**

Run: `mvn -q -pl ZhiFlow test`
Expected: PASS (green).

- [ ] **Step 6: Commit**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/setup/SetupController.java \
        ZhiFlow/src/test/java/fan/summer/zhiflow/setup/SetupControllerTest.java
git commit -m "✨ feat(setup): advertise resolved default DB path in /api/setup/types"
```

---

## Task 3: Add i18n keys for new status + setup copy

Add the keys both task 2 (status bar) and task 5 (setup wizard copy) will consume.

**Files:**
- Modify: `frontend/src/i18n/en.json`
- Modify: `frontend/src/i18n/zh.json`

**Interfaces:**
- Produces: keys `status.connecting`, `status.connected`, `status.reconnecting`, `status.offline`, `status.restarting` (first three already exist — keep them, add the last two), and `setup.dataFileLocation`.

- [ ] **Step 1: Add keys to `en.json`**

In `frontend/src/i18n/en.json`, replace the `status` block:

```json
  "status":   { "connecting": "Connecting…", "connected": "Connected",
                "reconnecting": "Reconnecting…" },
```

with:

```json
  "status":   { "connecting": "Connecting…", "connected": "Connected",
                "reconnecting": "Reconnecting…", "offline": "Offline",
                "restarting": "Restarting…" },
```

Then add a `setup` block. Insert after the `"common"` block (at the end of the object, before the final `}`):

```json
  "setup":    { "dataFileLocation": "Data file location" }
```

(Make sure to add a comma after the preceding `"common"` block.)

- [ ] **Step 2: Add keys to `zh.json`**

In `frontend/src/i18n/zh.json`, replace the `status` block:

```json
  "status":   { "connecting": "连接中…", "connected": "已连接",
                "reconnecting": "重新连接中…" },
```

with:

```json
  "status":   { "connecting": "连接中…", "connected": "已连接",
                "reconnecting": "重新连接中…", "offline": "离线",
                "restarting": "重启中…" },
```

Add the `setup` block after `"common"`:

```json
  "setup":    { "dataFileLocation": "数据文件位置" }
```

- [ ] **Step 3: Verify JSON is valid**

Run: `node -e "JSON.parse(require('fs').readFileSync('frontend/src/i18n/en.json','utf8')); JSON.parse(require('fs').readFileSync('frontend/src/i18n/zh.json','utf8')); console.log('ok')"`
Expected: prints `ok`.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/i18n/en.json frontend/src/i18n/zh.json
git commit -m "🌐 i18n: add offline/restarting status + setup.dataFileLocation keys"
```

---

## Task 4: Inject app version via Vite `define`

Wire `package.json`'s `version` into the app as a build-time constant `__APP_VERSION__`, replacing the hardcoded `"ZhiFlow 4.0.0"`.

**Files:**
- Modify: `frontend/vite.config.ts`
- Modify: `frontend/src/env.d.ts`

**Interfaces:**
- Produces: global `__APP_VERSION__: string` available app-wide; `StatusBar.vue` (Task 6) reads it.

- [ ] **Step 1: Add the `define` to `vite.config.ts`**

In `frontend/vite.config.ts`, the file currently begins:

```ts
import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'
import { fileURLToPath, URL } from 'node:url'
import { copyFileSync, mkdirSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'
```

Add a `readFileSync` + `createRequire` import and read the version. Replace the import block above with:

```ts
import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'
import { fileURLToPath, URL } from 'node:url'
import { copyFileSync, mkdirSync, existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// App version comes from package.json (single source of truth) and is injected
// as a build-time constant __APP_VERSION__ (see `define` below).
const pkgVersion = JSON.parse(
  readFileSync(resolve(__dirname, 'package.json'), 'utf8'),
).version as string
```

Then in the `defineConfig({ ... })` object, add a `define` key. The object currently starts:

```ts
export default defineConfig({
  plugins: [
```

Change to:

```ts
export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(pkgVersion),
  },
  plugins: [
```

- [ ] **Step 2: Declare the global type**

In `frontend/src/env.d.ts`, append at the end of the file:

```ts
/** Build-time app version, injected from package.json by vite.config.ts `define`. */
declare const __APP_VERSION__: string
```

- [ ] **Step 3: Verify it type-checks and builds**

Run: `cd frontend && npm run build`
Expected: build succeeds (vue-tsc + vite build). If it fails on `__APP_VERSION__`, the `declare const` from Step 2 was not picked up — re-check env.d.ts is at `frontend/src/env.d.ts`.

- [ ] **Step 4: Commit**

```bash
git add frontend/vite.config.ts frontend/src/env.d.ts
git commit -m "✨ feat(frontend): inject __APP_VERSION__ from package.json via Vite define"
```

---

## Task 5: Shared connection store (`connection.ts`)

A small Pinia store holding the backend connection state and a `restarting` flag, shared between `StatusBar` (owner of polling) and the setup wizard (sets `restarting`).

**Files:**
- Create: `frontend/src/stores/connection.ts`

**Interfaces:**
- Produces: `useConnectionStore()` exposing:
  - `state: Ref<ConnState>` where `ConnState = 'connecting' | 'connected' | 'reconnecting' | 'offline' | 'restarting'`
  - `setRestarting(on: boolean): void` — when `true`, forces `state = 'restarting'`; when `false`, resets to `'connecting'` so the poller can re-derive.
- Consumed by: `StatusBar.vue` (Task 6), `SetupWizard.vue` (Task 8).

- [ ] **Step 1: Create the store**

Create `frontend/src/stores/connection.ts`:

```ts
import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * Shared backend-connection state. The global StatusBar owns the health-poll
 * loop and writes the derived state here; the setup wizard flips `restarting`
 * on while it waits for the backend process to come back after initialize().
 *
 * Kept in a store (not a local ref in StatusBar) so the wizard and the bar —
 * which live in different parts of the tree — share one source of truth.
 */
export type ConnState = 'connecting' | 'connected' | 'reconnecting' | 'offline' | 'restarting'

export const useConnectionStore = defineStore('connection', () => {
  const state = ref<ConnState>('connecting')

  /** Called by the setup wizard around its restart-wait loop. */
  function setRestarting(on: boolean) {
    state.value = on ? 'restarting' : 'connecting'
  }

  return { state, setRestarting }
})
```

- [ ] **Step 2: Verify it type-checks**

Run: `cd frontend && npx vue-tsc --noEmit`
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/stores/connection.ts
git commit -m "✨ feat(frontend): add shared connection store (status + restarting flag)"
```

---

## Task 6: Rewrite `StatusBar.vue` to use the store (5 states, MDI icons, version)

Make the bottom bar read from the connection store, render 5 states with MDI icons + colors, and show the real version.

**Files:**
- Modify: `frontend/src/shell/StatusBar.vue`

**Interfaces:**
- Consumes: `useConnectionStore()` (Task 5), `__APP_VERSION__` (Task 4), i18n keys `status.*` (Task 3).
- Produces: a `StatusBar` component that owns the `/api/health` poll and writes derived state into the store.

- [ ] **Step 1: Rewrite the component**

Replace the entire contents of `frontend/src/shell/StatusBar.vue` with:

```vue
<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { api } from '@/api/client'
import { useConnectionStore, type ConnState } from '@/stores/connection'

const conn = useConnectionStore()
let timer: number | undefined
let everConnected = false

async function poll() {
  // While the wizard has flagged a restart, ignore transient poll results so
  // we don't flicker offline/reconnecting mid-restart.
  if (conn.state === 'restarting') return
  try {
    const r = await api.health()
    if (r.status === 'ok') {
      everConnected = true
      conn.state = 'connected'
    } else {
      conn.state = everConnected ? 'reconnecting' : 'offline'
    }
  } catch {
    conn.state = everConnected ? 'reconnecting' : 'offline'
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
  offline: 'status.offline',
  restarting: 'status.restarting',
}

const chipColor: Record<ConnState, string> = {
  connecting: 'default',
  connected: 'success',
  reconnecting: 'warning',
  offline: 'error',
  restarting: 'info',
}

const icon: Record<ConnState, string> = {
  connecting: 'mdi-circle-medium',
  connected: 'mdi-check-circle-outline',
  reconnecting: 'mdi-autorenew',
  offline: 'mdi-lan-disconnect',
  restarting: 'mdi-restart',
}
</script>

<template>
  <v-system-bar>
    <v-icon size="small" :icon="icon[conn.state]" />
    <v-chip :color="chipColor[conn.state]" size="x-small" variant="flat" class="ml-1">
      {{ $t(statusKey[conn.state]) }}
    </v-chip>
    <v-spacer />
    <span class="text-medium-emphasis text-caption">ZhiFlow {{ __APP_VERSION__ }}</span>
  </v-system-bar>
</template>
```

- [ ] **Step 2: Verify it type-checks and builds**

Run: `cd frontend && npm run build`
Expected: build succeeds.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/shell/StatusBar.vue
git commit -m "✨ feat(frontend): StatusBar reads shared state, 5 states + MDI icons + real version"
```

---

## Task 7: Lift `StatusBar` to root `App.vue` (show on the wizard too)

Render the status bar on every screen, including the setup wizard. **`AppShell.vue` currently also renders `<StatusBar />`** — lifting it to the root would render it twice, so this task removes it from `AppShell` at the same time.

**Files:**
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/shell/AppShell.vue`

**Interfaces:**
- Consumes: `StatusBar` (already imported by `AppShell`; import directly into `App.vue`).

- [ ] **Step 1: Edit `App.vue`**

Replace the entire contents of `frontend/src/App.vue` with:

```vue
<script setup lang="ts">
import { useRoute } from 'vue-router'
import AppShell from './shell/AppShell.vue'
import StatusBar from './shell/StatusBar.vue'

const route = useRoute()
</script>

<template>
  <!-- v-app is required by Vuetify for theme/layout (data-v-app). -->
  <v-app>
    <!-- Setup wizard renders full-screen without the app shell. -->
    <router-view v-if="route.name === 'setup'" />
    <AppShell v-else />
    <!-- Global bottom info bar: version + backend state. Always present. -->
    <StatusBar />
  </v-app>
</template>
```

- [ ] **Step 2: Remove the now-duplicate `StatusBar` from `AppShell.vue`**

`frontend/src/shell/AppShell.vue` currently is:

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

Replace its entire contents with (drop the StatusBar import and its `<StatusBar />` usage):

```vue
<script setup lang="ts">
import Sidebar from './Sidebar.vue'
</script>

<template>
  <Sidebar />
  <v-main>
    <router-view v-slot="{ Component }">
      <component :is="Component" />
    </router-view>
  </v-main>
</template>
```

- [ ] **Step 3: Verify it builds**

Run: `cd frontend && npm run build`
Expected: build succeeds.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/App.vue frontend/src/shell/AppShell.vue
git commit -m "✨ feat(frontend): render StatusBar globally (incl. setup wizard)"
```

---

## Task 8: Setup wizard — stack/center Test + Initialize, drive `restarting`

The default path already auto-fills from the backend (Task 2 + existing store logic in `setup.ts:36-39`). This task restructures the step-2 action area and wires the restart flag.

**Files:**
- Modify: `frontend/src/views/SetupWizard.vue`

**Interfaces:**
- Consumes: `useConnectionStore` (Task 5), i18n key `setup.dataFileLocation` (Task 3).
- Note: the store's `selectType()` already copies `f.default` into `params` — no store change needed. The field `:model-value` binds `setup.params[f.name]`, so the resolved path shows automatically.

- [ ] **Step 1: Update the `<script setup>` to import the store and set restarting**

In `frontend/src/views/SetupWizard.vue`, replace the script block (lines 1-69) with:

```ts
<script setup lang="ts">
import { onMounted, computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSetupStore } from '@/stores/setup'
import { useConnectionStore } from '@/stores/connection'
import { api } from '@/api/client'

const router = useRouter()
const setup = useSetupStore()
const conn = useConnectionStore()

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
  conn.setRestarting(true)
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
  conn.setRestarting(false)
  if (back) {
    router.replace('/')
  } else {
    restartFailed.value = true
    restartMessage.value = 'Restart timed out. Please manually restart the application.'
  }
}
</script>
```

- [ ] **Step 2: Restructure the step-2 action area in the template**

In the same file, the step-2 block currently has the test row and Initialize button at lines 118-136:

```vue
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
```

Replace that whole block with a centered, vertically-stacked action area:

```vue
        <div class="d-flex flex-column align-center my-4">
          <v-btn variant="tonal" :loading="setup.testing" @click="onTest">
            Test connection
          </v-btn>
          <div
            v-if="setup.testResult"
            :class="setup.testResult.success ? 'text-success' : 'text-error'"
            class="text-body-2 text-center mt-2"
          >
            <v-icon size="small" :icon="setup.testResult.success ? 'mdi-check' : 'mdi-alert-circle-outline'" />
            {{ setup.testResult.success
              ? `Connected (${setup.testResult.serverVersion})`
              : setup.testResult.error }}
          </div>
          <v-btn
            color="primary"
            class="mt-4"
            :disabled="!canInitialize"
            @click="onInitialize"
          >
            Initialize
          </v-btn>
        </div>
```

Changes: column layout, both buttons centered; the result message renders centered below the Test button; Initialize sits below; error icon switched to `mdi-alert-circle-outline`.

- [ ] **Step 3: Verify it builds**

Run: `cd frontend && npm run build`
Expected: build succeeds.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/SetupWizard.vue
git commit -m "✨ feat(setup): stack/center Test+Initialize buttons; drive restarting state"
```

---

## Task 9: Button alignment audit — AiChat + AiAgent input bars

Fix the two known `align-end` input-bar button rows so the button centers on the input box.

**Files:**
- Modify: `frontend/src/views/AiChat.vue`
- Modify: `frontend/src/views/AiAgent.vue`

**Interfaces:** none (class-only edits).

- [ ] **Step 1: Fix AiChat.vue**

In `frontend/src/views/AiChat.vue`, the input row (line 106) is:

```vue
    <div class="d-flex ga-2 align-end mt-2">
```

Change to:

```vue
    <div class="d-flex ga-2 align-center mt-2">
```

- [ ] **Step 2: Fix AiAgent.vue**

In `frontend/src/views/AiAgent.vue`, the goal row (line 257) is:

```vue
    <div class="d-flex ga-2 align-end mb-2">
```

Change to:

```vue
    <div class="d-flex ga-2 align-center mb-2">
```

- [ ] **Step 3: Confirm no other `align-end` button rows remain**

Run this check from the repo root:

```bash
grep -rn "d-flex.*align-end" frontend/src/views frontend/src/shell || echo "none"
```

Expected: prints `none` (no matches). If a match appears, evaluate whether it is a button-sibling row; only leave it if it is not a button sitting next to a component.

- [ ] **Step 4: Verify build**

Run: `cd frontend && npm run build`
Expected: build succeeds.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/AiChat.vue frontend/src/views/AiAgent.vue
git commit -m "🎨 style(frontend): center send/plan buttons on their input boxes"
```

---

## Task 10: Sidebar — MDI logo icon + rail-mode centering

Replace the `ZF` text avatar with an MDI logo icon and ensure the brand row centers when collapsed to rail.

**Files:**
- Modify: `frontend/src/shell/Sidebar.vue`

**Interfaces:** none (template + class edits).

- [ ] **Step 1: Replace the brand avatar**

In `frontend/src/shell/Sidebar.vue`, the brand block (lines 40-53) is:

```vue
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
```

Replace with (logo icon + rail-aware centering; collapse button hidden in rail to avoid crowding a 64px column):

```vue
    <div
      class="d-flex align-center py-3"
      :class="rail ? 'justify-center' : 'px-3'"
    >
      <v-avatar color="primary" size="32" rounded="lg">
        <v-icon icon="mdi-hexagon-multiple-outline" />
      </v-avatar>
      <span v-if="!rail" class="ml-3 font-weight-medium">ZhiFlow</span>
      <v-spacer v-if="!rail" />
      <v-btn
        v-if="!rail"
        icon="mdi-chevron-left"
        size="small"
        variant="text"
        :title="$t('sidebar.collapse')"
        @click="settings.setSidebarCollapsed(true)"
      />
    </div>
```

- [ ] **Step 2: Add an expand affordance at the bottom of the rail**

In the same file, the `#append` template (lines 68-79) currently holds the AI/agent/settings/theme items. Append a rail-only expand button at the very top of `#append` so a collapsed user can still expand. Replace:

```vue
    <template #append>
      <v-list density="compact" nav>
```

with:

```vue
    <template #append>
      <v-list v-if="rail" density="compact" nav class="pb-2">
        <v-list-item
          prepend-icon="mdi-chevron-right"
          :title="$t('sidebar.expand')"
          @click="settings.setSidebarCollapsed(false)"
        />
      </v-list>
      <v-list density="compact" nav>
```

- [ ] **Step 3: Verify build**

Run: `cd frontend && npm run build`
Expected: build succeeds.

- [ ] **Step 4: Visual check (manual)**

Run: `cd frontend && npm run dev`
- Expanded sidebar: hexagon logo icon + "ZhiFlow" + collapse chevron on the right.
- Click the chevron → rail (64px): logo icon centered, collapse chevron gone; a "Expand" row with `mdi-chevron-right` appears at the bottom; clicking it re-expands.
- All nav list-item icons stay centered (default Vuetify rail behavior — no custom padding added).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/shell/Sidebar.vue
git commit -m "✨ feat(frontend): MDI logo icon in sidebar + rail-mode centering"
```

---

## Task 11: Final verification (build + full-test sweep)

Confirm the whole change set builds and the backend tests are green.

- [ ] **Step 1: Backend tests**

Run: `mvn -q -pl ZhiFlow test`
Expected: PASS.

- [ ] **Step 2: Frontend build**

Run: `cd frontend && npm run build`
Expected: build succeeds (vue-tsc + vite build, no errors).

- [ ] **Step 3: No stray non-MDI icons**

Run: `grep -rnE "(<svg|fa-[a-z]|font-awesome|fontawesome|lucide|heroicons|tabler)" frontend/src || echo "none"`
Expected: prints `none`.

- [ ] **Step 4: No `align-end` button rows left**

Run: `grep -rn "d-flex.*align-end" frontend/src/views frontend/src/shell || echo "none"`
Expected: prints `none`.

If all pass, the change set is complete. No further commit (this task is verification only) unless an earlier step left something uncommitted.
