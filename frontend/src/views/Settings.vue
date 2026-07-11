<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useSettingsStore } from '@/stores/settings'
import type {
  AiConfigTestRequest,
  AiConfigTestResult,
  AiMode,
  LanguageName,
  ThemeName,
} from '@/api/types'

const { t } = useI18n()
const settings = useSettingsStore()

onMounted(() => {
  if (!settings.loaded) void settings.load().catch(() => {})
  if (!settings.aiLoaded) void settings.loadAi().catch(() => {})
})

// ── Appearance ──────────────────────────────────────────────
// Vuetify v-select renders `title`; the `value` (the enum) is what flows back
// into the store. Localized labels reuse the existing settings.* i18n keys.
const themeItems: { title: string; value: ThemeName }[] = [
  { title: t('settings.dark'), value: 'dark' },
  { title: t('settings.light'), value: 'light' },
]
const languageItems: { title: string; value: LanguageName }[] = [
  { title: t('settings.english'), value: 'en' },
  { title: t('settings.chinese'), value: 'zh' },
]

// ── AI Config ───────────────────────────────────────────────
// Local form state, synced from settings.aiSettings on load.
const aiForm = ref({
  mode: 'local' as AiMode,
  openai: { endpoint: '', apiKey: '', model: '' },
  anthropic: { endpoint: '', apiKey: '', model: '' },
  deepseek: { endpoint: '', apiKey: '', model: '' },
  ollama: { baseUrl: '', model: '' },
  temperature: 0.7,
  topP: 0.9,
  maxTokens: 2048,
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
  aiForm.value.systemPrompt = s.systemPrompt
}

// Sync deterministically once AI settings arrive. loadAi() is fire-and-forget;
// a watch on settings.aiSettings fires as soon as the store resolves, replacing
// a fragile setTimeout that could fire before the load completed on slow networks.
watch(() => settings.aiSettings, (s) => { if (s) syncFormFromStore() })
if (settings.aiSettings) syncFormFromStore()

const activeProvider = computed(() => {
  const m = aiForm.value.mode
  if (m === 'openai' || m === 'anthropic' || m === 'deepseek') return m
  return null // local
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
    systemPrompt: aiForm.value.systemPrompt,
  }
  // Only send provider fields for the active cloud provider + ollama always.
  if (aiForm.value.mode !== 'local') {
    const p = aiForm.value[aiForm.value.mode as 'openai' | 'anthropic' | 'deepseek']
    partial[aiForm.value.mode] = { endpoint: p.endpoint, apiKey: p.apiKey, model: p.model }
  }
  partial.ollama = { baseUrl: aiForm.value.ollama.baseUrl, model: aiForm.value.ollama.model }
  await settings.updateAi(partial as never)
  syncFormFromStore()
  saved.value = true
  setTimeout(() => {
    saved.value = false
  }, 2000)
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
    const msg = e instanceof Error ? e.message : String(e)
    testResult.value = { success: false, error: msg }
  } finally {
    testing.value = false
  }
}
</script>

<template>
  <v-container max-width="640">
    <h1 class="text-h5 mb-4">{{ $t('settings.title') }}</h1>

    <!-- ── Appearance ─────────────────────────────────────── -->
    <v-list lines="two">
      <v-list-item>
        <template #prepend><v-icon icon="mdi-palette-outline" /></template>
        <v-list-item-title>{{ $t('settings.theme') }}</v-list-item-title>
        <template #append>
          <v-select
            :model-value="settings.theme"
            :items="themeItems"
            item-title="title"
            item-value="value"
            density="compact"
            variant="outlined"
            hide-details
            style="max-width: 160px"
            @update:model-value="(v: ThemeName) => settings.setTheme(v)"
          />
        </template>
      </v-list-item>

      <v-list-item>
        <template #prepend><v-icon icon="mdi-translate" /></template>
        <v-list-item-title>{{ $t('settings.language') }}</v-list-item-title>
        <template #append>
          <v-select
            :model-value="settings.language"
            :items="languageItems"
            item-title="title"
            item-value="value"
            density="compact"
            variant="outlined"
            hide-details
            style="max-width: 160px"
            @update:model-value="(v: LanguageName) => settings.setLanguage(v)"
          />
        </template>
      </v-list-item>
    </v-list>

    <!-- ── AI Configuration ───────────────────────────────── -->
    <h2 class="text-subtitle-1 text-uppercase text-medium-emphasis mt-6 mb-3">
      {{ $t('aiSettings.sectionTitle') }}
    </h2>

    <v-card variant="outlined" rounded="lg" class="pa-4">
      <!-- Status -->
      <div class="d-flex align-center ga-3 mb-4">
        <span class="text-body-2 text-medium-emphasis">{{ $t('aiSettings.status') }}</span>
        <v-chip
          size="small"
          variant="tonal"
          :color="settings.aiSettings?.ready ? 'success' : 'default'"
        >
          {{ settings.aiSettings?.ready ? $t('aiSettings.ready') : $t('aiSettings.notReady') }}
        </v-chip>
        <span class="text-body-2 text-medium-emphasis">({{ settings.aiSettings?.activeMode }})</span>
      </div>

      <!-- Mode -->
      <div class="mb-3">
        <div class="text-caption text-medium-emphasis mb-1">{{ $t('aiSettings.mode') }}</div>
        <v-select
          v-model="aiForm.mode"
          :items="modes"
          item-title="title"
          item-value="value"
          variant="outlined"
          density="compact"
          hide-details
        />
      </div>

      <!-- Cloud provider fields (conditional) -->
      <template v-if="activeProvider">
        <v-text-field
          v-model="aiForm[activeProvider].endpoint"
          :label="$t('aiSettings.endpoint')"
          variant="outlined"
          density="compact"
          hide-details
          class="mb-3"
        />
        <v-text-field
          v-model="aiForm[activeProvider].apiKey"
          :label="$t('aiSettings.apiKey')"
          :type="showKey[activeProvider] ? 'text' : 'password'"
          :placeholder="settings.aiSettings?.[activeProvider]?.apiKeySet ? $t('aiSettings.apiKeyHint') : ''"
          :append-inner-icon="showKey[activeProvider] ? 'mdi-eye-off' : 'mdi-eye'"
          variant="outlined"
          density="compact"
          hide-details
          class="mb-3"
          @click:append-inner="showKey[activeProvider] = !showKey[activeProvider]"
        />
        <v-text-field
          v-model="aiForm[activeProvider].model"
          :label="$t('aiSettings.model')"
          variant="outlined"
          density="compact"
          hide-details
          class="mb-3"
        />
      </template>

      <!-- Ollama fields (local mode) -->
      <template v-else>
        <v-text-field
          v-model="aiForm.ollama.baseUrl"
          :label="$t('aiSettings.ollamaUrl')"
          variant="outlined"
          density="compact"
          hide-details
          class="mb-3"
        />
        <v-text-field
          v-model="aiForm.ollama.model"
          :label="$t('aiSettings.model')"
          variant="outlined"
          density="compact"
          hide-details
          class="mb-3"
        />
      </template>

      <!-- Test button + result -->
      <div class="d-flex align-center ga-3 mb-4">
        <v-btn variant="tonal" :loading="testing" @click="onTest">
          {{ testing ? $t('aiSettings.testing') : $t('aiSettings.test') }}
        </v-btn>
        <v-alert
          v-if="testResult"
          :type="testResult.success ? 'success' : 'error'"
          variant="tonal"
          density="compact"
          class="py-1 flex-grow-1"
        >
          {{ testResult.success ? $t('aiSettings.testSuccess') : $t('aiSettings.testFailed') }}
          <div v-if="testResult.error" class="text-caption">{{ testResult.error }}</div>
          <div v-if="testResult.warning" class="text-caption">{{ testResult.warning }}</div>
        </v-alert>
      </div>

      <!-- Sampling params -->
      <div class="text-caption text-medium-emphasis mb-2">{{ $t('aiSettings.temperature') }}</div>
      <v-text-field
        v-model.number="aiForm.temperature"
        type="number"
        step="0.1"
        min="0"
        max="2"
        :label="$t('aiSettings.temperature')"
        variant="outlined"
        density="compact"
        hide-details
        class="mb-3"
      />
      <v-text-field
        v-model.number="aiForm.topP"
        type="number"
        step="0.05"
        min="0"
        max="1"
        :label="$t('aiSettings.topP')"
        variant="outlined"
        density="compact"
        hide-details
        class="mb-3"
      />
      <v-text-field
        v-model.number="aiForm.maxTokens"
        type="number"
        step="1"
        min="1"
        :label="$t('aiSettings.maxTokens')"
        variant="outlined"
        density="compact"
        hide-details
        class="mb-3"
      />
      <v-textarea
        v-model="aiForm.systemPrompt"
        :label="$t('aiSettings.systemPrompt')"
        variant="outlined"
        density="compact"
        hide-details
        rows="3"
        class="mb-4"
      />

      <!-- Save -->
      <div class="d-flex align-center ga-3">
        <v-btn color="primary" @click="onSave">{{ $t('aiSettings.save') }}</v-btn>
        <span v-if="saved" class="text-success text-body-2">{{ $t('aiSettings.saved') }}</span>
      </div>
    </v-card>
  </v-container>
</template>
