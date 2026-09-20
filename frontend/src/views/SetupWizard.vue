<script setup lang="ts">
import { onMounted, computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useSetupStore } from '@/stores/setup'
import { useConnectionStore } from '@/stores/connection'
import { api } from '@/api/client'
import { waitForAppMode } from './setupRestartWait'

const router = useRouter()
const setup = useSetupStore()
const conn = useConnectionStore()
const { t, tm } = useI18n()

const step = ref<1 | 2 | 3>(1)
const restartMessage = ref('')
const restartFailed = ref(false)

const selectedMeta = computed(
  () => setup.types.find((t) => t.type === setup.selectedType) ?? null,
)
const canInitialize = computed(() => setup.testResult?.success === true)

onMounted(async () => {
  await setup.loadTypes()
  const h2 = setup.types.find((t) => t.type === 'h2')
  if (h2) setup.selectType('h2')
})

function chooseType(t: string) {
  setup.selectType(t)
  step.value = 2
}

function backToSelect() {
  step.value = 1
}

// Resolve a field's display label via i18n where a key exists, else fall back to the
// server-supplied label or the raw field name.
function fieldLabel(name: string): string {
  // tm (message map) lets us detect whether the key exists without vue-i18n warning on miss.
  const messages = tm('setup.fields') as Record<string, string> | undefined
  if (messages && messages[name]) return messages[name]
  const meta = selectedMeta.value?.fields.find((f) => f.name === name)
  return name === 'filePath' ? t('setup.dataFileLocation') : (meta?.label ?? name)
}

async function onTest() {
  await setup.testConnection()
}

async function onInitialize() {
  const ok = await setup.initialize()
  if (!ok) return
  step.value = 3
  restartMessage.value = t('setup.restarting')
  conn.setRestarting(true)
  // Wait out the backend restart (desktop supervisor respawns it into APP mode; the
  // deadline outlasts the shell's own boot patience — see setupRestartWait.ts).
  const outcome = await waitForAppMode({
    health: () => api.health(),
    setupStatus: () => api.getSetupStatus(),
  })
  conn.setRestarting(false)
  if (outcome === 'app') {
    router.replace('/')
  } else {
    restartFailed.value = true
    restartMessage.value = t('setup.restartTimeout')
  }
}

/** Timeout recovery: a full reload re-runs the boot probe against the live backend,
 * landing in the main shell when APP mode came up after the deadline (or back in the
 * wizard if the config was reset) instead of parking on this dead-end step. */
function reloadShell() {
  window.location.reload()
}
</script>

<template>
  <div class="cx-setup-wrap">
    <div class="cx-card" style="max-width: 560px; width: 100%; padding: 28px">
      <div style="font-size: 22px; font-weight: 650">{{ $t('setup.title', { brand: $t('brand') }) }}</div>
      <div class="cx-muted" style="margin: 4px 0 20px">{{ $t('setup.subtitle') }}</div>

      <!-- Step 1: choose type -->
      <div v-if="step === 1" class="cx-setup-grid">
        <div
          v-for="t in setup.types"
          :key="t.type"
          class="cx-card cx-card--hover"
          :class="{ 'cx-selected': setup.selectedType === t.type }"
          @click="chooseType(t.type)"
        >
          <div style="font-weight: 650">{{ t.label }}</div>
          <div class="cx-muted" style="font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em">
            {{ t.embedded ? $t('setup.local') : $t('setup.remote') }}
          </div>
        </div>
      </div>

      <!-- Step 2: configure + test -->
      <div v-else-if="step === 2">
        <button class="cx-btn cx-btn--text cx-btn--sm" @click="backToSelect">
          <i class="mdi mdi-arrow-left" />{{ $t('common.back') }}
        </button>
        <h2 style="font-size: 17px; font-weight: 650; margin: 8px 0 18px">
          {{ $t('setup.configureTitle', { label: selectedMeta?.label ?? '' }) }}
        </h2>

        <div v-for="f in selectedMeta?.fields ?? []" :key="f.name" class="cx-field" style="margin-bottom: 14px">
          <label class="cx-label">{{ fieldLabel(f.name) }}</label>
          <input
            class="cx-input"
            :type="f.secret ? 'password' : 'text'"
            :placeholder="f.name"
            :value="(setup.params as Record<string, unknown>)[f.name] as string"
            @input="(e) => ((setup.params as Record<string, unknown>)[f.name] = (e.target as HTMLInputElement).value)"
          />
        </div>

        <div style="display: flex; flex-direction: column; align-items: center; gap: 12px; margin: 20px 0">
          <button class="cx-btn cx-btn--tonal" :disabled="setup.testing" @click="onTest">
            <span v-if="setup.testing" class="cx-spin" />{{ $t('setup.testConnection') }}
          </button>
          <div
            v-if="setup.testResult"
            class="cx-row"
            :class="setup.testResult.success ? 'cx-ok' : 'cx-err'"
            style="font-size: 13px; text-align: center"
          >
            <i class="mdi sm" :class="setup.testResult.success ? 'mdi-check' : 'mdi-alert-circle-outline'" />
            {{ setup.testResult.success ? $t('setup.connected', { version: setup.testResult.serverVersion }) : setup.testResult.error }}
          </div>
          <button class="cx-btn cx-btn--primary" :disabled="!canInitialize" @click="onInitialize">{{ $t('setup.initialize') }}</button>
        </div>
      </div>

      <!-- Step 3: restart overlay -->
      <div v-else style="text-align: center; padding: 32px">
        <span class="cx-spin lg" style="margin-bottom: 16px" />
        <p>{{ restartMessage }}</p>
        <div v-if="restartFailed" class="cx-alert cx-alert--error" style="margin-top: 12px">
          <span class="cx-alert__body">{{ restartMessage }}</span>
        </div>
        <button v-if="restartFailed" class="cx-btn cx-btn--outline cx-btn--sm" @click="reloadShell">
          <i class="mdi mdi-refresh" />{{ t('auth.reload') }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cx-setup-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  width: 100%;
  padding: 24px;
  background: rgb(var(--v-theme-background));
}
.cx-setup-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}
.cx-selected { border-color: rgb(var(--v-theme-primary)) !important; }
.cx-ok { color: rgb(var(--v-theme-tertiary)); }
.cx-err { color: rgb(var(--v-theme-error)); }
</style>
