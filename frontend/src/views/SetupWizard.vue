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
  <div class="d-flex align-center justify-center h-100 pa-6">
    <v-card max-width="560" width="100%" rounded="lg" class="pa-6">
      <v-card-title class="text-h5">ZhiFlow Setup</v-card-title>
      <v-card-subtitle class="mb-4">Choose how to store your data.</v-card-subtitle>

      <!-- Step 1: choose type -->
      <div v-if="step === 1">
        <v-row>
          <v-col v-for="t in setup.types" :key="t.type" cols="12" sm="6">
            <v-card
              variant="outlined"
              rounded="lg"
              class="pa-4 h-100"
              :color="setup.selectedType === t.type ? 'primary' : undefined"
              :class="{ 'border-primary': setup.selectedType === t.type }"
              @click="chooseType(t.type)"
            >
              <div class="text-body-1 font-weight-bold">{{ t.label }}</div>
              <div class="text-caption text-uppercase text-medium-emphasis">
                {{ t.embedded ? 'local' : 'remote' }}
              </div>
            </v-card>
          </v-col>
        </v-row>
      </div>

      <!-- Step 2: configure + test -->
      <div v-else-if="step === 2">
        <v-btn variant="text" prepend-icon="mdi-arrow-left" size="small" @click="backToSelect">
          Back
        </v-btn>
        <h2 class="text-h6 mt-2 mb-4">{{ selectedMeta?.label }} configuration</h2>

        <div v-for="f in selectedMeta?.fields ?? []" :key="f.name" class="mb-3">
          <v-text-field
            :label="f.name === 'filePath' ? $t('setup.dataFileLocation') : (f.label ?? f.name)"
            :type="f.secret ? 'password' : 'text'"
            :placeholder="f.name"
            variant="outlined"
            density="compact"
            hide-details
            :model-value="(setup.params as Record<string, unknown>)[f.name] as string"
            @update:model-value="(v: string) => ((setup.params as Record<string, unknown>)[f.name] = v)"
          />
        </div>

        <div class="d-flex flex-column align-center my-4">
          <v-btn variant="tonal" :loading="setup.testing" @click="onTest">
            Test connection
          </v-btn>
          <div
            v-if="setup.testResult"
            :class="setup.testResult.success ? 'text-success' : 'text-error'"
            class="text-body-2 text-center mt-2"
          >
            <v-icon size="small" :icon="setup.testResult.success ? 'mdi-check' : 'mdi-alert-circle-outline'" />
            {{ setup.testResult.success
              ? `Connected (${setup.testResult.serverVersion})`
              : setup.testResult.error }}
          </div>
          <v-btn
            color="primary"
            class="mt-4"
            :disabled="!canInitialize"
            @click="onInitialize"
          >
            Initialize
          </v-btn>
        </div>
      </div>

      <!-- Step 3: restart overlay -->
      <div v-else class="text-center pa-8">
        <v-progress-circular indeterminate color="primary" size="40" class="mb-4" />
        <p class="text-body-1">{{ restartMessage }}</p>
        <v-alert v-if="restartFailed" type="warning" variant="tonal" class="mt-3">
          {{ restartMessage }}
        </v-alert>
      </div>
    </v-card>
  </div>
</template>
