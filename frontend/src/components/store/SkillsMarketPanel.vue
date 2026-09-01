<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { renderMarkdown } from '@/security/markdown'
import { useSkillsStore } from '@/stores/skills'
import { confirmAction } from '@/mf/desktop'
import type { SkillDetail } from '@/api/types'

/**
 * Skills tab of the Infinia Store: builtin + catalog skills in one grid, with the
 * install / update / toggle / uninstall lifecycle from the skills store. Local .fys
 * packages are picked through the store topbar's shared local-install button.
 */
const { t } = useI18n()
const skillStore = useSkillsStore()

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

const search = ref('')
const busy = ref<string | null>(null)
const error = ref<string | null>(null)
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

const needle = computed(() => search.value.trim().toLocaleLowerCase())
const matches = (text: string) => !needle.value || text.toLocaleLowerCase().includes(needle.value)

const skillCards = computed<SkillRow[]>(() =>
  skillRows.value.filter((s) => matches(`${s.name} ${s.description} ${s.meta} ${s.id}`)),
)
/** Top installed fast-row, mirroring the store catalog's installed strip. */
const installedRow = computed<SkillRow[]>(() => skillCards.value.filter((s) => s.installed || s.builtin))

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

/**
 * Re-point the open drawer at the refreshed row after an operation. The drawer holds a
 * snapshot object taken from the pre-operation list; without this it keeps rendering the
 * stale enabled label after an update — and dead action buttons once the subject vanished
 * (an uninstalled skill leaves no row at all).
 */
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

const installSkill = (id: string) => runSkill(id, () => skillStore.install(id))
const updateSkill = (id: string) => runSkill(id, () => skillStore.update(id))
const toggleSkill = (id: string, enabled: boolean) => runSkill(id, () => skillStore.setEnabled(id, enabled))
async function uninstallSkill(id: string) {
  if (!await confirmAction(t('skillsMarket.confirmUninstall'))) return
  void runSkill(id, async () => {
    const ok = await skillStore.uninstall(id)
    if (ok && skillDetail.value?.id === id) closeDetail()
    return ok
  })
}

function openDetail(row: SkillRow) {
  skillDetail.value = row
  void loadSkillBody(row.id)
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
  skillDetail.value = null
  skillBody.value = null
}

function md(src: string): string {
  return renderMarkdown(src)
}

async function refresh() {
  await skillStore.refresh()
}

onMounted(() => {
  void refresh()
})

defineExpose({ refresh })

void [installedRow, installSkill, updateSkill, toggleSkill, uninstallSkill, openDetail, closeDetail, md]
</script>

<template>
  <div class="skills-panel">
    <section class="panel-intro">
      <h1>{{ t('skillsMarket.title') }}</h1>
      <p>{{ t('skillsMarket.subtitle') }}</p>
    </section>

    <div class="cx-input-wrap panel-search">
      <i class="mdi mdi-magnify panel-search-icon" />
      <input v-model="search" class="cx-input" :placeholder="t('skillsMarket.search')">
    </div>

    <div v-if="error" class="cx-alert cx-alert--error panel-error">
      <i class="mdi mdi-alert-circle-outline" />
      <div class="cx-alert__body"><strong>{{ t('skillsMarket.operationFailed') }}</strong><br>{{ error }}</div>
      <button class="cx-iconbtn cx-iconbtn--sm" @click="error = null"><i class="mdi mdi-close" /></button>
    </div>

    <section v-if="installedRow.length" class="installed-row">
      <div class="section-heading">
        <h2>{{ t('skillsMarket.installed') }}</h2>
      </div>
      <div class="installed-row-pills">
        <button v-for="item in installedRow" :key="item.id" class="installed-pill" :title="item.name" :aria-label="item.name" @click="openDetail(item)">
          <i class="mdi" :class="`mdi-${item.icon}`" />
        </button>
      </div>
    </section>

    <div v-if="skillStore.loading" class="panel-empty"><span class="cx-spin lg" /></div>
    <div v-else-if="skillCards.length === 0" class="panel-empty">
      <i class="mdi lg mdi-script-text-outline" />
      <span>{{ skillRows.length ? t('skillsMarket.empty') : t('skillsMarket.catalogEmpty') }}</span>
    </div>
    <div v-else class="cx-card-grid">
      <article v-for="card in skillCards" :key="card.id" class="cx-card cx-card--hover ext-card" @click="openDetail(card)">
        <div class="ext-card-head">
          <span class="cx-avatar ext-icon"><i class="mdi" :class="`mdi-${card.icon}`" /></span>
          <div class="ext-card-titlewrap">
            <div class="ext-card-title">{{ card.name }}</div>
            <div class="cx-muted ext-card-meta">{{ card.builtin ? t('skillsMarket.builtin') : card.meta.split(' ·')[0] }}</div>
          </div>
        </div>
        <p class="cx-muted ext-card-desc">{{ card.description }}</p>
        <div class="ext-card-actions" @click.stop>
          <button v-if="!card.installed && !card.builtin" class="cx-btn cx-btn--primary cx-btn--sm" :disabled="busy === card.id" @click="installSkill(card.id)">{{ t('skillsMarket.install') }}</button>
          <template v-if="card.installed">
            <label v-if="card.updateAvailable" class="cx-btn cx-btn--outline cx-btn--sm update-link" @click="updateSkill(card.id)">{{ t('skillsMarket.update') }}</label>
            <label class="cx-switch" :title="card.enabled ? t('skillsMarket.disable') : t('skillsMarket.enable')">
              <input type="checkbox" :checked="card.enabled" @change="toggleSkill(card.id, !card.enabled)">
              <span class="cx-switch__track" /><span class="cx-switch__thumb" />
            </label>
          </template>
        </div>
      </article>
    </div>

    <!-- Skill detail drawer (body preview + lifecycle) -->
    <div v-if="skillDetail" class="cx-detail-overlay" @click.self="closeDetail">
      <div class="cx-detail-drawer">
        <div class="cx-row" style="align-items: center; margin-bottom: 16px">
          <span class="cx-grow" style="font-weight: 600; font-size: 16px">{{ skillDetail.name }}</span>
          <button class="cx-iconbtn cx-iconbtn--sm" :title="t('skillsMarket.close')" @click="closeDetail">
            <i class="mdi mdi-close" />
          </button>
        </div>

        <p class="cx-muted" style="line-height: 1.6; margin-bottom: 16px">{{ skillDetail.description }}</p>
        <dl class="detail-facts">
          <div>
            <dt>{{ t('skillsMarket.author') }}</dt>
            <dd>{{ skillDetail.builtin ? t('skillsMarket.builtin') : (skillDetail.meta.split(' ·')[0] || '—') }}</dd>
          </div>
        </dl>
        <h3 class="detail-h3">{{ t('skillsMarket.preview') }}</h3>
        <div v-if="skillBodyLoading" class="cx-muted">{{ t('common.loading') }}</div>
        <div v-else-if="skillBody" class="cx-md cx-skill-body" v-html="md(skillBody.body)" />
        <p v-else class="cx-muted">{{ t('skillsMarket.builtinReadonly') }}</p>
        <div v-if="skillDetail.installed" class="detail-actions">
          <button v-if="skillDetail.updateAvailable" class="cx-btn cx-btn--outline" @click="updateSkill(skillDetail.id)">{{ t('skillsMarket.update') }}</button>
          <button class="cx-btn cx-btn--text danger" @click="uninstallSkill(skillDetail.id)">{{ t('skillsMarket.uninstall') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.skills-panel { width: min(730px, 100%); margin: 0 auto; padding: 28px 8px 40px; }
.panel-intro { margin: 0 0 18px; }
.panel-intro h1 { margin: 0 0 7px; font-size: 28px; line-height: 1.2; font-weight: 600; letter-spacing: -.02em; }
.panel-intro p { margin: 0; color: rgb(var(--v-theme-secondary)); font-size: 15px; }
.panel-search { width: 100%; max-width: none; }
.panel-search .cx-input { padding-left: 36px; height: 34px; border-radius: 18px; background: rgb(var(--v-theme-surface)); }
.panel-search-icon { position: absolute; left: 11px; z-index: 1; color: rgb(var(--v-theme-secondary)); font-size: 18px; }
.panel-error { margin: 0 0 12px; }

.section-heading { display: flex; align-items: center; justify-content: space-between; min-height: 32px; border-bottom: 1px solid rgb(var(--v-theme-outline-variant)); }
.section-heading h2 { margin: 0; font-size: 16px; font-weight: 550; }
.installed-row { margin-bottom: 18px; }
.installed-row-pills { display: flex; gap: 10px; overflow-x: auto; padding: 10px 2px 4px; }
.installed-pill { width: 38px; height: 38px; flex: 0 0 auto; display: inline-flex; align-items: center; justify-content: center; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 10px; background: rgb(var(--v-theme-surface)); color: inherit; font: inherit; cursor: pointer; transition: background .12s, transform .12s; }
.installed-pill:hover { background: rgb(var(--v-theme-surface-container-high)); transform: translateY(-1px); }
.installed-pill .mdi { font-size: 21px; color: rgb(var(--v-theme-primary)); }

.cx-card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 14px; }
.ext-card { display: flex; flex-direction: column; padding: 16px; cursor: pointer; }
.ext-card-head { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.ext-icon { width: 40px; height: 40px; border-radius: 11px; flex: 0 0 auto; }
.ext-icon .mdi { font-size: 21px; }
.ext-card-titlewrap { flex: 1; min-width: 0; }
.ext-card-title { font-weight: 650; font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ext-card-meta { font-size: 11px; opacity: .75; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ext-card-desc { font-size: 13px; line-height: 1.45; margin: 0 0 12px;
  display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  min-height: 38px; flex: 1 1 auto;
}
.ext-card-actions { display: flex; align-items: center; gap: 8px; padding-top: 10px; border-top: 1px solid rgb(var(--v-theme-outline-variant)); }
.ext-card-actions .cx-btn { margin-left: auto; }
.ext-card-actions .cx-btn--sm { height: 30px; padding: 0 14px; font-size: 12px; }
.update-link { cursor: pointer; }
.danger { color: rgb(var(--v-theme-error)); }

/* Toggle switch */
.cx-switch { position: relative; display: inline-flex; cursor: pointer; flex: 0 0 auto; width: 38px; height: 22px; }
.cx-switch input { position: absolute; opacity: 0; width: 100%; height: 100%; margin: 0; cursor: pointer; }
.cx-switch__track { width: 38px; height: 22px; border-radius: 11px; background: rgb(var(--v-theme-surface-variant)); transition: background .15s ease; }
.cx-switch__thumb { position: absolute; top: 3px; left: 3px; width: 16px; height: 16px; border-radius: 50%; background: rgb(var(--v-theme-surface)); transition: transform .15s ease; box-shadow: 0 1px 2px rgba(0,0,0,.3); }
.cx-switch input:checked ~ .cx-switch__track { background: rgb(var(--v-theme-primary)); }
.cx-switch input:checked ~ .cx-switch__thumb { transform: translateX(16px); }

.panel-empty { min-height: 200px; padding: 40px; display: flex; flex-direction: column; gap: 12px; align-items: center; justify-content: center; text-align: center; color: rgb(var(--v-theme-secondary)); font-size: 13px; }

/* Detail drawer */
.cx-detail-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; justify-content: flex-end; z-index: 1200; }
.cx-detail-drawer { width: min(560px, 100%); height: 100%; background: rgb(var(--v-theme-surface)); padding: 22px 26px; overflow-y: auto; box-shadow: -4px 0 16px rgba(0,0,0,.25); }
.detail-facts { margin: 0 0 18px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.detail-facts div { display: flex; flex-direction: column; gap: 4px; }
.detail-facts dt { color: rgb(var(--v-theme-secondary)); font-size: 12px; }
.detail-facts dd { margin: 0; font-size: 13px; }
.detail-h3 { margin: 0 0 10px; font-size: 13px; }
.detail-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 24px; padding-top: 16px; border-top: 1px solid rgb(var(--v-theme-outline-variant)); }
.cx-skill-body { font-size: 13px; line-height: 1.6; }
.cx-skill-body :deep(h1), .cx-skill-body :deep(h2), .cx-skill-body :deep(h3) { margin: 16px 0 8px; }
.cx-skill-body :deep(pre) { background: rgb(var(--v-theme-surface-variant)); padding: 10px; border-radius: 8px; overflow-x: auto; }
.cx-skill-body :deep(code) { font-family: ui-monospace, monospace; font-size: 12px; }

@media (max-width: 700px) {
  .skills-panel { padding-left: 2px; padding-right: 2px; }
  .panel-intro h1 { font-size: 25px; }
}
</style>
