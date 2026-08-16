<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import type { AgentTool, WorkflowDefinition } from '@/api/types'
import { WORKFLOW_TEMPLATES, type WorkflowTemplate } from '@/components/agent/workflowTemplates'

/**
 * Flowise-style flow library: the landing page listing saved flows as cards,
 * plus entry points for a blank flow and the built-in templates.
 */
const { t } = useI18n()
const router = useRouter()

const workflows = ref<WorkflowDefinition[]>([])
const tools = ref<AgentTool[]>([])
const loading = ref(true)
const errorMsg = ref<string | null>(null)

const sorted = computed(() => [...workflows.value])

onMounted(async () => {
  try {
    [workflows.value, tools.value] = await Promise.all([api.workflows(), api.agentTools()])
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
  } finally {
    loading.value = false
  }
})

function openFlow(id: string) {
  void router.push(`/flows/${id}`)
}

function newFlow() {
  void router.push('/flows/new')
}

function applyTemplate(template: WorkflowTemplate) {
  void router.push({ path: '/flows/new', query: { template: template.id } })
}

/** Tools of one template the installation currently lacks (plugin missing or disabled). */
function templateMissingTools(template: WorkflowTemplate): string[] {
  return template.requiredTools.filter((name) => !tools.value.some((tool) => tool.name === name))
}

/** Saves the definition as a copy, then opens the copy in the builder. */
async function duplicateFlow(definition: WorkflowDefinition) {
  try {
    // Strip an existing "(copy)"-style suffix so duplicating a duplicate stays readable.
    const baseName = definition.name.replace(/\s*[（(][^)）]*[)）]\s*$/, '').trim()
    const copyName = `${baseName} (${t('agent.workflowCopySuffix')})`.slice(0, 160)
    const saved = await api.createWorkflow({
      name: copyName,
      description: definition.description,
      inputSchema: definition.inputSchema,
      plan: definition.plan,
      layout: definition.layout ?? undefined,
      graph: definition.graph ?? undefined,
    })
    workflows.value = [saved, ...workflows.value.filter((item) => item.id !== saved.id)]
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
  }
}

async function deleteFlow(definition: WorkflowDefinition) {
  if (!window.confirm(t('agent.deleteWorkflowConfirm'))) return
  try {
    await api.deleteWorkflow(definition.id)
    workflows.value = workflows.value.filter((item) => item.id !== definition.id)
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
  }
}
</script>

<template>
  <div class="cx-page flow-library">
    <header class="flow-library__head">
      <div>
        <h1 class="cx-page-title">{{ t('flows.title') }}</h1>
        <p class="cx-muted">{{ t('flows.subtitle') }}</p>
      </div>
      <button class="cx-btn cx-btn--primary" @click="newFlow"><i class="mdi mdi-plus" /> {{ t('flows.newFlow') }}</button>
    </header>

    <div v-if="errorMsg" class="cx-alert cx-alert--error">
      <span class="cx-alert__body">{{ errorMsg }}</span>
      <button class="cx-iconbtn cx-iconbtn--sm" @click="errorMsg = null"><i class="mdi mdi-close" /></button>
    </div>

    <div v-if="WORKFLOW_TEMPLATES.length" class="flow-library__section">
      <h2>{{ t('agent.templatesTitle') }}</h2>
      <div class="flow-cards">
        <button
          v-for="template in WORKFLOW_TEMPLATES"
          :key="template.id"
          class="flow-card flow-card--template"
          :disabled="!!templateMissingTools(template).length"
          :title="templateMissingTools(template).length
            ? t('agent.templateMissingTools', { names: templateMissingTools(template).join(', ') })
            : ''"
          @click="applyTemplate(template)"
        >
          <span class="flow-card__icon"><i class="mdi" :class="template.icon" /></span>
          <span class="flow-card__body">
            <strong>{{ t(template.titleKey) }}</strong>
            <small>{{ templateMissingTools(template).length
              ? t('agent.templateNeedsPlugins', { names: templateMissingTools(template).join(', ') })
              : t(template.descriptionKey) }}</small>
          </span>
          <i class="mdi mdi-arrow-right flow-card__go" />
        </button>
      </div>
    </div>

    <div class="flow-library__section">
      <h2>{{ t('flows.savedFlows') }}</h2>
      <div v-if="loading" class="cx-muted">{{ t('common.loading') }}…</div>
      <div v-else-if="!sorted.length" class="flow-library__empty">
        <i class="mdi mdi-vector-polyline" />
        <strong>{{ t('flows.emptyTitle') }}</strong>
        <span>{{ t('agent.newWorkflowHint') }}</span>
        <button class="cx-btn cx-btn--outline" @click="newFlow"><i class="mdi mdi-plus" /> {{ t('flows.newFlow') }}</button>
      </div>
      <div v-else class="flow-cards">
        <article
          v-for="definition in sorted"
          :key="definition.id"
          class="flow-card"
          @click="openFlow(definition.id)"
        >
          <span class="flow-card__icon"><i class="mdi mdi-vector-polyline" /></span>
          <span class="flow-card__body">
            <strong>{{ definition.name }}</strong>
            <small>{{ definition.description || t('agent.noDescription') }}</small>
            <small class="flow-card__meta">
              {{ definition.plan.steps.length }} {{ t('agent.nodes') }}
              · {{ new Date(definition.updatedAt).toLocaleString() }}
            </small>
          </span>
          <span class="cx-chip" :class="definition.published ? 'cx-chip--success' : ''">
            {{ definition.published ? t('agent.published') : t('agent.draft') }}
          </span>
          <span class="flow-card__actions" @click.stop>
            <button
              class="cx-iconbtn cx-iconbtn--sm"
              :title="t('agent.duplicateWorkflow')"
              @click="duplicateFlow(definition)"
            ><i class="mdi mdi-content-copy" /></button>
            <button
              class="cx-iconbtn cx-iconbtn--sm"
              :title="t('agent.deleteWorkflow')"
              @click="deleteFlow(definition)"
            ><i class="mdi mdi-delete-outline" /></button>
          </span>
        </article>
      </div>
    </div>
  </div>
</template>

<style scoped>
.flow-library { max-width: 1080px; }

.flow-library__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;
}

.flow-library__head p { margin: 4px 0 0; font-size: 13px; }

.flow-library__section { margin-bottom: 26px; }
.flow-library__section h2 {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: .06em;
  color: rgba(var(--v-theme-on-surface), .6);
}

.flow-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 10px;
}

.flow-card {
  position: relative;
  display: flex;
  gap: 11px;
  align-items: flex-start;
  width: 100%;
  padding: 14px;
  text-align: left;
  color: inherit;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 12px;
  background: rgb(var(--v-theme-surface));
  cursor: pointer;
}

.flow-card:hover { border-color: rgb(var(--v-theme-primary)); }
.flow-card:disabled { opacity: .55; cursor: not-allowed; }

.flow-card--template { border-style: dashed; }

.flow-card__icon {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 36px;
  height: 36px;
  color: rgb(var(--v-theme-primary));
  border-radius: 10px;
  background: rgba(var(--v-theme-primary), .12);
}

.flow-card__icon i { font-size: 19px; }
.flow-card__body { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 3px; }
.flow-card__body strong { overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.flow-card__body small { overflow: hidden; color: rgba(var(--v-theme-on-surface), .58); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.flow-card__meta { color: rgba(var(--v-theme-on-surface), .45); font-size: 10px; }
.flow-card__go { align-self: center; color: rgba(var(--v-theme-on-surface), .35); }

.flow-card .cx-chip { align-self: flex-start; }

.flow-card__actions {
  position: absolute;
  right: 10px;
  bottom: 10px;
  display: flex;
  gap: 2px;
}

.flow-card__body { padding-right: 4px; }

.flow-library__empty {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  padding: 42px 16px;
  color: rgba(var(--v-theme-on-surface), .6);
  border: 1px dashed rgb(var(--v-theme-outline-variant));
  border-radius: 12px;
  text-align: center;
}

.flow-library__empty i { font-size: 30px; opacity: .5; }
.flow-library__empty strong { color: rgb(var(--v-theme-on-surface)); font-size: 14px; }
.flow-library__empty .cx-btn { margin-top: 6px; }
</style>
