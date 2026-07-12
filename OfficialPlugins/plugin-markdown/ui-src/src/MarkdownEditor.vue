<script setup lang="ts">
import { inject, onBeforeUnmount, onMounted, ref } from 'vue';
import type { PluginTheme, PluginUiContext, RenderResult } from './pluginUi';

// Host context is provided by main.ts via app.provide('pluginCtx', ctx).
// It may be missing in a bare standalone preview, so guard every access.
const ctx = inject<PluginUiContext | undefined>('pluginCtx', undefined);

const SAMPLE = '# Hello FengYu\n\nType **markdown** here.';

const markdown = ref<string>(SAMPLE);
const html = ref<string>('');
const isError = ref<boolean>(false);
const theme = ref<PluginTheme>(ctx?.theme === 'light' ? 'light' : 'dark');

let debounceTimer: ReturnType<typeof setTimeout> | null = null;
let unsubscribeTheme: (() => void) | null = null;

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

async function render(): Promise<void> {
  if (!ctx?.api?.invoke) {
    // No host wiring (standalone) — show the raw source so the pane isn't blank.
    isError.value = false;
    html.value = '<pre>' + escapeHtml(markdown.value) + '</pre>';
    return;
  }
  try {
    const res = (await ctx.api.invoke('render', { markdown: markdown.value })) as RenderResult;
    if (res && res.success) {
      isError.value = false;
      html.value = typeof res.html === 'string' ? res.html : '';
    } else {
      isError.value = true;
      html.value = escapeHtml((res && res.error) || 'Render failed');
    }
  } catch (err) {
    isError.value = true;
    html.value = escapeHtml(err instanceof Error ? err.message : String(err));
  }
}

function scheduleRender(): void {
  if (debounceTimer !== null) {
    clearTimeout(debounceTimer);
  }
  debounceTimer = setTimeout(() => {
    debounceTimer = null;
    void render();
  }, 250);
}

onMounted(() => {
  void render();
  if (ctx?.onThemeChange) {
    unsubscribeTheme = ctx.onThemeChange((t: PluginTheme) => {
      theme.value = t === 'light' ? 'light' : 'dark';
    });
  }
});

onBeforeUnmount(() => {
  if (debounceTimer !== null) {
    clearTimeout(debounceTimer);
    debounceTimer = null;
  }
  if (unsubscribeTheme) {
    unsubscribeTheme();
    unsubscribeTheme = null;
  }
});
</script>

<template>
  <v-card variant="outlined" rounded="lg" class="mde-card">
    <v-card-item>
      <v-card-title class="text-subtitle-1">Markdown</v-card-title>
    </v-card-item>

    <v-card-text class="mde-split">
      <div class="mde-pane mde-editor">
        <div class="mde-pane-title">Markdown</div>
        <textarea
          class="mde-textarea"
          v-model="markdown"
          spellcheck="false"
          @input="scheduleRender"
        ></textarea>
      </div>
      <div class="mde-pane mde-preview">
        <div class="mde-pane-title">Preview</div>
        <div class="mde-preview-body" :class="{ 'mde-error': isError }" v-html="html"></div>
      </div>
    </v-card-text>
  </v-card>
</template>

<style scoped>
.mde-card {
  width: 100%;
  height: 100%;
  min-height: 320px;
  display: flex;
  flex-direction: column;
}

/* Two-pane editor/preview split (flex row, preserved from the original). */
.mde-split {
  flex: 1 1 auto;
  display: flex;
  flex-direction: row;
  gap: 1px;
  min-height: 0;
  padding: 0;
  background: rgba(var(--v-theme-on-surface), 0.12);
}

.mde-pane {
  flex: 1 1 50%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: rgb(var(--v-theme-surface));
}

.mde-pane-title {
  flex: 0 0 auto;
  padding: 8px 14px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: rgb(var(--v-theme-on-surface));
  opacity: 0.7;
  border-bottom: 1px solid rgba(var(--v-theme-on-surface), 0.12);
}

/* Native textarea kept as-is per brief (third-party editors out of scope). */
.mde-textarea {
  flex: 1 1 auto;
  width: 100%;
  min-height: 0;
  resize: none;
  border: none;
  outline: none;
  box-sizing: border-box;
  padding: 14px;
  background: rgb(var(--v-theme-surface));
  color: rgb(var(--v-theme-on-surface));
  font-family: 'SF Mono', 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
  caret-color: rgb(var(--v-theme-primary));
}

.mde-textarea::selection {
  background: rgb(var(--v-theme-primary));
  color: rgb(var(--v-theme-on-primary));
}

.mde-preview-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  padding: 14px 18px;
  line-height: 1.65;
  font-size: 14px;
  color: rgb(var(--v-theme-on-surface));
}

.mde-preview-body.mde-error {
  color: rgb(var(--v-theme-error));
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  white-space: pre-wrap;
}

/* Markdown element styling within the v-html preview (deep — content is dynamic).
   Theme-aware via Vuetify on-surface/primary tokens; no legacy token refs. */
.mde-preview-body :deep(h1),
.mde-preview-body :deep(h2),
.mde-preview-body :deep(h3) {
  line-height: 1.3;
  margin: 0.6em 0 0.4em;
}
.mde-preview-body :deep(h1) { font-size: 1.7em; }
.mde-preview-body :deep(h2) { font-size: 1.4em; }
.mde-preview-body :deep(h3) { font-size: 1.2em; }
.mde-preview-body :deep(p) { margin: 0.5em 0; }
.mde-preview-body :deep(a) { color: rgb(var(--v-theme-primary)); }
.mde-preview-body :deep(ul),
.mde-preview-body :deep(ol) { padding-left: 1.4em; margin: 0.5em 0; }
.mde-preview-body :deep(code) {
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  font-size: 0.9em;
  padding: 0.12em 0.36em;
  border-radius: 4px;
  background: rgba(var(--v-theme-on-surface), 0.12);
}
.mde-preview-body :deep(pre) {
  background: rgba(var(--v-theme-on-surface), 0.08);
  border: 1px solid rgba(var(--v-theme-on-surface), 0.12);
  border-radius: 6px;
  padding: 10px 12px;
  overflow: auto;
}
.mde-preview-body :deep(pre code) {
  background: none;
  padding: 0;
}
.mde-preview-body :deep(blockquote) {
  margin: 0.6em 0;
  padding: 0.2em 0 0.2em 1em;
  border-left: 3px solid rgb(var(--v-theme-primary));
  opacity: 0.85;
}
.mde-preview-body :deep(table) {
  border-collapse: collapse;
}
.mde-preview-body :deep(th),
.mde-preview-body :deep(td) {
  border: 1px solid rgba(var(--v-theme-on-surface), 0.12);
  padding: 4px 8px;
}
.mde-preview-body :deep(img) { max-width: 100%; }
.mde-preview-body :deep(hr) {
  border: none;
  border-top: 1px solid rgba(var(--v-theme-on-surface), 0.12);
  margin: 1em 0;
}
</style>
