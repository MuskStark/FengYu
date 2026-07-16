<script setup lang="ts">
import { ref } from 'vue'
import type { FengYuClient } from '@infinia/plugin-sdk'
import { call, field } from '../rpc'

const props = defineProps<{ client: FengYuClient; locale: string }>()
const emit = defineEmits<{ (e: 'toast', msg: string): void }>()

interface Check { name: string; value: string; ok: boolean }
interface Detection { executable: string | null; pythonVersion: string | null; pipVersion: string | null; ok: boolean }

const detection = ref<Detection | null>(null)
const checks = ref<Check[]>([])
const loading = ref(false)

async function refresh() {
  loading.value = true
  try {
    const det = await call(props.client, 'python.detect', {})
    detection.value = det.success ? (field<Detection>(det, 'detection') ?? null) : null
    const doc = await call(props.client, 'doctor', {})
    checks.value = doc.success ? (field<Check[]>(doc, 'checks') ?? []) : []
    if (!doc.success) emit('toast', doc.summary)
  } finally {
    loading.value = false
  }
}
refresh()
</script>

<template>
  <v-card flat border>
    <v-card-text>
      <div class="d-flex align-center mb-4 gap-2">
        <v-chip v-if="detection?.ok" color="success">
          Python {{ detection.pythonVersion }} · pip {{ detection.pipVersion }}
        </v-chip>
        <v-chip v-else-if="detection" color="error">Python not detected (>=3.10 required)</v-chip>
        <v-spacer />
        <v-btn variant="outlined" :loading="loading" @click="refresh">Re-check</v-btn>
      </div>

      <v-table>
        <thead>
          <tr><th>Check</th><th>Value</th><th>Status</th></tr>
        </thead>
        <tbody>
          <tr v-for="c in checks" :key="c.name">
            <td>{{ c.name }}</td>
            <td>{{ c.value }}</td>
            <td>
              <v-chip :color="c.ok ? 'success' : 'error'" size="small">{{ c.ok ? 'OK' : 'FAIL' }}</v-chip>
            </td>
          </tr>
          <tr v-if="!checks.length">
            <td colspan="3" class="text-center text-medium-emphasis">No checks yet</td>
          </tr>
        </tbody>
      </v-table>
    </v-card-text>
  </v-card>
</template>

<style scoped>
.gap-2 { gap: 8px; }
</style>
