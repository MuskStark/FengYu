<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  EdgeLabelRenderer,
  getBezierPath,
  useVueFlow,
  type EdgeProps,
} from '@vue-flow/core'

/**
 * 1:1 Vue port of Flowise's AgentFlowEdge: a bezier edge stroked with a
 * source→target color gradient, a transparent 15px hit path, and a 12px
 * gradient delete button that only appears while the edge is hovered.
 * Colors come from the endpoint nodes' Flowise data.color (defaults
 * #ae53ba → #2a8af6, exactly as in the source).
 */
const props = defineProps<EdgeProps>()
const emit = defineEmits<{ delete: [id: string] }>()

const { t } = useI18n()
const { findNode } = useVueFlow()
const isHovered = ref(false)

function nodeColor(id: string | null | undefined, fallback: string): string {
  if (!id) return fallback
  const data = findNode(id)?.data as { color?: string } | undefined
  return data?.color || fallback
}

const sourceColor = computed(() => nodeColor(props.source, '#ae53ba'))
const targetColor = computed(() => nodeColor(props.target, '#2a8af6'))
const gradientId = computed(() => `edge-gradient-${props.id}`)

/**
 * Branch label of a control-flow edge (drawn from flow_if's true/false port): the
 * declared port title of the source node, resolved live so renames track.
 */
const branchLabel = computed(() => {
  if (!props.sourceHandleId) return null
  const data = findNode(props.source)?.data as {
    descriptor?: { outputs?: Array<{ name: string; title?: string }> },
  } | undefined
  const port = data?.descriptor?.outputs?.find((output) => output.name === props.sourceHandleId)
  return port?.title || props.sourceHandleId
})

// Separate computeds (NOT a one-time destructure): the path/label positions must
// track the endpoint nodes while they are dragged.
function bezierPath(): ReturnType<typeof getBezierPath> {
  // The source nudges equal coordinates by 0.0001 so straight lines still
  // render the gradient — mirrored here.
  const xEqual = props.sourceX === props.targetX
  const yEqual = props.sourceY === props.targetY
  return getBezierPath({
    sourceX: xEqual ? props.sourceX + 0.0001 : props.sourceX,
    sourceY: yEqual ? props.sourceY + 0.0001 : props.sourceY,
    sourcePosition: props.sourcePosition,
    targetX: props.targetX,
    targetY: props.targetY,
    targetPosition: props.targetPosition,
  })
}

const path = computed(() => bezierPath()[0])
const labelX = computed(() => bezierPath()[1])
const labelY = computed(() => bezierPath()[2])

function onDeleteClick(event: MouseEvent) {
  event.stopPropagation()
  emit('delete', props.id)
}
</script>

<template>
  <defs>
    <linearGradient :id="gradientId">
      <stop offset="0%" :stop-color="sourceColor" />
      <stop offset="100%" :stop-color="targetColor" />
    </linearGradient>
  </defs>
  <!-- transparent 15px hit path -->
  <path
    :d="path"
    class="af-edge-hit"
    @mouseenter="isHovered = true"
    @mouseleave="isHovered = false"
  />
  <path
    :id="id"
    class="af-edge"
    :class="{ selected }"
    :style="{ stroke: `url(#${gradientId})` }"
    :d="path"
    :marker-end="markerEnd as string | undefined"
    @mouseenter="isHovered = true"
    @mouseleave="isHovered = false"
  />
  <EdgeLabelRenderer v-if="isHovered">
    <button
      class="af-edge-delete"
      :style="{
        transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
        background: `linear-gradient(to right, ${sourceColor}, ${targetColor})`,
      }"
      :aria-label="t('flows.deleteEdge')"
      @click="onDeleteClick"
      @mouseenter="isHovered = true"
      @mouseleave="isHovered = false"
    ><i class="mdi mdi-close" /></button>
  </EdgeLabelRenderer>
  <!-- Branch chip (control-flow edges): always visible, nudged off the midpoint so
       it never sits under the hover delete button. -->
  <EdgeLabelRenderer v-if="branchLabel">
    <span
      class="af-edge-branch"
      :style="{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY - 14}px)` }"
    >{{ branchLabel }}</span>
  </EdgeLabelRenderer>
</template>

<style scoped>
.af-edge-hit {
  stroke: transparent;
  stroke-width: 15;
  fill: none;
  cursor: pointer;
}

.af-edge {
  stroke-width: 2;
  fill: none;
  cursor: pointer;
  opacity: 0.75;
}

.af-edge.selected {
  stroke-width: 3;
  filter: drop-shadow(0 0 3px rgba(0, 0, 0, 0.3));
  opacity: 1;
}

/* AgentFlowEdge's hover delete: 12px gradient circle, scales to 1.2 on hover */
.af-edge-delete {
  position: absolute;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 12px;
  height: 12px;
  padding: 2px;
  color: #fff;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  font-size: 10px;
  box-shadow: 0 0 4px rgba(0, 0, 0, 0.3);
  transition: all 0.2s ease-in-out;
  pointer-events: all;
}

.af-edge-delete i {
  font-size: 12px;
  line-height: 1;
}

.af-edge-delete:hover {
  transform: scale(1.2);
  box-shadow: 0 0 8px rgba(0, 0, 0, 0.4);
}

/* Branch chip: a small surface-colored tag naming the branch an edge carries. */
.af-edge-branch {
  position: absolute;
  z-index: 1;
  padding: 1px 6px;
  color: rgba(var(--v-theme-on-surface), .8);
  background: rgb(var(--v-theme-surface));
  border: 1px solid rgba(var(--v-theme-on-surface), .16);
  border-radius: 999px;
  font-size: 9.5px;
  white-space: nowrap;
  pointer-events: none;
}
</style>
