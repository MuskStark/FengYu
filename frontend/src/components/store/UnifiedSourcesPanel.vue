<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { usePluginStore } from '@/stores/pluginStore'
import UnifiedSourceBadge from '@/components/store/UnifiedSourceBadge.vue'
import StoreSourceManager from '@/components/store/StoreSourceManager.vue'
import { confirmAction } from '@/mf/desktop'
import type { UnifiedCatalogEntry } from '@/api/types'

/**
 * Unified plugin sources tab of the Infinia Store (the /api/plugin-store compatibility
 * layer): aggregates the subscribed FengYu / Claude / Codex / Grok catalogs and installs
 * through the unified installer.
 */
const { t } = useI18n()
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

async function confirmStoreUpdate(uid: string) {
  if (!await confirmAction(t('store.sources.confirmUpdatePermissions'))) return
  await storeView.update(uid, true)
}

onMounted(() => {
  void loadStore()
})

defineExpose({ refresh: loadStore })

void [storeDetailRecord, safeHomepage, applyStoreFilter, confirmStoreUpdate]
</script>

<template>
  <div class="sources-panel">
    <StoreSourceManager />

    <div class="d-flex align-center ga-2 my-3 sources-filter-row">
      <v-select v-model="storeFilterType" :items="['FENGYU','CLAUDE','CODEX','GROK']"
                :label="t('store.sources.sourceTypeAll')" clearable density="compact"
                style="max-width: 180px" @update:model-value="applyStoreFilter" />
      <v-text-field v-model="storeSearch" density="compact" append-icon="mdi-magnify"
                    :placeholder="t('store.sources.search')"
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
            <span v-if="storeView.busy === e.uid" class="cx-spin" />{{ storeView.busy === e.uid ? t('store.sources.cloneInProgress') : t('store.sources.install') }}
          </button>
          <button
            v-else-if="e.updateAvailable"
            class="cx-btn cx-btn--outline cx-btn--sm"
            :disabled="storeView.busy === e.uid"
            @click="confirmStoreUpdate(e.uid)"
          >{{ t('store.sources.update') }}</button>
          <label v-else class="cx-switch" :title="e.enabled ? t('store.sources.disable') : t('store.sources.enable')">
            <input
              type="checkbox"
              :checked="e.enabled"
              :disabled="storeView.busy === e.uid"
              @change="storeView.setEnabled(e.uid, !e.enabled)"
            >
            <span class="cx-switch__track" /><span class="cx-switch__thumb" />
            <span class="cx-muted cx-switch-label">{{ e.enabled ? t('store.sources.disable') : t('store.sources.enable') }}</span>
          </label>
        </div>
      </article>
    </div>
    <div v-else-if="storeView.loading" class="panel-empty"><span class="cx-spin lg" /></div>
    <div v-else-if="storeView.error" class="cx-alert cx-alert--error panel-error">
      <i class="mdi mdi-alert-circle-outline" />
      <div class="cx-alert__body">{{ storeView.error }}</div>
    </div>
    <div v-else class="panel-empty">
      <i class="mdi lg mdi-store-search-outline" />
      <span>{{ t('store.sources.noSources') }}</span>
    </div>

    <!-- Store entry detail drawer -->
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
            <div class="text-caption text-medium-emphasis">{{ t('store.sources.pinnedSha') }}</div>
            <code class="text-caption">{{ storeDetail.pinnedSha }}</code>
          </div>
          <div v-if="storeDetailRecord && storeDetailRecord.declaredSkills.length" class="mb-2">
            <div class="text-caption text-medium-emphasis">{{ t('store.sources.declaredSkills') }}</div>
            <v-chip v-for="s in storeDetailRecord.declaredSkills" :key="s" size="small" class="mr-1">{{ s }}</v-chip>
          </div>
          <div v-if="storeDetailRecord && storeDetailRecord.hasMcpServers" class="mb-2">
            <div class="text-caption text-medium-emphasis">{{ t('store.sources.mcpServers') }}</div>
            <v-chip v-for="m in storeDetailRecord.mcpServerRefs" :key="m" size="small" class="mr-1">{{ m }}</v-chip>
            <v-alert type="warning" variant="tonal" density="compact" class="mt-2">
              {{ t('store.sources.mcpWarning') }}
            </v-alert>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-btn v-if="safeHomepage" :href="safeHomepage" target="_blank" rel="noopener noreferrer">{{ t('store.sources.homepage') }}</v-btn>
          <v-spacer />
          <v-btn @click="storeDetail = null">{{ t('store.sources.close') }}</v-btn>
        </v-card-actions>
      </v-card>
    </v-navigation-drawer>
  </div>
</template>

<style scoped>
.sources-panel { width: min(930px, 100%); margin: 0 auto; padding: 20px 8px 40px; }
.sources-filter-row { flex: 0 0 auto; }
.sources-filter-row .v-field { min-height: 40px; }
.panel-error { margin: 0 0 12px; }

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
.ext-card-actions { display: flex; align-items: center; gap: 8px; padding-top: 10px; border-top: 1px solid rgb(var(--v-theme-outline-variant)); }
.ext-card-actions .cx-btn { margin-left: auto; }
.ext-card-actions .cx-btn--sm { height: 30px; padding: 0 14px; font-size: 12px; }

/* Toggle switch */
.cx-switch { position: relative; display: inline-flex; cursor: pointer; flex: 0 0 auto; width: 38px; height: 22px; }
.cx-switch input { position: absolute; opacity: 0; width: 100%; height: 100%; margin: 0; cursor: pointer; }
.cx-switch__track { width: 38px; height: 22px; border-radius: 11px; background: rgb(var(--v-theme-surface-variant)); transition: background .15s ease; }
.cx-switch__thumb { position: absolute; top: 3px; left: 3px; width: 16px; height: 16px; border-radius: 50%; background: rgb(var(--v-theme-surface)); transition: transform .15s ease; box-shadow: 0 1px 2px rgba(0,0,0,.3); }
.cx-switch input:checked ~ .cx-switch__track { background: rgb(var(--v-theme-primary)); }
.cx-switch input:checked ~ .cx-switch__thumb { transform: translateX(16px); }
.cx-switch-label { font-size: 12px; margin-left: 8px; }

.panel-empty { min-height: 200px; padding: 40px; display: flex; flex-direction: column; gap: 12px; align-items: center; justify-content: center; text-align: center; color: rgb(var(--v-theme-secondary)); font-size: 13px; }
</style>
