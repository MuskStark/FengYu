<script setup lang="ts">
import { inject, onBeforeUnmount, onMounted, ref } from 'vue';
import type { PluginTheme, PluginUiContext, RenderResult } from './pluginUi';

// Host context is provided by main.ts via app.provide('pluginCtx', ctx).
// It may be missing in a bare standalone preview, so guard every access.
const ctx = inject<PluginUiContext | undefined>('pluginCtx', undefined);

const SAMPLE = '# Hello ZhiFlow\n\nType **markdown** here.';

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
  <div class="mde-root" :class="theme === 'light' ? 'theme-light' : 'theme-dark'">
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
  </div>
</template>

<style scoped>
.mde-root {
  display: flex;
  flex-direction: row;
  width: 100%;
  height: 100%;
  min-height: 320px;
  box-sizing: border-box;
  gap: 1px;
  background: var(--sk-border, #3a3a44);
  color: var(--sk-text, #e6e6ea);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}

.mde-pane {
  flex: 1 1 50%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--sk-bg-elevated, #2b2b33);
  overflow: hidden;
}

.mde-pane-title {
  flex: 0 0 auto;
  padding: 8px 14px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--sk-text, #e6e6ea);
  opacity: 0.7;
  border-bottom: 1px solid var(--sk-border, #3a3a44);
}

.mde-textarea {
  flex: 1 1 auto;
  width: 100%;
  min-height: 0;
  resize: none;
  border: none;
  outline: none;
  box-sizing: border-box;
  padding: 14px;
  background: var(--sk-bg, #1e1e26);
  color: var(--sk-text, #e6e6ea);
  font-family: 'SF Mono', 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
  caret-color: var(--sk-accent, #6c8cff);
}

.mde-textarea::selection {
  background: var(--sk-accent, #6c8cff);
  color: #fff;
}

.mde-preview-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  padding: 14px 18px;
  line-height: 1.65;
  font-size: 14px;
  color: var(--sk-text, #e6e6ea);
}

.mde-preview-body.mde-error {
  color: var(--sk-danger, #e5484d);
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  white-space: pre-wrap;
}

/* Markdown element styling within the v-html preview (deep — content is dynamic). */
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
.mde-preview-body :deep(a) { color: var(--sk-accent, #6c8cff); }
.mde-preview-body :deep(ul),
.mde-preview-body :deep(ol) { padding-left: 1.4em; margin: 0.5em 0; }
.mde-preview-body :deep(code) {
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  font-size: 0.9em;
  padding: 0.12em 0.36em;
  border-radius: 4px;
  background: color-mix(in srgb, var(--sk-text, #e6e6ea) 12%, transparent);
}
.mde-preview-body :deep(pre) {
  background: var(--sk-bg, #1e1e26);
  border: 1px solid var(--sk-border, #3a3a44);
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
  border-left: 3px solid var(--sk-accent, #6c8cff);
  opacity: 0.85;
}
.mde-preview-body :deep(table) {
  border-collapse: collapse;
}
.mde-preview-body :deep(th),
.mde-preview-body :deep(td) {
  border: 1px solid var(--sk-border, #3a3a44);
  padding: 4px 8px;
}
.mde-preview-body :deep(img) { max-width: 100%; }
.mde-preview-body :deep(hr) {
  border: none;
  border-top: 1px solid var(--sk-border, #3a3a44);
  margin: 1em 0;
}
</style>
