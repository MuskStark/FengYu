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
  systemPrompt: '',
})
const showKey = ref<Record<string, boolean>>({})
const testing = ref(false)
const testResult = ref<AiConfigTestResult | null>(null)
const saved = ref(false)

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
  aiForm.value.systemPrompt = s.systemPrompt
}

watch(() => settings.aiSettings, (s) => { if (s) syncFormFromStore() })
if (settings.aiSettings) syncFormFromStore()

const activeProvider = computed(() => {
  const m = aiForm.value.mode
  if (m === 'openai' || m === 'anthropic' || m === 'deepseek') return m
  return null
})

// Whether the active provider expects /v1 in its base URL (OpenAI-compatible: yes;
// Anthropic: no). Drives the endpoint input hint so the user fills it in correctly.
const endpointNeedsV1 = computed(() => {
  const m = aiForm.value.mode
  return m === 'openai' || m === 'deepseek'
})

const modes: { title: string; value: AiMode }[] = [
  { title: t('aiSettings.modeLocal'), value: 'local' },
  { title: t('aiSettings.modeOpenai'), value: 'openai' },
  { title: t('aiSettings.modeAnthropic'), value: 'anthropic' },
  { title: t('aiSettings.modeDeepseek'), value: 'deepseek' },
]

async function onSave() {
  const partial: Record<string, unknown> = {
    mode: aiForm.value.mode,
    temperature: aiForm.value.temperature,
    topP: aiForm.value.topP,
    maxTokens: aiForm.value.maxTokens,
    maxToolRounds: aiForm.value.maxToolRounds,
    systemPrompt: aiForm.value.systemPrompt,
  }
  if (aiForm.value.mode !== 'local') {
    const p = aiForm.value[aiForm.value.mode as 'openai' | 'anthropic' | 'deepseek']
    partial[aiForm.value.mode] = { endpoint: p.endpoint, apiKey: p.apiKey, model: p.model }
  }
  partial.ollama = { baseUrl: aiForm.value.ollama.baseUrl, model: aiForm.value.ollama.model }
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
  const id = dbProvisionTargetId.value
  showDbProvisionConfirm.value = false
  if (!id) return
  dbProvisioning.value = id
  dbError.value = null
  try {
    const result = await api.provisionPluginDb(id)
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

async function onTest() {
  testing.value = true
  testResult.value = null
  try {
    const mode = aiForm.value.mode
    const req: AiConfigTestRequest = { mode }
    if (mode === 'local') {
      req.baseUrl = aiForm.value.ollama.baseUrl
      req.model = aiForm.value.ollama.model
    } else {
      const p = aiForm.value[mode as 'openai' | 'anthropic' | 'deepseek']
      req.endpoint = p.endpoint
      req.apiKey = p.apiKey
      req.model = p.model
    }
    testResult.value = await settings.testAi(req)
  } catch (e: unknown) {
    testResult.value = { success: false, error: e instanceof Error ? e.message : String(e) }
  } finally {
    testing.value = false
  }
}
</script>

<template>
  <div style="flex: 1 1 auto; min-height: 0; overflow-y: auto">
    <div class="cx-page">
      <h1 class="cx-page-title">{{ $t('settings.title') }}</h1>

      <!-- Appearance -->
      <div class="cx-card">
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
        <div class="cx-setting-row">
          <div class="cx-setting-row__label">
            <i class="mdi mdi-palette-outline" />
            <span>{{ $t('settings.theme') }}</span>
          </div>
          <div class="cx-segment">
            <button
              v-for="i in themeItems"
              :key="i.value"
              :class="{ active: settings.theme === i.value }"
              @click="settings.setTheme(i.value)"
            >{{ i.title }}</button>
          </div>
        </div>
        <div class="cx-setting-row">
          <div class="cx-setting-row__label">
            <i class="mdi mdi-translate" />
            <span>{{ $t('settings.language') }}</span>
          </div>
          <div class="cx-segment">
            <button
              v-for="i in languageItems"
              :key="i.value"
              :class="{ active: settings.language === i.value }"
              @click="settings.setLanguage(i.value)"
            >{{ i.title }}</button>
          </div>
        </div>
      </div>

      <div class="cx-section-title">{{ $t('settings.runtimeSecurity') }}</div>
      <div class="cx-card">
        <div class="cx-setting-row">
          <div class="cx-setting-row__label">
            <i class="mdi mdi-shield-lock-outline" />
            <span>{{ $t('settings.processIsolation') }}</span>
          </div>
          <span
            v-if="isolationStatus"
            class="cx-chip"
            :class="isolationStatus.sandboxed ? 'cx-chip--success' : 'cx-chip--warn'"
          >
            {{ isolationStatus.sandboxed
              ? $t('settings.sandboxActive', { backend: isolationStatus.backend })
              : $t('settings.compatibilityApproval') }}
          </span>
        </div>
        <div v-if="isolationStatus?.compatibilityMode" class="cx-setting-row">
          <div class="cx-setting-row__label">
            <i class="mdi mdi-shield-alert-outline" />
            <span>{{ $t('settings.unsandboxedPluginsTitle') }}</span>
          </div>
          <div class="cx-segment">
            <button
              :class="{ active: !settings.unsandboxedPlugins }"
              @click="settings.setUnsandboxedPlugins(false)"
            >{{ $t('settings.unsandboxedOff') }}</button>
            <button
              :class="{ active: settings.unsandboxedPlugins }"
              @click="requestEnableUnsandboxed()"
            >{{ $t('settings.unsandboxedOn') }}</button>
          </div>
        </div>
        <div
          v-if="isolationStatus?.compatibilityMode"
          class="cx-muted"
          style="color: var(--md-sys-color-error); font-size: 12px; margin-top: -8px;"
        >
          {{ $t('settings.unsandboxedPluginsWarn') }}
        </div>
        <div class="cx-setting-row">
          <div class="cx-setting-row__label">
            <i class="mdi mdi-connection" />
            <span>MCP</span>
          </div>
          <span v-if="mcpStatus" class="cx-muted" style="font-size: 13px">
            {{ $t('settings.mcpSummary', {
              connections: mcpStatus.connectionCount,
              tools: mcpStatus.toolCount,
            }) }}
          </span>
        </div>
      </div>

      <div class="cx-section-title">{{ $t('settings.pluginDbSection') }}</div>
      <div class="cx-card">
        <div class="cx-muted" style="font-size: 12px; margin-bottom: 12px">
          {{ $t('settings.pluginDbSectionHint') }}
        </div>
        <div v-if="dbPlugins.length === 0" class="cx-muted" style="font-size: 13px">
          {{ $t('settings.pluginDbNoPlugins') }}
        </div>
        <div
          v-for="p in dbPlugins"
          :key="p.pluginId"
          class="cx-setting-row"
        >
          <div class="cx-setting-row__label">
            <i class="mdi mdi-database-lock-outline" />
            <span>{{ p.name }} <span class="cx-muted" style="font-size: 12px">{{ p.pluginId }}</span></span>
          </div>
          <span v-if="p.provisioned" class="cx-chip cx-chip--success">
            {{ $t('settings.pluginDbAuthorized') }}
          </span>
          <button
            v-else
            class="cx-btn cx-btn--primary"
            :disabled="dbProvisioning === p.pluginId"
            @click="requestDbProvision(p.pluginId)"
          >
            {{ dbProvisioning === p.pluginId ? $t('settings.pluginDbProvisioning') : $t('settings.pluginDbAuthorize') }}
          </button>
        </div>
        <div
          v-if="dbError"
          class="cx-muted"
          style="color: var(--md-sys-color-error); font-size: 12px; margin-top: 8px"
        >
          {{ $t('settings.pluginDbError', { message: dbError }) }}
        </div>
      </div>

      <!-- AI configuration -->
      <div class="cx-section-title">{{ $t('aiSettings.sectionTitle') }}</div>
      <div class="cx-card">
        <!-- Status -->
        <div class="cx-row" style="margin-bottom: 16px">
          <span class="cx-muted" style="font-size: 13px">{{ $t('aiSettings.status') }}</span>
          <span class="cx-chip" :class="settings.aiSettings?.ready ? 'cx-chip--success' : ''">
            {{ settings.aiSettings?.ready ? $t('aiSettings.ready') : $t('aiSettings.notReady') }}
          </span>
          <span class="cx-muted" style="font-size: 13px">({{ settings.aiSettings?.activeMode }})</span>
        </div>

        <!-- Mode -->
        <div class="cx-field" style="margin-bottom: 14px">
          <label class="cx-label">{{ $t('aiSettings.mode') }}</label>
          <select v-model="aiForm.mode" class="cx-select">
            <option v-for="m in modes" :key="m.value" :value="m.value">{{ m.title }}</option>
          </select>
        </div>

        <!-- Cloud provider fields -->
        <template v-if="activeProvider">
          <div class="cx-field" style="margin-bottom: 14px">
            <label class="cx-label">{{ $t('aiSettings.endpoint') }}</label>
            <input
              v-model="aiForm[activeProvider].endpoint"
              class="cx-input"
              :placeholder="endpointNeedsV1 ? 'https://api.openai.com/v1' : 'https://api.anthropic.com'"
            />
            <div class="cx-muted" style="font-size: 12px; margin-top: 4px">
              {{ endpointNeedsV1 ? $t('aiSettings.endpointHintOpenai') : $t('aiSettings.endpointHintAnthropic') }}
            </div>
          </div>
          <div class="cx-field" style="margin-bottom: 14px">
            <label class="cx-label">{{ $t('aiSettings.apiKey') }}</label>
            <div class="cx-input-wrap">
              <input
                v-model="aiForm[activeProvider].apiKey"
                class="cx-input"
                :type="showKey[activeProvider] ? 'text' : 'password'"
                :placeholder="settings.aiSettings?.[activeProvider]?.apiKeySet ? $t('aiSettings.apiKeyHint') : ''"
              />
              <button class="cx-iconbtn cx-iconbtn--sm" @click="showKey[activeProvider] = !showKey[activeProvider]">
                <i class="mdi" :class="showKey[activeProvider] ? 'mdi-eye-off' : 'mdi-eye'" />
              </button>
            </div>
          </div>
          <div class="cx-field" style="margin-bottom: 14px">
            <label class="cx-label">{{ $t('aiSettings.model') }}</label>
            <input v-model="aiForm[activeProvider].model" class="cx-input" />
          </div>
        </template>

        <!-- Ollama fields -->
        <template v-else>
          <div class="cx-field" style="margin-bottom: 14px">
            <label class="cx-label">{{ $t('aiSettings.ollamaUrl') }}</label>
            <input v-model="aiForm.ollama.baseUrl" class="cx-input" />
          </div>
          <div class="cx-field" style="margin-bottom: 14px">
            <label class="cx-label">{{ $t('aiSettings.model') }}</label>
            <input v-model="aiForm.ollama.model" class="cx-input" />
          </div>
        </template>

        <!-- Test -->
        <div class="cx-row" style="margin-bottom: 16px; align-items: flex-start">
          <button class="cx-btn cx-btn--tonal" :disabled="testing" @click="onTest">
            <span v-if="testing" class="cx-spin" />
            {{ testing ? $t('aiSettings.testing') : $t('aiSettings.test') }}
          </button>
          <div
            v-if="testResult"
            class="cx-alert cx-grow"
            :class="testResult.success ? 'cx-alert--success' : 'cx-alert--error'"
          >
            <div class="cx-alert__body">
              {{ testResult.success ? $t('aiSettings.testSuccess') : $t('aiSettings.testFailed') }}
              <div v-if="testResult.error" style="font-size: 12px">{{ testResult.error }}</div>
              <div v-if="testResult.warning" style="font-size: 12px">{{ testResult.warning }}</div>
            </div>
          </div>
        </div>

        <!-- Sampling -->
        <div class="cx-setting-row">
          <div class="cx-setting-row__label"><span>{{ $t('aiSettings.temperature') }}</span></div>
          <input v-model.number="aiForm.temperature" class="cx-input cx-input--narrow" type="number" step="0.1" min="0" max="2" />
        </div>
        <div class="cx-setting-row">
          <div class="cx-setting-row__label"><span>{{ $t('aiSettings.topP') }}</span></div>
          <input v-model.number="aiForm.topP" class="cx-input cx-input--narrow" type="number" step="0.05" min="0" max="1" />
        </div>
        <div class="cx-setting-row" style="margin-bottom: 14px">
          <div class="cx-setting-row__label"><span>{{ $t('aiSettings.maxTokens') }}</span></div>
          <input v-model.number="aiForm.maxTokens" class="cx-input cx-input--narrow" type="number" step="1" min="1" />
        </div>
        <div class="cx-setting-row" style="margin-bottom: 14px">
          <div class="cx-setting-row__label">
            <span>{{ $t('aiSettings.maxToolRounds') }}</span>
            <span class="cx-muted" style="font-size: 12px; margin-left: 6px">{{ $t('aiSettings.maxToolRoundsHint') }}</span>
          </div>
          <input v-model.number="aiForm.maxToolRounds" class="cx-input cx-input--narrow" type="number" step="1" min="0" max="10000" />
        </div>
        <div class="cx-field" style="margin-bottom: 16px">
          <label class="cx-label">{{ $t('aiSettings.systemPrompt') }}</label>
          <textarea v-model="aiForm.systemPrompt" class="cx-textarea" rows="3" />
        </div>

        <!-- Save -->
        <div class="cx-row">
          <button class="cx-btn cx-btn--primary" @click="onSave">{{ $t('aiSettings.save') }}</button>
          <span v-if="saved" class="cx-chip cx-chip--success">{{ $t('aiSettings.saved') }}</span>
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
  </div>
</template>
