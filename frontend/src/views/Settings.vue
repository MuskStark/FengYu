<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useSettingsStore } from '@/stores/settings'
import { api } from '@/api/client'
import type {
  AiConfigTestRequest,
  AiConfigTestResult,
  AiMode,
  LanguageName,
  LogLevel,
  McpStatus,
  PluginDbProvisionResult,
  ProcessIsolationStatus,
  ThemeName,
} from '@/api/types'

const { t } = useI18n()
const settings = useSettingsStore()
const mcpStatus = ref<McpStatus | null>(null)
const isolationStatus = ref<ProcessIsolationStatus | null>(null)
const showUnsandboxedConfirm = ref(false)
const showDbProvisionConfirm = ref(false)
const dbProvisionTargetId = ref<string | null>(null)
const dbPlugins = ref<Array<PluginDbProvisionResult & { name: string }>>([])
const dbProvisioning = ref<string | null>(null)
const dbError = ref<string | null>(null)

onMounted(() => {
  if (!settings.loaded) void settings.load().catch(() => {})
  if (!settings.aiLoaded) void settings.loadAi().catch(() => {})
  void api.mcpStatus().then((value) => { mcpStatus.value = value }).catch(() => {})
  void api.processIsolationStatus()
    .then((value) => { isolationStatus.value = value })
    .catch(() => {})
  void loadDbPlugins()
})

const themeItems: { title: string; value: ThemeName }[] = [
  { title: t('settings.dark'), value: 'dark' },
  { title: t('settings.light'), value: 'light' },
]
const languageItems: { title: string; value: LanguageName }[] = [
  { title: t('settings.english'), value: 'en' },
  { title: t('settings.chinese'), value: 'zh' },
]
const logLevelItems: LogLevel[] = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'OFF']

const aiForm = ref({
  mode: 'local' as AiMode,
  openai: { endpoint: '', apiKey: '', model: '' },
  anthropic: { endpoint: '', apiKey: '', model: '' },
  deepseek: { endpoint: '', apiKey: '', model: '' },
  ollama: { baseUrl: '', model: '' },
  temperature: 0.7,
  topP: 0.9,
  maxTokens: 2048,
  maxToolRounds: 50,
  contextWindowTokens: 32768,
  systemPrompt: '',
})
const showKey = ref<Record<string, boolean>>({})
const testing = ref(false)
const testResult = ref<AiConfigTestResult | null>(null)
const saved = ref(false)

// ── Update channel proxy ───────────────────────────────────────────────
const proxyUrl = ref('')
const proxySaved = ref(false)
const proxyError = ref<string | null>(null)

watch(() => settings.updateApiBase, (v) => { proxyUrl.value = v ?? '' }, { immediate: true })

// Left-nav section switching (in-page, no new routes).
type SectionId = 'providers' | 'generate' | 'appearance' | 'runtime' | 'mcp' | 'database' | 'update'
const activeSection = ref<SectionId>('providers')
type NavGroup = 'ai' | 'personalize' | 'system'
const groupLabel = (g: NavGroup) =>
  g === 'ai' ? t('settings.groupAI') : g === 'personalize' ? t('settings.groupPersonalize') : t('settings.groupSystem')
const sections = computed(() => [
  { id: 'providers' as const, icon: 'mdi-robot-outline', label: t('aiSettings.providers'), group: 'ai' as NavGroup },
  { id: 'generate' as const, icon: 'mdi-tune-vertical', label: t('aiSettings.generate'), group: 'ai' as NavGroup },
  { id: 'appearance' as const, icon: 'mdi-palette-outline', label: t('settings.theme'), group: 'personalize' as NavGroup },
  { id: 'runtime' as const, icon: 'mdi-shield-lock-outline', label: t('settings.runtimeSecurity'), group: 'system' as NavGroup },
  { id: 'mcp' as const, icon: 'mdi-connection', label: 'MCP', group: 'system' as NavGroup },
  { id: 'database' as const, icon: 'mdi-database-lock-outline', label: t('settings.pluginDbSection'), group: 'system' as NavGroup },
  { id: 'update' as const, icon: 'mdi-update', label: t('settings.updateChannelSection'), group: 'system' as NavGroup },
])
type NavRow =
  | { type: 'group'; label: string }
  | { type: 'item'; id: SectionId; icon: string; label: string }
const navRows = computed<NavRow[]>(() => {
  const rows: NavRow[] = []
  let last = ''
  for (const s of sections.value) {
    if (s.group !== last) {
      rows.push({ type: 'group', label: groupLabel(s.group) })
      last = s.group
    }
    rows.push({ type: 'item', id: s.id, icon: s.icon, label: s.label })
  }
  return rows
})

// ── Provider master-detail ─────────────────────────────────────────────
type ProviderId = 'openai' | 'anthropic' | 'deepseek'
const providerList: { id: ProviderId; label: string; initial: string; color: string; domain: string }[] = [
  { id: 'openai', label: t('aiSettings.modeOpenai'), initial: 'O', color: '#10a37f', domain: 'api.openai.com' },
  { id: 'anthropic', label: t('aiSettings.modeAnthropic'), initial: 'A', color: '#c96342', domain: 'api.anthropic.com' },
  { id: 'deepseek', label: t('aiSettings.modeDeepseek'), initial: 'D', color: '#4d6bfe', domain: 'api.deepseek.com' },
]
const selectedProvider = ref<ProviderId>(
  ['openai', 'anthropic', 'deepseek'].includes(settings.aiSettings?.mode ?? '')
    ? (settings.aiSettings!.mode as ProviderId)
    : 'openai',
)
// selectedProvider is initialized before the AI config loads (mode unknown → openai).
// Once the config arrives, re-point the selection to the real active provider (once),
// so the list highlight, the green "active" dot, and the detail pane all agree on load.
let providersInitialized = false
const providerQuery = ref('')
const filteredProviders = computed(() => {
  const q = providerQuery.value.trim().toLowerCase()
  if (!q) return providerList
  return providerList.filter((p) =>
    p.label.toLowerCase().includes(q) || p.id.includes(q) || p.domain.includes(q),
  )
})
const selectedProviderMeta = computed(() => providerList.find((p) => p.id === selectedProvider.value)!)
const providerActive = computed(() => aiForm.value.mode === selectedProvider.value)
// OpenAI-compatible providers expect /v1 in the base URL; Anthropic does not.
const selectedNeedsV1 = computed(() => selectedProvider.value === 'openai' || selectedProvider.value === 'deepseek')
function activateProvider(id: ProviderId) {
  aiForm.value.mode = id
}
async function copyText(text: string) {
  try { await navigator.clipboard.writeText(text) } catch { /* clipboard unavailable */ }
}

// ── Models card ────────────────────────────────────────────────────────
// The backend stores a single `model` per provider; the card presents it as a
// one-row list with capability filtering, mirroring the multi-model mockup.
type ModelCap = 'text' | 'vision' | 'reasoning'
const modelFilters = [
  { id: 'all' as const, label: t('aiSettings.filterAll') },
  { id: 'text' as const, label: t('aiSettings.filterText') },
  { id: 'vision' as const, label: t('aiSettings.filterVision') },
  { id: 'reasoning' as const, label: t('aiSettings.filterReasoning') },
]
const modelFilter = ref<'all' | ModelCap>('all')
const currentModelCap = ref<ModelCap>('text')
const modelRows = computed(() => {
  const name = aiForm.value[selectedProvider.value].model.trim()
  return name ? [{ name, cap: currentModelCap.value }] : []
})
const filteredModelRows = computed(() =>
  modelFilter.value === 'all'
    ? modelRows.value
    : modelRows.value.filter((m) => m.cap === modelFilter.value),
)
const addingModel = ref(false)
const newModelName = ref('')
const newModelCap = ref<ModelCap>('text')
function commitModel() {
  const n = newModelName.value.trim()
  if (n) {
    aiForm.value[selectedProvider.value].model = n
    currentModelCap.value = newModelCap.value
  }
  newModelName.value = ''
  addingModel.value = false
}
function removeModel() {
  aiForm.value[selectedProvider.value].model = ''
}
function capLabel(c: ModelCap) {
  return c === 'text' ? t('aiSettings.capText')
    : c === 'vision' ? t('aiSettings.capVision')
      : t('aiSettings.capReasoning')
}
function capChipClass(c: ModelCap) {
  return c === 'text' ? 'cx-chip--success' : ''
}

function syncFormFromStore() {
  const s = settings.aiSettings
  if (!s) return
  aiForm.value.mode = s.mode
  aiForm.value.openai = { endpoint: s.openai.endpoint, apiKey: s.openai.apiKey, model: s.openai.model }
  aiForm.value.anthropic = { endpoint: s.anthropic.endpoint, apiKey: s.anthropic.apiKey, model: s.anthropic.model }
  aiForm.value.deepseek = { endpoint: s.deepseek.endpoint, apiKey: s.deepseek.apiKey, model: s.deepseek.model }
  aiForm.value.ollama = { baseUrl: s.ollama.baseUrl, model: s.ollama.model }
  aiForm.value.temperature = s.temperature
  aiForm.value.topP = s.topP
  aiForm.value.maxTokens = s.maxTokens
  aiForm.value.maxToolRounds = s.maxToolRounds
  aiForm.value.contextWindowTokens = s.contextWindowTokens
  aiForm.value.systemPrompt = s.systemPrompt
}

function applyLoadedSettings() {
  const s = settings.aiSettings
  if (!s) return
  syncFormFromStore()
  if (!providersInitialized) {
    providersInitialized = true
    if (s.mode === 'openai' || s.mode === 'anthropic' || s.mode === 'deepseek') {
      selectedProvider.value = s.mode
    }
  }
}
watch(() => settings.aiSettings, () => applyLoadedSettings())
if (settings.aiSettings) applyLoadedSettings()

// Process-isolation badge state.
const isolationChipClass = computed(() => {
  const s = isolationStatus.value
  if (!s) return ''
  if (s.compatibilityMode) return 'cx-chip--warn'
  return 'cx-chip--success'
})
const isolationChipLabel = computed(() => {
  const s = isolationStatus.value
  if (!s) return ''
  if (s.compatibilityMode) return t('settings.compatibilityApproval')
  if (s.sandboxed) return t('settings.sandboxActive', { backend: s.backend })
  if (s.reduced) return t('settings.sandboxReduced', { backend: s.backend })
  return t('settings.compatibilityApproval')
})

// Persist every provider's config (not just the active one) so editing any
// provider in the master-detail survives a save.
async function onSave() {
  const partial: Record<string, unknown> = {
    mode: aiForm.value.mode,
    openai: { endpoint: aiForm.value.openai.endpoint, apiKey: aiForm.value.openai.apiKey, model: aiForm.value.openai.model },
    anthropic: { endpoint: aiForm.value.anthropic.endpoint, apiKey: aiForm.value.anthropic.apiKey, model: aiForm.value.anthropic.model },
    deepseek: { endpoint: aiForm.value.deepseek.endpoint, apiKey: aiForm.value.deepseek.apiKey, model: aiForm.value.deepseek.model },
    ollama: { baseUrl: aiForm.value.ollama.baseUrl, model: aiForm.value.ollama.model },
    temperature: aiForm.value.temperature,
    topP: aiForm.value.topP,
    maxTokens: aiForm.value.maxTokens,
    maxToolRounds: aiForm.value.maxToolRounds,
    contextWindowTokens: aiForm.value.contextWindowTokens,
    systemPrompt: aiForm.value.systemPrompt,
  }
  await settings.updateAi(partial as never)
  syncFormFromStore()
  saved.value = true
  setTimeout(() => { saved.value = false }, 2000)
}

function requestEnableUnsandboxed() {
  if (settings.unsandboxedPlugins) return
  showUnsandboxedConfirm.value = true
}

async function confirmEnableUnsandboxed() {
  showUnsandboxedConfirm.value = false
  await settings.setUnsandboxedPlugins(true)
}

async function loadDbPlugins() {
  try {
    const all = await api.getPlugins()
    const dbOnes = all.filter((p) => p.permissions?.includes('database'))
    const results = await Promise.all(
      dbOnes.map(async (p) => {
        const status = await api.pluginDbStatus(p.id).catch(() => null)
        return {
          provisioned: status?.provisioned ?? false,
          status: status?.status ?? 'unknown',
          pluginId: p.id,
          name: p.name,
        }
      }),
    )
    dbPlugins.value = results
  } catch {
    dbPlugins.value = []
  }
}

function requestDbProvision(pluginId: string) {
  dbProvisionTargetId.value = pluginId
  dbError.value = null
  showDbProvisionConfirm.value = true
}

async function confirmDbProvision() {
  const id = dbProvisionTargetId
  showDbProvisionConfirm.value = false
  if (!id.value) return
  dbProvisioning.value = id.value
  dbError.value = null
  try {
    const result = await api.provisionPluginDb(id.value)
    if (!result.provisioned) {
      dbError.value = result.status
    } else {
      void loadDbPlugins()
    }
  } catch (e: unknown) {
    dbError.value = e instanceof Error ? e.message : String(e)
  } finally {
    dbProvisioning.value = null
  }
}

// Test the currently-selected provider's credentials.
async function onTest() {
  testing.value = true
  testResult.value = null
  try {
    const mode = selectedProvider.value
    const p = aiForm.value[mode]
    const req: AiConfigTestRequest = { mode, endpoint: p.endpoint, apiKey: p.apiKey, model: p.model }
    testResult.value = await settings.testAi(req)
  } catch (e: unknown) {
    testResult.value = { success: false, error: e instanceof Error ? e.message : String(e) }
  } finally {
    testing.value = false
  }
}

async function onSaveProxy() {
  proxyError.value = null
  try {
    await settings.setUpdateApiBase(proxyUrl.value.trim())
    proxySaved.value = true
    setTimeout(() => { proxySaved.value = false }, 2000)
  } catch (e: unknown) {
    proxyError.value = e instanceof Error ? e.message : String(e)
  }
}
</script>

<template>
  <div class="set-shell">
    <!-- Left section navigation -->
    <aside class="set-nav">
      <div class="set-nav-hd">{{ $t('settings.title') }}</div>
      <template v-for="(row, i) in navRows" :key="i">
        <div v-if="row.type === 'group'" class="set-nav-grp">{{ row.label }}</div>
        <button
          v-else
          class="set-nav-item"
          :class="{ active: activeSection === row.id }"
          @click="activeSection = row.id"
        >
          <i class="mdi" :class="row.icon" />
          <span>{{ row.label }}</span>
        </button>
      </template>
    </aside>

    <!-- Right content pane -->
    <div class="set-content">
      <!-- ═══ AI Providers: master-detail ═══ -->
      <div v-if="activeSection === 'providers'" class="prov">
        <!-- Provider list -->
        <aside class="prov-list">
          <div class="prov-list-hd">
            <div class="prov-list-title">{{ $t('aiSettings.providers') }}</div>
            <div class="cx-input-wrap prov-search">
              <i class="mdi mdi-magnify prov-search-icon" />
              <input v-model="providerQuery" class="cx-input" :placeholder="$t('aiSettings.searchProvider')">
            </div>
          </div>
          <div class="prov-list-body">
            <div class="prov-grp">{{ $t('aiSettings.providersGroup') }}</div>
            <button
              v-for="p in filteredProviders"
              :key="p.id"
              class="prov-item"
              :class="{ active: selectedProvider === p.id }"
              @click="selectedProvider = p.id"
            >
              <span class="prov-logo" :style="{ background: p.color }">{{ p.initial }}</span>
              <span class="prov-name">{{ p.label }}</span>
              <span v-if="aiForm.mode === p.id" class="prov-on" />
            </button>
          </div>
        </aside>

        <!-- Provider detail -->
        <div class="prov-detail">
          <div class="prov-detail-inner">
            <div class="prov-head">
              <span class="prov-logo prov-logo--lg" :style="{ background: selectedProviderMeta.color }">{{ selectedProviderMeta.initial }}</span>
              <div class="prov-head-ti">
                <h2>{{ selectedProviderMeta.label }}
                  <span class="cx-muted prov-domain">{{ selectedProviderMeta.domain }}</span>
                </h2>
                <div class="cx-muted prov-status">
                  {{ providerActive ? $t('aiSettings.enabled') : $t('aiSettings.notEnabled') }}
                </div>
              </div>
              <label class="prov-toggle" :title="providerActive ? $t('aiSettings.enabled') : $t('aiSettings.activate')">
                <input type="checkbox" :checked="providerActive" @change="activateProvider(selectedProvider)">
                <span class="prov-toggle__track" /><span class="prov-toggle__thumb" />
              </label>
            </div>

            <div class="cx-card">
              <h3 class="prov-card-h">{{ $t('aiSettings.authSection') }}</h3>
              <p class="cx-muted prov-card-desc">{{ $t('aiSettings.authHint') }}</p>
              <div class="prov-divider" />

              <div class="cx-field" style="margin-bottom: 14px">
                <label class="cx-label">{{ $t('aiSettings.apiKey') }}</label>
                <div class="prov-key-row">
                  <input
                    v-model="aiForm[selectedProvider].apiKey"
                    class="cx-input prov-key-input"
                    :type="showKey[selectedProvider] ? 'text' : 'password'"
                    :placeholder="settings.aiSettings?.[selectedProvider]?.apiKeySet ? $t('aiSettings.apiKeyHint') : ''"
                  />
                  <button class="prov-key-mini" :title="showKey[selectedProvider] ? $t('aiSettings.hideKey') : $t('aiSettings.showKey')" @click="showKey[selectedProvider] = !showKey[selectedProvider]">
                    <i class="mdi" :class="showKey[selectedProvider] ? 'mdi-eye-off' : 'mdi-eye'" />
                  </button>
                  <button class="prov-key-mini" :title="$t('aiSettings.test')" @click="onTest">
                    <i class="mdi" :class="testing ? 'mdi-loading mdi-spin' : 'mdi-connection'" />
                  </button>
                </div>
              </div>

              <div class="cx-field" style="margin-bottom: 14px">
                <label class="cx-label">{{ $t('aiSettings.endpoint') }}</label>
                <div class="cx-input-wrap">
                  <input
                    v-model="aiForm[selectedProvider].endpoint"
                    class="cx-input"
                    :placeholder="selectedNeedsV1 ? 'https://api.openai.com/v1' : 'https://api.anthropic.com'"
                  />
                  <button class="cx-iconbtn cx-iconbtn--sm" :title="$t('aichat.copy')" @click="copyText(aiForm[selectedProvider].endpoint)">
                    <i class="mdi mdi-content-copy" />
                  </button>
                </div>
                <div class="cx-muted" style="font-size: 12px; margin-top: 4px">
                  {{ selectedNeedsV1 ? $t('aiSettings.endpointHintOpenai') : $t('aiSettings.endpointHintAnthropic') }}
                </div>
              </div>

              <div v-if="testResult" class="cx-alert" :class="testResult.success ? 'cx-alert--success' : 'cx-alert--error'">
                <div class="cx-alert__body">
                  {{ testResult.success ? $t('aiSettings.testSuccess') : $t('aiSettings.testFailed') }}
                  <div v-if="testResult.error" style="font-size: 12px">{{ testResult.error }}</div>
                  <div v-if="testResult.warning" style="font-size: 12px">{{ testResult.warning }}</div>
                </div>
              </div>
            </div>

            <!-- Models -->
            <div class="cx-card" style="margin-top: 16px">
              <div class="prov-models-head">
                <h3 class="prov-card-h">{{ $t('aiSettings.models') }}</h3>
                <button class="cx-btn cx-btn--primary cx-btn--sm" @click="addingModel = !addingModel">
                  <i class="mdi mdi-plus" />{{ $t('aiSettings.addModel') }}
                </button>
              </div>
              <p class="cx-muted prov-card-desc">{{ $t('aiSettings.modelsHint') }}</p>
              <div class="prov-divider" />
              <div class="prov-filter-chips">
                <button v-for="c in modelFilters" :key="c.id" class="cx-chip" :class="{ 'cx-chip--solid': modelFilter === c.id }" @click="modelFilter = c.id">{{ c.label }}</button>
              </div>
              <div v-if="addingModel" class="prov-add-model">
                <input v-model="newModelName" class="cx-input" :placeholder="$t('aiSettings.modelNamePh')" @keydown.enter="commitModel" />
                <select v-model="newModelCap" class="cx-select prov-cap-select">
                  <option value="text">{{ $t('aiSettings.capText') }}</option>
                  <option value="vision">{{ $t('aiSettings.capVision') }}</option>
                  <option value="reasoning">{{ $t('aiSettings.capReasoning') }}</option>
                </select>
                <button class="cx-btn cx-btn--primary cx-btn--sm" @click="commitModel">{{ $t('common.confirm') }}</button>
              </div>
              <div v-if="filteredModelRows.length" class="prov-model-list">
                <div v-for="m in filteredModelRows" :key="m.name" class="prov-model-row">
                  <i class="mdi mdi-lightning-bolt" />
                  <span class="prov-model-name">{{ m.name }}</span>
                  <span class="cx-chip" :class="capChipClass(m.cap)">{{ capLabel(m.cap) }}</span>
                  <button class="cx-iconbtn cx-iconbtn--sm" :title="$t('common.cancel')" @click="removeModel"><i class="mdi mdi-close" /></button>
                </div>
              </div>
              <div v-else class="cx-muted prov-model-empty">{{ $t('aiSettings.noModels') }}</div>
            </div>

            <div class="cx-row" style="margin-top: 16px">
              <button class="cx-btn cx-btn--primary" @click="onSave">{{ $t('aiSettings.save') }}</button>
              <span v-if="saved" class="cx-chip cx-chip--success">{{ $t('aiSettings.saved') }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- ═══ Other sections: single content column ═══ -->
      <div v-else class="set-inner">
        <!-- Generation params -->
        <section v-show="activeSection === 'generate'">
          <h2 class="set-h">{{ $t('aiSettings.generate') }}</h2>
          <div class="cx-card">
            <div class="cx-row" style="margin-bottom: 16px">
              <span class="cx-muted" style="font-size: 13px">{{ $t('aiSettings.status') }}</span>
              <span class="cx-chip" :class="settings.aiSettings?.ready ? 'cx-chip--success' : ''">
                {{ settings.aiSettings?.ready ? $t('aiSettings.ready') : $t('aiSettings.notReady') }}
              </span>
              <span class="cx-muted" style="font-size: 13px">({{ settings.aiSettings?.activeMode }})</span>
            </div>
            <div class="cx-setting-row">
              <div class="cx-setting-row__label"><span>{{ $t('aiSettings.temperature') }}</span></div>
              <input v-model.number="aiForm.temperature" class="cx-input cx-input--narrow" type="number" step="0.1" min="0" max="2" />
            </div>
            <div class="cx-setting-row">
              <div class="cx-setting-row__label"><span>{{ $t('aiSettings.topP') }}</span></div>
              <input v-model.number="aiForm.topP" class="cx-input cx-input--narrow" type="number" step="0.05" min="0" max="1" />
            </div>
            <div class="cx-setting-row">
              <div class="cx-setting-row__label"><span>{{ $t('aiSettings.maxTokens') }}</span></div>
              <input v-model.number="aiForm.maxTokens" class="cx-input cx-input--narrow" type="number" step="1" min="1" />
            </div>
            <div class="cx-setting-row">
              <div class="cx-setting-row__label">
                <span>{{ $t('aiSettings.maxToolRounds') }}</span>
                <span class="cx-muted" style="font-size: 12px; margin-left: 6px">{{ $t('aiSettings.maxToolRoundsHint') }}</span>
              </div>
              <input v-model.number="aiForm.maxToolRounds" class="cx-input cx-input--narrow" type="number" step="1" min="0" max="10000" />
            </div>
            <div class="cx-setting-row" style="margin-bottom: 14px">
              <div class="cx-setting-row__label">
                <span>{{ $t('aiSettings.contextWindowTokens') }}</span>
                <span class="cx-muted" style="font-size: 12px; margin-left: 6px">{{ $t('aiSettings.contextWindowTokensHint') }}</span>
              </div>
              <input v-model.number="aiForm.contextWindowTokens" class="cx-input cx-input--narrow" type="number" step="1024" min="0" max="2000000" />
            </div>
            <div class="cx-field" style="margin-bottom: 16px">
              <label class="cx-label">{{ $t('aiSettings.systemPrompt') }}</label>
              <textarea v-model="aiForm.systemPrompt" class="cx-textarea" rows="3" />
            </div>
            <div class="cx-row">
              <button class="cx-btn cx-btn--primary" @click="onSave">{{ $t('aiSettings.save') }}</button>
              <span v-if="saved" class="cx-chip cx-chip--success">{{ $t('aiSettings.saved') }}</span>
            </div>
          </div>
        </section>

        <!-- Appearance -->
        <section v-show="activeSection === 'appearance'">
          <h2 class="set-h">{{ $t('settings.theme') }}</h2>
          <div class="cx-card">
            <div class="cx-setting-row">
              <div class="cx-setting-row__label">
                <i class="mdi mdi-palette-outline" />
                <span>{{ $t('settings.theme') }}</span>
              </div>
              <div class="cx-segment">
                <button v-for="i in themeItems" :key="i.value" :class="{ active: settings.theme === i.value }" @click="settings.setTheme(i.value)">{{ i.title }}</button>
              </div>
            </div>
            <div class="cx-setting-row">
              <div class="cx-setting-row__label">
                <i class="mdi mdi-translate" />
                <span>{{ $t('settings.language') }}</span>
              </div>
              <div class="cx-segment">
                <button v-for="i in languageItems" :key="i.value" :class="{ active: settings.language === i.value }" @click="settings.setLanguage(i.value)">{{ i.title }}</button>
              </div>
            </div>
            <div class="cx-setting-row">
              <div class="cx-setting-row__label">
                <i class="mdi mdi-text-box-search-outline" />
                <div>
                  <div>{{ $t('settings.logLevel') }}</div>
                  <div class="cx-muted" style="font-size: 12px">{{ $t('settings.logLevelHint') }}</div>
                </div>
              </div>
              <select
                :value="settings.logLevel"
                class="cx-select"
                style="width: 140px"
                @change="settings.setLogLevel(($event.target as HTMLSelectElement).value as LogLevel)"
              >
                <option v-for="level in logLevelItems" :key="level" :value="level">{{ level }}</option>
              </select>
            </div>
          </div>
        </section>

        <!-- Runtime & security -->
        <section v-show="activeSection === 'runtime'">
          <h2 class="set-h">{{ $t('settings.runtimeSecurity') }}</h2>
          <div class="cx-card">
            <div class="cx-setting-row">
              <div class="cx-setting-row__label">
                <i class="mdi mdi-shield-lock-outline" />
                <span>{{ $t('settings.processIsolation') }}</span>
              </div>
              <span v-if="isolationStatus" class="cx-chip" :class="isolationChipClass">{{ isolationChipLabel }}</span>
            </div>
            <div v-if="isolationStatus?.reduced" class="cx-muted" style="font-size: 12px; margin: -6px 0 12px;">
              {{ $t('settings.sandboxReducedHint') }}
            </div>
            <div v-if="isolationStatus?.compatibilityMode" class="cx-setting-row">
              <div class="cx-setting-row__label">
                <i class="mdi mdi-shield-alert-outline" />
                <span>{{ $t('settings.unsandboxedPluginsTitle') }}</span>
              </div>
              <div class="cx-segment">
                <button :class="{ active: !settings.unsandboxedPlugins }" @click="settings.setUnsandboxedPlugins(false)">{{ $t('settings.unsandboxedOff') }}</button>
                <button :class="{ active: settings.unsandboxedPlugins }" @click="requestEnableUnsandboxed()">{{ $t('settings.unsandboxedOn') }}</button>
              </div>
            </div>
            <div v-if="isolationStatus?.compatibilityMode" class="cx-muted" style="color: rgb(var(--v-theme-error)); font-size: 12px; margin: -6px 0 0;">
              {{ $t('settings.unsandboxedPluginsWarn') }}
            </div>
          </div>
        </section>

        <!-- MCP -->
        <section v-show="activeSection === 'mcp'">
          <h2 class="set-h">MCP</h2>
          <div class="cx-card">
            <div class="cx-setting-row">
              <div class="cx-setting-row__label">
                <i class="mdi mdi-connection" />
                <span>MCP</span>
              </div>
              <span v-if="mcpStatus" class="cx-muted" style="font-size: 13px">
                {{ $t('settings.mcpSummary', { connections: mcpStatus.connectionCount, tools: mcpStatus.toolCount }) }}
              </span>
            </div>
          </div>
        </section>

        <!-- Database isolation -->
        <section v-show="activeSection === 'database'">
          <h2 class="set-h">{{ $t('settings.pluginDbSection') }}</h2>
          <div class="cx-card">
            <div class="cx-muted" style="font-size: 12px; margin-bottom: 12px">{{ $t('settings.pluginDbSectionHint') }}</div>
            <div v-if="dbPlugins.length === 0" class="cx-muted" style="font-size: 13px">{{ $t('settings.pluginDbNoPlugins') }}</div>
            <div v-for="p in dbPlugins" :key="p.pluginId" class="cx-setting-row">
              <div class="cx-setting-row__label">
                <i class="mdi mdi-database-lock-outline" />
                <span>{{ p.name }} <span class="cx-muted" style="font-size: 12px">{{ p.pluginId }}</span></span>
              </div>
              <span v-if="p.provisioned" class="cx-chip cx-chip--success">{{ $t('settings.pluginDbAuthorized') }}</span>
              <button v-else class="cx-btn cx-btn--primary" :disabled="dbProvisioning === p.pluginId" @click="requestDbProvision(p.pluginId)">
                {{ dbProvisioning === p.pluginId ? $t('settings.pluginDbProvisioning') : $t('settings.pluginDbAuthorize') }}
              </button>
            </div>
            <div v-if="dbError" class="cx-muted" style="color: rgb(var(--v-theme-error)); font-size: 12px; margin-top: 8px">
              {{ $t('settings.pluginDbError', { message: dbError }) }}
            </div>
          </div>
        </section>

        <!-- Update channel -->
        <section v-show="activeSection === 'update'">
          <h2 class="set-h">{{ $t('settings.updateChannelSection') }}</h2>
          <div class="cx-card">
            <div class="cx-field" style="margin-bottom: 14px">
              <label class="cx-label">{{ $t('settings.updateProxyUrl') }}</label>
              <input v-model="proxyUrl" class="cx-input" :placeholder="$t('settings.updateProxyUrlPlaceholder')" />
              <div class="cx-muted" style="font-size: 12px; margin-top: 4px">{{ $t('settings.updateProxyUrlHint') }}</div>
            </div>
            <div class="cx-row">
              <button class="cx-btn cx-btn--primary" @click="onSaveProxy">{{ $t('aiSettings.save') }}</button>
              <span v-if="proxySaved" class="cx-chip cx-chip--success">{{ $t('settings.updateProxyUrlSaved') }}</span>
              <span v-if="proxyError" class="cx-alert cx-alert--error">{{ $t('settings.updateProxyUrlInvalid', { message: proxyError }) }}</span>
            </div>
          </div>
        </section>
      </div>
    </div>
  </div>

  <v-dialog v-model="showUnsandboxedConfirm" max-width="480">
    <v-card>
      <v-card-text>{{ $t('settings.unsandboxedPluginsConfirm') }}</v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" @click="showUnsandboxedConfirm = false">{{ $t('common.cancel') }}</v-btn>
        <v-btn color="error" variant="tonal" @click="confirmEnableUnsandboxed()">{{ $t('common.confirm') }}</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>

  <v-dialog v-model="showDbProvisionConfirm" max-width="480">
    <v-card>
      <v-card-title>{{ $t('settings.pluginDbConfirmTitle') }}</v-card-title>
      <v-card-text>{{ $t('settings.pluginDbConfirm') }}</v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" @click="showDbProvisionConfirm = false">{{ $t('common.cancel') }}</v-btn>
        <v-btn color="primary" variant="tonal" @click="confirmDbProvision()">{{ $t('common.confirm') }}</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.set-shell { flex: 1 1 auto; min-height: 0; height: 100%; display: flex; }
.set-nav {
  width: 232px; flex: 0 0 232px; height: 100%; overflow-y: auto;
  border-right: 1px solid var(--cx-border); background: rgb(var(--v-theme-background)); padding: 14px 10px;
}
.set-nav-hd { font-size: 15px; font-weight: 650; padding: 4px 10px 12px; }
.set-nav-grp { font-size: 11px; font-weight: 650; letter-spacing: 0.05em; text-transform: uppercase; color: rgb(var(--v-theme-secondary)); opacity: 0.7; padding: 14px 12px 4px; }
.set-nav-item {
  width: 100%; display: flex; align-items: center; gap: 10px; height: 36px; padding: 0 12px; margin: 1px 0;
  border: 0; border-radius: var(--cx-radius); background: transparent; color: rgb(var(--v-theme-secondary));
  font: inherit; font-size: 13px; text-align: left; cursor: pointer;
  transition: background 0.12s ease, color 0.12s ease;
}
.set-nav-item:hover { background: var(--cx-hover); color: rgb(var(--v-theme-on-surface)); }
.set-nav-item.active { background: var(--cx-hover-strong); color: rgb(var(--v-theme-on-surface)); }
.set-nav-item .mdi { font-size: 18px; flex: 0 0 auto; }

.set-content { flex: 1 1 auto; min-width: 0; height: 100%; overflow: hidden; }
.set-inner { height: 100%; overflow-y: auto; max-width: 720px; margin: 0 auto; padding: 28px 32px 48px; }
.set-h { font-size: 20px; font-weight: 650; margin: 0 0 18px; }

/* ── Provider master-detail ── */
.prov { display: flex; width: 100%; height: 100%; min-height: 0; }
.prov-list {
  width: 248px; flex: 0 0 248px; height: 100%; display: flex; flex-direction: column;
  border-right: 1px solid var(--cx-border); background: rgb(var(--v-theme-surface-container));
}
.prov-list-hd { padding: 16px 14px 12px; border-bottom: 1px solid var(--cx-border); }
.prov-list-title { font-weight: 650; font-size: 14px; margin-bottom: 10px; }
.prov-search { max-width: 100%; }
.prov-search .cx-input { padding-left: 34px; }
.prov-search-icon { position: absolute; left: 10px; top: 9px; color: rgb(var(--v-theme-secondary)); font-size: 16px; pointer-events: none; }
.prov-list-body { flex: 1 1 auto; overflow-y: auto; padding: 8px; }
.prov-grp { font-size: 11px; font-weight: 650; letter-spacing: 0.04em; text-transform: uppercase; color: rgb(var(--v-theme-secondary)); padding: 10px 8px 4px; }
.prov-item {
  width: 100%; display: flex; align-items: center; gap: 10px; height: 40px; padding: 0 8px; margin: 1px 0;
  border: 0; border-radius: var(--cx-radius); background: transparent; color: rgb(var(--v-theme-on-surface));
  font: inherit; font-size: 13px; text-align: left; cursor: pointer;
  transition: background 0.12s ease;
}
.prov-item:hover { background: var(--cx-hover); }
.prov-item.active { background: var(--cx-hover-strong); }
.prov-logo {
  width: 26px; height: 26px; border-radius: 7px; flex: 0 0 auto;
  display: grid; place-items: center; color: #fff; font-weight: 700; font-size: 12px;
}
.prov-logo--lg { width: 40px; height: 40px; border-radius: 10px; font-size: 16px; }
.prov-name { flex: 1 1 auto; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.prov-on { width: 7px; height: 7px; border-radius: 50%; background: rgb(var(--v-theme-tertiary)); flex: 0 0 auto; }

.prov-detail { flex: 1 1 auto; min-width: 0; height: 100%; overflow-y: auto; }
.prov-detail-inner { max-width: 680px; margin: 0 auto; padding: 26px 30px 48px; }
.prov-head { display: flex; align-items: center; gap: 14px; margin-bottom: 22px; }
.prov-head-ti { flex: 1 1 auto; min-width: 0; }
.prov-head-ti h2 { margin: 0; font-size: 17px; font-weight: 650; display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.prov-domain { font-size: 12px; font-weight: 400; }
.prov-status { font-size: 12px; margin-top: 3px; }
.prov-card-h { margin: 0 0 4px; font-size: 15px; font-weight: 650; }
.prov-card-desc { font-size: 12.5px; margin: 0 0 14px; }
.prov-divider { height: 1px; background: var(--cx-border-subtle); margin-bottom: 14px; }

/* Enable toggle (on = this provider is the active mode) */
.prov-toggle { position: relative; display: inline-flex; width: 40px; height: 22px; flex: 0 0 auto; cursor: pointer; }
.prov-toggle input { position: absolute; opacity: 0; width: 100%; height: 100%; margin: 0; cursor: pointer; }
.prov-toggle__track { width: 40px; height: 22px; border-radius: 999px; background: rgb(var(--v-theme-surface-variant)); transition: background .15s ease; }
.prov-toggle__thumb { position: absolute; top: 3px; left: 3px; width: 16px; height: 16px; border-radius: 50%; background: rgb(var(--v-theme-surface)); transition: transform .15s ease; box-shadow: 0 1px 2px rgba(0,0,0,.3); }
.prov-toggle input:checked ~ .prov-toggle__track { background: rgb(var(--v-theme-primary)); }
.prov-toggle input:checked ~ .prov-toggle__thumb { transform: translateX(18px); }

/* API key row with attached show/check mini buttons */
.prov-key-row { display: flex; align-items: stretch; }
.prov-key-input { flex: 1 1 auto; border-radius: var(--cx-radius) 0 0 var(--cx-radius); }
.prov-key-mini { width: 40px; flex: 0 0 auto; display: grid; place-items: center; border: 1px solid var(--cx-border); border-left: 0; background: rgb(var(--v-theme-surface)); color: rgb(var(--v-theme-secondary)); cursor: pointer; }
.prov-key-mini:last-child { border-radius: 0 var(--cx-radius) var(--cx-radius) 0; }
.prov-key-mini:hover { background: var(--cx-hover); color: rgb(var(--v-theme-on-surface)); }
.prov-key-mini .mdi { font-size: 18px; }

/* Models card */
.prov-models-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.prov-filter-chips { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 12px; }
.prov-filter-chips .cx-chip { cursor: pointer; }
.prov-add-model { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
.prov-add-model .cx-input { flex: 1 1 auto; }
.prov-cap-select { width: auto; max-width: 130px; }
.prov-model-list { display: flex; flex-direction: column; gap: 2px; }
.prov-model-row { display: flex; align-items: center; gap: 10px; height: 38px; padding: 0 4px; border-radius: var(--cx-radius); }
.prov-model-row:hover { background: var(--cx-hover); }
.prov-model-row .mdi-lightning-bolt { color: rgb(var(--v-theme-secondary)); font-size: 16px; }
.prov-model-name { flex: 1 1 auto; font-family: 'SF Mono','JetBrains Mono',ui-monospace,monospace; font-size: 12.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.prov-model-empty { font-size: 13px; padding: 8px 0; }
</style>
