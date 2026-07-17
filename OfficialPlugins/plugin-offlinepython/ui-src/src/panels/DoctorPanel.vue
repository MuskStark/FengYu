<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { FengYuClient } from '@infinia/plugin-sdk'
import { call, field } from '../rpc'

type Translate = (key: string, ...args: (string | number)[]) => string

const props = defineProps<{ client: FengYuClient; t: Translate }>()
const emit = defineEmits<{ (e: 'toast', msg: string): void }>()

interface Check { name: string; value: string; ok: boolean }
interface Detection { executable: string | null; pythonVersion: string | null; pipVersion: string | null; ok: boolean }

const detection = ref<Detection | null>(null)
const checks = ref<Check[]>([])
const loading = ref(false)

function errorText(error: unknown): string {
  return error instanceof Error && error.message ? error.message : props.t('opb.common.error')
}

async function refresh() {
  loading.value = true
  try {
    const det = await call(props.client, 'python.detect', {})
    detection.value = det.success ? (field<Detection>(det, 'detection') ?? null) : null
    const doc = await call(props.client, 'doctor', {})
    checks.value = doc.success ? (field<Check[]>(doc, 'checks') ?? []) : []
    if (!doc.success) emit('toast', doc.summary)
  } catch (error) {
    emit('toast', errorText(error))
  } finally {
    loading.value = false
  }
}

onMounted(refresh)
</script>

<template>
  <v-card flat border>
    <v-card-text>
      <div class="d-flex align-center mb-4 gap-2">
        <v-chip v-if="detection?.ok" color="success">
          {{ t('opb.python.detected', detection.pythonVersion ?? '', detection.pipVersion ?? '') }}
        </v-chip>
        <v-chip v-else-if="detection" color="error">{{ t('opb.python.missing') }}</v-chip>
        <v-spacer />
        <v-btn variant="outlined" :loading="loading" :disabled="loading" @click="refresh">{{ t('opb.doctor.refresh') }}</v-btn>
      </div>

      <v-table>
        <thead>
          <tr>
            <th>{{ t('opb.doctor.check') }}</th>
            <th>{{ t('opb.doctor.value') }}</th>
            <th>{{ t('opb.doctor.status') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in checks" :key="c.name">
            <td>{{ c.name }}</td>
            <td>{{ c.value }}</td>
            <td>
              <v-chip :color="c.ok ? 'success' : 'error'" size="small">{{ c.ok ? t('opb.common.ok') : t('opb.common.fail') }}</v-chip>
            </td>
          </tr>
          <tr v-if="!checks.length">
            <td colspan="3" class="text-center text-medium-emphasis">{{ t('opb.doctor.noChecks') }}</td>
          </tr>
        </tbody>
      </v-table>
    </v-card-text>
  </v-card>
</template>

<style scoped>
.gap-2 { gap: 8px; }
</style>
