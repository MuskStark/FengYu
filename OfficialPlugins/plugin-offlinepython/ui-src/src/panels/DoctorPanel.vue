<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import type { FengYuClient } from '@infinia/plugin-sdk'
import { FyEmptyState, FyLoadingState, FyPageHeader } from '@infinia/plugin-ui'
import { mdiStethoscope } from '@mdi/js'
import { createPluginRpc } from '../rpc'

type Translate = (key: string, ...args: (string | number)[]) => string

const props = defineProps<{ client: FengYuClient; t: Translate }>()
const emit = defineEmits<{ (e: 'toast', msg: string): void }>()

// Typed RPC client generated from manifest rpc.methods.
const rpc = createPluginRpc(props.client)
const abortController = new AbortController()
const signal = () => abortController.signal

interface Check { id?: string; value?: string | null; ok?: boolean }
interface Detection { executable?: string | null; pythonVersion?: string | null; pipVersion?: string | null; ok?: boolean }

const detection = ref<Detection | null>(null)
const checks = ref<Check[]>([])
const loading = ref(false)

/** Worker returns locale-independent short labels for status-flavored values;
 *  translate them here. Data values (versions, paths) pass through unchanged. */
const VALUE_LABELS = new Set(['not_found', 'missing', 'supported', 'unsupported', 'reachable', 'unreachable'])

function checkName(id?: string): string {
  return id ? props.t(`opb.doctor.check.${id}`) : ''
}

function checkValue(c: Check): string {
  const v = c.value ?? ''
  if (VALUE_LABELS.has(v)) return props.t(`opb.doctor.value.${v}`)
  if (c.id === 'disk_space') return props.t('opb.doctor.value.gb_available', v)
  return v
}

function errorText(error: unknown): string {
  return error instanceof Error && error.message ? error.message : props.t('opb.common.error')
}

async function refresh() {
  loading.value = true
  try {
    const det = await rpc.pythonDetect({}, { signal: signal() })
    detection.value = det.success ? (det.detection ?? null) : null
    const doc = await rpc.doctor({}, { signal: signal() })
    checks.value = doc.success ? (doc.checks ?? []) : []
    if (!doc.success) emit('toast', doc.summary)
  } catch (error) {
    emit('toast', errorText(error))
  } finally {
    loading.value = false
  }
}

onMounted(refresh)
onUnmounted(() => abortController.abort())
</script>

<template>
  <FyPageHeader :title="t('opb.doctor.title')" :description="t('opb.doctor.description')">
    <template #actions>
      <v-btn variant="outlined" :loading="loading" :disabled="loading" @click="refresh">{{ t('opb.doctor.refresh') }}</v-btn>
    </template>
  </FyPageHeader>

  <section class="opb-surface">
    <div class="opb-surface__section opb-doctor__summary">
      <div>
        <h2 class="opb-section-heading">{{ t('opb.doctor.runtimeTitle') }}</h2>
        <p class="opb-section-copy">{{ t('opb.doctor.runtimeHint') }}</p>
      </div>
      <div class="opb-actions">
        <span v-if="detection?.ok" class="opb-status opb-status--success">
          {{ t('opb.python.detected', detection.pythonVersion ?? '', detection.pipVersion ?? '') }}
        </span>
        <span v-else-if="detection" class="opb-status opb-status--error">{{ t('opb.python.missing') }}</span>
        <span v-else class="opb-status">{{ t('opb.doctor.notChecked') }}</span>
      </div>
      <code v-if="detection?.executable" class="opb-doctor__path">{{ detection.executable }}</code>
    </div>

    <div class="opb-surface__section">
      <h2 class="opb-section-heading">{{ t('opb.doctor.checksTitle') }}</h2>
      <p class="opb-section-copy">{{ t('opb.doctor.checksHint') }}</p>

      <FyLoadingState v-if="loading && !checks.length" :label="t('opb.doctor.checking')" />
      <FyEmptyState
        v-else-if="!checks.length"
        :title="t('opb.doctor.noChecks')"
        :message="t('opb.doctor.emptyHint')"
        :icon="mdiStethoscope"
      />
      <div v-else class="opb-table-scroll">
        <v-table>
        <thead>
          <tr>
            <th>{{ t('opb.doctor.check') }}</th>
            <th>{{ t('opb.doctor.value') }}</th>
            <th>{{ t('opb.doctor.status') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in checks" :key="c.id">
            <td>{{ checkName(c.id) }}</td>
            <td>{{ checkValue(c) }}</td>
            <td>
              <span class="opb-status" :class="c.ok ? 'opb-status--success' : 'opb-status--error'">
                {{ c.ok ? t('opb.common.ok') : t('opb.common.fail') }}
              </span>
            </td>
          </tr>
        </tbody>
        </v-table>
      </div>
    </div>
  </section>
</template>

<style scoped>
.opb-doctor__summary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 4px 20px;
  align-items: start;
}

.opb-doctor__summary .opb-section-copy { margin-bottom: 0; }
.opb-doctor__path {
  grid-column: 1 / -1;
  margin-top: 8px;
  color: rgb(var(--v-theme-secondary));
  font-size: 0.75rem;
  overflow-wrap: anywhere;
}

@media (max-width: 600px) {
  .opb-doctor__summary { grid-template-columns: 1fr; }
  .opb-doctor__path { grid-column: 1; }
}
</style>
