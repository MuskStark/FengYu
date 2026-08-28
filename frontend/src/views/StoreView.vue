<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/api/client'
import { useStoreStore } from '@/stores/storeStore'
import { usePluginsStore } from '@/stores/plugins'
import { useSkillsStore } from '@/stores/skills'
import type { PackageInspection, StoreCatalogEntry, StoreListingDetail } from '@/api/types'
import { confirmAction, makeDesktop } from '@/mf/desktop'

/**
 * Native Infinia Store surface (design §12.4 发现/我的库): catalog with type
 * filters and search, listing detail drawer (versions + permissions), install /
 * update / uninstall through the local /api/store orchestrator.
 */
const { t } = useI18n()
const store = useStoreStore()
const plugins = usePluginsStore()
const skills = useSkillsStore()
const desktop = makeDesktop()

const typeFilter = ref('')
const search = ref('')
const localFileInput = ref<HTMLInputElement | null>(null)
const localInstalling = ref(false)
const localError = ref<string | null>(null)
const detail = ref<StoreListingDetail | null>(null)
const detailLoading = ref(false)
const detailEntry = ref<StoreCatalogEntry | null>(null)
const notice = ref<string | null>(null)
let noticeTimer: number | undefined

const types = ['', 'PLUGIN', 'SKILL', 'MCP', 'FLOW', 'APP']

const typeLabel = (type: string) => (type ? t(`store.type.${type}`) : t('store.type.all'))

const installedEntries = computed(() => store.catalog.filter((entry) => entry.installed))

const categoryLabel = (category: string | null, type: string) => {
  if (!category) return typeLabel(type)
  const value = category.toLowerCase()
  const translated = t(`category.${value}`)
  if (translated !== `category.${value}`) return translated
  return category
    .split(/[-_\s]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1).toLowerCase())
    .join(' ')
}

const storeSections = computed(() => {
  const grouped = new Map<string, StoreCatalogEntry[]>()
  for (const entry of store.catalog) {
    const key = entry.category || entry.type
    const items = grouped.get(key) ?? []
    items.push(entry)
    grouped.set(key, items)
  }
  return [...grouped.entries()].map(([id, items]) => ({
    id,
    title: categoryLabel(items[0]?.category ?? null, items[0]?.type ?? id),
    items,
  }))
})

function showNotice(message: string) {
  notice.value = message
  if (noticeTimer) window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => (notice.value = null), 6000)
}

async function load() {
  await store.refreshAll(
    typeFilter.value || undefined,
    search.value.trim() || undefined,
  )
}

async function refreshAfterLocalInstall() {
  await Promise.all([load(), plugins.load(), skills.refresh()])
}

function localPackageLabel(path: string): string {
  return path.split(/[\\/]/).pop() || path
}

async function installLocalSkill(name: string, file?: File, path?: string) {
  const installed = file ? await skills.uploadFile(file) : await skills.uploadNative(path!)
  if (!installed) throw new Error(skills.error ?? t('store.localInstallFailed'))
  await refreshAfterLocalInstall()
  showNotice(t('store.localInstalled', { name }))
}

async function inspectLocalPlugin(file?: File, path?: string): Promise<PackageInspection | null> {
  try {
    return file ? await api.inspectPlugin(file) : await api.inspectNativePlugin(path!)
  } catch (error) {
    const status = (error as { response?: { status?: number } })?.response?.status
    if (status === 404 || status === 405) return null
    throw error
  }
}

async function installLocalPlugin(name: string, file?: File, path?: string) {
  const inspection = await inspectLocalPlugin(file, path)
  const displayName = inspection?.name || name
  const version = inspection?.version ? ` ${inspection.version}` : ''
  const prompt = [
    t(inspection?.installed ? 'store.confirmLocalUpdate' : 'store.confirmLocalInstall', {
      name: displayName,
      version,
    }),
    inspection?.permissions.length
      ? t('store.localPermissions', { permissions: inspection.permissions.join(', ') })
      : '',
  ].filter(Boolean).join('\n\n')
  if (!await confirmAction(prompt)) return

  if (file) await api.uploadPlugin(file, true)
  else await api.uploadNativePlugin(path!, true)
  await refreshAfterLocalInstall()
  showNotice(t('store.localInstalled', { name: displayName }))
}

async function handleLocalPackage(name: string, file?: File, path?: string) {
  const lower = name.toLowerCase()
  localInstalling.value = true
  localError.value = null
  try {
    if (lower.endsWith('.fys')) await installLocalSkill(name, file, path)
    else if (lower.endsWith('.fyp')) await installLocalPlugin(name, file, path)
    else throw new Error(t('market.unsupportedPackage'))
  } catch (error) {
    localError.value = error instanceof Error ? error.message : String(error)
  } finally {
    localInstalling.value = false
  }
}

async function chooseLocalPackage() {
  if (!desktop) {
    localFileInput.value?.click()
    return
  }
  const path = await desktop.pickFile([
    { name: 'FengYu Package', extensions: ['fyp', 'fys'] },
  ])
  if (path) await handleLocalPackage(localPackageLabel(path), undefined, path)
}

async function onLocalFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) await handleLocalPackage(file.name, file)
  input.value = ''
}

const updateByCoordinate = computed(() => {
  const map = new Map<string, string>()
  for (const u of store.updates) map.set(u.coordinate, u.availableVersion)
  return map
})

function typeIcon(type: string): string {
  switch (type) {
    case 'PLUGIN':
      return 'mdi-puzzle-outline'
    case 'SKILL':
      return 'mdi-school-outline'
    case 'MCP':
      return 'mdi-server-network-outline'
    case 'FLOW':
      return 'mdi-vector-polyline'
    case 'APP':
      return 'mdi-application-outline'
    default:
      return 'mdi-package-variant-closed'
  }
}

function typeAccent(type: string): string {
  switch (type) {
    case 'PLUGIN':
      return '#6d5dfc'
    case 'SKILL':
      return '#ec7a43'
    case 'MCP':
      return '#0b9a8a'
    case 'FLOW':
      return '#2d8df5'
    case 'APP':
      return '#dfaa28'
    default:
      return '#7d8793'
  }
}

function partsOf(coordinate: string): { namespace: string; slug: string } {
  const rest = coordinate.replace(/^infinia:\/\/[^/]+\//, '')
  const [namespace, slug] = rest.split('/')
  return { namespace: namespace ?? '', slug: slug ?? '' }
}

async function openDetail(entry: StoreCatalogEntry) {
  detailEntry.value = entry
  detail.value = null
  detailLoading.value = true
  try {
    const { namespace, slug } = partsOf(entry.coordinate)
    detail.value = await store.listing(namespace, slug)
  } finally {
    detailLoading.value = false
  }
}

function closeDetail() {
  detail.value = null
  detailEntry.value = null
}

async function install(entry: StoreCatalogEntry, confirmPermissions = false) {
  const updating = entry.installed
  const available = updateByCoordinate.value.get(entry.coordinate)
  if (updating && available && !confirmPermissions) {
    // Updates that escalate permissions must be confirmed explicitly.
    if (
      !(await confirmAction(
        t('store.confirmUpdate', {
          name: entry.name,
          from: entry.installedVersion ?? '',
          to: available,
        }),
      ))
    ) {
      return
    }
  }
  try {
    const result = await store.install(entry.coordinate, confirmPermissions)
    showNotice(t('store.installed', { name: result?.localId ?? entry.name }))
    if (detailEntry.value?.coordinate === entry.coordinate) {
      await openDetail(entry)
    }
  } catch {
    // store.error carries the reason; escalations surface their own retry hint.
    const message = store.error ?? ''
    if (message.includes('permission')) {
      if (await confirmAction(t('store.permissionEscalation', { error: message }))) {
        await install(entry, true)
      }
    }
  }
}

async function uninstall(entry: StoreCatalogEntry) {
  if (
    !(await confirmAction(
      t('store.confirmUninstall', { name: entry.name }),
    ))
  ) {
    return
  }
  await store.uninstall(entry.coordinate, false)
  showNotice(t('store.uninstalled', { name: entry.name }))
  closeDetail()
}

watch([typeFilter, search], () => {
  void load()
})

onMounted(() => {
  void store.loadStatus()
  void load()
})

void [detailLoading, detailEntry, typeIcon, typeLabel, typeAccent, categoryLabel, noticeTimer]
</script>

<template>
  <div class="store-view">
    <header class="store-topbar">
      <div class="store-topbar__tabs" aria-label="Store navigation">
        <span class="store-topbar__tab store-topbar__tab--active">{{ t('store.title') }}</span>
      </div>
      <div class="store-topbar__actions">
        <span v-if="store.apiBase" class="store-source" :title="store.apiBase">
          <span class="store-source__dot" />
          {{ t('store.connected') }}
        </span>
        <button class="store-local-button" :disabled="localInstalling" @click="chooseLocalPackage">
          <span v-if="localInstalling" class="cx-spin" />
          <i v-else class="mdi mdi-tray-arrow-up" />
          {{ localInstalling ? t('store.installingLocal') : t('store.installLocal') }}
        </button>
        <button class="store-icon-button" :aria-label="t('store.refresh')" :title="t('store.refresh')" @click="load">
          <i class="mdi mdi-refresh" />
        </button>
      </div>
    </header>

    <input ref="localFileInput" type="file" accept=".fyp,.fys" hidden @change="onLocalFilePicked">

    <header class="store-header">
      <h1 class="store-title">{{ t('store.title') }}</h1>
      <p class="store-subtitle">{{ t('store.subtitle') }}</p>
    </header>

    <div class="store-search" role="search">
      <i class="mdi mdi-magnify" aria-hidden="true" />
      <input v-model="search" :placeholder="t('store.searchPlaceholder')" :aria-label="t('store.searchPlaceholder')" />
      <button v-if="search" class="store-search__clear" :aria-label="t('store.clearSearch')" @click="search = ''">
        <i class="mdi mdi-close" />
      </button>
    </div>

    <div class="store-toolbar">
      <div class="store-filters" role="tablist" :aria-label="t('store.typeFilter')">
        <button
          v-for="type in types"
          :key="type"
          class="store-filter"
          :class="{ active: typeFilter === type }"
          role="tab"
          :aria-selected="typeFilter === type"
          @click="typeFilter = type"
        >
          {{ typeLabel(type) }}
        </button>
      </div>
    </div>

    <div v-if="store.error" class="cx-alert cx-alert--error" role="alert">
      {{ store.error }}
      <button class="cx-btn cx-btn--sm cx-btn--outline" @click="load()">
        {{ t('common.retry') }}
      </button>
    </div>
    <div v-else-if="notice" class="cx-alert cx-alert--success" role="status">
      {{ notice }}
    </div>
    <div v-if="localError" class="cx-alert cx-alert--error" role="alert">
      {{ localError }}
      <button class="cx-iconbtn cx-iconbtn--sm" :aria-label="t('common.close')" @click="localError = null">
        <i class="mdi mdi-close" />
      </button>
    </div>

    <div v-if="store.loading" class="store-loading">
      <span class="cx-spin" />
      {{ t('store.loading') }}
    </div>

    <div v-else-if="!store.catalog.length" class="store-empty">
      {{ t('store.empty') }}
    </div>

    <template v-else>
      <section v-if="installedEntries.length" class="store-installed-section">
        <div class="store-section-heading">
          <h2>{{ t('store.installedSection') }}</h2>
          <span>{{ installedEntries.length }}</span>
        </div>
        <div class="store-installed-row">
          <button
            v-for="entry in installedEntries"
            :key="entry.coordinate"
            class="store-installed-item"
            :title="entry.name"
            @click="openDetail(entry)"
          >
            <span class="store-icon store-icon--installed" :style="{ '--store-accent': typeAccent(entry.type) }">
              <i class="mdi" :class="typeIcon(entry.type)" />
            </span>
            <span>{{ entry.name }}</span>
          </button>
        </div>
      </section>

      <section v-for="section in storeSections" :key="section.id" class="store-catalog-section">
        <div class="store-section-heading">
          <h2>{{ section.title }}</h2>
          <span>{{ section.items.length }}</span>
        </div>
        <div class="store-list">
          <article v-for="entry in section.items" :key="entry.coordinate" class="store-list-item">
            <button class="store-list-item__main" @click="openDetail(entry)">
              <span class="store-icon" :style="{ '--store-accent': typeAccent(entry.type) }">
                <i class="mdi" :class="typeIcon(entry.type)" />
              </span>
              <span class="store-list-item__copy">
                <span class="store-list-item__name">{{ entry.name }}</span>
                <span class="store-list-item__summary">{{ entry.summary }}</span>
                <span class="store-list-item__meta">{{ entry.namespace }} · {{ typeLabel(entry.type) }}</span>
              </span>
            </button>
            <span class="store-list-item__actions">
              <span class="store-list-item__version">
                <template v-if="entry.installed && updateByCoordinate.get(entry.coordinate)">
                  {{ entry.installedVersion }} → {{ updateByCoordinate.get(entry.coordinate) }}
                </template>
                <template v-else-if="entry.installed">v{{ entry.installedVersion }}</template>
                <template v-else>v{{ entry.latestVersion }}</template>
              </span>
              <button
                v-if="entry.installed"
                class="store-row-icon-button"
                :aria-label="t('store.uninstall')"
                :title="t('store.uninstall')"
                :disabled="store.busy === entry.coordinate"
                @click="uninstall(entry)"
              >
                <i class="mdi mdi-delete-outline" />
              </button>
              <button
                class="store-install-button"
                :class="{ 'store-install-button--primary': !entry.installed }"
                :disabled="store.busy === entry.coordinate"
                @click="install(entry)"
              >
                <span v-if="store.busy === entry.coordinate" class="cx-spin" />
                {{ entry.installed ? (updateByCoordinate.get(entry.coordinate) ? t('store.update') : t('store.reinstall')) : t('store.install') }}
              </button>
            </span>
          </article>
        </div>
      </section>
    </template>

    <!-- Listing detail drawer -->
    <Teleport to="body">
      <div
        v-if="detailEntry"
        class="cx-detail-overlay"
        @click.self="closeDetail"
      />
      <aside v-if="detailEntry" class="cx-detail-drawer store-detail" role="dialog">
        <header class="store-detail__head">
          <div>
            <h2>{{ detailEntry.name }}</h2>
            <code class="store-detail__coordinate">{{ detailEntry.coordinate }}</code>
          </div>
          <button class="cx-iconbtn" :aria-label="t('common.close')" @click="closeDetail">
            <i class="mdi mdi-close" />
          </button>
        </header>

        <div v-if="detailLoading" class="store-loading"><span class="cx-spin" /></div>
        <template v-else-if="detail">
          <div class="store-detail__badge-row">
            <span class="store-icon" :style="{ '--store-accent': typeAccent(detailEntry.type) }">
              <i class="mdi" :class="typeIcon(detailEntry.type)" />
            </span>
            <span class="cx-chip">{{ typeLabel(detailEntry.type) }}</span>
            <span v-if="detailEntry.installed" class="cx-chip cx-chip--success">{{ t('store.installedChip') }}</span>
          </div>
          <p class="store-detail__summary">{{ detailEntry.summary }}</p>
          <dl class="store-detail__meta">
            <div>
              <dt>{{ t('store.publisher') }}</dt>
              <dd>{{ detail.publisherName }}</dd>
            </div>
            <div>
              <dt>{{ t('store.downloads') }}</dt>
              <dd>{{ detail.downloads }}</dd>
            </div>
          </dl>

          <h3>{{ t('store.versions') }}</h3>
          <ul class="store-detail__releases">
            <li v-for="release in detail.releases ?? []" :key="release.releaseId">
              <span class="store-detail__version">v{{ release.version }}</span>
              <span class="cx-chip">{{ release.channel }}</span>
              <span class="store-detail__date">{{ release.publishedAt }}</span>
            </li>
            <li v-if="!(detail.releases ?? []).length">{{ t('store.noReleases') }}</li>
          </ul>

          <h3>{{ t('store.permissions') }}</h3>
          <ul class="store-detail__permissions">
            <li v-for="p in detail.releases?.[0]?.permissions ?? []" :key="p.permissionId">
              <code>{{ p.permissionId }}</code>
              <span v-if="p.reason" class="store-detail__reason">{{ p.reason }}</span>
            </li>
            <li v-if="!(detail.releases?.[0]?.permissions ?? []).length">
              {{ t('store.noPermissions') }}
            </li>
          </ul>
          <footer class="store-detail__actions">
            <button v-if="detailEntry.installed" class="cx-btn cx-btn--sm cx-btn--outline" @click="uninstall(detailEntry)">
              {{ t('store.uninstall') }}
            </button>
            <button class="cx-btn cx-btn--sm cx-btn--primary" :disabled="store.busy === detailEntry.coordinate" @click="install(detailEntry)">
              {{ detailEntry.installed ? (updateByCoordinate.get(detailEntry.coordinate) ? t('store.update') : t('store.reinstall')) : t('store.install') }}
            </button>
          </footer>
        </template>
      </aside>
    </Teleport>
  </div>
</template>

<style scoped>
.store-view {
  width: 100%;
  min-height: 100%;
  padding: 0 clamp(18px, 3vw, 40px) 52px;
  overflow-y: auto;
  color: rgb(var(--v-theme-on-background));
}
.store-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 46px;
  border-bottom: 1px solid var(--cx-border-subtle);
}
.store-topbar__tabs,
.store-topbar__actions {
  display: flex;
  align-items: center;
  gap: 7px;
}
.store-topbar__tab {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  padding: 0 11px;
  border-radius: 8px;
  color: rgb(var(--v-theme-on-surface));
  font-size: 13px;
  font-weight: 600;
}
.store-topbar__tab--active {
  background: var(--cx-hover-strong);
}
.store-icon-button,
.store-row-icon-button,
.store-search__clear {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  border: 0;
  border-radius: 8px;
  color: rgb(var(--v-theme-on-surface));
  background: transparent;
  cursor: pointer;
}
.store-icon-button:hover,
.store-row-icon-button:hover,
.store-search__clear:hover {
  background: var(--cx-hover);
}
.store-icon-button .mdi,
.store-row-icon-button .mdi,
.store-search__clear .mdi {
  font-size: 18px;
}
.store-local-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px;
  border: 0;
  border-radius: 9px;
  background: rgb(var(--v-theme-on-surface));
  color: rgb(var(--v-theme-background));
  font: inherit;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
.store-local-button:hover:not(:disabled) {
  opacity: 0.82;
}
.store-local-button:disabled {
  cursor: default;
  opacity: 0.55;
}
.store-local-button .mdi {
  font-size: 16px;
}
.store-source {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: rgb(var(--v-theme-on-surface));
  font-size: 11px;
  opacity: 0.58;
}
.store-source__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #18a878;
}
.store-header {
  padding: 34px 8px 18px;
}
.store-title {
  margin: 0;
  font-size: clamp(1.65rem, 3vw, 2rem);
  font-weight: 650;
  letter-spacing: -0.025em;
}
.store-subtitle {
  margin: 6px 0 0;
  color: rgb(var(--v-theme-on-surface));
  font-size: 13px;
  opacity: 0.62;
}
.store-search {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 38px;
  padding: 0 11px;
  border: 1px solid var(--cx-border);
  border-radius: 19px;
  background: var(--cx-user-tint);
  color: rgb(var(--v-theme-on-surface));
  transition: border-color 0.15s ease, background 0.15s ease;
}
.store-search:focus-within {
  border-color: rgba(var(--v-theme-primary), 0.65);
  background: transparent;
}
.store-search > .mdi {
  font-size: 18px;
  opacity: 0.58;
}
.store-search input {
  min-width: 0;
  flex: 1;
  border: 0;
  outline: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  font-size: 13px;
}
.store-search input::placeholder {
  color: currentColor;
  opacity: 0.48;
}
.store-search__clear {
  width: 24px;
  height: 24px;
  opacity: 0.62;
}
.store-toolbar {
  display: flex;
  align-items: center;
  min-height: 52px;
  overflow-x: auto;
}
.store-filters {
  display: inline-flex;
  gap: 4px;
  padding: 3px;
  border-radius: 10px;
  background: var(--cx-user-tint);
}
.store-filter {
  padding: 6px 11px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: rgb(var(--v-theme-on-surface));
  font: inherit;
  font-size: 12px;
  white-space: nowrap;
  cursor: pointer;
  opacity: 0.62;
}
.store-filter:hover,
.store-filter.active {
  background: rgb(var(--v-theme-surface));
  color: rgb(var(--v-theme-on-surface));
  opacity: 1;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
}
.store-loading,
.store-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 180px;
  color: rgb(var(--v-theme-on-surface));
  font-size: 13px;
  opacity: 0.64;
}
.store-installed-section,
.store-catalog-section {
  margin-top: 20px;
}
.store-section-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 8px 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--cx-border-subtle);
}
.store-section-heading h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}
.store-section-heading > span {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--cx-user-tint);
  font-size: 10px;
  opacity: 0.65;
}
.store-installed-row {
  display: flex;
  gap: 14px;
  padding: 7px 8px 10px;
  overflow-x: auto;
}
.store-installed-item {
  display: inline-flex;
  flex: 0 0 auto;
  flex-direction: column;
  align-items: center;
  gap: 5px;
  min-width: 57px;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  font-size: 10px;
  white-space: nowrap;
  cursor: pointer;
}
.store-installed-item > span:last-child {
  max-width: 74px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.store-installed-item:hover .store-icon {
  transform: translateY(-2px);
}
.store-icon {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border: 1px solid color-mix(in srgb, var(--store-accent) 28%, transparent);
  border-radius: 11px;
  background: color-mix(in srgb, var(--store-accent) 12%, transparent);
  color: var(--store-accent);
  transition: transform 0.15s ease;
}
.store-icon .mdi {
  font-size: 20px;
}
.store-icon--installed {
  width: 35px;
  height: 35px;
  border-radius: 10px;
}
.store-icon--installed .mdi {
  font-size: 18px;
}
.store-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 22px;
}
.store-list-item {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
  min-height: 74px;
  padding: 9px 8px;
  border-bottom: 1px solid var(--cx-border-subtle);
}
.store-list-item:hover {
  background: var(--cx-hover);
}
.store-list-item__main {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  font: inherit;
  cursor: pointer;
}
.store-list-item__main:focus-visible,
.store-install-button:focus-visible,
.store-row-icon-button:focus-visible {
  outline: 2px solid rgb(var(--v-theme-primary));
  outline-offset: 2px;
}
.store-list-item__copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}
.store-list-item__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 600;
}
.store-list-item__summary,
.store-list-item__meta {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
}
.store-list-item__summary {
  color: rgb(var(--v-theme-on-surface));
  opacity: 0.66;
}
.store-list-item__meta,
.store-list-item__version {
  color: rgb(var(--v-theme-on-surface));
  font-size: 10px;
  opacity: 0.48;
}
.store-list-item__actions {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 4px;
}
.store-install-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 54px;
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--cx-border);
  border-radius: 8px;
  background: transparent;
  color: inherit;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.store-install-button:hover {
  background: var(--cx-hover-strong);
}
.store-install-button--primary {
  border-color: transparent;
  background: rgb(var(--v-theme-on-surface));
  color: rgb(var(--v-theme-background));
}
.store-install-button--primary:hover {
  opacity: 0.82;
}
.store-row-icon-button {
  width: 27px;
  height: 27px;
  opacity: 0.58;
}
.store-detail {
  display: flex;
  flex-direction: column;
  gap: 14px;
  overflow-y: auto;
}
.store-detail__head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
}
.store-detail__head h2 {
  margin: 0;
  font-size: 1.1rem;
}
.store-detail__coordinate {
  font-size: 0.72rem;
  opacity: 0.65;
}
.store-detail__badge-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.store-detail__summary {
  margin: 0;
  font-size: 0.85rem;
  opacity: 0.8;
}
.store-detail__meta {
  display: flex;
  gap: 24px;
  margin: 0;
}
.store-detail__meta dt {
  font-size: 0.72rem;
  opacity: 0.6;
}
.store-detail__meta dd {
  margin: 2px 0 0;
  font-size: 0.85rem;
}
.store-detail h3 {
  margin: 6px 0 6px;
  font-size: 0.8rem;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  opacity: 0.65;
}
.store-detail__releases,
.store-detail__permissions {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 0.82rem;
}
.store-detail__releases li {
  display: flex;
  align-items: center;
  gap: 8px;
}
.store-detail__version {
  font-weight: 600;
}
.store-detail__date {
  font-size: 0.75rem;
  opacity: 0.55;
}
.store-detail__permissions li {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.store-detail__permissions code {
  font-size: 0.78rem;
}
.store-detail__reason {
  font-size: 0.78rem;
  opacity: 0.65;
}
.store-detail__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: auto;
  padding-top: 8px;
}
@media (max-width: 720px) {
  .store-source {
    display: none;
  }
  .store-list {
    grid-template-columns: 1fr;
  }
  .store-list-item__version {
    display: none;
  }
}
</style>
