<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useStoreStore } from '@/stores/storeStore'
import type { StoreCatalogEntry, StoreListingDetail } from '@/api/types'
import { confirmAction } from '@/mf/desktop'

/**
 * Native Infinia Store surface (design §12.4 发现/我的库): catalog with type
 * filters and search, listing detail drawer (versions + permissions), install /
 * update / uninstall through the local /api/store orchestrator.
 */
const { t } = useI18n()
const store = useStoreStore()

const typeFilter = ref('')
const search = ref('')
const detail = ref<StoreListingDetail | null>(null)
const detailLoading = ref(false)
const detailEntry = ref<StoreCatalogEntry | null>(null)
const notice = ref<string | null>(null)
let noticeTimer: number | undefined

const types = ['', 'PLUGIN', 'SKILL', 'MCP', 'FLOW', 'APP']

const typeLabel = (type: string) => (type ? t(`store.type.${type}`) : t('store.type.all'))

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

void [detailLoading, detailEntry, typeIcon, typeLabel, noticeTimer]
</script>

<template>
  <div class="store-view">
    <header class="store-header">
      <div>
        <h1 class="store-title">{{ t('store.title') }}</h1>
        <p class="store-subtitle">
          {{ t('store.subtitle') }}
          <code v-if="store.apiBase" class="store-api-base">{{ store.apiBase }}</code>
        </p>
      </div>
    </header>

    <div class="store-toolbar">
      <div class="cx-segment" role="tablist" :aria-label="t('store.typeFilter')">
        <button
          v-for="type in types"
          :key="type"
          class="cx-segment__btn"
          :class="{ active: typeFilter === type }"
          role="tab"
          :aria-selected="typeFilter === type"
          @click="typeFilter = type"
        >
          {{ typeLabel(type) }}
        </button>
      </div>
      <v-text-field
        v-model="search"
        class="store-search"
        density="compact"
        variant="outlined"
        hide-details
        clearable
        :placeholder="t('store.searchPlaceholder')"
        prepend-inner-icon="mdi-magnify"
      />
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

    <div v-if="store.loading" class="store-loading">
      <span class="cx-spin" />
      {{ t('store.loading') }}
    </div>

    <div v-else-if="!store.catalog.length" class="store-empty">
      {{ t('store.empty') }}
    </div>

    <div v-else class="store-grid">
      <article
        v-for="entry in store.catalog"
        :key="entry.coordinate"
        class="cx-card cx-card--hover store-card"
      >
        <header class="store-card__head">
          <i class="mdi" :class="typeIcon(entry.type)" />
          <div class="store-card__title">
            <button class="store-card__name" @click="openDetail(entry)">
              {{ entry.name }}
            </button>
            <span class="store-card__meta">
              {{ entry.namespace }} · {{ typeLabel(entry.type) }}
            </span>
          </div>
          <span v-if="entry.installed" class="cx-chip cx-chip--success">
            {{ t('store.installedChip') }}
          </span>
        </header>
        <p class="store-card__summary">{{ entry.summary }}</p>
        <footer class="store-card__foot">
          <span class="store-card__version">
            <template v-if="entry.installed && updateByCoordinate.get(entry.coordinate)">
              {{ entry.installedVersion }} → {{ updateByCoordinate.get(entry.coordinate) }}
            </template>
            <template v-else-if="entry.installed">
              v{{ entry.installedVersion }}
            </template>
            <template v-else>v{{ entry.latestVersion }}</template>
          </span>
          <span class="store-card__actions">
            <button
              v-if="entry.installed"
              class="cx-btn cx-btn--sm cx-btn--outline"
              :disabled="store.busy === entry.coordinate"
              @click="uninstall(entry)"
            >
              <span v-if="store.busy === entry.coordinate" class="cx-spin" />
              {{ t('store.uninstall') }}
            </button>
            <button
              class="cx-btn cx-btn--sm"
              :class="{ 'cx-btn--primary': !entry.installed }"
              :disabled="store.busy === entry.coordinate"
              @click="install(entry)"
            >
              <span v-if="store.busy === entry.coordinate" class="cx-spin" />
              {{
                entry.installed
                  ? updateByCoordinate.get(entry.coordinate)
                    ? t('store.update')
                    : t('store.reinstall')
                  : t('store.install')
              }}
            </button>
          </span>
        </footer>
      </article>
    </div>

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
        </template>
      </aside>
    </Teleport>
  </div>
</template>

<style scoped>
.store-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px clamp(16px, 4vw, 40px);
  max-width: 1180px;
  margin: 0 auto;
  width: 100%;
}
.store-title {
  font-size: 1.4rem;
  font-weight: 600;
  margin: 0;
}
.store-subtitle {
  margin: 4px 0 0;
  opacity: 0.7;
  font-size: 0.85rem;
}
.store-api-base {
  margin-left: 6px;
  font-size: 0.75rem;
  opacity: 0.8;
}
.store-toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.store-search {
  max-width: 320px;
  flex: 1;
}
.store-loading,
.store-empty {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: center;
  padding: 40px 0;
  opacity: 0.7;
}
.store-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;
}
.store-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
}
.store-card__head {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}
.store-card__head > .mdi {
  font-size: 22px;
  opacity: 0.8;
}
.store-card__title {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.store-card__name {
  background: none;
  border: none;
  padding: 0;
  font: inherit;
  font-weight: 600;
  color: inherit;
  text-align: left;
  cursor: pointer;
}
.store-card__name:hover {
  text-decoration: underline;
}
.store-card__meta {
  font-size: 0.75rem;
  opacity: 0.6;
}
.store-card__summary {
  margin: 0;
  font-size: 0.85rem;
  opacity: 0.8;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.store-card__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: auto;
}
.store-card__version {
  font-size: 0.8rem;
  opacity: 0.75;
}
.store-card__actions {
  display: flex;
  gap: 6px;
  align-items: center;
}
.store-detail {
  display: flex;
  flex-direction: column;
  gap: 14px;
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
  font-size: 0.8rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  opacity: 0.65;
  margin: 6px 0 6px;
}
.store-detail__releases,
.store-detail__permissions {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 0.82rem;
}
.store-detail__releases li {
  display: flex;
  gap: 8px;
  align-items: center;
}
.store-detail__version {
  font-weight: 600;
}
.store-detail__date {
  opacity: 0.55;
  font-size: 0.75rem;
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
  opacity: 0.65;
  font-size: 0.78rem;
}
</style>
