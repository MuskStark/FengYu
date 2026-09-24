<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAiSessionStore } from '@/stores/aiSession'
import { useSettingsStore } from '@/stores/settings'
import { useSkillsStore } from '@/stores/skills'
import { usePluginsStore } from '@/stores/plugins'
import { makeDesktop, isDesktop } from '@/mf/desktop'
import { api } from '@/api/client'
import type { AiMode, ChatResource } from '@/api/types'
import { configuredChatModels } from '@/views/aiChatModels'
import MentionPanel from '@/components/chat/MentionPanel.vue'
import { wsRowIcon } from '@/components/chat/wsTree'
import { extractActiveMention, buildMentionMarkdown, type MentionTrigger } from '@/components/chat/mentionTriggers'
import { buildMentionSections, flattenMentionSections, type MentionOption, type MentionSection } from '@/components/chat/mentionSearch'
import { FLOW_CHAT_SEED_KEY } from '@/components/chat/flowSeed'

/**
 * Input half of the AI chat view: the context chips strip (draft attachments, committed
 * resources, output target, coding workspace), the composer card (approval prompts, editor,
 * attach / permission / model menus, voice input, send/stop) and the error surface. Owns all
 * composer-local state; conversation mutations go through the session and settings stores.
 */
const { t, locale } = useI18n()
const ai = useAiSessionStore()
const settings = useSettingsStore()
const skillsStore = useSkillsStore()
const pluginsStore = usePluginsStore()
const router = useRouter()
const composing = ref(false)
const permissionMenuOpen = ref(false)
const attachMenuOpen = ref(false)
const modelMenuOpen = ref(false)
const modelSwitching = ref(false)
const listening = ref(false)
const resourcesExpanded = ref(false)
/** Browser-only workspace attach dialog (desktop uses the native directory picker). */
const workspaceDialog = ref(false)
const workspacePathInput = ref('')
let speechRecognition: SpeechRecognitionLike | null = null

interface SpeechRecognitionLike {
  lang: string
  interimResults: boolean
  continuous: boolean
  onresult: ((event: { results: ArrayLike<{ 0: { transcript: string } }> }) => void) | null
  onerror: (() => void) | null
  onend: (() => void) | null
  start(): void
  stop(): void
}

onMounted(() => {
  void settings.loadAi().catch(() => {
    ai.error = t('aichat.modelsLoadFailed')
  })
  document.addEventListener('pointerdown', closeMenusOnOutsideClick)
  const el = editorEl.value
  if (el && el.contentEditable !== 'plaintext-only') {
    // Browsers without plaintext-only get the plain mode; the paste handler keeps input text-only.
    el.contentEditable = 'true'
  }
  void nextTick(rebuildEditorFromConversation)
})

onBeforeUnmount(() => {
  speechRecognition?.stop()
  document.removeEventListener('pointerdown', closeMenusOnOutsideClick)
})

/**
 * The composer's three popover menus (attach / permission / model) close on any
 * pointerdown outside their own trigger+menu pair. Each pair is tagged with the same
 * `data-menu` name (mirrors the sidebar account menu's outside-click handling); a
 * click inside the pair is left alone so the trigger's own toggle still works.
 */
function closeMenusOnOutsideClick(event: PointerEvent) {
  const within = (event.target as HTMLElement).closest('[data-menu]')?.getAttribute('data-menu')
  if (within !== 'attach') attachMenuOpen.value = false
  if (within !== 'permission') permissionMenuOpen.value = false
  if (within !== 'model') modelMenuOpen.value = false
}

const hasError = computed(() => ai.error !== null)
const activeConv = computed(() => ai.active)
/** Send is armed when the editor carries text or an inline mention token. */
const composerArmed = computed(() =>
  Boolean(ai.active?.draft.trim() || ai.active?.draftMentions.length))
const showExpandedResources = computed(() =>
  ai.turns.length === 0 || resourcesExpanded.value)
const modelOptions = computed(() => configuredChatModels(settings.aiSettings))
const activeModel = computed(() => modelOptions.value.find(
  option => option.mode === (settings.aiSettings?.activeMode ?? settings.aiSettings?.mode),
) ?? null)
const composerConfirmations = computed(() => ai.turns.flatMap(turn => turn.confirmations)
  .filter(item => ['pending', 'submitting', 'error'].includes(item.status)))
const permissionOptions = computed(() => [
  { id: 'ask-for-approval' as const, icon: 'mdi-hand-back-left-outline', title: t('aichat.permissionAsk'), description: t('aichat.permissionAskHint') },
  { id: 'approve-for-me' as const, icon: 'mdi-shield-check-outline', title: t('aichat.permissionAuto'), description: t('aichat.permissionAutoHint') },
  { id: 'full-access' as const, icon: 'mdi-shield-alert-outline', title: t('aichat.permissionFullAccess'), description: t('aichat.permissionFullHint') },
])

/**
 * Inline mention editor (ZCode's PromptMentionNode adapted to plain DOM): a
 * contenteditable surface where mentions are atomic chips INSIDE the text flow — tokens
 * carry their send-time markdown in data attributes, the caret treats them as one unit,
 * and a trailing space is appended after each insert. The conversation stores the plain
 * text plus the mention list (session memory, like draft attachments); on send the chips
 * serialize as markdown lines after the text.
 */
const editorEl = ref<HTMLElement | null>(null)
const editorEmpty = ref(true)

function autosize() {
  const el = editorEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

function onEditorInput() {
  syncFromEditor()
  updateMentionState()
}

/** Plain-text caret prefix, chips rendering their label into the flow (ZCode's text tokens). */
function textBeforeCaret(): string {
  const el = editorEl.value
  const sel = window.getSelection()
  if (!el || !sel || sel.rangeCount === 0) return ''
  const range = sel.getRangeAt(0)
  if (!range.collapsed || !el.contains(range.startContainer)) return ''
  const pre = document.createRange()
  pre.selectNodeContents(el)
  pre.setEnd(range.startContainer, range.startOffset)
  let text = ''
  pre.cloneContents().childNodes.forEach(node => {
    if (node.nodeType === Node.TEXT_NODE) text += node.textContent ?? ''
    else if (node.nodeName === 'BR') text += '\n'
    else text += (node as HTMLElement).dataset?.label ?? node.textContent ?? ''
  })
  return text
}

/** Serialized editor state: typed text (chips excluded) + the mention tokens in flow order. */
function serializeEditor(): { text: string; mentions: MentionOption[] } {
  const el = editorEl.value
  if (!el) return { text: '', mentions: [] }
  let text = ''
  const mentions: MentionOption[] = []
  const walk = (node: Node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      text += node.textContent ?? ''
    } else if (node.nodeType === Node.ELEMENT_NODE) {
      const element = node as HTMLElement
      if (element.classList.contains('composer-token')) {
        mentions.push(tokenFromDom(element))
      } else if (element.tagName === 'BR') {
        text += '\n'
      } else {
        element.childNodes.forEach(walk)
        if (/^(DIV|P)$/.test(element.tagName) && element.nextSibling) text += '\n'
      }
    }
  }
  ;[...el.childNodes].forEach(walk)
  return { text, mentions }
}

function tokenFromDom(element: HTMLElement): MentionOption {
  return {
    id: element.dataset.id ?? '',
    category: (element.dataset.category as MentionOption['category']) ?? 'file',
    label: element.dataset.label ?? element.textContent ?? '',
    description: element.dataset.description ?? '',
    value: element.dataset.value ?? '',
    markdown: element.dataset.markdown ?? '',
    icon: element.dataset.icon ?? 'mdi-file-outline',
    isDirectory: element.dataset.isDirectory === 'true' || undefined,
  }
}

function buildTokenDom(option: MentionOption): HTMLElement {
  const chip = document.createElement('span')
  chip.contentEditable = 'false'
  chip.className = `composer-token composer-token--${option.category}`
  chip.dataset.id = option.id
  chip.dataset.category = option.category
  chip.dataset.label = option.label
  chip.dataset.description = option.description
  chip.dataset.value = option.value
  chip.dataset.markdown = option.markdown
  chip.dataset.icon = option.icon
  if (option.isDirectory) chip.dataset.isDirectory = 'true'
  chip.title = option.description || option.label
  const icon = document.createElement('i')
  icon.className = `mdi ${option.icon}`
  chip.appendChild(icon)
  chip.appendChild(document.createTextNode(option.label))
  return chip
}

/** Mirror the editor into the conversation's draft (text + mention list). */
function syncFromEditor() {
  const { text, mentions } = serializeEditor()
  const conv = ai.active
  if (conv) {
    conv.draft = text
    conv.draftMentions = mentions
  }
  editorEmpty.value = text.length === 0 && mentions.length === 0
  autosize()
}

function placeCaretAtEnd(el: HTMLElement) {
  const sel = window.getSelection()
  if (!sel) return
  const range = document.createRange()
  range.selectNodeContents(el)
  range.collapse(false)
  sel.removeAllRanges()
  sel.addRange(range)
}

/** Rebuild the editor DOM for the active conversation (switch/initial mount). */
function rebuildEditorFromConversation() {
  const el = editorEl.value
  if (!el) return
  el.textContent = ai.active?.draft ?? ''
  for (const option of ai.active?.draftMentions ?? []) {
    el.appendChild(buildTokenDom(option))
    el.appendChild(document.createTextNode(' '))
  }
  placeCaretAtEnd(el)
  closeMention()
  syncFromEditor()
}

function onEditorPaste(event: ClipboardEvent) {
  // Keep pastes plain-text only even where contenteditable="plaintext-only" is unsupported.
  event.preventDefault()
  const text = event.clipboardData?.getData('text/plain') ?? ''
  if (text) document.execCommand('insertText', false, text)
  syncFromEditor()
}

function submit() {
  // The DOM is the source of truth (no v-model), so IME state cannot desync the send text.
  const { text, mentions } = serializeEditor()
  const mentionBlock = mentions.map(item => item.markdown).join('\n')
  const full = text + (mentionBlock ? `\n\n${mentionBlock}` : '')
  if (!full.trim() || ai.busy || modelSwitching.value) return
  if (!activeModel.value) {
    ai.error = t('aichat.noConfiguredModels')
    return
  }
  const conv = ai.active
  if (conv) {
    conv.draft = ''
    conv.draftMentions = []
  }
  if (editorEl.value) editorEl.value.textContent = ''
  editorEmpty.value = true
  void nextTick(autosize)
  void ai.send(full)
}

function onKeydown(e: KeyboardEvent) {
  if (e.isComposing || composing.value || e.keyCode === 229) return
  if (onMentionKeydown(e)) return
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    submit()
  } else if (e.key === 'Enter' && e.shiftKey) {
    // One uniform line-break across native contenteditable modes (div vs \n).
    e.preventDefault()
    document.execCommand('insertLineBreak')
    syncFromEditor()
  }
}

function onCompositionEnd(e: CompositionEvent) {
  composing.value = false
  void e
  syncFromEditor()
  updateMentionState()
}

// ── mentions (ZCode's @ / $ completion, adapted to the plain textarea) ───────────────
// Typing `@` opens the plugin→file panel, `$` the skills panel. Selected items become chips
// as inline chips inside the editor, serialized into the outgoing prompt as markdown on send.
// trigger/keyboard/dismiss semantics follow ZCode's MentionPlugin; chips stand in for its
// chips mirror ZCode's inline Lexical tokens on a plaintext-only contenteditable surface.

const mention = ref<{ trigger: MentionTrigger; query: string; tokenStart: number } | null>(null)
const mentionSections = ref<MentionSection[]>([])
const mentionSelectedIndex = ref(0)
const mentionLoading = ref(false)
/** Escape'd `trigger:query` signature — the same token does not reopen the panel (ZCode rule). */
let dismissedMentionSignature: string | null = null
/** Workspace file pool behind the @ panel's files group, cached per conversation. */
let filePool: { conversationId: number; options: MentionOption[] } | null = null
/** Saved flows behind the @ panel's flows group (session cache; flows rarely change mid-chat). */
let flowPool: MentionOption[] | null = null

const mentionFlat = computed(() => flattenMentionSections(mentionSections.value))
const mentionEmptyLabel = computed(() => {
  if (mention.value?.query) return t('aichat.mentionEmpty')
  return t(mention.value?.trigger === '$' ? 'aichat.mentionSkillHint' : 'aichat.mentionSearchHint')
})

function closeMention() {
  mention.value = null
  mentionSections.value = []
  mentionSelectedIndex.value = 0
}

/** Recompute the active mention token from the caret position (input/caret-move paths). */
function updateMentionState() {
  const active = extractActiveMention(textBeforeCaret())
  if (!active || `${active.trigger}:${active.query}` === dismissedMentionSignature) {
    closeMention()
    return
  }
  const changed = mention.value?.trigger !== active.trigger || mention.value?.query !== active.query
  mention.value = active
  if (changed) void rebuildMentionSections()
}

async function rebuildMentionSections() {
  const active = mention.value
  if (!active) return
  mentionLoading.value = true
  try {
    const pools: Partial<Record<'file' | 'skill' | 'plugin' | 'flow', MentionOption[]>> = {}
    if (active.trigger === '@') {
      if (pluginsStore.plugins.length === 0) void pluginsStore.load().then(() => rebuildMentionSections()).catch(() => {})
      pools.plugin = pluginsStore.plugins
        .filter(plugin => plugin.enabled !== false)
        .map(plugin => ({
          id: `plugin:${plugin.id}`,
          category: 'plugin' as const,
          label: plugin.name,
          description: plugin.description ?? '',
          value: plugin.id,
          markdown: buildMentionMarkdown('plugin', plugin.name, plugin.id),
          icon: 'mdi-puzzle-outline',
        }))
      pools.file = (await ensureFilePool()) ?? undefined
      pools.flow = flowPool ?? await ensureFlowPool()
    } else {
      if (skillsStore.skills.length === 0) void skillsStore.load().then(() => rebuildMentionSections()).catch(() => {})
      pools.skill = skillsStore.skills
        .filter(skill => skill.enabled)
        .map(skill => ({
          id: `skill:${skill.id}`,
          category: 'skill' as const,
          label: skill.name,
          description: skill.description ?? '',
          value: skill.id,
          markdown: buildMentionMarkdown('skill', skill.name, skill.id),
          icon: 'mdi-auto-fix',
        }))
    }
    if (mention.value !== active && mention.value?.trigger !== active.trigger) return // switched meanwhile
    mentionSections.value = buildMentionSections(active.trigger, pools, active.query)
    mentionSelectedIndex.value = 0
  } catch {
    mentionSections.value = []
  } finally {
    mentionLoading.value = false
  }
}

async function ensureFilePool(): Promise<MentionOption[] | null> {
  const conv = ai.active
  if (!conv?.workspaceRoot || conv.backendId == null) return null
  if (filePool && filePool.conversationId === conv.backendId) return filePool.options
  try {
    const tree = await api.getWorkspaceTree(conv.backendId)
    const options = tree.nodes.map(node => ({
      id: `file:${node.path}`,
      category: 'file' as const,
      label: node.name,
      description: node.path,
      value: node.path,
      markdown: buildMentionMarkdown('file', node.name, node.path, node.dir),
      icon: wsRowIcon(node, false),
      isDirectory: node.dir,
    }))
    filePool = { conversationId: conv.backendId, options }
    return options
  } catch {
    return null // no workspace attached (or tree unavailable) — the files group simply hides
  }
}

// A re-attached or switched workspace invalidates the cached file pool.
watch(() => [ai.active?.backendId, ai.active?.workspaceRoot], () => {
  filePool = null
})

/** Saved flows for the @ panel's flows group (ZCode's whiteboards slot: pick → hand off). */
async function ensureFlowPool(): Promise<MentionOption[] | undefined> {
  if (flowPool) return flowPool
  try {
    const workflows = await api.workflows()
    flowPool = workflows.map(flow => ({
      id: `flow:${flow.id}`,
      category: 'flow' as const,
      label: flow.name,
      description: flow.description ?? '',
      value: flow.id,
      markdown: `[@${flow.name}](flow://${flow.id})`,
      icon: 'mdi-vector-polyline',
    }))
    return flowPool
  } catch {
    return undefined // flows listing unavailable — the group simply hides
  }
}

/**
 * Flow picks take ZCode's whiteboard special case: no inline token is inserted — the current
 * editor content (text + mention markdown) is handed to the target flow's docked chat via a
 * sessionStorage seed, and the composer navigates there.
 */
function sendInputToFlow(option: MentionOption) {
  const { text, mentions } = serializeEditor()
  const mentionBlock = mentions.map(item => item.markdown).join('\n')
  const full = text + (mentionBlock ? `\n\n${mentionBlock}` : '')
  if (editorEl.value) editorEl.value.textContent = ''
  const conv = ai.active
  if (conv) {
    conv.draft = ''
    conv.draftMentions = []
  }
  editorEmpty.value = true
  closeMention()
  dismissedMentionSignature = null
  void nextTick(autosize)
  try {
    sessionStorage.setItem(FLOW_CHAT_SEED_KEY, JSON.stringify({
      workflowId: option.value, text: full, at: Date.now(),
    }))
  } catch {
    /* storage unavailable — the jump still lands on the flow, just without the seed */
  }
  void router.push(`/flows/${encodeURIComponent(option.value)}`)
}

/** Replace the typed `@query` token with an inline chip + trailing space (ZCode's insert flow). */
function applyMention(option: MentionOption) {
  if (option.category === 'flow') {
    sendInputToFlow(option)
    return
  }
  const active = mention.value
  const el = editorEl.value
  const sel = window.getSelection()
  if (!active || !el || !sel || sel.rangeCount === 0) return
  const node = sel.anchorNode
  if (!node || !el.contains(node) || node.nodeType !== Node.TEXT_NODE) return
  const textNode = node as Text
  const offset = Math.min(sel.anchorOffset, (textNode.textContent ?? '').length)
  const start = offset - active.query.length - 1
  const symbol = (textNode.textContent ?? '')[start]
  const accepted = active.trigger === '@' ? symbol === '@' : /[$¥￥]/.test(symbol ?? '')
  if (start < 0 || !accepted) return
  textNode.deleteData(start, active.query.length + 1)
  const chip = buildTokenDom(option)
  const space = document.createTextNode(' ')
  const range = sel.getRangeAt(0)
  range.insertNode(chip)
  chip.after(space)
  sel.setPosition(space, space.length)
  closeMention()
  dismissedMentionSignature = null
  syncFromEditor()
  void nextTick(() => {
    el.focus()
    autosize()
  })
}

/** Keyboard navigation over the flattened panel rows; returns true when the key was consumed. */
function onMentionKeydown(e: KeyboardEvent): boolean {
  if (!mention.value) return false
  const flat = mentionFlat.value
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault()
    if (flat.length > 0) {
      const delta = e.key === 'ArrowDown' ? 1 : -1
      mentionSelectedIndex.value = (mentionSelectedIndex.value + delta + flat.length) % flat.length
    }
    return true
  }
  if ((e.key === 'Enter' || e.key === 'Tab') && flat.length > 0) {
    e.preventDefault()
    applyMention(flat[mentionSelectedIndex.value])
    return true
  }
  if (e.key === 'Escape') {
    dismissedMentionSignature = `${mention.value.trigger}:${mention.value.query}`
    closeMention()
    return true
  }
  return false
}

// ── attachments: the conversation that opened the picker owns the result ─────────────

function attachFailure(kind: 'file' | 'directory' | 'output' | 'workspace', error: unknown) {
  const fallback = kind === 'output'
    ? t('aichat.outputTargetFailed')
    : kind === 'workspace' ? t('aichat.workspaceSetFailed')
      : kind === 'directory' ? t('aichat.attachDirectoryFailed') : t('aichat.attachFileFailed')
  ai.error = error instanceof Error && error.message ? error.message : fallback
}

/**
 * Adding a file only writes a DRAFT attachment into the conversation that opened the picker —
 * no backend call, no grant, no copy until send (E01/E02). No approval card, no plugin pick.
 */
async function attachFile() {
  attachMenuOpen.value = false
  const conv = ai.ensureConversation()
  const desktop = makeDesktop()
  if (desktop) {
    const path = await desktop.pickFile()
    if (!path) return
    // A switch or new chat during the picker never redirects the draft: only a live
    // conversation with this id receives it; deleting the origin simply drops it.
    if (ai.conversations.some(c => c.id === conv.id)) ai.attachNative(conv, path, 'file')
    return
  }
  const input = document.createElement('input')
  input.type = 'file'
  input.onchange = () => {
    const file = input.files?.[0]
    if (!file || !ai.conversations.some(c => c.id === conv.id)) return
    ai.attachUpload(conv, file)
  }
  input.click()
}

/** Adding a folder works the same way: a draft attachment, read-only, subdirectories included. */
async function attachDirectory() {
  attachMenuOpen.value = false
  const conv = ai.ensureConversation()
  const desktop = makeDesktop()
  if (desktop) {
    const path = await desktop.pickDirectory()
    if (!path) return
    if (ai.conversations.some(c => c.id === conv.id)) ai.attachNative(conv, path, 'directory')
    return
  }
  const input = document.createElement('input')
  input.type = 'file'
  input.setAttribute('webkitdirectory', '')
  input.multiple = true
  input.onchange = () => {
    const files = input.files ? Array.from(input.files) : []
    if (files.length === 0 || !ai.conversations.some(c => c.id === conv.id)) return
    ai.attachUploadDirectory(conv, files)
  }
  input.click()
}

/**
 * The output location authorizes the HOST to save generated results there — it never widens
 * any worker's writable roots. Desktop shells only: a browser cannot host-save to the local
 * disk, so the entry stays hidden there (the web keeps download-based saving).
 */
async function chooseOutputLocation() {
  attachMenuOpen.value = false
  const conv = ai.ensureConversation()
  const desktop = makeDesktop()
  if (!desktop) return
  const path = await desktop.pickDirectory()
  if (!path) return
  try {
    await ai.setOutputTarget(conv, path)
  } catch (e) {
    attachFailure('output', e)
  }
}

async function clearOutputLocation() {
  const conv = ai.active
  if (!conv) return
  try {
    await ai.setOutputTarget(conv, null)
  } catch (e) {
    attachFailure('output', e)
  }
}

/**
 * The coding workspace is the folder the model's read/write/edit/grep/glob tools operate in.
 * Desktop picks it natively; the browser form asks for the absolute path (the backend runs on
 * this machine, so a typed local path is meaningful there too).
 */
async function chooseWorkspace() {
  attachMenuOpen.value = false
  const conv = ai.ensureConversation()
  const desktop = makeDesktop()
  if (desktop) {
    const path = await desktop.pickDirectory()
    if (!path) return
    try {
      await ai.setWorkspace(conv, path)
    } catch (e) {
      attachFailure('workspace', e)
    }
    return
  }
  workspacePathInput.value = conv.workspaceRoot ?? ''
  workspaceDialog.value = true
}

async function confirmWorkspacePath() {
  const path = workspacePathInput.value.trim()
  if (!path) return
  workspaceDialog.value = false
  const conv = ai.active
  if (!conv) return
  try {
    await ai.setWorkspace(conv, path)
  } catch (e) {
    attachFailure('workspace', e)
  }
}

async function clearWorkspace() {
  const conv = ai.active
  if (!conv) return
  try {
    await ai.setWorkspace(conv, null)
  } catch (e) {
    attachFailure('workspace', e)
  }
}

function removeResource(resource: ChatResource) {
  const conv = ai.active
  if (conv) ai.removeResource(conv, resource.resourceId)
}

/** Removing a draft attachment is purely local — nothing was ever sent or granted (E02). */
function removeDraftAttachment(attachment: { attachmentId: string }) {
  const conv = ai.active
  if (conv) ai.removeDraftAttachment(conv, attachment.attachmentId)
}

async function refreshResource(resource: ChatResource) {
  const conv = ai.active
  if (!conv) return
  try {
    await ai.refreshResource(conv, resource.resourceId)
  } catch (e) {
    ai.error = e instanceof Error && e.message ? e.message : t('aichat.refreshFailed')
  }
}

function folderBasename(path: string): string {
  return path.replace(/[\\/]+$/, '').split(/[\\/]/).pop() ?? path
}

/** Same-name files from different folders stay distinct chips; the parent disambiguates. */
function resourceSubtitle(resource: ChatResource): string {
  if (resource.kind === 'directory') return t('aichat.resourceFolder')
  return resource.source === 'native' && resource.displayPath
    ? (resource.displayPath.split(/[\\/]/).slice(-2, -1)[0] ?? '')
    : ''
}

function selectPermissionMode(mode: typeof ai.permissionMode) {
  if (ai.busy) return
  ai.permissionMode = mode
  permissionMenuOpen.value = false
}

async function selectModel(mode: AiMode) {
  if (modelSwitching.value || mode === settings.aiSettings?.activeMode) {
    modelMenuOpen.value = false
    return
  }
  modelSwitching.value = true
  try {
    await settings.updateAi({ mode })
    modelMenuOpen.value = false
  } catch (error) {
    ai.error = error instanceof Error ? error.message : t('aichat.modelSwitchFailed')
  } finally {
    modelSwitching.value = false
  }
}

function toggleVoiceInput() {
  if (speechRecognition && listening.value) {
    speechRecognition.stop()
    return
  }
  const speechWindow = window as typeof window & {
    SpeechRecognition?: new () => SpeechRecognitionLike
    webkitSpeechRecognition?: new () => SpeechRecognitionLike
  }
  const Recognition = speechWindow.SpeechRecognition ?? speechWindow.webkitSpeechRecognition
  if (!Recognition) {
    ai.error = t('aichat.voiceUnavailable')
    return
  }
  const recognition = new Recognition()
  speechRecognition = recognition
  recognition.lang = locale.value.startsWith('zh') ? 'zh-CN' : 'en-US'
  recognition.interimResults = false
  recognition.continuous = false
  recognition.onresult = (event) => {
    const transcript = event.results[0]?.[0]?.transcript?.trim()
    const el = editorEl.value
    if (transcript && el) {
      const { text } = serializeEditor()
      placeCaretAtEnd(el)
      document.execCommand('insertText', false, (text && !text.endsWith(' ') ? ' ' : '') + transcript)
      syncFromEditor()
    }
    void nextTick(autosize)
  }
  recognition.onerror = () => { listening.value = false }
  recognition.onend = () => {
    listening.value = false
    speechRecognition = null
  }
  listening.value = true
  recognition.start()
}

watch(() => ai.activeId, async () => {
  resourcesExpanded.value = false
  await nextTick()
  rebuildEditorFromConversation()
})

/** Exposed for the transcript's empty-state hero button, which shares the attach gesture. */
defineExpose({ chooseWorkspace })
</script>

<template>
  <!-- Conversation attachments: unsent drafts first, then committed resources — one chip per
       aggregated selection (never per plugin) -->
  <div v-if="ai.activeDraftAttachments.length || ai.activeResources.length || activeConv?.outputTarget || activeConv?.workspaceRoot || (activeConv?.attaching ?? 0) > 0" class="cx-conversation composer-strip">
    <div class="composer-strip__row">
      <span
        v-for="attachment in ai.activeDraftAttachments"
        :key="attachment.attachmentId"
        class="cx-chip composer-chip"
        :title="attachment.displayPath ?? attachment.name"
      >
        <i class="mdi" :class="attachment.kind === 'directory' ? 'mdi-folder' : 'mdi-file-outline'" />
        {{ attachment.name }}
        <span class="cx-muted composer-chip__hint">{{ $t('aichat.attachmentReadOnlyHint') }}</span>
        <button class="cx-iconbtn cx-iconbtn--sm" :title="$t('aichat.removeResource')" @click="removeDraftAttachment(attachment)">
          <i class="mdi mdi-close" />
        </button>
      </span>
      <button
        v-if="ai.turns.length > 0 && !resourcesExpanded && ai.activeResources.length"
        class="cx-chip composer-chip composer-chip--toggle"
        @click="resourcesExpanded = true"
      >
        <i class="mdi mdi-paperclip" />
        {{ $t('aichat.resourceCount', { count: ai.activeResources.length }) }}
        <i class="mdi mdi-chevron-down" />
      </button>
      <template v-if="showExpandedResources">
        <span
          v-for="resource in ai.activeResources"
          :key="resource.resourceId"
          class="cx-chip composer-chip"
          :title="resource.displayPath ?? resource.name"
        >
          <i class="mdi" :class="resource.kind === 'directory' ? 'mdi-folder' : 'mdi-file-outline'" />
          {{ resource.name }}
          <span class="cx-muted composer-chip__hint">
            {{ resourceSubtitle(resource) ? `${resourceSubtitle(resource)} · ` : '' }}{{ $t('aichat.resourceReadOnly') }}
          </span>
          <button
            v-if="resource.kind === 'file' && resource.source === 'native'"
            class="cx-iconbtn cx-iconbtn--sm"
            :title="$t('aichat.refreshResource')"
            @click="refreshResource(resource)"
          ><i class="mdi mdi-refresh" /></button>
          <button class="cx-iconbtn cx-iconbtn--sm" :title="$t('aichat.removeResource')" @click="removeResource(resource)">
            <i class="mdi mdi-close" />
          </button>
        </span>
      </template>
      <span v-if="(activeConv?.attaching ?? 0) > 0" class="cx-chip composer-chip">
        <span class="cx-spin" />{{ $t('aichat.attachInProgress') }}
      </span>
      <span v-if="activeConv?.outputTarget" class="cx-chip composer-chip" :title="activeConv.outputTarget">
        <i class="mdi mdi-content-save-outline" />
        {{ $t('aichat.saveToPrefix') }}{{ folderBasename(activeConv.outputTarget) }}
        <button
          v-if="isDesktop()"
          class="cx-iconbtn cx-iconbtn--sm"
          :title="$t('aichat.changeOutputFolder')"
          @click="chooseOutputLocation"
        ><i class="mdi mdi-pencil-outline" /></button>
        <button class="cx-iconbtn cx-iconbtn--sm" :title="$t('aichat.clearOutputFolder')" @click="clearOutputLocation">
          <i class="mdi mdi-close" />
        </button>
      </span>
      <span v-if="activeConv?.workspaceRoot" class="cx-chip composer-chip" :title="activeConv.workspaceRoot">
        <i class="mdi mdi-code-braces" />
        {{ $t('aichat.workspacePrefix') }}{{ folderBasename(activeConv.workspaceRoot) }}
        <button class="cx-iconbtn cx-iconbtn--sm" :title="$t('aichat.changeWorkspace')" @click="chooseWorkspace">
          <i class="mdi mdi-pencil-outline" />
        </button>
        <button class="cx-iconbtn cx-iconbtn--sm" :title="$t('aichat.clearWorkspace')" @click="clearWorkspace">
          <i class="mdi mdi-close" />
        </button>
      </span>
    </div>
  </div>

  <!-- Composer -->
  <div class="composer-zone">
    <div v-if="hasError" class="cx-alert cx-alert--error cx-conversation composer-error">
      <span class="cx-alert__body">{{ ai.error }}</span>
      <button class="cx-iconbtn cx-iconbtn--sm" @click="ai.error = null"><i class="mdi mdi-close" /></button>
    </div>

    <div class="cx-composer composer-card">
      <MentionPanel
        v-if="mention"
        class="composer-mention-anchor"
        :sections="mentionSections"
        :selected-index="mentionSelectedIndex"
        :loading="mentionLoading"
        :empty-label="mentionEmptyLabel"
        @select="applyMention"
        @hover="index => mentionSelectedIndex = index"
      />
      <div v-for="item in composerConfirmations" :key="item.confirmationId" class="composer-confirmation">
        <div class="composer-confirmation__title">
          <i class="mdi mdi-shield-outline" />{{ $t('aichat.confirmTitle') }}
        </div>
        <div v-for="row in item.summary" :key="row.label" class="composer-confirmation__row">
          <span class="cx-muted composer-confirmation__label">{{ row.label }}</span>
          <code class="composer-confirmation__value">{{ row.value }}</code>
        </div>
        <div v-if="item.status === 'pending'" class="composer-confirmation__actions">
          <button class="cx-btn cx-btn--primary cx-btn--sm" @click="ai.resolveConfirmation(item, true)">{{ $t('aichat.approveOnce') }}</button>
          <button class="cx-btn cx-btn--text cx-btn--sm" @click="ai.resolveConfirmation(item, false)">{{ $t('aichat.rejectSend') }}</button>
        </div>
        <div v-else-if="item.status === 'submitting'" class="cx-muted"><span class="cx-spin" /> {{ $t('aichat.submittingApproval') }}</div>
        <div v-else class="cx-alert cx-alert--error">{{ item.error }}</div>
      </div>
      <div class="composer-input-row">
        <div
          ref="editorEl"
          class="composer-editor"
          contenteditable="plaintext-only"
          role="textbox"
          aria-multiline="true"
          :aria-label="$t('aichat.placeholder')"
          spellcheck="false"
          @input="onEditorInput"
          @keydown="onKeydown"
          @keyup="updateMentionState"
          @click="updateMentionState"
          @blur="closeMention"
          @compositionstart="composing = true"
          @compositionend="onCompositionEnd"
          @paste="onEditorPaste"
        ></div>
        <span v-if="editorEmpty" class="composer-editor__placeholder" aria-hidden="true">
          {{ $t('aichat.placeholder') }}
        </span>
      </div>

      <div class="composer-toolbar">
        <div class="composer-toolbar__group">
          <button
            class="cx-iconbtn cx-iconbtn--round"
            :title="$t('aichat.addContext')"
            data-menu="attach"
            @click="attachMenuOpen = !attachMenuOpen"
          ><i class="mdi mdi-plus" /></button>
          <div v-if="attachMenuOpen" data-menu="attach" class="cx-card composer-menu composer-menu--attach">
            <div class="cx-muted composer-menu__hint composer-menu__hint--compact">{{ $t('aichat.attachMenuHint') }}</div>
            <button class="cx-btn cx-btn--text composer-menu__item" @click="attachFile">
              <i class="mdi mdi-file-outline" />
              <span class="composer-menu__stack">
                <span>{{ $t('aichat.attachFile') }}</span>
                <span class="cx-muted composer-menu__sub composer-menu__sub--sm">{{ $t('aichat.attachFileHint') }}</span>
              </span>
            </button>
            <button class="cx-btn cx-btn--text composer-menu__item" @click="attachDirectory">
              <i class="mdi mdi-folder-outline" />
              <span class="composer-menu__stack">
                <span>{{ $t('aichat.attachDirectory') }}</span>
                <span class="cx-muted composer-menu__sub composer-menu__sub--sm">{{ $t('aichat.attachDirectoryHint') }}</span>
              </span>
            </button>
            <button class="cx-btn cx-btn--text composer-menu__item" @click="chooseWorkspace">
              <i class="mdi mdi-code-braces" />
              <span class="composer-menu__stack">
                <span>{{ $t('aichat.setWorkspace') }}</span>
                <span class="cx-muted composer-menu__sub composer-menu__sub--sm">{{ $t('aichat.setWorkspaceHint') }}</span>
              </span>
            </button>
            <button v-if="isDesktop()" class="cx-btn cx-btn--text composer-menu__item" @click="chooseOutputLocation">
              <i class="mdi mdi-content-save-outline" />
              <span class="composer-menu__stack">
                <span>{{ $t('aichat.setOutputFolder') }}</span>
                <span class="cx-muted composer-menu__sub composer-menu__sub--sm">{{ $t('aichat.setOutputFolderHint') }}</span>
              </span>
            </button>
          </div>

          <!-- Browser workspace attach: the backend runs on this machine, so a typed local
               absolute path is the folder the coding tools will operate in. -->
          <div v-if="workspaceDialog" class="cx-card composer-workspace-dialog">
            <div class="composer-workspace-dialog__title">{{ $t('aichat.workspacePathPrompt') }}</div>
            <input
              v-model="workspacePathInput"
              class="cx-grow composer-workspace-dialog__input"
              :placeholder="$t('aichat.workspacePathPlaceholder')"
              @keydown.enter="confirmWorkspacePath"
            >
            <div class="composer-workspace-dialog__actions">
              <button class="cx-btn cx-btn--text cx-btn--sm" @click="workspaceDialog = false">{{ $t('common.cancel') }}</button>
              <button class="cx-btn cx-btn--primary cx-btn--sm" :disabled="!workspacePathInput.trim()" @click="confirmWorkspacePath">
                {{ $t('aichat.workspaceAttach') }}
              </button>
            </div>
          </div>

          <button data-menu="permission" class="cx-btn cx-btn--text cx-btn--sm composer-trigger" :style="ai.permissionMode === 'full-access' ? 'color: rgb(var(--v-theme-error))' : undefined" :disabled="ai.busy" @click="permissionMenuOpen = !permissionMenuOpen">
            <i class="mdi" :class="ai.permissionMode === 'full-access' ? 'mdi-shield-alert-outline' : 'mdi-shield-check-outline'" />
            {{ ai.permissionMode === 'ask-for-approval' ? $t('aichat.permissionAsk') : ai.permissionMode === 'approve-for-me' ? $t('aichat.permissionAuto') : $t('aichat.permissionFullAccess') }}
            <i class="mdi mdi-chevron-down" />
          </button>
          <div v-if="permissionMenuOpen && !ai.busy" data-menu="permission" class="cx-card composer-menu composer-menu--permission">
            <div class="cx-muted composer-menu__hint">{{ $t('aichat.permissionQuestion') }}</div>
            <button v-for="option in permissionOptions" :key="option.id" class="cx-btn cx-btn--text composer-menu__option" :style="option.id === 'full-access' ? 'color: rgb(var(--v-theme-error))' : undefined" @click="selectPermissionMode(option.id)">
              <i class="mdi composer-menu__option-icon" :class="option.icon" />
              <span class="composer-menu__stack composer-menu__stack--fill">
                <span class="composer-menu__option-title">{{ option.title }}</span>
                <span class="cx-muted composer-menu__option-desc">{{ option.description }}</span>
              </span>
              <i v-if="ai.permissionMode === option.id" class="mdi mdi-check" />
            </button>
          </div>
        </div>

        <div class="composer-toolbar__group">
          <button
            data-menu="model"
            class="cx-btn cx-btn--text cx-btn--sm composer-trigger--model"
            :disabled="modelSwitching || modelOptions.length === 0 || ai.busy"
            :title="$t('aichat.chooseModel')"
            @click="modelMenuOpen = !modelMenuOpen"
          >
            <i class="mdi mdi-lightning-bolt" />
            <span class="composer-model-name">{{ activeModel?.model ?? (modelOptions.length ? $t('aichat.selectModelShort') : $t('aichat.noConfiguredModelsShort')) }}</span>
            <span v-if="activeModel" class="cx-muted">{{ activeModel.provider }}</span>
            <i v-if="modelSwitching" class="mdi mdi-loading mdi-spin" />
            <i v-else class="mdi mdi-chevron-down" />
          </button>
          <div v-if="modelMenuOpen" data-menu="model" class="cx-card composer-menu composer-menu--model">
            <div class="cx-muted composer-menu__hint">{{ $t('aichat.configuredModels') }}</div>
            <button v-for="option in modelOptions" :key="option.mode" class="cx-btn cx-btn--text composer-menu__option composer-menu__option--model" @click="selectModel(option.mode)">
              <i class="mdi mdi-lightning-bolt" />
              <span class="composer-menu__stack composer-menu__stack--grow">
                <span class="composer-menu__option-title">{{ option.model }}</span>
                <span class="cx-muted composer-menu__sub">{{ option.provider }}</span>
              </span>
              <i v-if="settings.aiSettings?.activeMode === option.mode" class="mdi mdi-check" />
            </button>
          </div>

          <button class="cx-iconbtn cx-iconbtn--round" :class="{ 'cx-iconbtn--primary': listening }" :title="$t('aichat.voiceInput')" @click="toggleVoiceInput">
            <i class="mdi" :class="listening ? 'mdi-microphone' : 'mdi-microphone-outline'" />
          </button>
          <button
            v-if="ai.busy"
            class="cx-iconbtn cx-iconbtn--primary cx-iconbtn--round"
            :title="$t('aichat.stop')"
            @click="ai.stop()"
          ><i class="mdi mdi-stop" /></button>
            <button
              v-else
              class="cx-iconbtn cx-iconbtn--primary cx-iconbtn--round"
              :disabled="!composerArmed || modelSwitching || !activeModel"
              :title="$t('aichat.send')"
              @click="submit"
            ><i class="mdi mdi-arrow-up" /></button>
        </div>
      </div>
    </div>
    <div class="cx-conversation cx-muted composer-hint">
      {{ $t('aichat.hint') }}
    </div>
  </div>
</template>

<style scoped>
/* ── context chips strip ───────────────────────────────────────────────────────── */
.composer-strip { padding: 0 16px; }
.composer-strip__row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.cx-chip.composer-chip { gap: 6px; }
.cx-chip.composer-chip--toggle { cursor: pointer; }
.composer-chip__hint { font-size: 11px; }

/* ── composer zone ─────────────────────────────────────────────────────────────── */
.composer-zone { padding: 8px 16px 16px; }
.cx-alert.composer-error { margin-bottom: 8px; }

.cx-composer.composer-card {
  display: block;
  padding: 0;
  position: relative;
}

/* Tool approval prompts pinned above the editor. */
.composer-confirmation {
  padding: 12px 14px;
  border-bottom: 1px solid var(--cx-border);
}
.composer-confirmation__title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 650;
  margin-bottom: 7px;
}
.composer-confirmation__row {
  font-size: 12px;
  display: flex;
  gap: 8px;
  margin: 3px 0;
}
.composer-confirmation__label { min-width: 74px; }
.composer-confirmation__value { overflow-wrap: anywhere; }
.composer-confirmation__actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.composer-input-row {
  position: relative;
  padding: 10px 14px 2px;
}

/* Inline mention editor: plaintext-only contenteditable; chips are atomic DOM islands. */
.composer-editor {
  min-height: 31px;
  width: 100%;
  outline: none;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
  line-height: 1.6;
}
.composer-editor__placeholder {
  position: absolute;
  top: 10px;
  left: 14px;
  right: 14px;
  pointer-events: none;
  color: rgb(var(--v-theme-secondary));
  opacity: 0.6;
}

/* Mention token (ZCode's prompt-mention chip): category-colored, atomic, unselectable as text. */
.composer-token {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 7px;
  border-radius: 999px;
  border: 1px solid;
  font-size: 12px;
  font-weight: 550;
  line-height: 1.5;
  vertical-align: baseline;
  cursor: default;
  user-select: none;
}
.composer-token .mdi { font-size: 13px; }
.composer-token--file { color: rgb(var(--v-theme-tertiary)); border-color: rgb(var(--v-theme-tertiary)); }
.composer-token--skill { color: var(--cx-hl-keyword); border-color: var(--cx-hl-keyword); }
.composer-token--plugin { color: rgb(var(--v-theme-primary)); border-color: rgb(var(--v-theme-primary)); }

/* Completion panel: anchored to the composer card's top edge, full width (ZCode bottom-full). */
.composer-mention-anchor {
  position: absolute;
  left: 8px;
  right: 8px;
  bottom: calc(100% + 6px);
  z-index: 23;
}

.composer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 3px 10px 9px;
}
.composer-toolbar__group {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

/* ── popover menus (attach / permission / model) ───────────────────────────────── */
.cx-card.composer-menu {
  position: absolute;
  bottom: 48px;
  z-index: 22;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.18);
}
.cx-card.composer-menu--attach { left: 8px; min-width: 230px; padding: 7px; }
.cx-card.composer-menu--permission {
  left: 44px;
  width: min(440px, calc(100% - 52px));
  padding: 8px;
  z-index: 21;
}
.cx-card.composer-menu--model {
  right: 52px;
  width: min(380px, calc(100% - 16px));
  padding: 7px;
}
.composer-menu__hint { padding: 5px 10px 8px; font-size: 12px; }
.composer-menu__hint--compact { padding: 5px 10px 6px; font-size: 11px; }

.cx-btn.composer-menu__item {
  width: 100%;
  justify-content: flex-start;
}
.cx-btn.composer-menu__option {
  width: 100%;
  height: auto;
  justify-content: flex-start;
  text-align: left;
  padding: 10px;
  gap: 12px;
}
.cx-btn.composer-menu__option--model { padding: 9px 10px; gap: 10px; }

.composer-menu__stack { display: grid; text-align: left; }
.composer-menu__stack--fill { flex: 1; gap: 2px; }
.composer-menu__stack--grow { flex: 1; }
.composer-menu__sub { font-size: 12px; }
.composer-menu__sub--sm { font-size: 11px; }
.composer-menu__option-icon { font-size: 20px; }
.composer-menu__option-title { font-weight: 650; }
.composer-menu__option-desc { font-size: 12px; white-space: normal; }

.cx-btn.composer-trigger { padding: 3px 6px; }
.cx-btn.composer-trigger--model {
  padding: 3px 6px;
  max-width: min(320px, 42vw);
}
.composer-model-name {
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ── browser workspace attach dialog ───────────────────────────────────────────── */
.cx-card.composer-workspace-dialog {
  position: absolute;
  inset: auto 8px 44px 8px;
  padding: 12px;
  z-index: 22;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.18);
}
.composer-workspace-dialog__title { font-weight: 650; margin-bottom: 6px; }
.composer-workspace-dialog__input { width: 100%; padding: 6px 2px; font-size: 13px; }
.composer-workspace-dialog__actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 10px;
}

.composer-hint {
  text-align: center;
  font-size: 12px;
  margin-top: 8px;
}
</style>
