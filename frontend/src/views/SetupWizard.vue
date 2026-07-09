<script setup lang="ts">
import { onMounted, computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSetupStore } from '@/stores/setup'
import { api } from '@/api/client'

const router = useRouter()
const setup = useSetupStore()

const step = ref<1 | 2 | 3>(1)
const restartMessage = ref('')
const restartFailed = ref(false)

const selectedMeta = computed(
  () => setup.types.find((t) => t.type === setup.selectedType) ?? null,
)
const canInitialize = computed(() => setup.testResult?.success === true)

onMounted(async () => {
  await setup.loadTypes()
  // Default-select H2 (first embedded / recommended)
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
  // Show restart overlay, poll health until backend is back.
  step.value = 3
  restartMessage.value = 'Configuration complete. Restarting backend…'
  await waitForRestart()
}

async function waitForRestart() {
  const deadline = Date.now() + 30_000
  let back = false
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 500))
    try {
      await api.health()
      // Health passed — confirm setup status is now initialized.
      const status = await api.getSetupStatus()
      if (status.initialized) {
        back = true
        break
      }
    } catch {
      // Backend still down — keep polling.
    }
  }
  if (back) {
    router.replace('/')
  } else {
    restartFailed.value = true
    restartMessage.value = 'Restart timed out. Please manually restart the application.'
  }
}
</script>

<template>
  <div class="setup-root">
    <div class="setup-card">
      <h1 class="setup-title">ZhiFlow Setup</h1>
      <p class="setup-subtitle">Choose how to store your data.</p>

      <!-- Step 1: choose type -->
      <div v-if="step === 1" class="step">
        <div class="type-grid">
          <button
            v-for="t in setup.types"
            :key="t.type"
            class="type-card"
            :class="{ active: setup.selectedType === t.type }"
            @click="chooseType(t.type)"
          >
            <span class="type-label">{{ t.label }}</span>
            <span class="type-tag">{{ t.embedded ? 'local' : 'remote' }}</span>
          </button>
        </div>
      </div>

      <!-- Step 2: configure + test -->
      <div v-else-if="step === 2" class="step">
        <button class="link-btn" @click="backToSelect">← Back</button>
        <h2 class="step-title">{{ selectedMeta?.label }} configuration</h2>

        <div v-for="f in selectedMeta?.fields ?? []" :key="f.name" class="form-row">
          <label class="form-label">{{ f.label ?? f.name }}</label>
          <input
            v-if="!f.secret"
            class="sk-input"
            v-model="(setup.params as Record<string, unknown>)[f.name] as string"
            :placeholder="f.name"
          />
          <input
            v-else
            type="password"
            class="sk-input"
            v-model="(setup.params as Record<string, unknown>)[f.name] as string"
            :placeholder="f.name"
          />
        </div>

        <div class="test-row">
          <button class="sk-btn" :disabled="setup.testing" @click="onTest">
            {{ setup.testing ? 'Testing…' : 'Test connection' }}
          </button>
          <span
            v-if="setup.testResult"
            class="test-result"
            :class="setup.testResult.success ? 'ok' : 'fail'"
          >
            {{ setup.testResult.success
              ? `✓ Connected (${setup.testResult.serverVersion})`
              : `✗ ${setup.testResult.error}` }}
          </span>
        </div>

        <button class="sk-btn primary" :disabled="!canInitialize" @click="onInitialize">
          Initialize
        </button>
      </div>

      <!-- Step 3: restart overlay -->
      <div v-else class="step restart-step">
        <div class="spinner" />
        <p>{{ restartMessage }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.setup-root {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  width: 100%;
  background: var(--sk-bg);
  padding: 24px;
}
.setup-card {
  max-width: 560px;
  width: 100%;
  background: var(--sk-surface, var(--sk-bg));
  border: 1px solid var(--sk-border);
  border-radius: 12px;
  padding: 32px;
}
.setup-title {
  margin: 0 0 4px;
  font-size: 24px;
}
.setup-subtitle {
  margin: 0 0 24px;
  color: var(--sk-text-dim, #888);
  font-size: 14px;
}
.type-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.type-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 18px;
  border: 1px solid var(--sk-border);
  border-radius: 8px;
  background: transparent;
  color: var(--sk-text);
  cursor: pointer;
  transition: border-color 0.15s;
}
.type-card:hover {
  border-color: var(--sk-accent, #4a9);
}
.type-card.active {
  border-color: var(--sk-accent, #4a9);
  background: var(--sk-surface-alt, rgba(68, 170, 153, 0.08));
}
.type-label {
  font-size: 15px;
  font-weight: 600;
}
.type-tag {
  font-size: 11px;
  text-transform: uppercase;
  color: var(--sk-text-dim, #888);
}
.step-title {
  margin: 12px 0 16px;
  font-size: 17px;
}
.form-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 14px;
}
.form-label {
  font-size: 13px;
  color: var(--sk-text-dim, #aaa);
}
.sk-input {
  padding: 8px 10px;
  border: 1px solid var(--sk-border);
  border-radius: 6px;
  background: var(--sk-bg);
  color: var(--sk-text);
  font-size: 14px;
}
.test-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 0 20px;
}
.test-result.ok {
  color: #4a9;
  font-size: 13px;
}
.test-result.fail {
  color: #e55;
  font-size: 13px;
}
.sk-btn {
  padding: 8px 16px;
  border: 1px solid var(--sk-border);
  border-radius: 6px;
  background: transparent;
  color: var(--sk-text);
  cursor: pointer;
  font-size: 14px;
}
.sk-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.sk-btn.primary {
  background: var(--sk-accent, #4a9);
  color: #fff;
  border-color: var(--sk-accent, #4a9);
}
.link-btn {
  background: none;
  border: none;
  color: var(--sk-text-dim, #888);
  cursor: pointer;
  font-size: 13px;
  padding: 0;
  margin-bottom: 8px;
}
.restart-step {
  text-align: center;
  padding: 40px 0;
}
.spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--sk-border);
  border-top-color: var(--sk-accent, #4a9);
  border-radius: 50%;
  margin: 0 auto 16px;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
