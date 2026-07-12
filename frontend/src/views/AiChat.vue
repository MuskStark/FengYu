<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { marked } from 'marked'
import { useI18n } from 'vue-i18n'
import { useAiSessionStore } from '@/stores/aiSession'

const { t } = useI18n()
const ai = useAiSessionStore()
const draft = ref('')
// Plain div ref (NOT a Vuetify component ref) so scroll logic stays simple
// — component-instance $el indirection is unreliable. Same pattern as the
// original hand-written version.
const scroller = ref<HTMLElement | null>(null)

marked.setOptions({ breaks: true, gfm: true })

function md(src: string): string {
  return marked.parse(src) as string
}

function submit() {
  const text = draft.value
  if (!text.trim() || ai.busy) return
  draft.value = ''
  void ai.send(text)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    submit()
  }
}

const hasError = computed(() => ai.error !== null)

watch(
  () => ai.turns.map((turn) => turn.content + turn.thinking).join('|'),
  async () => {
    await nextTick()
    const el = scroller.value
    if (el) el.scrollTop = el.scrollHeight
  },
)
</script>

<template>
  <div class="d-flex flex-column h-100 pa-4">
    <div class="d-flex align-center justify-space-between mb-2">
      <h1 class="text-h5">{{ $t('aichat.title') }}</h1>
      <v-btn variant="outlined" prepend-icon="mdi-broom" @click="ai.clear()">
        {{ $t('aichat.clear') }}
      </v-btn>
    </div>

    <v-alert
      v-if="hasError"
      type="error"
      variant="tonal"
      class="mb-2"
      closable
      @click:close="ai.error = null"
    >{{ ai.error }}</v-alert>

    <div ref="scroller" class="flex-grow-1 overflow-y-auto d-flex flex-column ga-4 pa-2">
      <div v-if="ai.turns.length === 0" class="text-medium-emphasis text-center mt-10">
        {{ $t('aichat.empty') }}
      </div>

      <div
        v-for="turn in ai.turns"
        :key="turn.id"
        class="d-flex flex-column ga-1"
        :class="turn.role === 'user' ? 'align-self-end align-end' : 'align-self-start align-start'"
        style="max-width: 80%"
      >
        <div class="text-caption text-medium-emphasis">
          {{ turn.role === 'user' ? t('aichat.you') : t('aichat.assistant') }}
        </div>

        <v-expansion-panels v-if="turn.thinking" variant="accordion">
          <v-expansion-panel>
            <v-expansion-panel-title class="text-caption">{{ $t('aichat.thinking') }}</v-expansion-panel-title>
            <v-expansion-panel-text>
              <div v-html="md(turn.thinking)" />
            </v-expansion-panel-text>
          </v-expansion-panel>
        </v-expansion-panels>

        <v-card
          v-if="turn.role === 'assistant'"
          variant="tonal"
          rounded="lg"
          class="pa-3"
        >
          <div v-html="md(turn.content)" />
        </v-card>
        <v-card v-else color="primary" variant="tonal" rounded="lg" class="pa-3">
          <div class="text-body-2" style="white-space: pre-wrap">{{ turn.content }}</div>
        </v-card>

        <div v-if="turn.streaming && !turn.content" class="text-medium-emphasis">…</div>
      </div>
    </div>

    <div class="d-flex ga-2 align-center mt-2">
      <v-textarea
        v-model="draft"
        :placeholder="$t('aichat.placeholder')"
        auto-grow
        rows="2"
        variant="outlined"
        hide-details
        class="flex-grow-1"
        @keydown="onKeydown"
      />
      <v-btn
        v-if="ai.busy"
        variant="outlined"
        prepend-icon="mdi-stop"
        @click="ai.stop()"
      >{{ $t('aichat.stop') }}</v-btn>
      <v-btn
        v-else
        color="primary"
        prepend-icon="mdi-send"
        :disabled="!draft.trim()"
        @click="submit"
      >{{ $t('aichat.send') }}</v-btn>
    </div>
  </div>
</template>
