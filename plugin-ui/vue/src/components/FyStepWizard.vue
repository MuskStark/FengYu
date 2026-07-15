<script setup lang="ts">
/**
 * FyStepWizard — a linear, value-keyed multi-step controller.
 *
 * - `steps` is an ordered list of `{ value, title, optional? }` entries.
 * - `modelValue` is the current step value (v-model). When omitted it defaults
 *   to the first step's value.
 * - `canContinue(from, to)` may be sync or async; it gates each forward
 *   transition. Returning `false` (or a promise that resolves `false`) emits
 *   `blocked` and leaves the wizard on the current step.
 * - Forward navigation from the final step emits `complete` instead of
 *   advancing.
 * - Back navigation (`data-action="back"`) moves to the previous step without
 *   consulting `canContinue` and is hidden on the first step.
 * - Each step renders its content through a named slot keyed by the step value
 *   (e.g. `<template #file>…</template>`).
 *
 * The header is built from core Vuetify primitives (`v-list`, `v-avatar`,
 * `v-divider`) so the component depends only on the globally registered
 * component set, not on the labs `v-stepper`.
 */
import { computed, ref, watch } from 'vue'

export interface FyWizardStep {
  value: string
  title: string
  optional?: boolean
}

const props = withDefaults(
  defineProps<{
    steps: FyWizardStep[]
    modelValue?: string
    canContinue?: (from: string, to: string) => boolean | Promise<boolean>
  }>(),
  {
    modelValue: undefined,
    canContinue: undefined,
  },
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: string): void
  (event: 'blocked', reason?: unknown): void
  (event: 'complete'): void
}>()

/**
 * Resolved current step value: the v-model when set, else the first step.
 *
 * The wizard keeps local state synced to the prop so internal navigation
 * works even when the host does not bind the v-model (uncontrolled use). The
 * host still receives `update:modelValue` on every move; a controlled host
 * wins because its prop change overrides the local copy via the watcher.
 */
const internalStep = ref(props.modelValue ?? props.steps[0]?.value)

watch(
  () => props.modelValue,
  (value) => {
    if (value !== undefined) internalStep.value = value
  },
)

function moveTo(value: string): void {
  internalStep.value = value
  emit('update:modelValue', value)
}

const current = computed(() => internalStep.value)

const currentIndex = computed(() => props.steps.findIndex((s) => s.value === current.value))
const activeStep = computed(() => props.steps[currentIndex.value])

const isLast = computed(() => currentIndex.value === props.steps.length - 1)
const isFirst = computed(() => currentIndex.value <= 0)

/** Guards against overlapping async canContinue checks. */
const resolving = ref(false)

async function next(): Promise<void> {
  if (resolving.value) return
  if (isLast.value) {
    emit('complete')
    return
  }
  const target = props.steps[currentIndex.value + 1]
  if (!target) return
  try {
    resolving.value = true
    const ok = props.canContinue ? await props.canContinue(current.value, target.value) : true
    if (ok) {
      moveTo(target.value)
    } else {
      emit('blocked')
    }
  } catch (error) {
    emit('blocked', error)
  } finally {
    resolving.value = false
  }
}

function back(): void {
  if (isFirst.value) return
  const target = props.steps[currentIndex.value - 1]
  if (!target) return
  moveTo(target.value)
}

/** Jump directly to an earlier (already-visited) step. */
function jump(index: number): void {
  if (index < 0 || index >= props.steps.length) return
  if (index <= currentIndex.value) moveTo(props.steps[index].value)
}

/** Dynamic slot name for the active step content. */
const activeSlot = computed(() => activeStep.value?.value)
</script>

<template>
  <div class="fy-step-wizard" data-step-wizard>
    <ol class="fy-step-wizard__header d-flex align-center ga-2 flex-wrap pa-2" aria-label="Progress">
      <template v-for="(step, index) in steps" :key="step.value">
        <li>
          <button
            type="button"
            class="fy-step-wizard__marker d-inline-flex align-center ga-2 pa-1"
            :class="{ 'fy-step-wizard__marker--active': index === currentIndex }"
            :aria-current="index === currentIndex ? 'step' : undefined"
            :disabled="index > currentIndex"
            @click="jump(index)"
          >
            <v-avatar
              size="24"
              :color="index < currentIndex ? 'success' : index === currentIndex ? 'primary' : 'surface-variant'"
            >
              <v-icon v-if="index < currentIndex" size="16" icon="mdi-check" />
              <span v-else class="text-caption">{{ index + 1 }}</span>
            </v-avatar>
            <span class="text-body-2">{{ step.title }}</span>
            <span v-if="step.optional" class="text-caption opacity-60">(optional)</span>
          </button>
        </li>
        <li v-if="index < steps.length - 1" aria-hidden="true">
          <v-divider length="24" thickness="2" class="fy-step-wizard__connector align-self-center" />
        </li>
      </template>
    </ol>

    <v-sheet variant="outlined" rounded class="fy-step-wizard__body">
      <slot v-if="activeSlot" :name="activeSlot" :step="activeStep" />
    </v-sheet>

    <div class="fy-step-wizard__actions d-flex justify-space-between align-center pa-2">
      <v-btn
        variant="text"
        :disabled="isFirst"
        data-action="back"
        prepend-icon="mdi-arrow-left"
        @click="back"
      >
        Back
      </v-btn>
      <v-btn
        color="primary"
        variant="tonal"
        :loading="resolving"
        :disabled="resolving"
        data-action="next"
        append-icon="mdi-arrow-right"
        @click="next"
      >
        {{ isLast ? 'Finish' : 'Next' }}
      </v-btn>
    </div>
  </div>
</template>
