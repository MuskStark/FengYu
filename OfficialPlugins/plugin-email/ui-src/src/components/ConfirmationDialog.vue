<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Confirmation, SummaryRow } from '../stores/compose'

const props = defineProps<{ modelValue: boolean; confirmation?: Confirmation; busy?: boolean }>()
defineEmits<{ 'update:modelValue': [value: boolean]; approve: []; reject: [] }>()
const { t, te } = useI18n()

const rows = computed<SummaryRow[]>(() => props.confirmation?.summary ?? [])
// Rows whose `group` is empty are top-level meta (account/mode/messages/ignored/skipped); the rest
// cluster by group so each message becomes one collapsible panel. Order follows backend emission.
const metaRows = computed(() => rows.value.filter(row => !row.group))
const messageGroups = computed(() => {
  const order: string[] = []
  const buckets = new Map<string, SummaryRow[]>()
  for (const row of rows.value) {
    if (!row.group) continue
    if (!buckets.has(row.group)) { buckets.set(row.group, []); order.push(row.group) }
    buckets.get(row.group)!.push(row)
  }
  return order.map(key => ({ key, rows: buckets.get(key)! }))
})

/** Resolve a label key through i18n; fall back to the raw key if no translation is registered. */
function labelOf(key: string): string {
  const path = `conf.${key}`
  return te(path) ? t(path) : key
}
/** Mode values (ATTACHMENT_TAGS, …) are data; map them to a human label when a translation exists. */
function valueOf(row: SummaryRow): string {
  if (!row.value) return t('common.none')
  if (row.label === 'mode') {
    const path = `conf.mode_${row.value}`
    return te(path) ? t(path) : row.value
  }
  return row.value
}
function groupTitle(group: { key: string; rows: SummaryRow[] }): string {
  const toRow = group.rows.find(row => row.label === 'to')
  const count = toRow ? toRow.value.split(',').map(s => s.trim()).filter(Boolean).length : 0
  return count ? `${group.key} · ${t('confirmation.recipients', { count })}` : group.key
}
</script>

<template>
  <v-dialog :model-value="modelValue" max-width="760" persistent @update:model-value="$emit('update:modelValue', $event)">
    <v-card class="confirm-dialog">
      <v-card-title>{{ t('confirmation.title') }}</v-card-title>
      <v-card-text>
        <h4 v-if="metaRows.length" class="confirm-section-title">{{ t('confirmation.summary') }}</h4>
        <dl v-if="metaRows.length" class="confirm-meta detail">
          <template v-for="row in metaRows" :key="row.label">
            <dt>{{ labelOf(row.label) }}</dt>
            <dd>{{ valueOf(row) }}</dd>
          </template>
        </dl>
        <h4 v-if="messageGroups.length" class="confirm-section-title">{{ t('confirmation.messages') }}</h4>
        <v-expansion-panels v-if="messageGroups.length" multiple>
          <v-expansion-panel v-for="group in messageGroups" :key="group.key">
            <v-expansion-panel-title>{{ groupTitle(group) }}</v-expansion-panel-title>
            <v-expansion-panel-text>
              <dl class="confirm-fields detail">
                <template v-for="row in group.rows" :key="row.label">
                  <dt>{{ labelOf(row.label) }}</dt>
                  <dd>{{ valueOf(row) }}</dd>
                </template>
              </dl>
            </v-expansion-panel-text>
          </v-expansion-panel>
        </v-expansion-panels>
        <p class="hint">{{ t('confirmation.expires', { time: confirmation?.expiresAt ?? '' }) }}</p>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn data-testid="confirmation-reject" :disabled="busy" @click="$emit('reject')">{{ t('confirmation.reject') }}</v-btn>
        <v-btn data-testid="confirmation-approve" color="primary" :loading="busy" @click="$emit('approve')">{{ t('confirmation.approve') }}</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
