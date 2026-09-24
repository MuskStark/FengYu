<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useBackgroundTasksStore } from '@/stores/backgroundTasks'
import { usePluginBackgroundJobsStore, type PluginBackgroundJob } from '@/stores/pluginBackgroundJobs'
import { usePluginsStore } from '@/stores/plugins'

type IndicatorStatus = 'running' | 'queued' | 'unknown'
type IndicatorEntry = {
  key: string
  kind: 'plugin' | 'workflow'
  title: string
  detail: string
  status: IndicatorStatus
  pluginJob?: PluginBackgroundJob
}

const { t } = useI18n()
const router = useRouter()
const background = useBackgroundTasksStore()
const pluginJobs = usePluginBackgroundJobsStore()
const plugins = usePluginsStore()
const root = ref<HTMLElement | null>(null)
const panelOpen = ref(false)

const entries = computed<IndicatorEntry[]>(() => [
  ...pluginJobs.activeJobs.map((job) => ({
    key: `plugin:${job.key}`,
    kind: 'plugin' as const,
    title: plugins.byId(job.pluginId)?.name ?? job.pluginId,
    detail: humanizeMethod(job.startMethod),
    status: job.status,
    pluginJob: job,
  })),
  ...background.activeTasks.map((task) => ({
    key: `workflow:${task.taskId}`,
    kind: 'workflow' as const,
    title: task.description || t('backgroundIndicator.workflowTask'),
    detail: task.kind,
    status: task.status as 'running' | 'queued',
  })),
])

const total = computed(() => entries.value.length)
const runningCount = computed(() => entries.value.filter((entry) => entry.status === 'running').length)
const queuedCount = computed(() => entries.value.filter((entry) => entry.status === 'queued').length)
const unknownCount = computed(() => entries.value.filter((entry) => entry.status === 'unknown').length)

const summary = computed(() => {
  if (unknownCount.value > 0) {
    return t('backgroundIndicator.summaryUnknown', { count: total.value })
  }
  if (runningCount.value > 0 && queuedCount.value > 0) {
    return t('backgroundIndicator.summaryMixed', {
      running: runningCount.value,
      queued: queuedCount.value,
    })
  }
  if (queuedCount.value > 0) {
    return t('backgroundIndicator.summaryQueued', { count: queuedCount.value })
  }
  return t('backgroundIndicator.summaryRunning', { count: runningCount.value })
})

function humanizeMethod(method: string): string {
  return method.replace(/_start$/, '').replace(/_/g, ' ')
}

function statusLabel(status: IndicatorStatus): string {
  return t(`backgroundIndicator.status.${status}`)
}

function statusIcon(status: IndicatorStatus): string {
  if (status === 'queued') return 'mdi-clock-outline'
  if (status === 'unknown') return 'mdi-alert-outline'
  return 'mdi-loading mdi-spin'
}

function openEntry(entry: IndicatorEntry) {
  panelOpen.value = false
  if (entry.pluginJob) {
    void router.push({ name: 'plugin', params: { id: entry.pluginJob.pluginId } })
  } else {
    void router.push('/flows/new')
  }
}

function dismissUnknown(job: PluginBackgroundJob) {
  pluginJobs.remove(job.key)
}

function closeOnOutsideClick(event: PointerEvent) {
  if (!root.value?.contains(event.target as Node)) panelOpen.value = false
}

function closeOnEscape(event: KeyboardEvent) {
  if (event.key === 'Escape') panelOpen.value = false
}

onMounted(() => {
  background.start()
  pluginJobs.start()
  if (!plugins.plugins.length) void plugins.load()
  document.addEventListener('pointerdown', closeOnOutsideClick)
  document.addEventListener('keydown', closeOnEscape)
})

onBeforeUnmount(() => {
  background.stop()
  pluginJobs.stop()
  document.removeEventListener('pointerdown', closeOnOutsideClick)
  document.removeEventListener('keydown', closeOnEscape)
})

watch(total, (count) => {
  if (count === 0) panelOpen.value = false
})
</script>

<template>
  <div
    v-if="total"
    ref="root"
    class="background-execution"
    aria-live="polite"
  >
    <section
      v-if="panelOpen"
      class="background-execution__panel"
      role="dialog"
      :aria-label="t('backgroundIndicator.title')"
    >
      <div class="background-execution__panel-head">
        <span>
          <i class="mdi mdi-progress-wrench" aria-hidden="true" />
          {{ t('backgroundIndicator.title') }}
        </span>
        <button
          class="cx-iconbtn cx-iconbtn--sm"
          :aria-label="t('backgroundIndicator.close')"
          @click="panelOpen = false"
        ><i class="mdi mdi-close" /></button>
      </div>

      <div class="background-execution__list">
        <div v-for="entry in entries" :key="entry.key" class="background-execution__item">
          <button class="background-execution__item-main" @click="openEntry(entry)">
            <i class="mdi background-execution__item-icon" :class="statusIcon(entry.status)" aria-hidden="true" />
            <span class="background-execution__item-copy">
              <strong>{{ entry.title }}</strong>
              <small>{{ entry.detail }} · {{ statusLabel(entry.status) }}</small>
            </span>
            <i class="mdi mdi-chevron-right background-execution__chevron" aria-hidden="true" />
          </button>
          <button
            v-if="entry.status === 'unknown' && entry.pluginJob"
            class="cx-iconbtn cx-iconbtn--sm background-execution__dismiss"
            :title="t('backgroundIndicator.dismiss')"
            :aria-label="t('backgroundIndicator.dismiss')"
            @click="dismissUnknown(entry.pluginJob)"
          ><i class="mdi mdi-close" /></button>
        </div>
      </div>
    </section>

    <button
      type="button"
      class="background-execution__trigger"
      :class="{
        'background-execution__trigger--warn': queuedCount > 0,
        'background-execution__trigger--unknown': unknownCount > 0,
      }"
      :aria-expanded="panelOpen"
      :aria-label="summary"
      @click="panelOpen = !panelOpen"
    >
      <i
        class="mdi"
        :class="unknownCount ? 'mdi-alert-outline' : queuedCount && !runningCount ? 'mdi-clock-outline' : 'mdi-loading mdi-spin'"
        aria-hidden="true"
      />
      <span>{{ summary }}</span>
      <i class="mdi" :class="panelOpen ? 'mdi-chevron-down' : 'mdi-chevron-up'" aria-hidden="true" />
    </button>
  </div>
</template>

<style scoped>
.background-execution {
  position: fixed;
  z-index: 75;
  left: 50%;
  bottom: 18px;
  transform: translateX(-50%);
  color: rgb(var(--v-theme-on-surface));
}

.background-execution__trigger {
  min-height: 36px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0 12px;
  border: 1px solid color-mix(in srgb, rgb(var(--v-theme-primary)) 38%, var(--cx-border));
  border-radius: 999px;
  background: rgb(var(--v-theme-surface-container-high));
  box-shadow: 0 8px 24px rgba(0, 0, 0, .25);
  color: rgb(var(--v-theme-on-surface));
  font: inherit;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
}
.background-execution__trigger:hover { background: rgb(var(--v-theme-surface-container-highest)); }
.background-execution__trigger > .mdi:first-child { color: rgb(var(--v-theme-primary)); font-size: 16px; }
.background-execution__trigger--warn > .mdi:first-child { color: rgb(var(--v-theme-warning)); }
.background-execution__trigger--unknown > .mdi:first-child { color: rgb(var(--v-theme-error)); }

.background-execution__panel {
  position: absolute;
  left: 50%;
  bottom: calc(100% + 10px);
  width: min(420px, calc(100vw - 32px));
  max-height: min(420px, calc(100vh - 96px));
  transform: translateX(-50%);
  overflow: hidden;
  border: 1px solid var(--cx-border);
  border-radius: 13px;
  background: rgb(var(--v-theme-surface-container-high));
  box-shadow: 0 16px 42px rgba(0, 0, 0, .32);
}
.background-execution__panel-head {
  min-height: 44px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 8px 6px 13px;
  border-bottom: 1px solid var(--cx-border);
  font-size: 13px;
  font-weight: 650;
}
.background-execution__panel-head > span { display: inline-flex; align-items: center; gap: 7px; }
.background-execution__panel-head > span .mdi { color: rgb(var(--v-theme-primary)); font-size: 17px; }
.background-execution__list { max-height: 350px; overflow-y: auto; padding: 5px; }
.background-execution__item { display: flex; align-items: center; border-radius: 9px; }
.background-execution__item:hover { background: var(--cx-hover); }
.background-execution__item-main {
  min-width: 0;
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 8px;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.background-execution__item-icon { flex: 0 0 auto; color: rgb(var(--v-theme-primary)); font-size: 18px; }
.background-execution__item-copy { min-width: 0; flex: 1 1 auto; display: flex; flex-direction: column; gap: 2px; }
.background-execution__item-copy strong,
.background-execution__item-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.background-execution__item-copy strong { font-size: 12.5px; font-weight: 600; }
.background-execution__item-copy small { color: rgb(var(--v-theme-secondary)); font-size: 11px; }
.background-execution__chevron { flex: 0 0 auto; color: rgb(var(--v-theme-secondary)); }
.background-execution__dismiss { flex: 0 0 auto; margin-right: 5px; }

@media (max-width: 600px) {
  .background-execution { bottom: 12px; }
  .background-execution__trigger { max-width: calc(100vw - 28px); }
  .background-execution__trigger span { overflow: hidden; text-overflow: ellipsis; }
}

@media (prefers-reduced-motion: reduce) {
  .background-execution :deep(.mdi-spin) { animation: none; }
}
</style>
