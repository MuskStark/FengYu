<script setup lang="ts">
/**
 * FyTaskTable — a read-only task list rendered with Vuetify's data table.
 *
 * - `tasks` is the list of {@link FyTaskRow} entries to render.
 * - Each status is conveyed with BOTH an MDI icon and a text label so the
 *   meaning does not rely on color alone.
 * - `page` and `itemsPerPage` are controlled (v-model) so the host can drive
 *   pagination.
 * - Loading, error, and empty states are surfaced through slots; the default
 *   empty state delegates to {@link FyEmptyState}.
 *
 * `VDataTable` graduated from the Vuetify labs bundle into the main component
 * set in Vuetify 3.12, so it is available as the globally-registered
 * `v-data-table` and is also imported explicitly below for the render cells.
 */
import { computed, h } from 'vue'
import { VDataTable } from 'vuetify/components'
import {
  mdiAlertCircleOutline,
  mdiCancel,
  mdiCheckCircleOutline,
  mdiClockOutline,
  mdiProgressClock,
} from '@mdi/js'
import FyIcon from './FyIcon.vue'
import FyEmptyState from './FyEmptyState.vue'
import FyErrorState from './FyErrorState.vue'
import FyLoadingState from './FyLoadingState.vue'

export interface FyTaskRow {
  id: string
  name: string
  status: 'queued' | 'running' | 'success' | 'error' | 'cancelled'
  detail?: string
}

export type FyTaskStatus = FyTaskRow['status']

const props = withDefaults(
  defineProps<{
    tasks: FyTaskRow[]
    page?: number
    itemsPerPage?: number
    loading?: boolean
    error?: string
    headers?: { title: string; key: string; sortable?: boolean; align?: 'start' | 'center' | 'end' }[]
  }>(),
  {
    page: 1,
    itemsPerPage: 10,
    loading: false,
    error: '',
    headers: undefined,
  },
)

const emit = defineEmits<{
  (event: 'update:page', value: number): void
  (event: 'update:itemsPerPage', value: number): void
}>()

const STATUS_META: Record<FyTaskStatus, { icon: string; label: string }> = {
  queued: { icon: mdiClockOutline, label: 'Queued' },
  running: { icon: mdiProgressClock, label: 'Running' },
  success: { icon: mdiCheckCircleOutline, label: 'Success' },
  error: { icon: mdiAlertCircleOutline, label: 'Error' },
  cancelled: { icon: mdiCancel, label: 'Cancelled' },
}

const resolvedHeaders = computed(
  () =>
    props.headers ?? [
      { title: 'Task', key: 'name', sortable: true, align: 'start' as const },
      { title: 'Status', key: 'status', sortable: true, align: 'start' as const },
      { title: 'Detail', key: 'detail', sortable: false, align: 'start' as const },
    ],
)

function onPage(value: number): void {
  emit('update:page', value)
}

function onItemsPerPage(value: number): void {
  emit('update:itemsPerPage', value)
}

/**
 * Status cell renderer: icon + visible text label. Built as a render function
 * so it can be attached to the data-table column via the `headers` `cell`
 * slot without a separate `<template>` per status.
 */
function StatusBadge({ status }: { status: FyTaskStatus }) {
  const meta = STATUS_META[status] ?? STATUS_META.queued
  return h('span', { class: ['fy-task-table__status'], 'data-status': status }, [
    h(FyIcon, { path: meta.icon, size: 16 }),
    h('span', meta.label),
  ])
}
</script>

<template>
  <div class="fy-task-table">
    <FyErrorState v-if="error" title="Could not load tasks" :message="error" />

    <FyLoadingState v-else-if="loading && tasks.length === 0" label="Loading tasks" />

    <FyEmptyState v-else-if="tasks.length === 0" title="No tasks yet" message="Tasks will appear here once they start." />

    <VDataTable
      v-else
      :items="tasks"
      :headers="resolvedHeaders"
      :page="page"
      :items-per-page="itemsPerPage"
      :loading="loading"
      hover
      @update:page="onPage"
      @update:items-per-page="onItemsPerPage"
    >
      <template #item.status="{ item }">
        <StatusBadge :status="(item as FyTaskRow).status" />
      </template>
      <template #item.detail="{ item }">
        <span class="text-body-2 opacity-70">{{ (item as FyTaskRow).detail ?? '—' }}</span>
      </template>
      <template #no-data>
        <FyEmptyState title="No tasks yet" message="Tasks will appear here once they start." />
      </template>
    </VDataTable>
  </div>
</template>

<style scoped>
.fy-task-table__status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 24px;
  padding: 2px 8px;
  color: rgb(var(--v-theme-secondary));
  background: rgb(var(--v-theme-surface-container-high));
  border-radius: 999px;
  font-size: 0.75rem;
}
.fy-task-table__status[data-status='running'] { color: rgb(var(--v-theme-on-surface)); }
.fy-task-table__status[data-status='success'] { color: rgb(var(--v-theme-tertiary)); }
.fy-task-table__status[data-status='error'] { color: rgb(var(--v-theme-error)); }
</style>
