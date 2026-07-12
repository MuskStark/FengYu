<script setup lang="ts">
import { onMounted, computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSetupStore } from '@/stores/setup'
import { useConnectionStore } from '@/stores/connection'
import { api } from '@/api/client'

const router = useRouter()
const setup = useSetupStore()
const conn = useConnectionStore()

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

async function onTest() {
  await setup.testConnection()
}

async function onInitialize() {
  const ok = await setup.initialize()
  if (!ok) return
  step.value = 3
  restartMessage.value = 'Configuration complete. Restarting backend…'
  conn.setRestarting(true)
  await waitForRestart()
}

async function waitForRestart() {
  const deadline = Date.now() + 30_000
  let back = false
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 500))
    try {
      const h = await api.health()
      if (h.status !== 'ok') continue
      const status = await api.getSetupStatus()
      if (status.initialized) {
        back = true
        break
      }
    } catch {
      // Backend still down — keep polling.
    }
  }
  conn.setRestarting(false)
  if (back) {
    router.replace('/')
  } else {
    restartFailed.value = true
    restartMessage.value = 'Restart timed out. Please manually restart the application.'
  }
}
</script>

<template>
  <div class="cx-setup-wrap">
    <div class="cx-card" style="max-width: 560px; width: 100%; padding: 28px">
      <div style="font-size: 22px; font-weight: 650">Infinia Setup</div>
      <div class="cx-muted" style="margin: 4px 0 20px">Choose how to store your data.</div>

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
            {{ t.embedded ? 'local' : 'remote' }}
          </div>
        </div>
      </div>

      <!-- Step 2: configure + test -->
      <div v-else-if="step === 2">
        <button class="cx-btn cx-btn--text cx-btn--sm" @click="backToSelect">
          <i class="mdi mdi-arrow-left" />Back
        </button>
        <h2 style="font-size: 17px; font-weight: 650; margin: 8px 0 18px">{{ selectedMeta?.label }} configuration</h2>

        <div v-for="f in selectedMeta?.fields ?? []" :key="f.name" class="cx-field" style="margin-bottom: 14px">
          <label class="cx-label">{{ f.name === 'filePath' ? $t('setup.dataFileLocation') : (f.label ?? f.name) }}</label>
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
            <span v-if="setup.testing" class="cx-spin" />Test connection
          </button>
          <div
            v-if="setup.testResult"
            class="cx-row"
            :class="setup.testResult.success ? 'cx-ok' : 'cx-err'"
            style="font-size: 13px; text-align: center"
          >
            <i class="mdi sm" :class="setup.testResult.success ? 'mdi-check' : 'mdi-alert-circle-outline'" />
            {{ setup.testResult.success ? `Connected (${setup.testResult.serverVersion})` : setup.testResult.error }}
          </div>
          <button class="cx-btn cx-btn--primary" :disabled="!canInitialize" @click="onInitialize">Initialize</button>
        </div>
      </div>

      <!-- Step 3: restart overlay -->
      <div v-else style="text-align: center; padding: 32px">
        <span class="cx-spin lg" style="margin-bottom: 16px" />
        <p>{{ restartMessage }}</p>
        <div v-if="restartFailed" class="cx-alert cx-alert--error" style="margin-top: 12px">
          <span class="cx-alert__body">{{ restartMessage }}</span>
        </div>
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
