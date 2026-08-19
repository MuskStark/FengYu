<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useTheme } from 'vuetify'
import { Handle, Position, type NodeProps } from '@vue-flow/core'
import {
  flowTypeColor,
  missingRequiredNodeInputs,
  workflowInputSummaries,
  workflowNodeColor,
  workflowNodeTitle,
  workflowToolCategory,
  type WorkflowNodeData,
} from './workflow'
import type { FlowNodeOutput } from '@/api/types'

/**
 * 1:1 Vue port of Flowise's AgentFlowNode (packages/agentflow/src/features/canvas/).
 * Every value below traces to source: the card mirrors CardWrapper (radius 8 /
 * padding 12 / width max-content) tinted by useNodeColors' MUI formulas
 * (dark: darken(color, 0.8), hover 0.7; border alpha .5 / hover .8 / selected 1),
 * the input handle is NodeInputHandle's 5×20 color bar, the output handle is
 * NodeOutputHandles' hover-revealed chevron circle, and the icon badge is
 * NodeIcon's 40px rounded square (radius 15) filled with the node color.
 *
 * Descriptor v2 additions: output handles are tinted by their declared data type
 * and carry a tooltip (type + description + example), the card shows the author's
 * custom title, run status renders as a badge (running/success/failed), and pinned
 * nodes carry a pin marker.
 */
const props = defineProps<NodeProps<WorkflowNodeData> & { runStatus?: string | null }>()
const emit = defineEmits<{ 'open-editor': [] }>()

const theme = useTheme()
const { t } = useI18n()
const isDark = computed(() => theme.current.value.dark)
const isHovered = ref(false)
const nodeColor = computed(() => props.data.descriptor?.color
  || props.data.color
  || workflowNodeColor(props.data.tool)
  || '#666666')
const nodeLabel = computed(() => workflowNodeTitle({ data: props.data }))
const nodeSubtitle = computed(() => props.data.tool.pluginId || 'FengYu')
/** Named output ports from the declaration; single/default port when undeclared. */
const outputPorts = computed(() => props.data.descriptor?.outputs?.length
  ? props.data.descriptor.outputs
  : null)
const missingRequired = computed(() =>
  missingRequiredNodeInputs({ data: props.data }))
const inputs = computed(() =>
  workflowInputSummaries(props.data.tool.inputSchema, props.data.argsText))
const configuredCount = computed(() =>
  inputs.value.filter((field) => field.configured).length)

// ── MUI color math (verbatim semantics from @mui/material/styles) ───────────
function hexToRgb(hex: string): [number, number, number] {
  const value = hex.replace('#', '')
  const full = value.length === 3 ? value.split('').map((c) => c + c).join('') : value
  const num = parseInt(full, 16)
  return [(num >> 16) & 255, (num >> 8) & 255, num & 255]
}

/** MUI darken(color, k): channel * (1 - k). */
function darken(hex: string, coefficient: number): string {
  const [r, g, b] = hexToRgb(hex)
  const f = 1 - coefficient
  return `rgb(${Math.round(r * f)}, ${Math.round(g * f)}, ${Math.round(b * f)})`
}

/** MUI alpha(color, a). */
function alpha(hex: string, value: number): string {
  const [r, g, b] = hexToRgb(hex)
  return `rgba(${r}, ${g}, ${b}, ${value})`
}

/** MUI lighten(color, k): channel + (255 - channel) * k. */
function lighten(hex: string, coefficient: number): string {
  const [r, g, b] = hexToRgb(hex)
  return `rgb(${Math.round(r + (255 - r) * coefficient)}, ${Math.round(g + (255 - g) * coefficient)}, ${Math.round(b + (255 - b) * coefficient)})`
}

// ── useNodeColors: stateColor drives border + selected ring ──────────────────
const stateColor = computed(() => {
  if (props.selected) return nodeColor.value
  if (isHovered.value) return alpha(nodeColor.value, 0.8)
  return alpha(nodeColor.value, 0.5)
})
/** useNodeColors: dark tints the color, light washes it — following the app theme. */
const backgroundColor = computed(() => isDark.value
  ? darken(nodeColor.value, isHovered.value ? 0.7 : 0.8)
  : lighten(nodeColor.value, isHovered.value ? 0.8 : 0.9))

const cardStyle = computed(() => ({
  backgroundColor: backgroundColor.value,
  borderColor: props.data.available === false ? '#f44336' : stateColor.value,
  borderWidth: '1px',
  boxShadow: props.selected ? `0 0 0 1px ${stateColor.value}` : 'none',
}))

const icon = computed(() => props.data.descriptor?.icon || (() => {
  const category = workflowToolCategory(props.data.tool)
  const icons: Record<string, string> = {
    browser: 'mdi-web',
    email: 'mdi-email-outline',
    excel: 'mdi-table',
    python: 'mdi-language-python',
    skills: 'mdi-lightbulb-outline',
    content: 'mdi-text-box-outline',
    other: 'mdi-cog-outline',
  }
  return icons[category] ?? 'mdi-cog-outline'
})())

// ── descriptor v2: typed ports + tooltips + run/pin badges ──────────────────
function portType(port: FlowNodeOutput): string {
  return port.type ?? 'any'
}

function firstExample(port: FlowNodeOutput): string | null {
  const example = port.examples?.[0]
  if (example === undefined || example === null) return null
  const text = typeof example === 'string' ? example : JSON.stringify(example)
  return text.length > 60 ? `${text.slice(0, 57)}…` : text
}

function portTooltip(port: FlowNodeOutput): string {
  const parts = [`${port.title || port.name} · ${t(`agent.flowType.${portType(port)}`)}`]
  if (port.description ?? port.help) parts.push(port.description ?? port.help!)
  const example = firstExample(port)
  if (example) parts.push(`${t('agent.exampleLabel')}: ${example}`)
  return parts.join('\n')
}

const runBadge = computed(() => {
  switch (props.runStatus) {
    case 'running': return { icon: 'mdi-loading', cls: 'afn__run--running' }
    case 'complete': return { icon: 'mdi-check-circle', cls: 'afn__run--complete' }
    case 'failed': return { icon: 'mdi-close-circle', cls: 'afn__run--failed' }
    default: return null
  }
})
</script>

<template>
  <div
    class="afn"
    @mouseenter="isHovered = true"
    @mouseleave="isHovered = false"
    @dblclick="emit('open-editor')"
  >
    <div class="afn__card" :style="cardStyle">
      <!-- NodeWarningIndicator (restored): 22px white circle, orange alert, top-left -10 -->
      <span
        v-if="missingRequired.length || !data.available"
        class="afn__warn"
        :title="data.available
          ? t('agent.requiredInputsMissing', { count: missingRequired.length })
          : t('agent.toolUnavailableShort')"
      ><i class="mdi mdi-alert-circle" /></span>

      <!-- Run status badge (top-right) + pinned marker: canvas-level execution feedback -->
      <span v-if="runBadge" class="afn__run" :class="runBadge.cls"><i class="mdi" :class="runBadge.icon" /></span>
      <span v-if="data.pinnedOutput !== undefined" class="afn__pin" :title="t('agent.pinnedResultTitle')"><i class="mdi mdi-pin" /></span>

      <div class="afn__inner">
        <!-- Input handle: a small node-colored dot. No inner content — children of a
             handle intercept vue-flow's elementFromPoint hit test and break drops. -->
        <Handle type="target" :position="Position.Left" class="afn__in" :connectable="connectable" />

        <div class="afn__row">
          <div class="afn__iconbox">
            <!-- NodeIcon colored branch: 40×40, radius 15px, filled with node color -->
            <div class="afn__badge" :style="{ backgroundColor: nodeColor }">
              <i class="mdi" :class="icon" />
            </div>
          </div>
          <div class="afn__meta">
            <div class="afn__label" :title="data.tool.localizedDescription || data.tool.description">
              {{ nodeLabel }}
            </div>
            <div class="afn__sub">{{ nodeSubtitle }} · {{ configuredCount }}/{{ inputs.length }}</div>
          </div>
        </div>

        <!-- Output ports: explicit declarations render NAMED handles (Flowise
             outputAnchors) tinted by their declared type (descriptor v2);
             undeclared nodes keep the single default dot. No inner content —
             children of a handle intercept vue-flow's elementFromPoint hit test
             and break drops. -->
        <template v-if="outputPorts">
          <div
            v-for="(port, portIndex) in outputPorts"
            :key="port.name"
            class="afn__port"
            :style="{ top: `${((portIndex + 1) / (outputPorts.length + 1)) * 100}%` }"
          >
            <span class="afn__port-label" :title="portTooltip(port)">{{ port.title || port.name }}</span>
            <Handle
              type="source"
              :id="port.name"
              :position="Position.Right"
              class="afn__out"
              :style="{ background: flowTypeColor(port.type) }"
              :connectable="connectable"
            />
          </div>
        </template>
        <Handle
          v-else
          type="source"
          :position="Position.Right"
          class="afn__out"
          :connectable="connectable"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.afn {
  font-family: inherit;
}

/* CardWrapper: radius 8, padding 12, width max-content, overflow visible */
.afn__card {
  position: relative;
  display: flex;
  align-items: center;
  width: max-content;
  min-width: 180px;
  height: auto;
  padding: 12px;
  border-style: solid;
  border-radius: 8px;
  color: rgb(var(--v-theme-on-surface));
  cursor: grab;
  overflow: visible;
}

.afn__card:active {
  cursor: grabbing;
}

.afn__inner {
  /* No positioning context — handles resolve against .afn__card so they sit on
     the card's edges, exactly like vue-flow's default offsetParent contract. */
  display: flex;
  flex-direction: column;
  width: 100%;
}

.afn__row {
  display: flex;
  flex-direction: row;
  align-items: center;
}

/* NODE_ICON_CONTAINER_WIDTH = 50 */
.afn__iconbox {
  width: 50px;
  flex: 0 0 auto;
}

.afn__badge {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 15px;
  color: #fff;
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.08) inset;
  cursor: grab;
}

.afn__badge i {
  font-size: 22px;
}

.afn__meta {
  display: flex;
  min-width: 0;
  flex-direction: column;
  padding-right: 8px;
}

/* AgentFlowNode Typography: 0.85rem / 500 */
.afn__label {
  max-width: 180px;
  overflow: hidden;
  font-size: 13.6px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.afn__sub {
  max-width: 180px;
  overflow: hidden;
  color: rgba(var(--v-theme-on-surface), .55); /* tokens text.secondary */
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.afn__port {
  position: absolute;
  right: -14px;
  display: flex;
  align-items: center;
  gap: 3px;
  transform: translateY(-50%);
}

.afn__port-label {
  color: rgba(var(--v-theme-on-surface), 0.75);
  font-size: 9px;
  white-space: nowrap;
  background: rgb(var(--v-theme-surface));
  border-radius: 6px;
  padding: 1px 5px;
  box-shadow: 0 0 0 1px rgba(var(--v-theme-on-surface), 0.12);
}

/* Input handle: a filled node-colored dot pinned to the card's left edge.
   Explicit left/top override vue-flow's centered .vue-flow__handle-left default
   (which landed mid-card and looked like a stray checkbox beside the icon). */
.afn__in {
  position: absolute;
  left: -6px;
  top: 50%;
  transform: translate(0, -50%);
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 2px solid rgb(var(--v-theme-surface));
  background: v-bind(nodeColor);
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.12);
  cursor: crosshair;
}

/* Output handle: a filled dot pinned to the card's right edge, the mirror of
   .afn__in. Descriptor v2 tints it by the port's declared data type (inline
   style), falling back to the node color for undeclared ports. */
.afn__out {
  position: absolute;
  left: auto;
  right: -6px;
  top: 50%;
  transform: translate(0, -50%);
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 2px solid rgb(var(--v-theme-surface));
  background: v-bind(nodeColor);
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.12);
  cursor: crosshair;
}

/* NodeWarningIndicator: 22px white circle, orange alert, top-left -10 */
.afn__warn {
  position: absolute;
  top: -10px;
  left: -10px;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #fff;
  color: orange;
  cursor: default;
  pointer-events: all;
}

.afn__warn i {
  font-size: 18px;
}

/* Run status badge (top-right) — mirrors the warning badge's geometry */
.afn__run {
  position: absolute;
  top: -10px;
  right: -10px;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #fff;
  cursor: default;
  pointer-events: all;
}

.afn__run i { font-size: 16px; }
.afn__run--running { color: rgb(var(--v-theme-primary)); }
.afn__run--running i { animation: afn-spin 1s linear infinite; }
.afn__run--complete { color: rgb(var(--v-theme-success)); }
.afn__run--failed { color: rgb(var(--v-theme-error)); }

@keyframes afn-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* Pinned-result marker (bottom-right): the node serves its authored value */
.afn__pin {
  position: absolute;
  right: -8px;
  bottom: -8px;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  color: #fff;
  background: rgb(var(--v-theme-warning));
  cursor: default;
  pointer-events: all;
}

.afn__pin i { font-size: 14px; }
</style>
