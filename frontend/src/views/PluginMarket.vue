<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/api/client'
import type { MarketplacePlugin } from '@/api/types'
import { usePluginsStore } from '@/stores/plugins'
import { makeDesktop } from '@/mf/desktop'

const { t } = useI18n()
const plugins = ref<MarketplacePlugin[]>([])
const selectedId = ref<string | null>(null)
const search = ref('')
const section = ref<'discover' | 'installed'>('discover')
const source = ref<'all' | 'official' | 'community'>('all')
const loading = ref(false)
const busy = ref<string | null>(null)
const error = ref<string | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const runtimePlugins = usePluginsStore()
const desktop = makeDesktop()

const visible = computed(() => {
  const needle = search.value.trim().toLocaleLowerCase()
  return plugins.value.filter((plugin) => {
    if (section.value === 'installed' && !plugin.installed) return false
    if (source.value === 'official' && !plugin.official) return false
    if (source.value === 'community' && plugin.official) return false
    return !needle || `${plugin.name} ${plugin.description} ${plugin.author ?? ''}`.toLocaleLowerCase().includes(needle)
  })
})
const selected = computed(() => plugins.value.find((plugin) => plugin.id === selectedId.value) ?? visible.value[0])

async function load() {
  loading.value = true
  error.value = null
  try {
    plugins.value = await api.getMarketplacePlugins()
    if (!selectedId.value && plugins.value.length) selectedId.value = plugins.value[0].id
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function run(id: string, action: () => Promise<void>) {
  busy.value = id
  error.value = null
  try {
    await action()
    await load()
    await runtimePlugins.load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = null
  }
}

async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  await run('upload', () => api.uploadPlugin(file))
  input.value = ''
}

async function chooseLocalPackage() {
  if (!desktop) { fileInput.value?.click(); return }
  const path = await desktop.pickFile([{ name: 'FengYu Plugin', extensions: ['fyp'] }])
  if (path) await run('upload', () => api.uploadNativePlugin(path))
}

function uninstall(plugin: MarketplacePlugin) {
  if (window.confirm(t('market.confirmUninstall'))) {
    void run(plugin.id, () => api.uninstallPlugin(plugin.id))
  }
}

onMounted(load)
</script>

<template>
  <div class="market-page">
    <header class="market-header">
      <div>
        <h1 class="cx-page-title">{{ t('market.title') }}</h1>
        <p class="cx-muted market-subtitle">{{ t('market.subtitle') }}</p>
      </div>
      <input ref="fileInput" type="file" accept=".fyp" hidden @change="upload">
      <button class="cx-btn cx-btn--outline" :disabled="busy === 'upload'" @click="chooseLocalPackage">
        <span v-if="busy === 'upload'" class="cx-spin" />
        <i v-else class="mdi mdi-tray-arrow-up" />{{ t('market.upload') }}
      </button>
    </header>

    <div v-if="error" class="cx-alert cx-alert--error market-error">
      <i class="mdi mdi-alert-circle-outline" />
      <div class="cx-alert__body"><strong>{{ t('market.operationFailed') }}</strong><br>{{ error }}</div>
      <button class="cx-iconbtn cx-iconbtn--sm" @click="error = null"><i class="mdi mdi-close" /></button>
    </div>

    <div class="market-workspace">
      <aside class="market-filter">
        <button :class="['market-filter-item', { active: section === 'discover' }]" @click="section = 'discover'">
          <i class="mdi mdi-compass-outline" />{{ t('market.discover') }}
        </button>
        <button :class="['market-filter-item', { active: section === 'installed' }]" @click="section = 'installed'">
          <i class="mdi mdi-check-circle-outline" />{{ t('market.installed') }}
          <span class="market-count">{{ plugins.filter(p => p.installed).length }}</span>
        </button>
        <div class="cx-subheader">{{ t('source.official') }}</div>
        <button :class="['market-filter-item', { active: source === 'all' }]" @click="source = 'all'">{{ t('market.all') }}</button>
        <button :class="['market-filter-item', { active: source === 'official' }]" @click="source = 'official'">{{ t('market.official') }}</button>
        <button :class="['market-filter-item', { active: source === 'community' }]" @click="source = 'community'">{{ t('market.thirdParty') }}</button>
      </aside>

      <section class="market-list">
        <div class="market-search cx-input-wrap">
          <i class="mdi mdi-magnify market-search-icon" />
          <input v-model="search" class="cx-input" :placeholder="t('market.search')">
        </div>
        <div v-if="loading" class="market-empty"><span class="cx-spin lg" /></div>
        <div v-else-if="visible.length === 0" class="market-empty">
          <i class="mdi mdi-puzzle-outline lg" />
          <span>{{ plugins.length ? t('market.empty') : t('market.catalogEmpty') }}</span>
        </div>
        <button
          v-for="plugin in visible" v-else :key="plugin.id"
          :class="['plugin-row', { active: selected?.id === plugin.id }]"
          @click="selectedId = plugin.id"
        >
          <span class="plugin-icon"><i class="mdi" :class="`mdi-${plugin.icon || 'puzzle-outline'}`" /></span>
          <span class="plugin-row-copy">
            <span class="plugin-name">{{ plugin.name }}</span>
            <span class="plugin-description">{{ plugin.description }}</span>
            <span class="plugin-meta">{{ plugin.author || plugin.id }} · v{{ plugin.version }}</span>
          </span>
          <span v-if="plugin.updateAvailable" class="cx-dot update-dot" />
          <i class="mdi mdi-chevron-right sm" />
        </button>
      </section>

      <section class="market-detail">
        <div v-if="selected" class="detail-content">
          <div class="detail-heading">
            <span class="plugin-icon plugin-icon--large"><i class="mdi" :class="`mdi-${selected.icon || 'puzzle-outline'}`" /></span>
            <div class="cx-grow">
              <div class="detail-title-row">
                <h2>{{ selected.name }}</h2>
                <span v-if="selected.official" class="cx-chip cx-chip--solid"><i class="mdi mdi-check-decagram sm" />{{ t('market.official') }}</span>
              </div>
              <div class="cx-muted">{{ selected.author || selected.id }}</div>
            </div>
          </div>

          <p class="detail-description">{{ selected.description }}</p>
          <div class="detail-actions">
            <button v-if="!selected.installed" class="cx-btn cx-btn--primary" :disabled="busy === selected.id" @click="run(selected.id, () => api.installPlugin(selected.id))">
              <span v-if="busy === selected.id" class="cx-spin" />{{ t('market.install') }}
            </button>
            <button v-if="selected.updateAvailable" class="cx-btn cx-btn--primary" :disabled="busy === selected.id" @click="run(selected.id, () => api.updatePlugin(selected.id))">
              <span v-if="busy === selected.id" class="cx-spin" />{{ t('market.update') }}
            </button>
            <button v-if="selected.installed" class="cx-btn cx-btn--outline" :disabled="busy === selected.id" @click="run(selected.id, () => api.setPluginEnabled(selected.id, !selected.enabled))">
              {{ selected.enabled ? t('market.disable') : t('market.enable') }}
            </button>
            <button v-if="selected.installed" class="cx-btn cx-btn--text danger" :disabled="busy === selected.id" @click="uninstall(selected)">{{ t('market.uninstall') }}</button>
          </div>

          <dl class="detail-facts">
            <div><dt>{{ t('market.version') }}</dt><dd>{{ selected.installedVersion || selected.version }}</dd></div>
            <div><dt>{{ t('market.author') }}</dt><dd>{{ selected.author || '—' }}</dd></div>
          </dl>
          <div class="detail-section">
            <h3>{{ t('market.permissions') }}</h3>
            <div v-if="selected.permissions.length" class="permission-list">
              <span v-for="permission in selected.permissions" :key="permission" class="cx-chip"><i class="mdi mdi-shield-key-outline sm" />{{ permission }}</span>
            </div>
            <p v-else class="cx-muted">{{ t('market.noPermissions') }}</p>
          </div>
        </div>
        <div v-else class="market-empty">{{ t('market.select') }}</div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.market-page { height: 100%; min-height: 0; display: flex; flex-direction: column; background: rgb(var(--v-theme-background)); }
.market-header { height: 82px; padding: 17px 22px 13px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid rgb(var(--v-theme-outline-variant)); }
.market-subtitle { margin: 3px 0 0; font-size: 13px; }
.market-error { margin: 12px 18px 0; }
.market-workspace { flex: 1; min-height: 0; display: grid; grid-template-columns: 172px minmax(300px, 410px) minmax(360px, 1fr); }
.market-filter { padding: 14px 9px; border-right: 1px solid rgb(var(--v-theme-outline-variant)); }
.market-filter-item { width: 100%; height: 34px; padding: 0 10px; display: flex; gap: 9px; align-items: center; border: 0; border-radius: 8px; color: rgb(var(--v-theme-secondary)); background: transparent; font: inherit; font-size: 13px; text-align: left; cursor: pointer; }
.market-filter-item:hover, .market-filter-item.active { color: rgb(var(--v-theme-on-surface)); background: rgb(var(--v-theme-surface-container-high)); }
.market-count { margin-left: auto; opacity: .65; }
.market-list { min-height: 0; display: flex; flex-direction: column; border-right: 1px solid rgb(var(--v-theme-outline-variant)); }
.market-search { margin: 14px 12px 9px; }
.market-search .cx-input { padding-left: 36px; }
.market-search-icon { position: absolute; left: 11px; z-index: 1; color: rgb(var(--v-theme-secondary)); font-size: 18px; }
.plugin-row { margin: 1px 7px; padding: 11px 9px; min-width: 0; display: flex; align-items: center; gap: 11px; border: 0; border-radius: 10px; background: transparent; color: inherit; font: inherit; text-align: left; cursor: pointer; }
.plugin-row:hover, .plugin-row.active { background: rgb(var(--v-theme-surface-container-high)); }
.plugin-icon { width: 40px; height: 40px; flex: 0 0 auto; display: inline-flex; align-items: center; justify-content: center; border-radius: 10px; color: rgb(var(--v-theme-on-primary-container)); background: rgb(var(--v-theme-primary-container)); }
.plugin-icon--large { width: 58px; height: 58px; border-radius: 14px; }.plugin-icon--large .mdi { font-size: 29px; }
.plugin-row-copy { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }.plugin-name { font-size: 13px; font-weight: 650; }.plugin-description { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: rgb(var(--v-theme-secondary)); font-size: 12px; }.plugin-meta { color: rgb(var(--v-theme-secondary)); opacity: .75; font-size: 11px; }
.update-dot { color: rgb(var(--v-theme-primary)); }.market-detail { min-width: 0; overflow-y: auto; }.detail-content { max-width: 720px; padding: 28px 30px; }.detail-heading { display: flex; gap: 16px; align-items: center; }.detail-title-row { display: flex; align-items: center; gap: 9px; }.detail-title-row h2 { margin: 0; font-size: 21px; }.detail-description { margin: 24px 0 18px; line-height: 1.65; color: rgb(var(--v-theme-secondary)); }.detail-actions { display: flex; gap: 8px; padding-bottom: 24px; border-bottom: 1px solid rgb(var(--v-theme-outline-variant)); }.danger { color: rgb(var(--v-theme-error)); }.detail-facts { margin: 22px 0; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }.detail-facts div { display: flex; flex-direction: column; gap: 5px; }.detail-facts dt { color: rgb(var(--v-theme-secondary)); font-size: 12px; }.detail-facts dd { margin: 0; font-size: 13px; }.detail-section h3 { margin: 0 0 12px; font-size: 13px; }.permission-list { display: flex; flex-wrap: wrap; gap: 7px; }.market-empty { flex: 1; min-height: 160px; padding: 30px; display: flex; flex-direction: column; gap: 10px; align-items: center; justify-content: center; text-align: center; color: rgb(var(--v-theme-secondary)); font-size: 13px; }
@media (max-width: 980px) { .market-workspace { grid-template-columns: 145px minmax(280px, 1fr); }.market-detail { display: none; } }
</style>
