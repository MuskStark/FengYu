# Unified Plugin Store — Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the frontend for the unified plugin store — a Pinia store, API client methods, types, and a UI in the existing `PluginMarket.vue` that aggregates FengYu/Claude/Codex plugins with source badges, filtering, a source manager, and per-source-type install.

**Architecture:** New `frontend/src/stores/pluginStore.ts` (separate from `stores/plugins.ts`, which only holds runtime-enabled plugins). New types + API methods in `api/types.ts` + `api/client.ts` mirroring the existing skills block. `PluginMarket.vue` gains a "stores" sub-view (source CRUD + filters + unified card grid extending the current plugins tab). All calls hit `/api/plugin-store/*` from Plan A.

**Tech Stack:** Vue 3.5 `<script setup>` + TypeScript, Vuetify 3 (Material 3), Pinia, vue-i18n, Axios. Build via `npm run build` (Vite).

**Depends on:** Plan A backend (`docs/superpowers/plans/2026-08-03-plugin-store-backend.md`) — its REST API must exist. Tests in this plan run against the real backend (manual smoke); no new unit-test framework added (frontend has no existing test runner — match the repo's manual-verification convention).

**Spec:** `docs/superpowers/specs/2026-08-03-plugin-store-codex-claude-compat-design.md` (§6 Frontend)

## Global Constraints

- Frontend lives in `frontend/`; build with `npm --prefix frontend run build`.
- Mirror the existing skills-block style in `api/client.ts` (lines 344–388): one-line methods returning `http.get/post/...().then((r) => r.data)`, URLs `encodeURIComponent`-encoded.
- All new UI text must be i18n'd via `vue-i18n` keys (existing pattern in `PluginMarket.vue` uses `t('...')`). Add keys to both `frontend/src/i18n/locales/en.ts` and `zh.ts` (verify the locale file paths before editing).
- Conventional commits with emojis (✨ feat, 🐛 fix, ♻️ refactor, 🎨 style). Commit per task.
- `PluginMarket.vue` is large (477 lines); extend it with a new tab value `'stores'` + components, do not rewrite it. Match surrounding style (Composition API refs, `busy`/`error` refs for per-row state).

---

## File Structure

| File | Responsibility |
|---|---|
| `frontend/src/api/types.ts` (modify) | Add `StoreSource`, `StoreSourceType`, `UnifiedCatalogEntry`, `InterfaceMeta`, `InstallRecord` types. |
| `frontend/src/api/client.ts` (modify) | Add store methods: `getStoreSources`, `addStoreSource`, `deleteStoreSource`, `refreshStoreSource`, `getUnifiedCatalog`, `installUnified`, `updateUnified`, `uninstallUnified`, `setUnifiedEnabled`, `getInstallHistory`. |
| `frontend/src/stores/pluginStore.ts` (create) | Pinia store: sources, catalog, history, filters, busy/error. |
| `frontend/src/i18n/locales/en.ts` + `zh.ts` (modify) | New i18n keys for the store UI. |
| `frontend/src/views/PluginMarket.vue` (modify) | Add `'stores'` tab: source manager + unified grid + detail drawer with skills/mcp/sha/interface. |
| `frontend/src/components/store/UnifiedSourceBadge.vue` (create) | Small chip: CLAUDE/CODEX/FENGYU with color. |
| `frontend/src/components/store/StoreSourceManager.vue` (create) | Source list + add/refresh/delete dialog. |

---

### Task 1: Types + API client methods

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/client.ts`

**Interfaces:**
- Produces: TypeScript types and `api.*` methods consumed by the Pinia store (Task 2) and the view (Task 4). These mirror the backend records from Plan A exactly.

- [ ] **Step 1: Add types to `frontend/src/api/types.ts`**

Append after the existing `MarketplacePlugin` type (find the line with `export interface MarketplacePlugin {` and add after the block):

```ts
// ── Unified Plugin Store (FengYu + Claude + Codex) ──
export type StoreSourceType = 'FENGYU' | 'CLAUDE' | 'CODEX'

export interface StoreSource {
  origin: string
  sourceType: StoreSourceType
  catalogUrl: string
  name: string
}

export interface StoreAuthor {
  name: string
  email?: string | null
  url?: string | null
}

export interface StoreInterfaceMeta {
  displayName?: string
  shortDescription?: string
  longDescription?: string
  developerName?: string
  category?: string
  capabilities?: string[]
  websiteURL?: string
  brandColor?: string
  logo?: string
  screenshots?: string[]
  defaultPrompt?: string[]
}

export interface UnifiedCatalogEntry {
  uid: string
  origin: string
  sourceType: StoreSourceType
  name: string
  displayName: string
  description: string
  author: StoreAuthor | null
  category: string | null
  keywords: string[]
  homepage: string | null
  pinnedSha: string | null
  declaredSkills: string[]
  mcpServers: string[]
  interfaceMeta: StoreInterfaceMeta | null
  installed: boolean
  installedVersion: string | null
  updateAvailable: boolean
  enabled: boolean
}

export interface InstallRecord {
  uid: string
  pluginName: string
  sourceType: StoreSourceType
  origin: string
  version: string | null
  pinnedSha: string | null
  hasMcpServers: boolean
  enabled: boolean
  installedAt: string
  updatedAt: string
}
```

- [ ] **Step 2: Add API methods to `frontend/src/api/client.ts`**

Find the skills block (ending `uninstallSkill: (id: string) => ...delete(...)`) and add a new block before the closing `}` of the `api` object:

```ts
  // ── Unified Plugin Store ─────────────────────────────────
  /** List subscribed marketplace sources. */
  getStoreSources: () =>
    http.get<StoreSource[]>('/api/plugin-store/sources').then((r) => r.data),

  /** Subscribe to a new marketplace source. */
  addStoreSource: (name: string, sourceType: StoreSourceType, catalogUrl: string) =>
    http
      .post<StoreSource>('/api/plugin-store/sources', { name, sourceType, catalogUrl })
      .then((r) => r.data),

  /** Unsubscribe a source (does not uninstall plugins). */
  deleteStoreSource: (origin: string) =>
    http.delete(`/api/plugin-store/sources/${encodeURIComponent(origin)}`),

  /** Force-refresh a source's cached catalog. */
  refreshStoreSource: (origin: string) =>
    http.post(`/api/plugin-store/sources/${encodeURIComponent(origin)}/refresh`),

  /** Aggregated catalog (optionally filtered by sourceType/category/query server-side). */
  getUnifiedCatalog: (params?: { sourceType?: StoreSourceType; category?: string; q?: string }) =>
    http
      .get<UnifiedCatalogEntry[]>('/api/plugin-store/catalog', { params })
      .then((r) => r.data),

  /** Install (or update) a plugin by uid; backend dispatches by sourceType. */
  installUnified: (uid: string) =>
    http.post(`/api/plugin-store/${encodeURIComponent(uid)}/install`),

  updateUnified: (uid: string) =>
    http.post(`/api/plugin-store/${encodeURIComponent(uid)}/update`),

  uninstallUnified: (uid: string) =>
    http.delete(`/api/plugin-store/${encodeURIComponent(uid)}`),

  setUnifiedEnabled: (uid: string, enabled: boolean) =>
    http.patch(`/api/plugin-store/${encodeURIComponent(uid)}/enabled`, { enabled }),

  /** Installation history (install records across all sources). */
  getInstallHistory: () =>
    http.get<InstallRecord[]>('/api/plugin-store/history').then((r) => r.data),
```

And add the type imports at the top of `client.ts` (alongside the existing `import type { ... } from '@/api/types'`):

```ts
import type {
  // ... existing imports ...
  StoreSource,
  StoreSourceType,
  UnifiedCatalogEntry,
  InstallRecord,
} from '@/api/types'
```

- [ ] **Step 3: Verify it type-checks**

Run: `npm --prefix frontend run build`
Expected: BUILD SUCCESS (build runs `vue-tsc` type-check). If it fails, fix the import list / type names to match `types.ts`.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/api/client.ts
git commit -m "✨ feat(store-fe): add unified store types + API client methods"
```

---

### Task 2: Pinia store

**Files:**
- Create: `frontend/src/stores/pluginStore.ts`

**Interfaces:**
- Consumes: `api` methods (Task 1).
- Produces: `usePluginStore()` with reactive `sources`, `catalog`, `history`, `filter`, `loading`, `error`, and actions `loadSources`, `loadCatalog`, `loadHistory`, `addSource`, `deleteSource`, `refreshSource`, `install`, `uninstall`, `update`, `setEnabled`. Consumed by the view (Task 4).

- [ ] **Step 1: Create the store**

`frontend/src/stores/pluginStore.ts`:
```ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type {
  InstallRecord,
  StoreSource,
  StoreSourceType,
  UnifiedCatalogEntry,
} from '@/api/types'

export interface StoreFilter {
  sourceType?: StoreSourceType
  category?: string
  q?: string
}

export const usePluginStore = defineStore('pluginStore', () => {
  const sources = ref<StoreSource[]>([])
  const catalog = ref<UnifiedCatalogEntry[]>([])
  const history = ref<InstallRecord[]>([])
  const filter = ref<StoreFilter>({})
  const loading = ref(false)
  const error = ref<string | null>(null)
  const busy = ref<string | null>(null) // uid of in-flight install/update

  async function loadSources() {
    try {
      sources.value = await api.getStoreSources()
    } catch (e) {
      error.value = errMsg(e)
    }
  }

  async function loadCatalog() {
    loading.value = true
    error.value = null
    try {
      catalog.value = await api.getUnifiedCatalog(filter.value)
    } catch (e) {
      error.value = errMsg(e)
    } finally {
      loading.value = false
    }
  }

  async function loadHistory() {
    try {
      history.value = await api.getInstallHistory()
    } catch (e) {
      error.value = errMsg(e)
    }
  }

  async function addSource(name: string, sourceType: StoreSourceType, catalogUrl: string) {
    await api.addStoreSource(name, sourceType, catalogUrl)
    await loadSources()
    await loadCatalog()
  }

  async function deleteSource(origin: string) {
    await api.deleteStoreSource(origin)
    await loadSources()
    await loadCatalog()
  }

  async function refreshSource(origin: string) {
    await api.refreshStoreSource(origin)
    await loadCatalog()
  }

  async function install(uid: string) {
    busy.value = uid
    try {
      await api.installUnified(uid)
      await Promise.all([loadCatalog(), loadHistory()])
    } finally {
      busy.value = null
    }
  }

  async function update(uid: string) {
    busy.value = uid
    try {
      await api.updateUnified(uid)
      await Promise.all([loadCatalog(), loadHistory()])
    } finally {
      busy.value = null
    }
  }

  async function uninstall(uid: string) {
    busy.value = uid
    try {
      await api.uninstallUnified(uid)
      await Promise.all([loadCatalog(), loadHistory()])
    } finally {
      busy.value = null
    }
  }

  async function setEnabled(uid: string, enabled: boolean) {
    await api.setUnifiedEnabled(uid, enabled)
    await loadCatalog()
  }

  function setFilter(f: StoreFilter) {
    filter.value = f
  }

  function errMsg(e: unknown): string {
    return e instanceof Error ? e.message : String(e)
  }

  return {
    sources, catalog, history, filter, loading, error, busy,
    loadSources, loadCatalog, loadHistory,
    addSource, deleteSource, refreshSource,
    install, uninstall, update, setEnabled, setFilter,
  }
})
```

- [ ] **Step 2: Verify type-check/build**

Run: `npm --prefix frontend run build`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/stores/pluginStore.ts
git commit -m "✨ feat(store-fe): add pluginStore Pinia store (sources, catalog, history, actions)"
```

---

### Task 3: i18n keys

**Files:**
- Modify: `frontend/src/i18n/locales/en.ts`
- Modify: `frontend/src/i18n/locales/zh.ts`

**Interfaces:**
- Produces: i18n keys used by the view (Task 4). Verify the locale file paths exist first; if the repo uses a different i18n layout, adapt.

- [ ] **Step 1: Confirm the locale file paths**

Run: `ls frontend/src/i18n/locales/ 2>/dev/null || find frontend/src -name 'en.ts' -path '*i18n*'`
If the path differs (e.g. `frontend/src/locales/en.ts`), substitute it below.

- [ ] **Step 2: Add keys to the English locale**

Add a `store` block under the appropriate top-level key (e.g. under `plugins` or as a sibling `store` key — match the existing structure):

```ts
    store: {
      title: 'Plugin Store',
      tab: 'Stores',
      sourceTypeAll: 'All sources',
      addSource: 'Add source',
      sourceName: 'Name',
      sourceType: 'Type',
      catalogUrl: 'Catalog URL',
      refresh: 'Refresh',
      delete: 'Remove source',
      installed: 'Installed',
      updateAvailable: 'Update available',
      install: 'Install',
      update: 'Update',
      uninstall: 'Uninstall',
      enable: 'Enable',
      disable: 'Disable',
      declaredSkills: 'Declared skills',
      mcpServers: 'MCP servers',
      pinnedSha: 'Pinned SHA',
      mcpWarning: 'Declares MCP servers — pending runtime support',
      cloneInProgress: 'Cloning & verifying…',
      noSources: 'No sources subscribed. Add one to browse plugins.',
    },
```

- [ ] **Step 3: Add the matching Chinese keys to `zh.ts`**

```ts
    store: {
      title: '插件商店',
      tab: '商店',
      sourceTypeAll: '全部来源',
      addSource: '添加源',
      sourceName: '名称',
      sourceType: '类型',
      catalogUrl: '目录 URL',
      refresh: '刷新',
      delete: '移除源',
      installed: '已安装',
      updateAvailable: '有更新',
      install: '安装',
      update: '更新',
      uninstall: '卸载',
      enable: '启用',
      disable: '禁用',
      declaredSkills: '声明的技能',
      mcpServers: 'MCP 服务器',
      pinnedSha: '固定 SHA',
      mcpWarning: '声明了 MCP 服务器 —— 运行时支持待启用',
      cloneInProgress: '正在克隆并校验…',
      noSources: '尚未订阅任何源。添加一个以浏览插件。',
    },
```

- [ ] **Step 4: Verify build**

Run: `npm --prefix frontend run build`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/i18n/locales/en.ts frontend/src/i18n/locales/zh.ts
git commit -m "📝 docs(store-fe): add i18n keys (en + zh) for unified store UI"
```

---

### Task 4: Components + extend `PluginMarket.vue`

**Files:**
- Create: `frontend/src/components/store/UnifiedSourceBadge.vue`
- Create: `frontend/src/components/store/StoreSourceManager.vue`
- Modify: `frontend/src/views/PluginMarket.vue`

**Interfaces:**
- Consumes: `usePluginStore` (Task 2), `UnifiedCatalogEntry` type (Task 1), i18n keys (Task 3).
- Produces: the `'stores'` tab in `PluginMarket.vue` rendering a source manager + unified card grid + detail drawer.

- [ ] **Step 1: Create the source badge component**

`frontend/src/components/store/UnifiedSourceBadge.vue`:
```vue
<script setup lang="ts">
import { computed } from 'vue'
import type { StoreSourceType } from '@/api/types'

const props = defineProps<{ type: StoreSourceType }>()
const color = computed(() => {
  switch (props.type) {
    case 'CLAUDE': return 'deep-orange'
    case 'CODEX': return 'teal'
    default: return 'primary'
  }
})
const label = computed(() => props.type)
</script>

<template>
  <v-chip :color="color" size="x-small" label variant="tonal">{{ label }}</v-chip>
</template>
```

- [ ] **Step 2: Create the source manager component**

`frontend/src/components/store/StoreSourceManager.vue`:
```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { usePluginStore } from '@/stores/pluginStore'
import type { StoreSourceType } from '@/api/types'

const { t } = useI18n()
const store = usePluginStore()

const dialog = ref(false)
const name = ref('')
const type = ref<StoreSourceType>('CLAUDE')
const url = ref('')
const error = ref<string | null>(null)

async function submit() {
  error.value = null
  try {
    await store.addSource(name.value, type.value, url.value)
    dialog.value = false
    name.value = ''
    url.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}
</script>

<template>
  <div>
    <div class="d-flex align-center ga-2 mb-3">
      <v-btn variant="tonal" prepend-icon="mdi-plus" @click="dialog = true">
        {{ t('plugins.store.addSource') }}
      </v-btn>
    </div>

    <v-list v-if="store.sources.length" density="compact">
      <v-list-item v-for="s in store.sources" :key="s.origin" :title="s.name" :subtitle="s.catalogUrl">
        <template #prepend><UnifiedSourceBadge :type="s.sourceType" /></template>
        <template #append>
          <v-btn icon="mdi-refresh" size="small" variant="text"
                 @click="store.refreshSource(s.origin)" />
          <v-btn icon="mdi-delete" size="small" variant="text"
                 @click="store.deleteSource(s.origin)" />
        </template>
      </v-list-item>
    </v-list>
    <p v-else class="text-medium-emphasis">{{ t('plugins.store.noSources') }}</p>

    <v-dialog v-model="dialog" max-width="500">
      <v-card :title="t('plugins.store.addSource')">
        <v-card-text>
          <v-text-field v-model="name" :label="t('plugins.store.sourceName')" />
          <v-select v-model="type" :items="['FENGYU','CLAUDE','CODEX']"
                    :label="t('plugins.store.sourceType')" />
          <v-text-field v-model="url" :label="t('plugins.store.catalogUrl')"
                        placeholder="https://…" />
          <v-alert v-if="error" type="error" density="compact">{{ error }}</v-alert>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="dialog = false">Cancel</v-btn>
          <v-btn variant="tonal" @click="submit">OK</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>
```

- [ ] **Step 3: Add the `'stores'` tab to `PluginMarket.vue`**

In `frontend/src/views/PluginMarket.vue`:

a) Extend the tab type and add store state. Find `const tab = ref<'plugins' | 'skills'>('plugins')` and change it to:
```ts
const tab = ref<'plugins' | 'skills' | 'stores'>('plugins')
```

b) Add imports after the existing store imports:
```ts
import { usePluginStore } from '@/stores/pluginStore'
import UnifiedSourceBadge from '@/components/store/UnifiedSourceBadge.vue'
import StoreSourceManager from '@/components/store/StoreSourceManager.vue'
import type { UnifiedCatalogEntry } from '@/api/types'
```

c) Add store + drawer state near the other refs:
```ts
const storeView = usePluginStore()
const storeDetail = ref<UnifiedCatalogEntry | null>(null)
const storeFilterType = ref<string | undefined>(undefined)
const storeSearch = ref('')

async function loadStore() {
  await Promise.all([storeView.loadSources(), storeView.loadCatalog(), storeView.loadHistory()])
}
function applyStoreFilter() {
  storeView.setFilter({
    sourceType: storeFilterType.value as any,
    q: storeSearch.value || undefined,
  })
  storeView.loadCatalog()
}
```

d) Add `loadStore()` to the existing `onMounted` (or wherever the initial `load()` is called):
```ts
onMounted(() => {
  load()
  loadStore()
})
```

e) In the `<template>`, find the existing `<v-tabs>` and add a third tab; then add a `v-window-item` for `stores` alongside the plugins/skills ones:
```vue
        <v-tab value="stores">{{ t('plugins.store.tab') }}</v-tab>
```
```vue
      <v-window-item value="stores">
        <v-container fluid class="pa-0">
          <StoreSourceManager />

          <div class="d-flex align-center ga-2 my-3">
            <v-select v-model="storeFilterType" :items="['FENGYU','CLAUDE','CODEX']"
                      :label="t('plugins.store.sourceTypeAll')" clearable density="compact"
                      style="max-width: 180px" @update:model-value="applyStoreFilter" />
            <v-text-field v-model="storeSearch" density="compact" append-icon="mdi-magnify"
                          @click:append="applyStoreFilter" @keyup.enter="applyStoreFilter" />
          </div>

          <v-row v-if="storeView.catalog.length">
            <v-col v-for="e in storeView.catalog" :key="e.uid" cols="12" sm="6" md="4" lg="3">
              <v-card hover @click="storeDetail = e">
                <v-card-title class="d-flex align-center ga-2">
                  <span class="text-truncate">{{ e.displayName }}</span>
                  <UnifiedSourceBadge :type="e.sourceType" />
                </v-card-title>
                <v-card-subtitle>{{ e.category || '—' }}</v-card-subtitle>
                <v-card-text class="text-body-2 text-medium-emphasis"
                             style="display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden">
                  {{ e.description }}
                </v-card-text>
                <v-card-actions>
                  <v-btn v-if="!e.installed" color="primary" variant="tonal"
                         :loading="storeView.busy === e.uid" @click.stop="storeView.install(e.uid)">
                    {{ t('plugins.store.install') }}
                  </v-btn>
                  <v-btn v-else-if="e.updateAvailable" color="warning" variant="tonal"
                         :loading="storeView.busy === e.uid" @click.stop="storeView.update(e.uid)">
                    {{ t('plugins.store.update') }}
                  </v-btn>
                  <v-switch v-else :model-value="e.enabled" color="success" hide-details density="compact"
                            :label="e.enabled ? t('plugins.store.enable') : t('plugins.store.disable')"
                            @update:model-value="(v:boolean) => storeView.setEnabled(e.uid, v)"
                            @click.stop />
                </v-card-actions>
              </v-card>
            </v-col>
          </v-row>
          <v-alert v-else-if="storeView.loading" type="info" variant="tonal">Loading…</v-alert>
          <v-alert v-else-if="storeView.error" type="error" variant="tonal">{{ storeView.error }}</v-alert>
        </v-container>
      </v-window-item>
```

f) Add a detail drawer at the bottom of the template (for the unified-store entries):
```vue
    <v-navigation-drawer v-model="!!storeDetail" location="right" temporary width="420"
                         :scrim="false" style="z-index: 200">
      <v-card v-if="storeDetail" flat>
        <v-card-title class="d-flex align-center ga-2">
          {{ storeDetail.displayName }}
          <UnifiedSourceBadge :type="storeDetail.sourceType" />
        </v-card-title>
        <v-card-text>
          <p class="mb-3">{{ storeDetail.description }}</p>
          <div v-if="storeDetail.pinnedSha" class="mb-2">
            <div class="text-caption text-medium-emphasis">{{ t('plugins.store.pinnedSha') }}</div>
            <code class="text-caption">{{ storeDetail.pinnedSha }}</code>
          </div>
          <div v-if="storeDetail.declaredSkills.length" class="mb-2">
            <div class="text-caption text-medium-emphasis">{{ t('plugins.store.declaredSkills') }}</div>
            <v-chip v-for="s in storeDetail.declaredSkills" :key="s" size="small" class="mr-1">{{ s }}</v-chip>
          </div>
          <div v-if="storeDetail.mcpServers.length" class="mb-2">
            <div class="text-caption text-medium-emphasis">{{ t('plugins.store.mcpServers') }}</div>
            <v-chip v-for="m in storeDetail.mcpServers" :key="m" size="small" class="mr-1">{{ m }}</v-chip>
            <v-alert type="warning" variant="tonal" density="compact" class="mt-2">
              {{ t('plugins.store.mcpWarning') }}
            </v-alert>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-btn v-if="storeDetail.homepage" :href="storeDetail.homepage" target="_blank">Homepage</v-btn>
          <v-spacer />
          <v-btn @click="storeDetail = null">Close</v-btn>
        </v-card-actions>
      </v-card>
    </v-navigation-drawer>
```

> **Note:** the exact i18n key path (`plugins.store.*` vs `store.*`) must match what you added in Task 3. If you added under a top-level `store` key, change the prefix here. Pick one and be consistent.

- [ ] **Step 4: Build to catch type/template errors**

Run: `npm --prefix frontend run build`
Expected: BUILD SUCCESS. Fix any type errors (e.g. tab type, missing imports, i18n key mismatches).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/store/ \
        frontend/src/views/PluginMarket.vue
git commit -m "✨ feat(store-fe): add unified store tab (source manager, grid, detail drawer)"
```

---

### Task 5: Manual smoke + final build

**Files:** none (verification)

- [ ] **Step 1: Build frontend + backend together**

Run: `npm --prefix frontend run build && ./mvnw -f FengYu/pom.xml clean package -DskipTests`
Expected: both succeed.

- [ ] **Step 2: Manual smoke (per spec §12)**

Boot the backend (`java -jar FengYu/target/FengYu-*.jar --token=t`) and the frontend dev server (`npm --prefix frontend run dev`), then in the browser:

1. Open the Plugins page, switch to the **Stores** tab.
2. Click **Add source**, add a Claude source pointing at `https://raw.githubusercontent.com/anthropics/claude-plugins-public/main/.claude-plugin/marketplace.json`, type CLAUDE.
3. Confirm the catalog populates with cards showing the CLAUDE badge.
4. Install a small skills-only plugin; confirm its `SKILL.md` appears under `~/.fengyu/skills/<uid>/`.
5. Check the detail drawer shows pinnedSha and any declared skills/mcpServers.

- [ ] **Step 3: Commit any fixes surfaced by the smoke (if any)**

If smoke surfaced bugs, fix and commit with the appropriate emoji prefix. If clean, no commit.

---

## Self-Review Notes (for the implementer)

**Spec coverage** (spec §6 sub-section → task):
- §6.1 Pinia store → Task 2
- §6.2 API client → Task 1
- §6.3 UI changes (source manager, badges, filters, detail drawer with skills/mcp/sha, install progress) → Task 4
- §6.4 category normalization → **DEFERRED**: the backend passes the raw category string; the frontend currently just displays it. Full category normalization (mapping `security`→`OTHER` etc., per spec §6.4) is a UX polish step — recommend adding it as a follow-up task after the initial UI lands and you can see real category values from all 3 sources.

**Placeholder check:** no TBD/TODO. i18n keys are concrete. Component code is complete.

**Type consistency:** `UnifiedCatalogEntry` field names match Plan A's backend JSON exactly (camelCase, Jackson default serializes Java records to camelCase). `uid`, `sourceType`, `pinnedSha`, `declaredSkills`, `mcpServers`, `interfaceMeta`, `installed`, `installedVersion`, `updateAvailable`, `enabled` all align.
