<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/api/client'
import { renderMarkdown } from '@/security/markdown'
import type { MarketplacePlugin, SkillDetail } from '@/api/types'
import { usePluginsStore } from '@/stores/plugins'
import { useSkillsStore } from '@/stores/skills'
import { makeDesktop } from '@/mf/desktop'

const { t } = useI18n()

// ── shared UI state ──────────────────────────────────────────────
const tab = ref<'plugins' | 'skills'>('plugins')
const search = ref('')
const busy = ref<string | null>(null)
const error = ref<string | null>(null)
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
      updateAvailable: s.updateAvailable, permissions: [],
    })),
)

const cards = computed<CardItem[]>(() => (tab.value === 'plugins' ? pluginCards.value : skillCards.value))
/** Top installed fast-row for the current tab. */
const installedRow = computed<CardItem[]>(() => cards.value.filter((c) => c.installed || c.builtin))

// ── mutation wrappers ────────────────────────────────────────────

async function runPlugin(id: string, action: () => Promise<unknown>) {
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

async function runSkill(id: string, action: () => Promise<boolean>) {
  busy.value = id
  error.value = null
  try {
    const ok = await action()
    if (!ok && skillStore.error) error.value = skillStore.error
  } finally {
    busy.value = null
  }
}

const installPlugin = (id: string) => runPlugin(id, () => api.installPlugin(id))
const updatePlugin = (id: string) => runPlugin(id, () => api.updatePlugin(id))
const togglePlugin = (id: string, enabled: boolean) => runPlugin(id, () => api.setPluginEnabled(id, enabled))
function uninstallPlugin(id: string) {
  if (window.confirm(t('market.confirmUninstall'))) void runPlugin(id, () => api.uninstallPlugin(id))
}

const installSkill = (id: string) => runSkill(id, () => skillStore.install(id))
const updateSkill = (id: string) => runSkill(id, () => skillStore.update(id))
const toggleSkill = (id: string, enabled: boolean) => runSkill(id, () => skillStore.setEnabled(id, enabled))
function uninstallSkill(id: string) {
  if (window.confirm(t('skillsMarket.confirmUninstall'))) void runSkill(id, () => skillStore.uninstall(id))
}

// ── single Upload dispatching .fyp / .fys by extension ───────────

async function dispatchUpload(filename: string, installSkillPkg: () => Promise<boolean>, installPluginPkg: () => Promise<unknown>) {
  const name = filename.toLowerCase()
  busy.value = 'upload'
  error.value = null
  try {
    if (name.endsWith('.fys')) {
      const ok = await installSkillPkg()
      if (!ok) throw new Error(skillStore.error ?? 'upload failed')
    } else if (name.endsWith('.fyp')) {
      await installPluginPkg()
    } else {
      throw new Error(t('market.unsupportedPackage'))
    }
    await load()
    await skillStore.refresh()
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
  await dispatchUpload(file.name, () => skillStore.uploadFile(file), () => api.uploadPlugin(file))
  input.value = ''
}

async function chooseLocalPackage() {
  if (!desktop) { fileInput.value?.click(); return }
  const path = await desktop.pickFile([{ name: 'FengYu Package', extensions: ['fyp', 'fys'] }])
  if (!path) return
  await dispatchUpload(path, () => skillStore.uploadNative(path), () => api.uploadNativePlugin(path))
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
})
</script>

<template>
  <div class="market-page">
    <!-- header: tabs + search + upload -->
    <header class="market-header">
      <div class="cx-segment tab-segment">
        <button :class="{ active: tab === 'plugins' }" @click="tab = 'plugins'">
          <i class="mdi mdi-puzzle-outline" />{{ t('market.tabPlugins') }}
        </button>
        <button :class="{ active: tab === 'skills' }" @click="tab = 'skills'">
          <i class="mdi mdi-script-text-outline" />{{ t('market.tabSkills') }}
        </button>
      </div>
      <div class="cx-input-wrap market-search">
        <i class="mdi mdi-magnify market-search-icon" />
        <input v-model="search" class="cx-input" :placeholder="t('market.search')">
      </div>
      <input ref="fileInput" type="file" accept=".fyp,.fys" hidden @change="upload">
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

    <div class="market-scroll">
      <!-- Installed fast-row -->
      <div v-if="installedRow.length" class="installed-row">
        <span class="installed-row-label">{{ t('market.installedRow') }}</span>
        <div class="installed-row-pills">
          <button
            v-for="item in installedRow" :key="item.id"
            class="installed-pill"
            :title="item.name"
            @click="openDetail(item)"
          >
            <i class="mdi" :class="`mdi-${item.icon}`" />
            <span class="installed-pill-name">{{ item.name }}</span>
          </button>
        </div>
      </div>

      <!-- Loading / empty / grid -->
      <div v-if="loading && tab === 'plugins'" class="market-empty"><span class="cx-spin lg" /></div>
      <div v-else-if="cards.length === 0" class="market-empty">
        <i class="mdi lg" :class="tab === 'plugins' ? 'mdi-puzzle-outline' : 'mdi-script-text-outline'" />
        <span>{{ plugins.length || skillRows.length ? t('market.empty') : t('market.catalogEmpty') }}</span>
      </div>
      <div v-else class="cx-card-grid">
        <article
          v-for="card in cards" :key="`${card.kind}-${card.id}`"
          class="cx-card cx-card--hover ext-card"
          @click="openDetail(card)"
        >
          <div class="ext-card-head">
            <span class="cx-avatar ext-icon"><i class="mdi" :class="`mdi-${card.icon}`" /></span>
            <div class="ext-card-titlewrap">
              <div class="ext-card-title">
                {{ card.name }}
                <span v-if="card.updateAvailable" class="cx-dot update-dot" :title="t('market.update')" />
              </div>
              <div class="cx-muted ext-card-meta">{{ card.author }} <template v-if="card.version && card.version !== '—'">· v{{ card.version }}</template></div>
            </div>
          </div>

          <p class="cx-muted ext-card-desc">{{ card.description }}</p>

          <div class="ext-card-badges">
            <span v-if="card.official" class="cx-chip cx-chip--solid"><i class="mdi mdi-check-decagram sm" />{{ t('market.official') }}</span>
            <span v-else class="cx-chip">{{ t('source.third_party') }}</span>
            <span v-if="card.builtin" class="cx-chip cx-chip--success">{{ t('skillsMarket.builtin') }}</span>
            <span v-if="card.installed" class="cx-chip cx-chip--muted">{{ t('market.installedLabel') }}</span>
          </div>

          <!-- actions: stop propagation so the card click (detail) doesn't fire -->
          <div class="ext-card-actions" @click.stop>
            <button
              v-if="!card.installed && !card.builtin"
              class="cx-btn cx-btn--primary cx-btn--sm"
              :disabled="busy === card.id"
              @click="card.kind === 'plugin' ? installPlugin(card.id) : installSkill(card.id)"
            >
              <span v-if="busy === card.id" class="cx-spin" />{{ t('market.install') }}
            </button>
            <button
              v-if="card.updateAvailable"
              class="cx-btn cx-btn--outline cx-btn--sm"
              :disabled="busy === card.id"
              @click="card.kind === 'plugin' ? updatePlugin(card.id) : updateSkill(card.id)"
            >{{ t('market.update') }}</button>
            <template v-if="card.installed">
              <label class="cx-switch" :title="card.enabled ? t('market.disable') : t('market.enable')">
                <input
                  type="checkbox"
                  :checked="card.enabled"
                  :disabled="busy === card.id"
                  @change="card.kind === 'plugin' ? togglePlugin(card.id, !card.enabled) : toggleSkill(card.id, !card.enabled)"
                >
                <span class="cx-switch__track" /><span class="cx-switch__thumb" />
              </label>
              <button
                class="cx-iconbtn cx-iconbtn--sm danger"
                :title="t('market.uninstall')"
                :disabled="busy === card.id"
                @click="card.kind === 'plugin' ? uninstallPlugin(card.id) : uninstallSkill(card.id)"
              ><i class="mdi mdi-delete-outline" /></button>
            </template>
          </div>
        </article>
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
        </template>

        <!-- skill detail -->
        <template v-else-if="skillDetail">
          <p class="cx-muted" style="line-height: 1.6; margin-bottom: 16px">{{ skillDetail.description }}</p>
          <dl class="detail-facts">
            <div><dt>{{ t('skillsMarket.author') }}</dt><dd>{{ skillDetail.builtin ? t('skillsMarket.builtin') : (skillDetail.meta.split(' ·')[0] || '—') }}</dd></div>
          </dl>
          <h3 class="detail-h3">{{ t('skillsMarket.preview') }}</h3>
          <div v-if="skillBodyLoading" class="cx-muted">{{ t('common.loading') }}</div>
          <div v-else-if="skillBody" class="cx-md cx-skill-body" v-html="md(skillBody.body)" />
          <p v-else class="cx-muted">{{ t('skillsMarket.builtinReadonly') }}</p>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.market-page { height: 100%; min-height: 0; display: flex; flex-direction: column; background: rgb(var(--v-theme-background)); }
.market-header { padding: 14px 20px; display: flex; align-items: center; gap: 12px; border-bottom: 1px solid rgb(var(--v-theme-outline-variant)); flex: 0 0 auto; }
.tab-segment { flex: 0 0 auto; }
.tab-segment button { display: inline-flex; align-items: center; gap: 6px; }
.market-search { flex: 1; max-width: 420px; }
.market-search .cx-input { padding-left: 36px; }
.market-search-icon { position: absolute; left: 11px; z-index: 1; color: rgb(var(--v-theme-secondary)); font-size: 18px; }
.market-error { margin: 12px 20px 0; flex: 0 0 auto; }

.market-scroll { flex: 1; min-height: 0; overflow-y: auto; padding: 16px 20px 24px; }

/* Installed fast-row */
.installed-row { margin-bottom: 18px; }
.installed-row-label { display: block; font-size: 12px; color: rgb(var(--v-theme-secondary)); margin-bottom: 8px; text-transform: uppercase; letter-spacing: .04em; }
.installed-row-pills { display: flex; gap: 8px; overflow-x: auto; padding-bottom: 4px; }
.installed-pill { flex: 0 0 auto; display: inline-flex; align-items: center; gap: 7px; padding: 6px 12px 6px 8px; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 999px; background: rgb(var(--v-theme-surface)); color: inherit; font: inherit; font-size: 13px; cursor: pointer; transition: background .12s; }
.installed-pill:hover { background: rgb(var(--v-theme-surface-container-high)); }
.installed-pill .mdi { font-size: 18px; color: rgb(var(--v-theme-primary)); }
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
.detail-facts { margin: 0 0 18px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.detail-facts div { display: flex; flex-direction: column; gap: 4px; }
.detail-facts dt { color: rgb(var(--v-theme-secondary)); font-size: 12px; }
.detail-facts dd { margin: 0; font-size: 13px; }
.detail-h3 { margin: 0 0 10px; font-size: 13px; }
.permission-list { display: flex; flex-wrap: wrap; gap: 7px; }
.cx-skill-body { font-size: 13px; line-height: 1.6; }
.cx-skill-body :deep(h1), .cx-skill-body :deep(h2), .cx-skill-body :deep(h3) { margin: 16px 0 8px; }
.cx-skill-body :deep(pre) { background: rgb(var(--v-theme-surface-variant)); padding: 10px; border-radius: 8px; overflow-x: auto; }
.cx-skill-body :deep(code) { font-family: ui-monospace, monospace; font-size: 12px; }
</style>
