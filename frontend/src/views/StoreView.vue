<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/api/client'
import { useStoreStore } from '@/stores/storeStore'
import { usePluginsStore } from '@/stores/plugins'
import { useSkillsStore } from '@/stores/skills'
import SkillsMarketPanel from '@/components/store/SkillsMarketPanel.vue'
import UnifiedSourcesPanel from '@/components/store/UnifiedSourcesPanel.vue'
import type { PackageInspection, StoreCatalogEntry, StoreListingDetail } from '@/api/types'
import { confirmAction, makeDesktop } from '@/mf/desktop'
import { renderMarkdown } from '@/security/markdown'

/**
 * Native Infinia Store surface (design §12.4 发现/我的库): catalog with type
 * filters and search, listing detail drawer (versions + permissions), install /
 * update / uninstall through the local /api/store orchestrator. The topbar also
 * hosts the peer tabs — the skill market and the unified plugin sources (the
 * /api/plugin-store compatibility layer) — that previously lived on the removed
 * plugin-market page.
 */
const { t, locale } = useI18n()
const store = useStoreStore()
const plugins = usePluginsStore()
const skills = useSkillsStore()
const desktop = makeDesktop()

const tab = ref<'store' | 'skills' | 'sources'>('store')
const skillsPanel = ref<InstanceType<typeof SkillsMarketPanel> | null>(null)
const sourcesPanel = ref<InstanceType<typeof UnifiedSourcesPanel> | null>(null)

const typeFilter = ref('')
const search = ref('')
const localFileInput = ref<HTMLInputElement | null>(null)
const localInstalling = ref(false)
const localError = ref<string | null>(null)
const detail = ref<StoreListingDetail | null>(null)
const detailLoading = ref(false)
const detailEntry = ref<StoreCatalogEntry | null>(null)
const detailError = ref<string | null>(null)
const selectedReleaseId = ref<string | null>(null)
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

/** Topbar refresh: reload whichever peer tab is active. */
function refreshActive() {
  if (tab.value === 'skills') void skillsPanel.value?.refresh()
  else if (tab.value === 'sources') void sourcesPanel.value?.refresh()
  else void load()
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
    else throw new Error(t('store.unsupportedPackage'))
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

const selectedRelease = computed(() => {
  const releases = detail.value?.releases ?? []
  return releases.find((release) => release.releaseId === selectedReleaseId.value) ?? releases[0] ?? null
})

function formatDate(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium' }).format(date)
}

function formatBytes(value: number | null | undefined): string {
  if (!value || value < 0) return '—'
  if (value < 1024) return `${value} B`
  const units = ['KB', 'MB', 'GB']
  let size = value / 1024
  let unit = 0
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024
    unit += 1
  }
  return `${size.toFixed(size >= 10 ? 0 : 1)} ${units[unit]}`
}

function releaseStatusClass(status: string): string {
  return status.toLowerCase().replace(/[^a-z0-9]+/g, '-')
}

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
  detailError.value = null
  selectedReleaseId.value = null
  detailLoading.value = true
  try {
    const { namespace, slug } = partsOf(entry.coordinate)
    detail.value = await store.listing(namespace, slug)
    if (!detail.value) detailError.value = store.error ?? t('store.detailLoadFailed')
  } catch (error) {
    detailError.value = error instanceof Error ? error.message : String(error)
  } finally {
    detailLoading.value = false
  }
}

function closeDetail() {
  detail.value = null
  detailEntry.value = null
  detailError.value = null
  selectedReleaseId.value = null
}

function retryDetail() {
  if (detailEntry.value) void openDetail(detailEntry.value)
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

void [detailLoading, detailEntry, detailError, selectedReleaseId, selectedRelease, typeIcon, typeLabel, typeAccent, categoryLabel, formatDate, formatBytes, releaseStatusClass, renderMarkdown, noticeTimer]
</script>

<template>
  <div class="store-view">
    <header class="store-topbar">
      <div class="store-topbar__tabs" aria-label="Store navigation">
        <button
          class="store-topbar__tab"
          :class="{ 'store-topbar__tab--active': tab === 'store' }"
          @click="tab = 'store'"
        >
          {{ t('store.title') }}
        </button>
        <button
          class="store-topbar__tab"
          :class="{ 'store-topbar__tab--active': tab === 'skills' }"
          @click="tab = 'skills'"
        >
          {{ t('store.skillsTab') }}
        </button>
        <button
          class="store-topbar__tab"
          :class="{ 'store-topbar__tab--active': tab === 'sources' }"
          @click="tab = 'sources'"
        >
          {{ t('store.sources.tab') }}
        </button>
      </div>
      <div class="store-topbar__actions">
        <span v-if="tab === 'store' && store.apiBase" class="store-source" :title="store.apiBase">
          <span class="store-source__dot" />
          {{ t('store.connected') }}
        </span>
        <button class="store-local-button" :disabled="localInstalling" @click="chooseLocalPackage">
          <span v-if="localInstalling" class="cx-spin" />
          <i v-else class="mdi mdi-tray-arrow-up" />
          {{ localInstalling ? t('store.installingLocal') : t('store.installLocal') }}
        </button>
        <button class="store-icon-button" :aria-label="t('store.refresh')" :title="t('store.refresh')" @click="refreshActive">
          <i class="mdi mdi-refresh" />
        </button>
      </div>
    </header>

    <input ref="localFileInput" type="file" accept=".fyp,.fys" hidden @change="onLocalFilePicked">

    <div v-if="notice" class="cx-alert cx-alert--success" role="status">
      {{ notice }}
    </div>
    <div v-if="localError" class="cx-alert cx-alert--error" role="alert">
      {{ localError }}
      <button class="cx-iconbtn cx-iconbtn--sm" :aria-label="t('common.close')" @click="localError = null">
        <i class="mdi mdi-close" />
      </button>
    </div>

    <SkillsMarketPanel v-if="tab === 'skills'" ref="skillsPanel" />
    <UnifiedSourcesPanel v-else-if="tab === 'sources'" ref="sourcesPanel" />

    <template v-else>
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
      <Transition name="store-detail-fade">
        <div
          v-if="detailEntry"
          class="cx-detail-overlay"
          @click.self="closeDetail"
        />
      </Transition>
      <Transition name="store-detail-slide">
        <aside
          v-if="detailEntry"
          class="cx-detail-drawer store-detail"
          role="dialog"
          aria-modal="true"
          :aria-label="detailEntry.name"
        >
        <header class="store-detail__head">
          <div>
            <h2>{{ detailEntry.name }}</h2>
            <code class="store-detail__coordinate">{{ detailEntry.coordinate }}</code>
          </div>
          <button class="cx-iconbtn" :aria-label="t('common.close')" @click="closeDetail">
            <i class="mdi mdi-close" />
          </button>
        </header>

        <div v-if="detailLoading" class="store-loading"><span class="cx-spin" />{{ t('store.loadingDetail') }}</div>
        <div v-else-if="detailError" class="cx-detail-error" role="alert">
          <i class="mdi mdi-alert-circle-outline" />
          <span>{{ detailError }}</span>
          <button class="cx-btn cx-btn--sm cx-btn--outline" @click="retryDetail">{{ t('common.retry') }}</button>
        </div>
        <template v-else-if="detail">
          <div class="store-detail__badge-row">
            <span class="store-icon" :style="{ '--store-accent': typeAccent(detailEntry.type) }">
              <i class="mdi" :class="typeIcon(detailEntry.type)" />
            </span>
            <span class="cx-chip">{{ typeLabel(detailEntry.type) }}</span>
            <span class="cx-chip" :class="`store-status--${releaseStatusClass(detail.status)}`">{{ detail.status }}</span>
            <span v-if="detailEntry.installed" class="cx-chip cx-chip--success">{{ t('store.installedChip') }}</span>
          </div>
          <div v-if="detail.descriptionMarkdown" class="store-detail__description" v-html="renderMarkdown(detail.descriptionMarkdown)" />
          <p v-else class="store-detail__summary">{{ detailEntry.summary }}</p>
          <dl class="store-detail__meta">
            <div>
              <dt>{{ t('store.publisher') }}</dt>
              <dd>{{ detail.publisherName || detailEntry.item?.publisherName || '—' }}</dd>
            </div>
            <div>
              <dt>{{ t('store.category') }}</dt>
              <dd>{{ categoryLabel(detail.category || detailEntry.category, detailEntry.type) }}</dd>
            </div>
            <div>
              <dt>{{ t('store.downloads') }}</dt>
              <dd>{{ detail.downloads.toLocaleString(locale) }}</dd>
            </div>
            <div>
              <dt>{{ t('store.defaultChannel') }}</dt>
              <dd>{{ detail.defaultChannel || '—' }}</dd>
            </div>
          </dl>
          <div v-if="detail.tags?.length" class="store-detail__tags">
            <span v-for="tag in detail.tags" :key="tag" class="cx-chip">{{ tag }}</span>
          </div>

          <h3>{{ t('store.versions') }}</h3>
          <div v-if="detail.releases?.length" class="store-detail__releases" role="listbox" :aria-label="t('store.versions')">
            <button
              v-for="release in detail.releases"
              :key="release.releaseId"
              class="store-release"
              :class="{ 'store-release--selected': selectedRelease?.releaseId === release.releaseId }"
              role="option"
              :aria-selected="selectedRelease?.releaseId === release.releaseId"
              @click="selectedReleaseId = release.releaseId"
            >
              <span class="store-detail__version">v{{ release.version }}</span>
              <span class="cx-chip">{{ release.channel }}</span>
              <span class="store-release__status">{{ release.status }}</span>
              <span class="store-detail__date">{{ formatDate(release.publishedAt) }}</span>
              <i class="mdi mdi-chevron-right" aria-hidden="true" />
            </button>
          </div>
          <p v-else class="store-detail__muted">{{ t('store.noReleases') }}</p>

          <template v-if="selectedRelease">
            <div class="store-detail__release-meta">
              <div v-if="selectedRelease.requiresHost">
                <span>{{ t('store.requiresHost') }}</span>
                <code>{{ selectedRelease.requiresHost }}</code>
              </div>
              <div>
                <span>{{ t('store.releaseStatus') }}</span>
                <span>{{ selectedRelease.status }}</span>
              </div>
            </div>

            <template v-if="selectedRelease.changelogMarkdown">
              <h3>{{ t('store.changelog') }}</h3>
              <div class="store-detail__changelog" v-html="renderMarkdown(selectedRelease.changelogMarkdown)" />
            </template>

            <h3>{{ t('store.permissions') }}</h3>
            <ul class="store-detail__permissions">
              <li v-for="p in selectedRelease.permissions ?? []" :key="p.permissionId">
                <div class="store-detail__permission-head">
                  <code>{{ p.permissionId }}</code>
                  <span class="cx-chip" :class="p.required ? 'cx-chip--warning' : ''">{{ p.required ? t('store.required') : t('store.optional') }}</span>
                </div>
                <span v-if="p.scope" class="store-detail__scope">{{ p.scope }}</span>
                <span v-if="p.reason" class="store-detail__reason">{{ p.reason }}</span>
              </li>
              <li v-if="!(selectedRelease.permissions ?? []).length" class="store-detail__muted">
                {{ t('store.noPermissions') }}
              </li>
            </ul>

            <h3>{{ t('store.dependencies') }}</h3>
            <ul v-if="selectedRelease.dependencies?.length" class="store-detail__dependencies">
              <li v-for="dependency in selectedRelease.dependencies" :key="dependency.coordinate">
                <code>{{ dependency.coordinate }}</code>
                <span v-if="dependency.range" class="cx-chip">{{ dependency.range }}</span>
                <span v-if="dependency.optional" class="store-detail__date">{{ t('store.optional') }}</span>
              </li>
            </ul>
            <p v-else class="store-detail__muted">{{ t('store.noDependencies') }}</p>

            <h3>{{ t('store.artifacts') }}</h3>
            <ul v-if="selectedRelease.artifacts?.length" class="store-detail__artifacts">
              <li v-for="artifact in selectedRelease.artifacts" :key="artifact.artifactId">
                <div class="store-detail__artifact-head">
                  <strong>{{ artifact.filename || artifact.kind }}</strong>
                  <span class="store-detail__date">{{ formatBytes(artifact.size) }}</span>
                </div>
                <span class="store-detail__date">{{ [artifact.platform, artifact.arch, artifact.kind].filter(Boolean).join(' · ') }}</span>
                <code v-if="artifact.sha256" class="store-detail__hash">SHA-256 {{ artifact.sha256 }}</code>
              </li>
            </ul>
            <p v-else class="store-detail__muted">{{ t('store.noArtifacts') }}</p>
          </template>
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
      </Transition>
    </Teleport>
    </template>
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
  min-height: var(--cx-window-bar-height);
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
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: rgb(var(--v-theme-on-surface));
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
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
.cx-detail-overlay {
  position: fixed;
  inset: 0;
  z-index: 1200;
  background: rgba(0, 0, 0, 0.45);
}
.cx-detail-drawer {
  position: fixed;
  inset: 0 0 0 auto;
  z-index: 1201;
  width: min(560px, 100vw);
  height: 100dvh;
  padding: 22px 26px;
  border-left: 1px solid var(--cx-border-subtle);
  background: rgb(var(--v-theme-surface));
  box-shadow: -4px 0 20px rgba(0, 0, 0, 0.2);
}
.store-detail-fade-enter-active,
.store-detail-fade-leave-active {
  transition: opacity 180ms ease;
}
.store-detail-fade-enter-from,
.store-detail-fade-leave-to {
  opacity: 0;
}
.store-detail-slide-enter-active {
  transition: transform 240ms cubic-bezier(0.22, 1, 0.36, 1), opacity 180ms ease;
}
.store-detail-slide-leave-active {
  transition: transform 200ms cubic-bezier(0.4, 0, 1, 1), opacity 160ms ease;
}
.store-detail-slide-enter-from,
.store-detail-slide-leave-to {
  transform: translateX(100%);
  opacity: 0.72;
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
.store-detail__description {
  margin: 0;
  font-size: 0.85rem;
  line-height: 1.55;
}
.store-detail__description :deep(p) {
  margin: 0 0 8px;
}
.store-detail__description :deep(p:last-child) {
  margin-bottom: 0;
}
.store-detail__description :deep(a),
.store-detail__changelog :deep(a) {
  color: rgb(var(--v-theme-primary));
}
.store-detail__description :deep(code),
.store-detail__changelog :deep(code) {
  padding: 1px 4px;
  border-radius: 4px;
  background: var(--cx-hover-strong);
  font-size: 0.78rem;
}
.store-detail__meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 20px;
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
.store-detail__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
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
.store-release {
  display: grid;
  grid-template-columns: auto auto 1fr auto auto;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 9px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.store-release:hover {
  background: var(--cx-hover);
}
.store-release--selected {
  border-color: var(--cx-border);
  background: var(--cx-hover-strong);
}
.store-release:focus-visible,
.store-detail__actions button:focus-visible {
  outline: 2px solid rgb(var(--v-theme-primary));
  outline-offset: 2px;
}
.store-release__status {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.75rem;
  opacity: 0.65;
}
.store-release .mdi {
  font-size: 16px;
  opacity: 0.55;
}
.store-detail__release-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.store-detail__release-meta > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--cx-hover);
  font-size: 0.78rem;
}
.store-detail__release-meta > div > span:first-child {
  font-size: 0.68rem;
  opacity: 0.6;
}
.store-detail__release-meta code {
  overflow-wrap: anywhere;
}
.store-detail__changelog {
  font-size: 0.82rem;
  line-height: 1.5;
}
.store-detail__changelog :deep(p) {
  margin: 0 0 7px;
}
.store-detail__changelog :deep(ul),
.store-detail__changelog :deep(ol) {
  margin: 5px 0;
  padding-left: 20px;
}
.store-detail__changelog :deep(pre) {
  max-width: 100%;
  overflow-x: auto;
  padding: 8px;
  border-radius: 7px;
  background: var(--cx-hover-strong);
}
.store-detail__permissions li {
  padding: 7px 9px;
  border-radius: 8px;
  background: var(--cx-hover);
}
.store-detail__permission-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.store-detail__scope,
.store-detail__muted {
  font-size: 0.76rem;
  opacity: 0.62;
}
.store-detail__dependencies,
.store-detail__artifacts {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 0.8rem;
}
.store-detail__dependencies li,
.store-detail__artifacts li {
  min-width: 0;
  padding: 7px 9px;
  border-radius: 8px;
  background: var(--cx-hover);
}
.store-detail__dependencies li {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
}
.store-detail__dependencies code {
  overflow-wrap: anywhere;
}
.store-detail__artifact-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}
.store-detail__artifact-head strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
}
.store-detail__hash {
  display: block;
  margin-top: 5px;
  overflow-wrap: anywhere;
  font-size: 0.7rem;
  opacity: 0.72;
}
.store-detail__muted {
  margin: 0;
}
.cx-detail-error {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 12px;
  border: 1px solid color-mix(in srgb, #d64b4b 30%, transparent);
  border-radius: 9px;
  color: #d64b4b;
  font-size: 0.82rem;
}
.cx-detail-error span {
  flex: 1 1 160px;
}
.cx-detail-error .cx-btn {
  color: inherit;
  border-color: currentColor;
}
.store-status--published,
.store-status--active {
  color: #16885e;
}
.store-status--deprecated,
.store-status--withdrawn {
  color: #b66b1c;
}
.store-status--blocked,
.store-status--rejected {
  color: #c44747;
}
.cx-chip--warning {
  color: #9a651c;
  background: color-mix(in srgb, #d99a2b 14%, transparent);
}
.store-detail__permissions li {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.store-detail__version {
  font-weight: 600;
}
.store-detail__date {
  font-size: 0.75rem;
  opacity: 0.55;
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
  .store-detail__meta {
    grid-template-columns: 1fr 1fr;
  }
  .store-release {
    grid-template-columns: auto auto 1fr auto;
  }
  .store-release__status {
    display: none;
  }
}
@media (prefers-reduced-motion: reduce) {
  .store-detail-fade-enter-active,
  .store-detail-fade-leave-active,
  .store-detail-slide-enter-active,
  .store-detail-slide-leave-active {
    transition: none;
  }
}
</style>
