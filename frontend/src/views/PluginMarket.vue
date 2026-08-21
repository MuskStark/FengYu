<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/api/client'
import { renderMarkdown } from '@/security/markdown'
import type { MarketplacePlugin, PackageInspection, SkillDetail } from '@/api/types'
import { usePluginsStore } from '@/stores/plugins'
import { useSkillsStore } from '@/stores/skills'
import { usePluginStore } from '@/stores/pluginStore'
import UnifiedSourceBadge from '@/components/store/UnifiedSourceBadge.vue'
import StoreSourceManager from '@/components/store/StoreSourceManager.vue'
import type { UnifiedCatalogEntry } from '@/api/types'
import { makeDesktop, confirmAction } from '@/mf/desktop'

const { t, locale } = useI18n()

// ── shared UI state ──────────────────────────────────────────────
const tab = ref<'plugins' | 'skills' | 'stores'>('plugins')
const search = ref('')
const busy = ref<string | null>(null)
const error = ref<string | null>(null)
const addMenuOpen = ref(false)
const catalogScope = ref<'public' | 'personal'>('public')
const marketSettingsOpen = ref(false)
const marketSettingsBusy = ref(false)
const marketSettingsError = ref<string | null>(null)
const marketSourceName = ref('FengYu Market')
const marketSourceUrl = ref('')
const marketSourceOrigin = ref<string | null>(null)
const remoteSearchResults = ref<UnifiedCatalogEntry[]>([])
const remoteSearchReady = ref(false)
const remoteSearching = ref(false)
const remoteSearchError = ref<string | null>(null)
let remoteSearchTimer: ReturnType<typeof setTimeout> | undefined
let remoteSearchRequest = 0
const fileInput = ref<HTMLInputElement | null>(null)
const runtimePlugins = usePluginsStore()
const skillStore = useSkillsStore()
const desktop = makeDesktop()

// ── data: plugins (tab 1) ────────────────────────────────────────
const plugins = ref<MarketplacePlugin[]>([])
const loading = ref(false)
const pluginDetail = ref<MarketplacePlugin | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    plugins.value = await api.getMarketplacePlugins()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

// The "plugins" tab name/description are resolved server-side per request locale, so re-fetch when
// the UI language changes so the cards track it without a manual reload. (The Store tab is covered
// by the pluginStore locale watcher; this view-local ref needs its own.)
watch(locale, () => { void load() })

// ── data: skills (tab 2) ─────────────────────────────────────────
/** Normalized row for either a builtin or an installed/market skill. */
interface SkillRow {
  id: string
  name: string
  description: string
  icon: string
  meta: string
  official: boolean
  builtin: boolean
  installed: boolean
  enabled: boolean
  updateAvailable: boolean
}
const skillDetail = ref<SkillRow | null>(null)
const skillBody = ref<SkillDetail | null>(null)
const skillBodyLoading = ref(false)

// ── data: unified store (tab 3) ─────────────────────────────────
const storeView = usePluginStore()
const storeDetail = ref<UnifiedCatalogEntry | null>(null)
const storeFilterType = ref<string | undefined>(undefined)
const storeSearch = ref('')

/**
 * Install record matching the currently-open store detail drawer, if the plugin is installed.
 * Skills/MCP are only known AFTER install (read from the cloned plugin.json), so the catalog
 * entry's declaredSkills/mcpServers are always empty — render these sections from this record.
 */
const storeDetailRecord = computed(() =>
  storeDetail.value
    ? storeView.history.find((h) => h.uid === storeDetail.value!.uid) ?? null
    : null,
)

/**
 * Homepage URL only if it uses a safe scheme. Catalog fields are attacker-controlled for
 * third-party sources, so binding `homepage` straight to :href would allow `javascript:` URIs
 * to execute in the app origin (which carries the auth token). Returns undefined for unsafe
 * schemes so the Homepage button is hidden.
 */
const safeHomepage = computed(() => {
  const url = storeDetail.value?.homepage
  if (!url) return undefined
  return /^(https?:|mailto:)/i.test(url) ? url : undefined
})

async function loadStore() {
  await Promise.all([storeView.loadSources(), storeView.loadCatalog(), storeView.loadHistory()])
}
function applyStoreFilter() {
  storeView.setFilter({
    sourceType: storeFilterType.value as UnifiedCatalogEntry['sourceType'] | undefined,
    q: storeSearch.value || undefined,
  })
  storeView.loadCatalog()
}

const skillRows = computed<SkillRow[]>(() => {
  const builtin: SkillRow[] = skillStore.skills
    .filter((s) => s.source === 'BUILTIN')
    .map((s) => ({
      id: s.id, name: s.name, description: s.description, icon: 'script-text-outline',
      meta: t('skillsMarket.builtin'), official: true, builtin: true,
      installed: false, enabled: s.enabled, updateAvailable: false,
    }))
  const market: SkillRow[] = skillStore.market.map((s) => ({
    id: s.id, name: s.name, description: s.description,
    icon: s.icon || 'script-text-outline',
    meta: `${s.author || s.id} · v${s.installedVersion || s.version}`,
    official: s.official, builtin: false, installed: s.installed, enabled: s.enabled,
    updateAvailable: s.updateAvailable,
  }))
  return [...builtin, ...market]
})

// ── unified card model for the grid (both tabs) ──────────────────
/**
 * One normalized card for the main grid regardless of tab. The `kind` discriminates which
 * lifecycle (plugin vs skill) the action buttons dispatch to; everything the card renders
 * (icon, name, badges, version, installed/enabled flags) lives on the flat shape.
 */
interface CardItem {
  kind: 'plugin' | 'skill'
  id: string
  name: string
  description: string
  icon: string
  version: string
  author: string
  official: boolean
  builtin: boolean
  installed: boolean
  enabled: boolean
  updateAvailable: boolean
  permissions: string[]
  category: string
}

const needle = computed(() => search.value.trim().toLocaleLowerCase())
const matches = (text: string) => !needle.value || text.toLocaleLowerCase().includes(needle.value)

const pluginCards = computed<CardItem[]>(() =>
  plugins.value
    .filter((p) => matches(`${p.name} ${p.description} ${p.author ?? ''} ${p.id}`))
    .map((p) => ({
      kind: 'plugin' as const,
      id: p.id, name: p.name, description: p.description,
      icon: p.icon || 'puzzle-outline', version: p.installedVersion || p.version,
      author: p.author || p.id, official: p.official, builtin: false,
      installed: p.installed, enabled: p.enabled, updateAvailable: p.updateAvailable,
      permissions: p.permissions ?? [],
      category: p.category || 'other',
    })),
)

const skillCards = computed<CardItem[]>(() =>
  skillRows.value
    .filter((s) => matches(`${s.name} ${s.description} ${s.meta} ${s.id}`))
    .map((s) => ({
      kind: 'skill' as const,
      id: s.id, name: s.name, description: s.description,
      icon: s.icon, version: s.builtin ? '—' : (s.meta.split('· v')[1] || '').trim(),
      author: s.builtin ? t('skillsMarket.builtin') : (s.meta.split(' ·')[0] || s.id),
      official: s.official, builtin: s.builtin, installed: s.installed, enabled: s.enabled,
      updateAvailable: s.updateAvailable, permissions: [], category: 'skills',
    })),
)

const cards = computed<CardItem[]>(() => (tab.value === 'plugins' ? pluginCards.value : skillCards.value))
/** Top installed fast-row for the current tab. */
const installedRow = computed<CardItem[]>(() => cards.value.filter((c) => c.installed || c.builtin))

interface PluginSection {
  id: string
  title: string
  items: CardItem[]
}

const catalogPlugins = computed(() => catalogScope.value === 'public'
  ? pluginCards.value
  : pluginCards.value.filter((card) => card.installed))
const featuredPlugins = computed(() => catalogPlugins.value.filter((card) => card.official).slice(0, 6))
const pluginSections = computed<PluginSection[]>(() => {
  const grouped = new Map<string, CardItem[]>()
  for (const card of catalogPlugins.value) {
    const category = card.category || 'other'
    const items = grouped.get(category) ?? []
    items.push(card)
    grouped.set(category, items)
  }
  return [...grouped.entries()]
    .sort(([a], [b]) => (a === 'other' ? 1 : b === 'other' ? -1 : a.localeCompare(b)))
    .map(([id, items]) => ({
      id,
      title: t(`category.${id}`) === `category.${id}` ? id : t(`category.${id}`),
      items,
    }))
})

const localPluginSearchEmpty = computed(() => tab.value === 'plugins' && Boolean(needle.value) && pluginCards.value.length === 0)
const remoteFallbackVisible = computed(() => localPluginSearchEmpty.value)

// ── mutation wrappers ────────────────────────────────────────────

async function runPlugin(id: string, action: () => Promise<unknown>) {
  busy.value = id
  error.value = null
  try {
    await action()
    await load()
    await runtimePlugins.load()
    refreshPluginDetail()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = null
  }
}

/**
 * Re-point the open drawer at the refreshed row after an operation. The drawer holds a
 * snapshot object taken from the pre-operation list, so without this it keeps rendering
 * the stale version/enabled label after an update or toggle — and dead action buttons
 * once the subject vanished (an uninstalled third-party plugin leaves no row at all).
 */
function refreshPluginDetail() {
  if (!pluginDetail.value) return
  pluginDetail.value = plugins.value.find((p) => p.id === pluginDetail.value!.id) ?? null
}

async function runSkill(id: string, action: () => Promise<boolean>) {
  busy.value = id
  error.value = null
  try {
    const ok = await action()
    if (!ok && skillStore.error) error.value = skillStore.error
    refreshSkillDetail()
  } finally {
    busy.value = null
  }
}

/** Skill twin of {@link refreshPluginDetail}; also reloads the preview after an update. */
function refreshSkillDetail() {
  if (!skillDetail.value) return
  const row = skillRows.value.find((s) => s.id === skillDetail.value!.id)
  if (!row) {
    closeDetail()
    return
  }
  skillDetail.value = row
  if (skillBody.value) void loadSkillBody(row.id)
}

const installPlugin = (id: string) => runPlugin(id, () => api.installPlugin(id))
const updatePlugin = (id: string) => runPlugin(id, () => api.updatePlugin(id))
const togglePlugin = (id: string, enabled: boolean) => runPlugin(id, () => api.setPluginEnabled(id, enabled))
async function uninstallPlugin(id: string) {
  if (!await confirmAction(t('market.confirmUninstall'))) return
  const deleteData = await confirmAction(t('market.confirmDeleteData'))
  void runPlugin(id, async () => {
    try {
      await api.uninstallPlugin(id, deleteData)
    } finally {
      // The uninstall can fail AFTER the package directory is already gone (the backend
      // deletes files before writing the response), so re-sync against the live list in
      // both outcomes: a vanished/uninstalled row closes the drawer (its buttons would
      // only error), while a still-installed row keeps it open for a retry.
      await load()
      if (pluginDetail.value?.id === id
        && !plugins.value.some((p) => p.id === id && p.installed)) {
        closeDetail()
      }
    }
  })
}

const installSkill = (id: string) => runSkill(id, () => skillStore.install(id))
const updateSkill = (id: string) => runSkill(id, () => skillStore.update(id))

async function confirmStoreUpdate(uid: string) {
  if (!await confirmAction(t('market.confirmUpdatePermissions'))) return
  await storeView.update(uid, true)
}
const toggleSkill = (id: string, enabled: boolean) => runSkill(id, () => skillStore.setEnabled(id, enabled))
async function uninstallSkill(id: string) {
  if (!await confirmAction(t('skillsMarket.confirmUninstall'))) return
  void runSkill(id, async () => {
    const ok = await skillStore.uninstall(id)
    if (ok && skillDetail.value?.id === id) closeDetail()
    return ok
  })
}

// ── local package pick: .fys installs directly, .fyp confirms first ─
/**
 * One picked local .fyp awaiting the user's confirmation. `inspection` is the pre-install
 * manifest read (`/inspect`, nothing installed yet), so the dialog can show the exact
 * version step (upgrade / same / downgrade) before the upload replaces the plugin.
 */
interface PendingPluginPackage {
  label: string
  file?: File
  path?: string
  inspection: PackageInspection
}

const pendingPackage = ref<PendingPluginPackage | null>(null)
/** Upload failure while the confirm dialog is open — the page-level banner sits behind the scrim. */
const confirmError = ref<string | null>(null)
const inspecting = ref(false)
const success = ref<string | null>(null)
/** Which package flavors the next pick accepts: the Add menu takes .fyp/.fys, the per-plugin
 * "Update from local" button takes .fyp only. */
const pickMode = ref<'package' | 'plugin'>('package')
let successTimer: ReturnType<typeof setTimeout> | undefined

function showSuccess(message: string) {
  success.value = message
  if (successTimer) clearTimeout(successTimer)
  successTimer = setTimeout(() => { success.value = null }, 6_000)
}

/** Install a picked .fys skill package directly (skills have no inspection endpoint). */
async function dispatchSkillPackage(file?: File, path?: string) {
  busy.value = 'upload'
  error.value = null
  try {
    const ok = file ? await skillStore.uploadFile(file) : await skillStore.uploadNative(path!)
    if (!ok) throw new Error(skillStore.error ?? 'upload failed')
    await load()
    await skillStore.refresh()
    await runtimePlugins.load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = null
  }
}

/** Route one picked local package: .fys installs now, .fyp opens the confirm dialog. */
async function handlePickedLocal(name: string, file?: File, path?: string) {
  const lower = name.toLowerCase()
  if (lower.endsWith('.fys')) {
    await dispatchSkillPackage(file, path)
    return
  }
  if (!lower.endsWith('.fyp')) {
    error.value = t('market.unsupportedPackage')
    return
  }
  busy.value = 'upload'
  error.value = null
  inspecting.value = true
  try {
    const inspection = file ? await api.inspectPlugin(file) : await api.inspectNativePlugin(path!)
    pendingPackage.value = { label: name, file, path, inspection }
    confirmError.value = null
  } catch (e) {
    // A pre-inspection endpoint that answers 404/405 means the running backend predates
    // it (e.g. an IDE session started from stale classes). The upload endpoint itself is
    // unchanged there, so fall back to confirming from the file name instead of failing
    // the whole install with an opaque "not allowed" error.
    const status = (e as { response?: { status?: number } })?.response?.status
    if (status === 404 || status === 405) {
      pendingPackage.value = {
        label: name, file, path,
        inspection: {
          id: '', name, version: '', installed: false, installedVersion: null, comparison: null,
          permissions: [], addedPermissions: [], removedPermissions: [], permissionEscalation: false,
        },
      }
      confirmError.value = null
      return
    }
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    inspecting.value = false
    busy.value = null
  }
}

/** Apply the confirmed package: the upload endpoint stops the running Worker (update gate)
 * and atomically swaps the installed directory. */
async function confirmPendingPackage() {
  const pkg = pendingPackage.value
  if (!pkg) return
  busy.value = 'upload'
  error.value = null
  confirmError.value = null
  try {
    if (pkg.file) await api.uploadPlugin(pkg.file, true)
    else await api.uploadNativePlugin(pkg.path!, true)
    pendingPackage.value = null
    await load()
    await runtimePlugins.load()
    refreshPluginDetail()
    showSuccess(t(pkg.inspection.installed ? 'market.updateLocalDone' : 'market.installLocalDone',
      { name: pkg.inspection.name, version: pkg.inspection.version || pkg.label }))
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    confirmError.value = error.value
  } finally {
    busy.value = null
  }
}

function closePendingPackage() {
  if (busy.value === 'upload') return
  pendingPackage.value = null
  confirmError.value = null
}

async function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  await handlePickedLocal(file.name, file, undefined)
  input.value = ''
}

async function chooseLocalPackage(mode: 'package' | 'plugin' = 'package') {
  addMenuOpen.value = false
  pickMode.value = mode
  if (!desktop) { fileInput.value?.click(); return }
  const path = await desktop.pickFile([
    { name: 'FengYu Package', extensions: mode === 'plugin' ? ['fyp'] : ['fyp', 'fys'] },
  ])
  if (path) await handlePickedLocal(path, undefined, path)
}

// ── detail drawer ────────────────────────────────────────────────

function openDetail(card: CardItem) {
  if (card.kind === 'plugin') {
    pluginDetail.value = plugins.value.find((p) => p.id === card.id) ?? null
    skillDetail.value = null
  } else {
    skillDetail.value = skillRows.value.find((s) => s.id === card.id) ?? null
    pluginDetail.value = null
    if (skillDetail.value) void loadSkillBody(skillDetail.value.id)
  }
}

async function loadSkillBody(id: string) {
  skillBodyLoading.value = true
  skillBody.value = null
  try {
    skillBody.value = await skillStore.detail(id)
  } finally {
    skillBodyLoading.value = false
  }
}

function closeDetail() {
  pluginDetail.value = null
  skillDetail.value = null
  skillBody.value = null
}

function md(src: string): string {
  return renderMarkdown(src)
}

onMounted(async () => {
  await Promise.all([load(), skillStore.load(), skillStore.loadMarket()])
  // Load the unified store catalog in parallel with the legacy marketplaces.
  void loadStore()
})

function refreshMarket() {
  addMenuOpen.value = false
  if (tab.value === 'stores') void loadStore()
  else if (tab.value === 'skills') void Promise.all([skillStore.load(), skillStore.loadMarket()])
  else void load()
}

function openSources() {
  addMenuOpen.value = false
  tab.value = 'stores'
}

async function openMarketSettings() {
  marketSettingsError.value = null
  await storeView.loadSources()
  const current = storeView.sources.find((source) => source.sourceType === 'FENGYU')
  marketSourceOrigin.value = current?.origin ?? null
  marketSourceName.value = current?.name ?? 'FengYu Market'
  marketSourceUrl.value = current?.catalogUrl ?? ''
  marketSettingsOpen.value = true
}

async function saveMarketSettings() {
  const url = marketSourceUrl.value.trim()
  const name = marketSourceName.value.trim() || 'FengYu Market'
  if (!/^https?:\/\//i.test(url)) {
    marketSettingsError.value = t('market.marketAddressInvalid')
    return
  }

  marketSettingsBusy.value = true
  marketSettingsError.value = null
  try {
    const current = marketSourceOrigin.value
      ? storeView.sources.find((source) => source.origin === marketSourceOrigin.value)
      : undefined
    const unchanged = current?.sourceType === 'FENGYU' && current.name === name && current.catalogUrl === url
    if (!unchanged) {
      if (current) await storeView.deleteSource(current.origin)
      await storeView.addSource(name, 'FENGYU', url)
    }
    await storeView.loadSources()
    await storeView.loadCatalog()
    marketSettingsOpen.value = false
    if (remoteFallbackVisible.value) void searchRemoteCatalog()
  } catch (e) {
    marketSettingsError.value = e instanceof Error ? e.message : String(e)
  } finally {
    marketSettingsBusy.value = false
  }
}

async function searchRemoteCatalog() {
  const query = needle.value
  if (!remoteFallbackVisible.value || !query) {
    remoteSearchResults.value = []
    remoteSearchReady.value = false
    remoteSearchError.value = null
    remoteSearching.value = false
    return
  }

  const request = ++remoteSearchRequest
  remoteSearching.value = true
  remoteSearchReady.value = false
  remoteSearchError.value = null
  try {
    const results = await api.getUnifiedCatalog({ q: query })
    if (request !== remoteSearchRequest) return
    remoteSearchResults.value = results ?? []
    remoteSearchReady.value = true
  } catch (e) {
    if (request !== remoteSearchRequest) return
    remoteSearchResults.value = []
    remoteSearchError.value = e instanceof Error ? e.message : String(e)
    remoteSearchReady.value = true
  } finally {
    if (request === remoteSearchRequest) remoteSearching.value = false
  }
}

function scheduleRemoteSearch() {
  if (remoteSearchTimer) clearTimeout(remoteSearchTimer)
  remoteSearchResults.value = []
  remoteSearchReady.value = false
  remoteSearchError.value = null
  if (!localPluginSearchEmpty.value) {
    remoteSearchRequest += 1
    remoteSearching.value = false
    return
  }
  remoteSearchTimer = setTimeout(() => { void searchRemoteCatalog() }, 220)
}

watch([search, tab, () => pluginCards.value.length], scheduleRemoteSearch)
onBeforeUnmount(() => {
  if (remoteSearchTimer) clearTimeout(remoteSearchTimer)
  if (successTimer) clearTimeout(successTimer)
  remoteSearchRequest += 1
})

// Keep template-only bindings visible to vue-tsc's noUnusedLocals pass. These are consumed by
// event/directive expressions and component tags in the SFC template.
void [
  UnifiedSourceBadge, StoreSourceManager, storeDetailRecord, safeHomepage, applyStoreFilter,
  installedRow, featuredPlugins, pluginSections, installPlugin, updatePlugin, togglePlugin,
  uninstallPlugin, installSkill, toggleSkill, onFilePicked, chooseLocalPackage, openDetail,
  closeDetail, confirmPendingPackage, closePendingPackage, refreshMarket, openSources,
  confirmStoreUpdate,
]
</script>

<template>
  <div class="market-page">
    <!-- ChatGPT-style app bar: view switching is separate from the catalog controls. -->
    <header class="market-header">
      <div class="cx-segment tab-segment">
        <button :class="{ active: tab === 'plugins' }" @click="tab = 'plugins'">
          {{ t('market.tabPlugins') }}
        </button>
        <button :class="{ active: tab === 'skills' }" @click="tab = 'skills'">
          {{ t('market.tabSkills') }}
        </button>
      </div>
      <div class="market-header-actions">
        <button class="cx-iconbtn" :title="t('market.refresh')" :aria-label="t('market.refresh')" @click="refreshMarket">
          <i class="mdi mdi-refresh" />
        </button>
        <button class="cx-iconbtn" :title="t('market.marketSettings')" :aria-label="t('market.marketSettings')" @click="openMarketSettings">
          <i class="mdi mdi-cog-outline" />
        </button>
        <div class="market-add-wrap">
          <button class="market-add-button" :aria-expanded="addMenuOpen" @click="addMenuOpen = !addMenuOpen">
            {{ t('market.add') }} <i class="mdi mdi-chevron-down sm" />
          </button>
          <div v-if="addMenuOpen" class="market-add-menu">
            <button @click="addMenuOpen = false; chooseLocalPackage()"><i class="mdi mdi-tray-arrow-up" />{{ t('market.upload') }}</button>
            <button @click="openSources"><i class="mdi mdi-store-outline" />{{ t('market.store.tab') }}</button>
          </div>
        </div>
      </div>
    </header>

    <div v-if="error" class="cx-alert cx-alert--error market-error">
      <i class="mdi mdi-alert-circle-outline" />
      <div class="cx-alert__body"><strong>{{ t('market.operationFailed') }}</strong><br>{{ error }}</div>
      <button class="cx-iconbtn cx-iconbtn--sm" @click="error = null"><i class="mdi mdi-close" /></button>
    </div>

    <div v-if="success" class="cx-alert cx-alert--success market-error">
      <i class="mdi mdi-check-circle-outline" />
      <div class="cx-alert__body">{{ success }}</div>
      <button class="cx-iconbtn cx-iconbtn--sm" @click="success = null"><i class="mdi mdi-close" /></button>
    </div>

    <div class="market-scroll">
      <!-- ═══ Unified store tab (FengYu + Claude + Codex + Grok) ═══ -->
      <div v-if="tab === 'stores'" class="store-tab">
        <StoreSourceManager />

        <div class="d-flex align-center ga-2 my-3 store-filter-row">
          <v-select v-model="storeFilterType" :items="['FENGYU','CLAUDE','CODEX','GROK']"
                    :label="t('market.store.sourceTypeAll')" clearable density="compact"
                    style="max-width: 180px" @update:model-value="applyStoreFilter" />
          <v-text-field v-model="storeSearch" density="compact" append-icon="mdi-magnify"
                        :placeholder="t('market.search')"
                        @click:append="applyStoreFilter" @keyup.enter="applyStoreFilter" />
        </div>

        <div v-if="storeView.catalog.length" class="cx-card-grid">
          <article
            v-for="e in storeView.catalog" :key="e.uid"
            class="cx-card cx-card--hover ext-card"
            @click="storeDetail = e"
          >
            <div class="ext-card-head">
              <span class="cx-avatar ext-icon"><i class="mdi mdi-store-outline" /></span>
              <div class="ext-card-titlewrap">
                <div class="ext-card-title">
                  <span class="text-truncate">{{ e.displayName }}</span>
                  <UnifiedSourceBadge :type="e.sourceType" />
                </div>
                <div class="cx-muted ext-card-meta">{{ e.category || '—' }}</div>
              </div>
            </div>

            <p class="cx-muted ext-card-desc">{{ e.description }}</p>

            <div class="ext-card-actions" @click.stop>
              <button
                v-if="!e.installed"
                class="cx-btn cx-btn--primary cx-btn--sm"
                :disabled="storeView.busy === e.uid"
                @click="storeView.install(e.uid)"
              >
                <span v-if="storeView.busy === e.uid" class="cx-spin" />{{ storeView.busy === e.uid ? t('market.store.cloneInProgress') : t('market.store.install') }}
              </button>
              <button
                v-else-if="e.updateAvailable"
                class="cx-btn cx-btn--outline cx-btn--sm"
                :disabled="storeView.busy === e.uid"
                @click="confirmStoreUpdate(e.uid)"
              >{{ t('market.store.update') }}</button>
              <label v-else class="cx-switch" :title="e.enabled ? t('market.store.disable') : t('market.store.enable')">
                <input
                  type="checkbox"
                  :checked="e.enabled"
                  :disabled="storeView.busy === e.uid"
                  @change="storeView.setEnabled(e.uid, !e.enabled)"
                >
                <span class="cx-switch__track" /><span class="cx-switch__thumb" />
                <span class="cx-muted cx-switch-label">{{ e.enabled ? t('market.store.enable') : t('market.store.disable') }}</span>
              </label>
            </div>
          </article>
        </div>
        <div v-else-if="storeView.loading" class="market-empty"><span class="cx-spin lg" /></div>
        <div v-else-if="storeView.error" class="cx-alert cx-alert--error market-error">
          <i class="mdi mdi-alert-circle-outline" />
          <div class="cx-alert__body">{{ storeView.error }}</div>
        </div>
      </div>

      <!-- ═══ Legacy plugins/skills tabs ═══ -->
      <div v-else class="market-content">
        <section class="market-intro">
          <h1>{{ tab === 'plugins' ? t('market.title') : t('skillsMarket.title') }}</h1>
          <p>{{ tab === 'plugins' ? t('market.subtitle') : t('skillsMarket.subtitle') }}</p>
        </section>

        <div class="cx-input-wrap market-search">
          <i class="mdi mdi-magnify market-search-icon" />
          <input v-model="search" class="cx-input" :placeholder="t('market.search')">
        </div>

        <input ref="fileInput" type="file" :accept="pickMode === 'plugin' ? '.fyp' : '.fyp,.fys'" hidden @change="onFilePicked">

        <section v-if="installedRow.length" class="installed-row">
          <div class="section-heading">
            <h2>{{ t('market.installedRow') }}</h2>
            <button class="cx-iconbtn cx-iconbtn--sm" :title="t('market.manageInstalled')" @click="tab = 'plugins'">
              <i class="mdi mdi-cog-outline" />
            </button>
          </div>
          <div class="installed-row-pills">
            <button v-for="item in installedRow" :key="item.id" class="installed-pill" :title="item.name" :aria-label="item.name" @click="openDetail(item)">
              <i class="mdi" :class="`mdi-${item.icon}`" />
            </button>
          </div>
        </section>

        <div class="market-filter-row">
          <div class="cx-segment catalog-tabs">
            <button :class="{ active: catalogScope === 'public' }" @click="catalogScope = 'public'">{{ t('market.public') }}</button>
            <button :class="{ active: catalogScope === 'personal' }" @click="catalogScope = 'personal'">{{ t('market.personal') }}</button>
          </div>
          <button class="cx-iconbtn" :title="t('market.filter')" :aria-label="t('market.filter')"><i class="mdi mdi-filter-variant" /></button>
        </div>

      <!-- Loading / empty / grid -->
      <div v-if="loading && tab === 'plugins'" class="market-empty"><span class="cx-spin lg" /></div>
      <div v-else-if="cards.length === 0 && !remoteFallbackVisible" class="market-empty">
        <i class="mdi lg" :class="tab === 'plugins' ? 'mdi-puzzle-outline' : 'mdi-script-text-outline'" />
        <span>{{ plugins.length || skillRows.length ? t('market.empty') : t('market.catalogEmpty') }}</span>
      </div>
      <section v-else-if="remoteFallbackVisible" class="remote-fallback-section">
        <div class="plugin-section-heading">
          <h2>{{ t('market.remoteResults') }}</h2>
          <span v-if="remoteSearching" class="cx-muted remote-search-status"><span class="cx-spin" />{{ t('market.remoteSearching') }}</span>
        </div>
        <div v-if="remoteSearchResults.length" class="plugin-list-grid">
          <article v-for="entry in remoteSearchResults" :key="entry.uid" class="plugin-list-item" @click="storeDetail = entry">
            <span class="plugin-list-icon"><i class="mdi mdi-store-outline" /></span>
            <div class="plugin-list-copy">
              <strong>{{ entry.displayName }}</strong>
              <span>{{ entry.description }}</span>
            </div>
            <UnifiedSourceBadge :type="entry.sourceType" />
            <div class="plugin-list-action" @click.stop>
              <button v-if="!entry.installed" class="cx-btn cx-btn--outline cx-btn--sm" :disabled="storeView.busy === entry.uid" @click="storeView.install(entry.uid)">
                <span v-if="storeView.busy === entry.uid" class="cx-spin" />{{ t('market.install') }}
              </button>
              <button v-else-if="entry.updateAvailable" class="cx-btn cx-btn--outline cx-btn--sm" :disabled="storeView.busy === entry.uid" @click="confirmStoreUpdate(entry.uid)">{{ t('market.update') }}</button>
              <button v-else class="plugin-status-button" :class="{ enabled: entry.enabled }" @click="storeView.setEnabled(entry.uid, !entry.enabled)">{{ entry.enabled ? t('market.enabledShort') : t('market.disabledShort') }}</button>
            </div>
          </article>
        </div>
        <div v-else-if="!remoteSearching" class="remote-empty">
          <i class="mdi mdi-store-search-outline lg" />
          <p v-if="remoteSearchError">{{ remoteSearchError }}</p>
          <p v-else-if="!storeView.sources.length">{{ t('market.remoteConfigureHint') }}</p>
          <p v-else>{{ t('market.remoteNoResults') }}</p>
          <button v-if="!storeView.sources.length" class="cx-btn cx-btn--outline" @click="openMarketSettings">{{ t('market.marketSettings') }}</button>
        </div>
      </section>
      <div v-else-if="tab === 'plugins'" class="plugin-catalog">
        <section v-if="featuredPlugins.length" class="plugin-section">
          <div class="plugin-section-heading"><h2>{{ t('market.featured') }}</h2></div>
          <div class="plugin-list-grid">
            <article v-for="card in featuredPlugins" :key="`featured-${card.id}`" class="plugin-list-item" @click="openDetail(card)">
              <span class="plugin-list-icon"><i class="mdi" :class="`mdi-${card.icon}`" /></span>
              <div class="plugin-list-copy"><strong>{{ card.name }}</strong><span>{{ card.description }}</span></div>
              <div class="plugin-list-action" @click.stop>
                <button v-if="!card.installed" class="cx-btn cx-btn--outline cx-btn--sm" :disabled="busy === card.id" @click="installPlugin(card.id)"><span v-if="busy === card.id" class="cx-spin" />{{ t('market.install') }}</button>
                <button v-else class="plugin-more-button" :title="t('market.manageInstalled')" @click="openDetail(card)">•••</button>
              </div>
            </article>
          </div>
        </section>
        <section v-for="section in pluginSections" :key="section.id" class="plugin-section">
          <div class="plugin-section-heading"><h2>{{ section.title }}</h2></div>
          <div class="plugin-list-grid">
            <article v-for="card in section.items" :key="card.id" class="plugin-list-item" @click="openDetail(card)">
              <span class="plugin-list-icon"><i class="mdi" :class="`mdi-${card.icon}`" /></span>
              <div class="plugin-list-copy"><strong>{{ card.name }}</strong><span>{{ card.description }}</span></div>
              <div class="plugin-list-action" @click.stop>
                <button v-if="!card.installed && !card.builtin" class="cx-btn cx-btn--outline cx-btn--sm" :disabled="busy === card.id" @click="installPlugin(card.id)"><span v-if="busy === card.id" class="cx-spin" />{{ t('market.install') }}</button>
                <button v-else-if="card.updateAvailable" class="cx-btn cx-btn--outline cx-btn--sm" :disabled="busy === card.id" @click="updatePlugin(card.id)">{{ t('market.update') }}</button>
                <button v-else class="plugin-status-button" :class="{ enabled: card.enabled }" @click="togglePlugin(card.id, !card.enabled)">{{ card.enabled ? t('market.enabledShort') : t('market.disabledShort') }}</button>
              </div>
            </article>
          </div>
        </section>
      </div>
      <div v-else class="cx-card-grid">
        <article v-for="card in cards" :key="`${card.kind}-${card.id}`" class="cx-card cx-card--hover ext-card" @click="openDetail(card)">
          <div class="ext-card-head"><span class="cx-avatar ext-icon"><i class="mdi" :class="`mdi-${card.icon}`" /></span><div class="ext-card-titlewrap"><div class="ext-card-title">{{ card.name }}</div><div class="cx-muted ext-card-meta">{{ card.author }}</div></div></div>
          <p class="cx-muted ext-card-desc">{{ card.description }}</p>
          <div class="ext-card-actions" @click.stop><button v-if="!card.installed && !card.builtin" class="cx-btn cx-btn--primary cx-btn--sm" :disabled="busy === card.id" @click="installSkill(card.id)">{{ t('market.install') }}</button><template v-if="card.installed"><label class="cx-switch"><input type="checkbox" :checked="card.enabled" @change="toggleSkill(card.id, !card.enabled)"><span class="cx-switch__track" /><span class="cx-switch__thumb" /></label></template></div>
        </article>
      </div>
      </div>
    </div>

    <!-- Detail drawer (plugin permissions OR skill body preview) -->
    <div v-if="pluginDetail || skillDetail" class="cx-detail-overlay" @click.self="closeDetail">
      <div class="cx-detail-drawer">
        <div class="cx-row" style="align-items: center; margin-bottom: 16px">
          <span class="cx-grow" style="font-weight: 600; font-size: 16px">
            {{ pluginDetail?.name ?? skillDetail?.name ?? '…' }}
          </span>
          <button class="cx-iconbtn cx-iconbtn--sm" :title="t('skillsMarket.close')" @click="closeDetail">
            <i class="mdi mdi-close" />
          </button>
        </div>

        <!-- plugin detail -->
        <template v-if="pluginDetail">
          <p class="cx-muted" style="line-height: 1.6; margin-bottom: 16px">{{ pluginDetail.description }}</p>
          <dl class="detail-facts">
            <div><dt>{{ t('market.version') }}</dt><dd>{{ pluginDetail.installedVersion || pluginDetail.version }}</dd></div>
            <div><dt>{{ t('market.author') }}</dt><dd>{{ pluginDetail.author || '—' }}</dd></div>
          </dl>
          <h3 class="detail-h3">{{ t('market.permissions') }}</h3>
          <div v-if="pluginDetail.permissions.length" class="permission-list">
            <span v-for="permission in pluginDetail.permissions" :key="permission" class="cx-chip">
              <i class="mdi mdi-shield-key-outline sm" />{{ permission }}
            </span>
          </div>
          <p v-else class="cx-muted">{{ t('market.noPermissions') }}</p>
          <div v-if="pluginDetail.installed" class="detail-actions">
            <button class="cx-btn cx-btn--outline" @click="chooseLocalPackage('plugin')">{{ t('market.updateFromLocal') }}</button>
            <button v-if="pluginDetail.updateAvailable" class="cx-btn cx-btn--outline" @click="updatePlugin(pluginDetail.id)">{{ t('market.update') }}</button>
            <button class="cx-btn cx-btn--outline" @click="togglePlugin(pluginDetail.id, !pluginDetail.enabled)">{{ pluginDetail.enabled ? t('market.disable') : t('market.enable') }}</button>
            <button class="cx-btn cx-btn--text danger" @click="uninstallPlugin(pluginDetail.id)">{{ t('market.uninstall') }}</button>
          </div>
        </template>

        <!-- skill detail -->
        <template v-else-if="skillDetail">
          <p class="cx-muted" style="line-height: 1.6; margin-bottom: 16px">{{ skillDetail?.description }}</p>
          <dl class="detail-facts">
            <div><dt>{{ t('skillsMarket.author') }}</dt><dd>{{ skillDetail?.builtin ? t('skillsMarket.builtin') : (skillDetail?.meta.split(' ·')[0] || '—') }}</dd></div>
          </dl>
          <h3 class="detail-h3">{{ t('skillsMarket.preview') }}</h3>
          <div v-if="skillBodyLoading" class="cx-muted">{{ t('common.loading') }}</div>
          <div v-else-if="skillBody" class="cx-md cx-skill-body" v-html="md(skillBody.body)" />
          <p v-else class="cx-muted">{{ t('skillsMarket.builtinReadonly') }}</p>
          <div v-if="skillDetail?.installed" class="detail-actions">
            <button v-if="skillDetail?.updateAvailable" class="cx-btn cx-btn--outline" @click="skillDetail && updateSkill(skillDetail.id)">{{ t('market.update') }}</button>
            <button class="cx-btn cx-btn--text danger" @click="skillDetail && uninstallSkill(skillDetail.id)">{{ t('market.uninstall') }}</button>
          </div>
        </template>
      </div>
    </div>

    <!-- Remote market address settings. The backend persists this as a FengYu store source. -->
    <v-dialog v-model="marketSettingsOpen" max-width="560">
      <v-card :title="t('market.marketSettings')">
        <v-card-text>
          <p class="market-settings-description">{{ t('market.marketAddressHint') }}</p>
          <v-text-field v-model="marketSourceName" :label="t('market.marketName')" :disabled="marketSettingsBusy" />
          <v-text-field v-model="marketSourceUrl" :label="t('market.marketAddress')" placeholder="https://example.com/marketplace.json" :disabled="marketSettingsBusy" />
          <v-alert v-if="marketSettingsError" type="error" density="compact">{{ marketSettingsError }}</v-alert>
          <div v-if="storeView.sources.length" class="market-source-summary">
            <span class="cx-muted">{{ t('market.configuredSources') }}</span>
            <span v-for="source in storeView.sources" :key="source.origin" class="market-source-line">
              <UnifiedSourceBadge :type="source.sourceType" />{{ source.name }} · {{ source.catalogUrl }}
            </span>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="marketSettingsBusy" @click="marketSettingsOpen = false">{{ t('common.cancel') }}</v-btn>
          <v-btn variant="tonal" :loading="marketSettingsBusy" @click="saveMarketSettings">{{ t('market.saveMarketSettings') }}</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Local-package confirm: what the picked .fyp will do before it replaces anything. -->
    <v-dialog :model-value="!!pendingPackage" max-width="480" @update:model-value="closePendingPackage()">
      <v-card v-if="pendingPackage" :title="pendingPackage.inspection.installed ? t('market.updateFromLocalTitle') : t('market.installFromLocalTitle')">
        <v-card-text>
          <div class="package-confirm-file" :title="pendingPackage.label">
            <i class="mdi mdi-package-variant-closed" />
            <span>{{ pendingPackage.label }}</span>
          </div>
          <dl class="detail-facts">
            <div v-if="pendingPackage.inspection.version"><dt>{{ t('market.version') }}</dt><dd>{{ pendingPackage.inspection.version }}</dd></div>
            <div v-if="pendingPackage.inspection.installed">
              <dt>{{ t('market.localInstalledVersion') }}</dt>
              <dd class="package-version-step">
                {{ pendingPackage.inspection.installedVersion }} <i class="mdi mdi-arrow-right" />
                {{ pendingPackage.inspection.version }}
              </dd>
            </div>
          </dl>
          <div v-if="pendingPackage.inspection.permissions.length" class="package-permissions">
            <strong>{{ t('market.permissions') }}</strong>
            <div class="permission-list mt-2">
              <span v-for="permission in pendingPackage.inspection.permissions" :key="permission" class="cx-chip">
                <i class="mdi mdi-shield-key-outline sm" />{{ permission }}
              </span>
            </div>
          </div>
          <v-alert
            v-if="pendingPackage.inspection.installed && pendingPackage.inspection.permissionEscalation"
            type="warning" variant="tonal" density="compact" class="mt-3"
          >{{ t('market.permissionEscalation', { permissions: pendingPackage.inspection.addedPermissions.join(', ') }) }}</v-alert>
          <v-alert
            v-if="pendingPackage.inspection.comparison === 'downgrade'"
            type="warning" variant="tonal" density="compact"
          >{{ t('market.downgradeWarning') }}</v-alert>
          <v-alert
            v-else-if="pendingPackage.inspection.comparison === 'same'"
            type="info" variant="tonal" density="compact"
          >{{ t('market.sameVersionNotice') }}</v-alert>
          <p v-if="pendingPackage.inspection.installed" class="cx-muted package-confirm-note">
            {{ t('market.updateLocalNote') }}
          </p>
          <v-alert v-if="confirmError" type="error" variant="tonal" density="compact" class="mt-3">{{ confirmError }}</v-alert>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="busy === 'upload'" @click="closePendingPackage()">{{ t('common.cancel') }}</v-btn>
          <v-btn variant="tonal" :loading="busy === 'upload'" @click="confirmPendingPackage">
            {{ pendingPackage.inspection.installed ? t('market.update') : t('market.install') }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Store entry detail drawer (unified store tab) -->
    <v-navigation-drawer
      v-if="storeDetail"
      :model-value="true"
      location="right"
      temporary
      width="420"
      :scrim="false"
      style="z-index: 200"
      @update:model-value="storeDetail = null"
    >
      <v-card v-if="storeDetail" flat>
        <v-card-title class="d-flex align-center ga-2">
          {{ storeDetail.displayName }}
          <UnifiedSourceBadge :type="storeDetail.sourceType" />
        </v-card-title>
        <v-card-text>
          <p class="mb-3">{{ storeDetail.description }}</p>
          <div v-if="storeDetail.pinnedSha" class="mb-2">
            <div class="text-caption text-medium-emphasis">{{ t('market.store.pinnedSha') }}</div>
            <code class="text-caption">{{ storeDetail.pinnedSha }}</code>
          </div>
          <div v-if="storeDetailRecord && storeDetailRecord.declaredSkills.length" class="mb-2">
            <div class="text-caption text-medium-emphasis">{{ t('market.store.declaredSkills') }}</div>
            <v-chip v-for="s in storeDetailRecord.declaredSkills" :key="s" size="small" class="mr-1">{{ s }}</v-chip>
          </div>
          <div v-if="storeDetailRecord && storeDetailRecord.hasMcpServers" class="mb-2">
            <div class="text-caption text-medium-emphasis">{{ t('market.store.mcpServers') }}</div>
            <v-chip v-for="m in storeDetailRecord.mcpServerRefs" :key="m" size="small" class="mr-1">{{ m }}</v-chip>
            <v-alert type="warning" variant="tonal" density="compact" class="mt-2">
              {{ t('market.store.mcpWarning') }}
            </v-alert>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-btn v-if="safeHomepage" :href="safeHomepage" target="_blank" rel="noopener noreferrer">{{ t('market.store.homepage') }}</v-btn>
          <v-spacer />
          <v-btn @click="storeDetail = null">{{ t('market.store.close') }}</v-btn>
        </v-card-actions>
      </v-card>
    </v-navigation-drawer>
  </div>
</template>

<style scoped>
.market-page { height: 100%; min-height: 0; display: flex; flex-direction: column; background: rgb(var(--v-theme-background)); }
.market-header { min-height: 56px; padding: 10px 12px; display: flex; align-items: center; justify-content: space-between; gap: 12px; border-bottom: 1px solid rgb(var(--v-theme-outline-variant)); flex: 0 0 auto; background: rgb(var(--v-theme-surface)); }
.tab-segment { flex: 0 0 auto; gap: 8px; padding: 0; background: transparent; }
.tab-segment button { display: inline-flex; align-items: center; gap: 6px; min-height: 34px; padding: 5px 12px; border-radius: 14px; font-size: 16px; font-weight: 400; }
.tab-segment button.active { background: var(--cx-hover-strong); box-shadow: none; color: rgb(var(--v-theme-on-surface)); }
.market-header-actions { display: flex; align-items: center; gap: 4px; }
.market-add-wrap { position: relative; }
.market-add-button { display: inline-flex; align-items: center; gap: 5px; border: 0; border-radius: 10px; padding: 6px 10px; background: #202123; color: #fff; font: inherit; font-size: 13px; cursor: pointer; }
.market-add-button:hover { opacity: .88; }
.market-add-button .mdi { font-size: 16px; }
.market-add-menu { position: absolute; top: calc(100% + 7px); right: 0; z-index: 20; min-width: 190px; padding: 5px; border: 1px solid var(--cx-border); border-radius: 10px; background: rgb(var(--v-theme-surface)); box-shadow: 0 8px 24px rgba(0,0,0,.16); }
.market-add-menu button { width: 100%; display: flex; align-items: center; gap: 9px; border: 0; border-radius: 7px; padding: 9px 10px; background: transparent; color: inherit; font: inherit; font-size: 13px; text-align: left; cursor: pointer; }
.market-add-menu button:hover { background: var(--cx-hover); }
.market-add-menu .mdi { font-size: 17px; color: rgb(var(--v-theme-secondary)); }
.market-search { width: 100%; max-width: none; }
.market-search .cx-input { padding-left: 36px; }
.market-search-icon { position: absolute; left: 11px; z-index: 1; color: rgb(var(--v-theme-secondary)); font-size: 18px; }
.market-error { margin: 12px 20px 0; flex: 0 0 auto; }

/* Local install dropzone (click or drag a .fyp/.fys package) */
.local-install {
  display: flex; align-items: center; gap: 14px;
  margin-bottom: 16px;
  padding: 16px 18px;
  border: 1.5px dashed var(--cx-border);
  border-radius: var(--cx-radius-lg);
  background: rgb(var(--v-theme-surface));
  cursor: pointer;
  transition: border-color .13s ease, background .13s ease;
}
.local-install:hover { border-color: var(--cx-hover-strong); background: var(--cx-hover); }
.local-install .li-ic {
  width: 44px; height: 44px; border-radius: 12px; flex: 0 0 auto;
  display: grid; place-items: center;
  background: rgb(var(--v-theme-surface-container-high));
  color: rgb(var(--v-theme-on-surface));
}
.local-install .li-ic .mdi { font-size: 22px; }
.local-install .li-txt { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.local-install .li-txt strong { font-size: 14px; font-weight: 600; }
.local-install .li-txt .cx-muted { font-size: 12px; }
.local-install .li-btn { flex: 0 0 auto; pointer-events: none; }

.market-scroll { flex: 1; min-height: 0; overflow-y: auto; padding: 0 20px 40px; }
.market-content { width: min(730px, 100%); margin: 0 auto; padding: 28px 8px 40px; }
.market-intro { margin: 0 0 18px; }
.market-intro h1 { margin: 0 0 7px; font-size: 28px; line-height: 1.2; font-weight: 600; letter-spacing: -.02em; }
.market-intro p { margin: 0; color: rgb(var(--v-theme-secondary)); font-size: 15px; }
.market-content .market-search .cx-input { height: 34px; border-radius: 18px; background: rgb(var(--v-theme-surface)); }
.market-filter-row { display: flex; align-items: center; justify-content: space-between; margin: 20px 0 22px; }
.catalog-tabs { background: transparent; padding: 0; }
.catalog-tabs button { padding: 6px 10px; }
.catalog-tabs button.active { background: var(--cx-hover-strong); box-shadow: none; }
.section-heading, .plugin-section-heading { display: flex; align-items: center; justify-content: space-between; min-height: 32px; border-bottom: 1px solid rgb(var(--v-theme-outline-variant)); }
.section-heading h2, .plugin-section-heading h2 { margin: 0; font-size: 16px; font-weight: 550; }

/* Installed fast-row */
.installed-row { margin-bottom: 18px; }
.installed-row-pills { display: flex; gap: 10px; overflow-x: auto; padding: 10px 2px 4px; }
.installed-pill { width: 38px; height: 38px; flex: 0 0 auto; display: inline-flex; align-items: center; justify-content: center; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 10px; background: rgb(var(--v-theme-surface)); color: inherit; font: inherit; cursor: pointer; transition: background .12s, transform .12s; }
.installed-pill:hover { background: rgb(var(--v-theme-surface-container-high)); transform: translateY(-1px); }
.installed-pill .mdi { font-size: 21px; color: rgb(var(--v-theme-primary)); }
.plugin-section { margin-top: 22px; }
.plugin-list-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 38px; }
.plugin-list-item { min-width: 0; min-height: 76px; display: flex; align-items: center; gap: 12px; border-bottom: 1px solid rgb(var(--v-theme-outline-variant)); cursor: pointer; }
.plugin-list-item:hover .plugin-list-copy strong { text-decoration: underline; }
.plugin-list-icon { width: 40px; height: 40px; flex: 0 0 auto; display: inline-flex; align-items: center; justify-content: center; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 10px; background: rgb(var(--v-theme-surface)); }
.plugin-list-icon .mdi { font-size: 23px; color: rgb(var(--v-theme-primary)); }
.plugin-list-copy { min-width: 0; flex: 1 1 auto; display: flex; flex-direction: column; gap: 3px; }
.plugin-list-copy strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 14px; font-weight: 550; }
.plugin-list-copy span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: rgb(var(--v-theme-secondary)); font-size: 12px; }
.plugin-list-action { flex: 0 0 auto; }
.plugin-more-button { border: 0; background: transparent; color: rgb(var(--v-theme-secondary)); font: inherit; cursor: pointer; padding: 4px 7px; letter-spacing: 2px; }
.plugin-more-button:hover { color: rgb(var(--v-theme-on-surface)); }
.plugin-status-button { border: 1px solid var(--cx-border); border-radius: 8px; padding: 5px 9px; background: transparent; color: rgb(var(--v-theme-secondary)); font: inherit; font-size: 12px; cursor: pointer; }
.plugin-status-button.enabled { color: rgb(var(--v-theme-on-surface)); background: var(--cx-hover-strong); }
.remote-search-status { display: inline-flex; align-items: center; gap: 7px; font-size: 12px; }
.remote-search-status .cx-spin { width: 13px; height: 13px; border-width: 1.5px; }
.remote-empty { min-height: 180px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; text-align: center; color: rgb(var(--v-theme-secondary)); }
.remote-empty p { max-width: 460px; margin: 0; font-size: 13px; }
.market-settings-description { margin: 0 0 16px; color: rgb(var(--v-theme-secondary)); font-size: 13px; line-height: 1.5; }
/* Local-package confirm dialog */
.package-confirm-file { display: flex; align-items: center; gap: 9px; margin-bottom: 14px; font-size: 13px; }
.package-confirm-file .mdi { font-size: 19px; color: rgb(var(--v-theme-secondary)); }
.package-confirm-file span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; direction: rtl; text-align: left; }
.package-version-step { display: inline-flex; align-items: center; gap: 5px; }
.package-version-step .mdi { font-size: 14px; color: rgb(var(--v-theme-secondary)); }
.package-permissions { margin-top: 14px; }
.package-confirm-note { margin: 12px 0 0; font-size: 12px; }
.market-source-summary { display: flex; flex-direction: column; gap: 7px; margin-top: 18px; padding-top: 14px; border-top: 1px solid rgb(var(--v-theme-outline-variant)); font-size: 12px; }
.market-source-line { display: flex; align-items: center; gap: 7px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.installed-pill-name { white-space: nowrap; }

/* Card grid */
.cx-card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 14px; }
.ext-card { display: flex; flex-direction: column; padding: 16px; cursor: pointer; }
.ext-card-head { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.ext-icon { width: 40px; height: 40px; border-radius: 11px; flex: 0 0 auto; }
.ext-icon .mdi { font-size: 21px; }
.ext-card-titlewrap { flex: 1; min-width: 0; }
.ext-card-title { font-weight: 650; font-size: 14px; display: flex; align-items: center; gap: 6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ext-card-meta { font-size: 11px; opacity: .75; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ext-card-desc { font-size: 13px; line-height: 1.45; margin: 0 0 12px;
  display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  min-height: 38px; flex: 1 1 auto;
}
.ext-card-badges { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 12px; }
.cx-chip--muted { background: rgb(var(--v-theme-surface-variant)); color: rgb(var(--v-theme-secondary)); font-size: 11px; }
.ext-card-actions { display: flex; align-items: center; gap: 8px; padding-top: 10px; border-top: 1px solid rgb(var(--v-theme-outline-variant)); }
.ext-card-actions .cx-btn { margin-left: auto; }
.ext-card-actions .cx-btn--sm { height: 30px; padding: 0 14px; font-size: 12px; }
.update-dot { color: rgb(var(--v-theme-primary)); flex: 0 0 auto; }
.danger { color: rgb(var(--v-theme-error)); }

/* Toggle switch (mirrors Skills.vue) */
.cx-switch { position: relative; display: inline-flex; cursor: pointer; flex: 0 0 auto; width: 38px; height: 22px; }
.cx-switch input { position: absolute; opacity: 0; width: 100%; height: 100%; margin: 0; cursor: pointer; }
.cx-switch__track { width: 38px; height: 22px; border-radius: 11px; background: rgb(var(--v-theme-surface-variant)); transition: background .15s ease; }
.cx-switch__thumb { position: absolute; top: 3px; left: 3px; width: 16px; height: 16px; border-radius: 50%; background: rgb(var(--v-theme-surface)); transition: transform .15s ease; box-shadow: 0 1px 2px rgba(0,0,0,.3); }
.cx-switch input:checked ~ .cx-switch__track { background: rgb(var(--v-theme-primary)); }
.cx-switch input:checked ~ .cx-switch__thumb { transform: translateX(16px); }

/* Empty state */
.market-empty { min-height: 200px; padding: 40px; display: flex; flex-direction: column; gap: 12px; align-items: center; justify-content: center; text-align: center; color: rgb(var(--v-theme-secondary)); font-size: 13px; }

/* Detail drawer */
.cx-detail-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; justify-content: flex-end; z-index: 100; }
.cx-detail-drawer { width: min(560px, 100%); height: 100%; background: rgb(var(--v-theme-surface)); padding: 22px 26px; overflow-y: auto; box-shadow: -4px 0 16px rgba(0,0,0,.25); }

/* Unified store tab */
.store-tab { display: flex; flex-direction: column; }
.store-filter-row { flex: 0 0 auto; }
.store-filter-row .v-field { min-height: 40px; }
.cx-switch-label { font-size: 12px; margin-left: 8px; }
.detail-facts { margin: 0 0 18px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.detail-facts div { display: flex; flex-direction: column; gap: 4px; }
.detail-facts dt { color: rgb(var(--v-theme-secondary)); font-size: 12px; }
.detail-facts dd { margin: 0; font-size: 13px; }
.detail-h3 { margin: 0 0 10px; font-size: 13px; }
.permission-list { display: flex; flex-wrap: wrap; gap: 7px; }
.detail-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 24px; padding-top: 16px; border-top: 1px solid rgb(var(--v-theme-outline-variant)); }
.cx-skill-body { font-size: 13px; line-height: 1.6; }
.cx-skill-body :deep(h1), .cx-skill-body :deep(h2), .cx-skill-body :deep(h3) { margin: 16px 0 8px; }
.cx-skill-body :deep(pre) { background: rgb(var(--v-theme-surface-variant)); padding: 10px; border-radius: 8px; overflow-x: auto; }
.cx-skill-body :deep(code) { font-family: ui-monospace, monospace; font-size: 12px; }

@media (max-width: 700px) {
  .market-scroll { padding-left: 12px; padding-right: 12px; }
  .market-content { padding-left: 2px; padding-right: 2px; }
  .plugin-list-grid { grid-template-columns: 1fr; }
  .market-intro h1 { font-size: 25px; }
  .plugin-list-item { min-height: 70px; }
}
</style>
