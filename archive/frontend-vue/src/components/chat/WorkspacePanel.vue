<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import hljs from 'highlight.js/lib/common'
import { api } from '@/api/client'
import type { WorkspaceTree, WorkspaceFilePreview } from '@/api/types'
import WsTreeNode from './WsTreeNode.vue'
import { type WsTreeRow } from './wsTree'

/**
 * Read-only workspace side panel: an expandable file tree (from the server's bounded,
 * traversal-excluded walk) and a syntax-highlighted single-file preview. It exists so
 * "which files did the AI touch" is one click away from the tool timeline — the open-file
 * gesture on an activity row lands here via the {@link focus} prop.
 */
const props = defineProps<{
  conversationId: number
  root: string
  /** External open request ({path, seq}); seq makes repeated requests for one path re-fire. */
  focus: { path: string; seq: number } | null
}>()
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()

const tree = ref<WorkspaceTree | null>(null)
const treeLoading = ref(false)
const treeError = ref<string | null>(null)
const expanded = ref<Set<string>>(new Set())
const selected = ref<string | null>(null)
const preview = ref<WorkspaceFilePreview | null>(null)
const fileLoading = ref(false)
const fileError = ref<string | null>(null)
const fileCache = new Map<string, WorkspaceFilePreview>()

onMounted(loadTree)
watch(() => props.conversationId, loadTree)

async function loadTree() {
  treeLoading.value = true
  treeError.value = null
  try {
    tree.value = await api.getWorkspaceTree(props.conversationId)
    // Root-level directories start expanded; deeper levels stay collapsed until asked for.
    expanded.value = new Set(roots.value.map(node => node.path))
    selected.value = null
    preview.value = null
    fileCache.clear()
  } catch {
    treeError.value = t('aichat.workspaceTreeFailed')
  } finally {
    treeLoading.value = false
  }
}

// ── tree assembly (flat server list → nested rows) ─────────────────────────────────

const rowsByPath = computed(() => {
  const map = new Map<string, WsTreeRow>()
  for (const node of tree.value?.nodes ?? []) {
    map.set(node.path, { ...node, children: [] })
  }
  return map
})

function parentPath(path: string): string {
  return path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : ''
}

/** Top-level rows in display order (directories first, numeric-aware by name), children attached. */
const roots = computed<WsTreeRow[]>(() => {
  const all = [...rowsByPath.value.values()]
  for (const row of all) {
    const parent = parentPath(row.path)
    const owner = parent ? rowsByPath.value.get(parent) : undefined
    if (owner) owner.children.push(row)
  }
  const sort = (list: WsTreeRow[]) => {
    list.sort((a, b) => a.dir === b.dir
      ? a.name.localeCompare(b.name, undefined, { numeric: true })
      : (a.dir ? -1 : 1))
    list.forEach(row => sort(row.children))
  }
  sort(all)
  return all.filter(row => {
    const parent = parentPath(row.path)
    return !parent || !rowsByPath.value.has(parent)
  })
})

function toggle(row: WsTreeRow) {
  const next = new Set(expanded.value)
  if (next.has(row.path)) next.delete(row.path)
  else next.add(row.path)
  expanded.value = next
}

function expandAncestors(path: string) {
  const next = new Set(expanded.value)
  let dir = parentPath(path)
  while (dir) {
    next.add(dir)
    dir = parentPath(dir)
  }
  expanded.value = next
}

// ── file preview ────────────────────────────────────────────────────────────────────

async function open(path: string) {
  selected.value = path
  fileError.value = null
  expandAncestors(path)
  const cached = fileCache.get(path)
  if (cached) {
    preview.value = cached
    return
  }
  fileLoading.value = true
  try {
    const loaded = await api.getWorkspaceFile(props.conversationId, path)
    fileCache.set(path, loaded)
    if (selected.value === path) preview.value = loaded
  } catch {
    if (selected.value === path) fileError.value = t('aichat.workspaceFileFailed')
  } finally {
    fileLoading.value = false
  }
}

watch(() => props.focus, (focus) => {
  if (focus && focus.path) void open(focus.path)
})

const EXT_LANGUAGES: Record<string, string> = {
  ts: 'typescript', tsx: 'typescript', mts: 'typescript',
  js: 'javascript', jsx: 'javascript', mjs: 'javascript',
  json: 'json', java: 'java', py: 'python', md: 'markdown',
  css: 'css', scss: 'scss', html: 'xml', xml: 'xml', vue: 'xml', svg: 'xml',
  yml: 'yaml', yaml: 'yaml', sh: 'bash', bash: 'bash', zsh: 'bash',
  sql: 'sql', go: 'go', rs: 'rust', c: 'c', h: 'c', cpp: 'cpp', hpp: 'cpp', cc: 'cpp',
  cs: 'csharp', php: 'php', rb: 'ruby', kt: 'kotlin', swift: 'swift',
  ini: 'ini', toml: 'ini', properties: 'ini',
}

/**
 * Highlighted preview body. highlight.js escapes its input, so the generated HTML is safe to
 * inject — the same guarantee the chat markdown pipeline relies on.
 */
const highlightedContent = computed<string | null>(() => {
  const content = preview.value?.content
  if (content === undefined) return null
  const ext = (selected.value ?? '').split('.').pop()?.toLowerCase() ?? ''
  const language = EXT_LANGUAGES[ext]
  if (language && hljs.getLanguage(language)) {
    return hljs.highlight(content, { language, ignoreIllegals: true }).value
  }
  return content
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
})

const rootName = computed(() =>
  props.root.replace(/[\\/]+$/, '').split(/[\\/]/).pop() ?? props.root)
</script>

<template>
  <aside class="ws-panel">
    <header class="ws-panel__head">
      <i class="mdi mdi-folder-outline" />
      <span class="ws-panel__title">{{ $t('aichat.workspacePanel') }}</span>
      <span class="cx-muted ws-panel__root" :title="root">{{ rootName }}</span>
      <button class="cx-iconbtn cx-iconbtn--sm" :title="$t('common.close')" @click="emit('close')">
        <i class="mdi mdi-close" />
      </button>
    </header>

    <div class="ws-panel__body">
      <nav class="ws-tree">
        <div v-if="treeLoading" class="cx-muted ws-tree__status">
          <span class="cx-spin" /> {{ $t('aichat.workspaceTreeLoading') }}
        </div>
        <div v-else-if="treeError" class="cx-alert cx-alert--error ws-tree__status">{{ treeError }}</div>
        <template v-else>
          <div v-if="tree?.truncated" class="ws-tree__status ws-tree__status--warn">
            <i class="mdi mdi-information-outline" />
            {{ $t('aichat.workspaceTreeTruncated', { count: tree.nodes.length }) }}
          </div>
          <ul class="ws-tree__list">
            <WsTreeNode
              v-for="row in roots"
              :key="row.path"
              :row="row"
              :depth="0"
              :expanded="expanded"
              :selected="selected"
              @toggle="toggle"
              @open="open"
            />
          </ul>
        </template>
      </nav>

      <div class="ws-preview">
        <div v-if="fileLoading" class="cx-muted ws-preview__status"><span class="cx-spin" /></div>
        <div v-else-if="fileError" class="cx-alert cx-alert--error ws-preview__status">{{ fileError }}</div>
        <div v-else-if="!preview" class="cx-muted ws-preview__status">{{ $t('aichat.workspaceNoSelection') }}</div>
        <div v-else-if="preview.binary" class="cx-muted ws-preview__status">
          <i class="mdi mdi-file-question-outline" /> {{ $t('aichat.workspaceBinaryFile') }}
        </div>
        <div v-else-if="preview.tooLarge" class="cx-muted ws-preview__status">
          <i class="mdi mdi-file-alert-outline" /> {{ $t('aichat.workspaceLargeFile') }}
        </div>
        <template v-else>
          <div class="ws-preview__path" :title="preview.path">
            <i class="mdi mdi-file-code-outline" />
            <span>{{ preview.path }}</span>
          </div>
          <pre class="ws-preview__code"><code v-html="highlightedContent" /></pre>
        </template>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.ws-panel {
  display: flex;
  flex-direction: column;
  width: 420px;
  flex: 0 0 420px;
  min-height: 0;
  border-left: 1px solid var(--cx-border);
  background: rgb(var(--v-theme-surface-container));
}
.ws-panel__head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: var(--cx-window-bar-height);
  padding: 0 8px 0 14px;
  border-bottom: 1px solid var(--cx-border);
}
.ws-panel__title {
  font-size: 13px;
  font-weight: 650;
}
.ws-panel__root {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ws-panel__body {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
}

/* ── file tree column ────────────────────────────────────────────────────────────── */
.ws-tree {
  flex: 0 0 44%;
  min-width: 160px;
  overflow-y: auto;
  border-right: 1px solid var(--cx-border-subtle);
  padding: 6px 4px;
}
.ws-tree__list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.ws-tree__status {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  font-size: 12px;
}
.ws-tree__status--warn { color: rgb(var(--v-theme-warning)); }

/* ── preview column ───────────────────────────────────────────────────────────────── */
.ws-preview {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.ws-preview__status {
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 20px;
  font-size: 13px;
  text-align: center;
}
.ws-preview__path {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border-bottom: 1px solid var(--cx-border-subtle);
  font-family: 'SF Mono', 'JetBrains Mono', ui-monospace, Menlo, Consolas, monospace;
  font-size: 11px;
  color: rgb(var(--v-theme-secondary));
}
.ws-preview__path span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ws-preview__code {
  flex: 1 1 auto;
  margin: 0;
  padding: 10px 12px;
  overflow: auto;
  background: var(--cx-code-bg);
  font-size: 12px;
  line-height: 1.55;
}
</style>
