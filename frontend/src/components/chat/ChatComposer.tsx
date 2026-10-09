import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { LexicalComposer } from '@lexical/react/LexicalComposer'
import { ContentEditable } from '@lexical/react/LexicalContentEditable'
import { HistoryPlugin } from '@lexical/react/LexicalHistoryPlugin'
import { OnChangePlugin } from '@lexical/react/LexicalOnChangePlugin'
import { PlainTextPlugin } from '@lexical/react/LexicalPlainTextPlugin'
import { LexicalErrorBoundary } from '@lexical/react/LexicalErrorBoundary'
import { $createLineBreakNode, $createParagraphNode, $createTextNode, $getRoot, $insertNodes, $isElementNode, $isLineBreakNode, $isTextNode, $getSelection, $isRangeSelection, COMMAND_PRIORITY_CRITICAL, PASTE_COMMAND, type LexicalEditor, type TextNode, type RangeSelection } from 'lexical'
import { Check, ChevronDown, GitBranch, Plus, ArrowUp, Square, Mic, MicOff, Folder, FileImage, FileText, Clock, Pencil, Shield, ShieldAlert, X, Zap, HelpCircle, SlidersHorizontal } from 'lucide-react'
import { useAiSessionStore, inlineImagePreview } from '@/stores/aiSession'
import { questionAnswerable } from '@/lib/aiQuestion'
import {
  confirmationRetryable,
  dismissConfirmation,
  retryConfirmation,
} from '@/lib/aiConfirmation'
import { dismissQuestion, questionRetryable, retryQuestion } from '@/lib/aiQuestion'
import { useSettingsStore } from '@/stores/settings'
import { registerComposerKeyCommands, type ComposerKeyCommands } from './composerKeyCommands'
import { services } from '@/services'
import type { AiProviderEntry } from '@/services/types'
import { providerModelRows } from '@/lib/aiProviders'
import { buildMentionMarkdown, extractActiveMention, type MentionTrigger } from '@/lib/mentionTriggers'
import { buildMentionSections, flattenMentionSections, type MentionOption, type MentionSection } from '@/lib/mentionSearch'
import { FLOW_CHAT_SEED_KEY } from '@/lib/flowSeed'
import { getPlatform } from '@/platform'
import { appPrompt } from '@/lib/appDialogs'
import MentionPanel from './MentionPanel'
import WorkspaceBranchMenu from './WorkspaceBranchMenu'
import { PromptMentionNode, $createPromptMentionNode, $isPromptMentionNode, type PromptMentionPayload } from './PromptMentionNode'
import { useMentionPools, toPayload } from './mentionPools'
import { cn } from '@/lib/utils'
import '@/styles/composer.css'

/**
 * Lexical-based chat composer: plain-text editing with inline atomic
 * mention tokens, @ (plugins/files/flows) and $ (skills) completion panels, approval prompts,
 * permission/model menus, voice input, and send/stop. The editor DOM is the source of truth —
 * send serializes text + mention markdown directly from the node tree.
 * `centered` renders the draft-screen variant (narrow column under the greeting).
 */
export default function ChatComposer({ centered = false, onAttachWorkspace }: {
  centered?: boolean
  /** Shared attach gesture — the page owns the browser typed-path dialog / native picker. */
  onAttachWorkspace?: () => void
}) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  // Narrow subscriptions (no whole-store hook): the store mutates conversation rows
  // in place and republishes the array, so the per-slice reads below are either
  // scalars or cheap strings that only change when the rendered value truly does —
  // a token flush of ANY conversation must not re-render the composer subtree.
  const error = useAiSessionStore(state => state.error)
  const permissionMode = useAiSessionStore(state => state.permissionMode)
  const loadAi = useSettingsStore(state => state.loadAi)
  const pools = useMentionPools()

  const [mentionState, setMentionState] = useState<{ trigger: MentionTrigger; query: string } | null>(null)
  const [sections, setSections] = useState<MentionSection[]>([])
  const [selectedIndex, setSelectedIndex] = useState(0)
  const [dismissedSignature, setDismissedSignature] = useState<string | null>(null)
  const [attachMenuOpen, setAttachMenuOpen] = useState(false)
  const [permissionMenuOpen, setPermissionMenuOpen] = useState(false)
  const [modelMenuOpen, setModelMenuOpen] = useState(false)
  const [branchMenuOpen, setBranchMenuOpen] = useState(false)
  const [modelSwitching, setModelSwitching] = useState(false)
  const [listening, setListening] = useState(false)
  const [editorEmpty, setEditorEmpty] = useState(true)
  const editorRef = useRef<LexicalEditor | null>(null)
  const recognitionRef = useRef<{ stop: () => void } | null>(null)
  const activeId = useAiSessionStore(state => state.activeId)

  // The composer menus (attach / permission / model) are mutually exclusive, close on
  // outside pointer-down and on Escape — standard popover behavior.
  const zoneRef = useRef<HTMLDivElement | null>(null)
  const closeComposerMenus = useCallback(() => {
    setAttachMenuOpen(false)
    setPermissionMenuOpen(false)
    setModelMenuOpen(false)
  }, [])
  const toggleAttachMenu = () => { setAttachMenuOpen(open => !open); setPermissionMenuOpen(false); setModelMenuOpen(false) }
  const togglePermissionMenu = () => { setPermissionMenuOpen(open => !open); setAttachMenuOpen(false); setModelMenuOpen(false) }
  const toggleModelMenu = () => { setModelMenuOpen(open => !open); setAttachMenuOpen(false); setPermissionMenuOpen(false) }
  useEffect(() => {
    const onPointerDown = (event: PointerEvent) => {
      if (!zoneRef.current?.contains(event.target as Node)) closeComposerMenus()
    }
    const onKeydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeComposerMenus()
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeydown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeydown)
    }
  }, [closeComposerMenus])

  // Everything the composer renders about the active conversation, flattened into a
  // cheap string: rows are mutated in place, so identity selectors would miss those
  // edits while token flushes of OTHER conversations would trigger needlessly. The
  // string only changes when one of the exact reads below changes; the row itself
  // is then read live at render time.
  const activeSignature = useAiSessionStore(state => {
    const conv = state.conversations.find(item => item.id === state.activeId)
    if (!conv) return ''
    return [
      // The conversation id itself: two identically-shaped conversations must not
      // share a signature (the memo below would otherwise keep the stale one).
      state.activeId,
      conv.streaming ? 1 : 0,
      conv.turns.length,
      conv.draftMentions.length,
      conv.draftAttachments.map(item => item.attachmentId).join('.'),
      conv.queue.map(item => `${item.id}:${item.streamId ?? ''}:${item.prompt}`).join('.'),
      conv.workspaceRoot ?? '',
      conv.usage ? `${conv.usage.updatedAt}:${conv.usage.contextTokens}:${conv.usage.contextWindowTokens}:${conv.usage.compacted ? 1 : 0}` : '0',
    ].join('|')
  })
  const activeConv = useMemo(
    () => useAiSessionStore.getState().active(),
    [activeSignature])
  // Per-conversation streaming: the composer's stop/send state follows the ACTIVE
  // conversation only — background conversations streaming in parallel never lock it.
  const activeStreaming = activeConv?.streaming === true

  // Model menu reads the provider REGISTRY (B4), not the legacy four-slot mirror:
  // after the registry migration the flat key fields are blanked, so the legacy
  // list would hide every cloud provider. The active seat is the registry's own
  // activeProvider; per-provider model lists come from the vendor listing endpoint.
  const [providers, setProviders] = useState<AiProviderEntry[]>([])
  const [activeProviderId, setActiveProviderId] = useState('')
  const [providerModelLists, setProviderModelLists] = useState<
    Record<string, { loading: boolean; models: string[] }>
  >({})
  const [thinkingCfg, setThinkingCfg] = useState<{ level: string; levels: string[] }>({ level: 'off', levels: ['off'] })
  // Per-group manual model entry: the provider id whose "other model…" row is
  // open, plus the draft value. Null = all rows collapsed.
  const [customEntry, setCustomEntry] = useState<string | null>(null)
  const [customValue, setCustomValue] = useState('')
  const reloadRegistry = useCallback(async () => {
    const list = await services.aiConfig.listProviders().catch(() => null)
    if (list) {
      setProviders(list.providers)
      setActiveProviderId(list.activeProvider)
    }
    const cfg = await services.aiConfig.get().catch(() => null)
    if (cfg) {
      setThinkingCfg({ level: cfg.thinkingLevel ?? 'off', levels: cfg.thinkingLevels?.length ? cfg.thinkingLevels : ['off'] })
    }
  }, [])
  useEffect(() => { void reloadRegistry() }, [reloadRegistry])
  const activeEntry = providers.find(p => p.id === activeProviderId) ?? null
  // Interactive gate cards (approval prompts / ask_user questions) across every
  // conversation. A string signature rerender-triggers on exactly the states the
  // cards render (identity + status); the items themselves are read live at render.
  const confirmationSignature = useAiSessionStore(state => state.conversations
    .flatMap(conv => conv.turns.flatMap(turn => turn.confirmations))
    .map(item => `${(item as { confirmationId: string }).confirmationId}:${(item as { status: string }).status}`)
    .join('|'))
  const composerConfirmations = useMemo(() => useAiSessionStore.getState().conversations
    .flatMap(conv => conv.turns.flatMap(turn => turn.confirmations))
    .filter((item): item is import('@/lib/aiConfirmation').ToolConfirmation =>
      ['pending', 'submitting', 'error'].includes(String((item as { status: string }).status))),
    [confirmationSignature])
  const questionSignature = useAiSessionStore(state => state.conversations
    .flatMap(conv => conv.turns.flatMap(turn => turn.questions ?? []))
    .map(item => `${(item as { questionId: string }).questionId}:${(item as { status: string }).status}`)
    .join('|'))
  const composerQuestions = useMemo(() => useAiSessionStore.getState().conversations
    .flatMap(conv => conv.turns.flatMap(turn => turn.questions ?? []))
    .filter((item): item is import('@/lib/aiQuestion').QuestionCardState =>
      ['pending', 'submitting', 'error'].includes(String((item as { status: string }).status))),
    [questionSignature])

  const flatOptions = useMemo(() => flattenMentionSections(sections), [sections])
  const mentionEmptyLabel = mentionState?.query
    ? t('aichat.mentionEmpty')
    : t(mentionState?.trigger === '$' ? 'aichat.mentionSkillHint' : 'aichat.mentionSearchHint')

  // Rebuild sections whenever the active token or pools change.
  useEffect(() => {
    if (!mentionState) {
      setSections([])
      setSelectedIndex(0)
      return
    }
    setSections(buildMentionSections(mentionState.trigger, pools, mentionState.query))
    setSelectedIndex(0)
  }, [mentionState, pools])

  // ── token detection from the editor state (update-listener side lives in the plugin) ──
  const handleTextBeforeCaret = useCallback((text: string) => {
    const active = extractActiveMention(text)
    if (!active || `${active.trigger}:${active.query}` === dismissedSignature) {
      setMentionState(null)
      return
    }
    setMentionState(previous =>
      previous?.trigger === active.trigger && previous?.query === active.query
        ? previous
        : { trigger: active.trigger, query: active.query })
  }, [dismissedSignature])

  const insertMention = useCallback((option: MentionOption) => {
    const editor = editorRef.current
    const state = mentionState
    if (!editor || !state) return
    if (option.category === 'flow') {
      // Flow special case: flows hand off instead of inserting a token.
      sendInputToFlow(option)
      return
    }
    if (option.category === 'command' && state.trigger === '/') {
      // Slash command: replace the `/query` token with the expanded prompt template
      // ($ARGUMENTS / {{input}} take the filter text, else vanish).
      const expanded = option.value
        .replace(/\$ARGUMENTS|\{\{input\}\}/g, state.query.trim())
        .trim()
      editor.update(() => {
        const anchor = anchorTextOf($getSelection())
        if (!anchor) return
        const { node, offset } = anchor
        const text = node.getTextContent()
        const start = offset - state.query.length - 1
        if (start < 0 || text[start] !== '/') return
        node.spliceText(start, state.query.length + 1, '', true)
        // Real line-break nodes: a \n inside one TextNode serializes but never renders in
        // the contenteditable, so multi-line templates insert as proper paragraphs.
        const lines = expanded.split('\n')
        const nodes: import('lexical').LexicalNode[] = []
        lines.forEach((line, lineIndex) => {
          if (lineIndex > 0) nodes.push($createLineBreakNode())
          if (line) nodes.push($createTextNode(line))
        })
        if (nodes.length === 0) nodes.push($createTextNode(''))
        $insertNodes(nodes)
      })
      setMentionState(null)
      setDismissedSignature(null)
      editor.focus()
      return
    }
    editor.update(() => {
      const anchor = anchorTextOf($getSelection())
      if (!anchor) return
      const { node, offset } = anchor
      const text = node.getTextContent()
      const start = offset - state.query.length - 1
      const symbol = text[start]
      const accepted = state.trigger === '@' ? symbol === '@' : /[$¥￥]/.test(symbol ?? '')
      if (start < 0 || !accepted) return
      node.spliceText(start, state.query.length + 1, '', true)
      $insertNodes([$createPromptMentionNode(toPayload(option)), $createTextNode(' ')])
    })
    setMentionState(null)
    setDismissedSignature(null)
    editor.focus()
  }, [mentionState])

  /** Flow picks carry the current editor content to the target flow's chat via a seed. */
  function sendInputToFlow(option: MentionOption) {
    const editor = editorRef.current
    if (!editor) return
    const serialized = serializeEditorState(editor)
    const mentionBlock = serialized.mentions.map(item => item.markdown).join('\n')
    const full = serialized.text + (mentionBlock ? `\n\n${mentionBlock}` : '')
    editor.update(() => {
      $getRoot().clear()
    })
    const conv = useAiSessionStore.getState().active()
    if (conv) {
      conv.draft = ''
      conv.draftMentions = []
    }
    setMentionState(null)
    setDismissedSignature(null)
    try {
      sessionStorage.setItem(FLOW_CHAT_SEED_KEY, JSON.stringify({ workflowId: option.value, text: full, at: Date.now() }))
    } catch {
      /* jump still lands */
    }
    navigate(`/flows/${encodeURIComponent(option.value)}`)
  }

  // ── keyboard commands (CRITICAL priority — ahead of the editor's own handling) ─────
  // The handlers resolve the CURRENT commands object at event time through a ref,
  // so a late registry load (or any other re-render) can never leave Enter running
  // a stale submit closure. Identity stays stable → one registration per editor.
  const keyCommandsRef = useRef<ComposerKeyCommands>(null!)
  keyCommandsRef.current = {
    getMentionState: () => ({
      active: mentionState !== null,
      optionCount: flatOptions.length,
      selectedIndex,
    }),
    moveSelection: delta => setSelectedIndex(index =>
      (index + delta + flatOptions.length) % flatOptions.length),
    insertMentionAt: index => { if (flatOptions[index]) insertMention(flatOptions[index]) },
    getSubmit: () => submit,
    dismissMention: () => {
      if (mentionState) {
        setDismissedSignature(`${mentionState.trigger}:${mentionState.query}`)
        setMentionState(null)
      }
    },
    stopActiveStream: () => {
      const state = useAiSessionStore.getState()
      const streamingConv = state.conversations.find(c => c.id === state.activeId)
      if (streamingConv?.streaming) {
        state.stop(streamingConv)
        return true
      }
      return false
    },
  }
  const registerKeyCommands = useCallback(
    (editor: LexicalEditor) => registerComposerKeyCommands(editor, () => keyCommandsRef.current),
    [])

  // Image paste/drop → inline vision attachments (shared reader path); a file-mention
  // drop (from the workspace panel's tree) inserts an @-chip instead.
  const handleImageFiles = useCallback((files: File[]) => {
    const images = files.filter(file => file.type.startsWith('image/'))
    if (images.length === 0) return
    void readImageFiles(images).then(loaded => {
      if (loaded.length === 0) return
      const conv = useAiSessionStore.getState().active()
      if (conv) useAiSessionStore.getState().attachImages(conv, loaded)
    })
  }, [])

  // Stable callback identity: BootstrapPlugin re-registers on every identity change, which
  // would re-run the key-command setup on every render.
  const onPasteImages = useCallback((files: File[]): boolean => {
    handleImageFiles(files)
    return true
  }, [handleImageFiles])

  const insertFileMention = useCallback((path: string, name: string, icon: string) => {
    const editor = editorRef.current
    if (!editor) return
    editor.update(() => {
      $insertNodes([
        $createPromptMentionNode({
          id: `file:${path}`,
          category: 'file',
          label: name,
          description: path,
          value: path,
          markdown: buildMentionMarkdown('file', name, path, false),
          icon: icon || 'mdi-file-document-outline',
        }),
        $createTextNode(' '),
      ])
    })
    editor.focus()
  }, [])

  const onCardDrop = useCallback((event: React.DragEvent) => {
    const mention = event.dataTransfer?.getData('application/x-fengyu-file')
    if (mention) {
      event.preventDefault()
      try {
        const parsed = JSON.parse(mention) as { path: string; name: string; icon?: string }
        insertFileMention(parsed.path, parsed.name, parsed.icon ?? '')
      } catch {
        /* malformed payload ignored */
      }
      return
    }
    const files = Array.from(event.dataTransfer?.files ?? [])
    if (files.length === 0) return
    event.preventDefault()
    handleImageFiles(files)
  }, [handleImageFiles, insertFileMention])

  const submit = useCallback(() => {
    const editor = editorRef.current
    const store = useAiSessionStore.getState()
    if (!editor || modelSwitching) return
    const serialized = serializeEditorState(editor)
    const mentionBlock = serialized.mentions.map(item => item.markdown).join('\n')
    const full = serialized.text + (mentionBlock ? `\n\n${mentionBlock}` : '')
    if (!full.trim()) return
    if (!activeEntry) {
      useAiSessionStore.setState({ error: t('aichat.noConfiguredModels') })
      return
    }
    editor.update(() => {
      $getRoot().clear()
    })
    const conv = store.active()
    if (conv) {
      conv.draft = ''
      conv.draftMentions = []
    }
    setEditorEmpty(true)
    // While busy the store queues the send (same conversation) — the composer still clears.
    void store.send(full)
  }, [modelSwitching, activeEntry, t])

  // ── draft sync: mirror the editor into the active conversation ─────────────────────
  const onEditorChange = useCallback(() => {
    const editor = editorRef.current
    if (!editor) return
    const serialized = serializeEditorState(editor)
    const conv = useAiSessionStore.getState().active()
    if (conv) {
      conv.draft = serialized.text
      conv.draftMentions = serialized.mentions.map(toPayload)
    }
    setEditorEmpty(serialized.text.length === 0 && serialized.mentions.length === 0)
    // Mention token detection runs on the same change (caret-relative prefix).
    const prefix = textBeforeCaret(editor)
    handleTextBeforeCaret(prefix)
  }, [handleTextBeforeCaret])

  // Conversation switch → rebuild the editor from the conversation's draft + mentions.
  const restoredIdRef = useRef<number | null>(null)
  useEffect(() => {
    const editor = editorRef.current
    if (!editor || activeId === null) return
    const conv = useAiSessionStore.getState().conversations.find(item => item.id === activeId)
    if (!conv || restoredIdRef.current === activeId) return
    restoredIdRef.current = activeId
    editor.update(() => {
      const root = $getRoot()
      root.clear()
      root.append($createParagraphNode())
      const paragraph = root.getFirstChildOrThrow() as import('lexical').ElementNode
      let target: TextNode | PromptMentionNode | null = null
      if (conv.draft) {
        target = $createTextNode(conv.draft)
        paragraph.append(target)
      }
      for (const mention of conv.draftMentions) {
        const node = $createPromptMentionNode(mention)
        paragraph.append(node)
        target = node
      }
      if (target) target.selectEnd()
    })
    setMentionState(null)
    setDismissedSignature(null)
  }, [activeId])

  // ── suggested-prompt seeds: DraftHome chips hand their text to the editor ──────────
  useEffect(() => {
    const onSeed = (event: Event) => {
      const text = (event as CustomEvent<{ text?: string }>).detail?.text ?? ''
      const editor = editorRef.current
      if (!editor) return
      editor.update(() => {
        const root = $getRoot()
        root.clear()
        if (!text) return
        const paragraph = $createParagraphNode()
        paragraph.append($createTextNode(text))
        root.append(paragraph)
        paragraph.getLastChild()?.selectEnd()
      })
      setEditorEmpty(!text)
      if (!text) return
      const conv = useAiSessionStore.getState().active()
      if (conv) {
        conv.draft = text
        conv.draftMentions = []
      }
      editor.focus()
    }
    window.addEventListener('fengyu:composer-seed', onSeed)
    return () => window.removeEventListener('fengyu:composer-seed', onSeed)
  }, [])

  // ── attachments / workspace / output gestures ──────────────────────────────────────
  const ensureConversation = () => useAiSessionStore.getState().ensureConversation()

  async function chooseWorkspace() {
    setAttachMenuOpen(false)
    const conv = ensureConversation()
    const platform = getPlatform()
  const desktop = platform.capabilities.nativeFileDialogs ? platform : null
    if (desktop) {
      const path = await desktop.pickDirectory()
      if (!path) return
      try {
        await useAiSessionStore.getState().setWorkspace(conv, path)
      } catch {
        useAiSessionStore.setState({ error: t('aichat.workspaceSetFailed') })
      }
      return
    }
    const path = await appPrompt(t('aichat.workspacePathPrompt'))
    if (path && path.trim()) {
      try {
        await useAiSessionStore.getState().setWorkspace(conv, path.trim())
      } catch {
        useAiSessionStore.setState({ error: t('aichat.workspaceSetFailed') })
      }
    }
  }

  async function chooseOutputLocation() {
    setAttachMenuOpen(false)
    const conv = ensureConversation()
    const platform = getPlatform()
  const desktop = platform.capabilities.nativeFileDialogs ? platform : null
    if (!desktop) return
    const path = await desktop.pickDirectory()
    if (!path) return
    try {
      await useAiSessionStore.getState().setOutputTarget(conv, path)
    } catch {
      useAiSessionStore.setState({ error: t('aichat.outputTargetFailed') })
    }
  }

  async function attachFile() {
    setAttachMenuOpen(false)
    const conv = ensureConversation()
    const platform = getPlatform()
  const desktop = platform.capabilities.nativeFileDialogs ? platform : null
    if (desktop) {
      const path = await desktop.pickFile()
      if (path && useAiSessionStore.getState().conversations.some(item => item.id === conv.id)) {
        useAiSessionStore.getState().attachNative(conv, path, 'file')
      }
      return
    }
    const input = document.createElement('input')
    input.type = 'file'
    input.onchange = () => {
      const file = input.files?.[0]
      if (file && useAiSessionStore.getState().conversations.some(item => item.id === conv.id)) {
        useAiSessionStore.getState().attachUpload(conv, file)
      }
    }
    input.click()
  }

  async function attachDirectory() {
    setAttachMenuOpen(false)
    const conv = ensureConversation()
    const platform = getPlatform()
  const desktop = platform.capabilities.nativeFileDialogs ? platform : null
    if (desktop) {
      const path = await desktop.pickDirectory()
      if (path && useAiSessionStore.getState().conversations.some(item => item.id === conv.id)) {
        useAiSessionStore.getState().attachNative(conv, path, 'directory')
      }
      return
    }
    const input = document.createElement('input')
    input.type = 'file'
    input.setAttribute('webkitdirectory', '')
    input.multiple = true
    input.onchange = () => {
      const files = input.files ? Array.from(input.files) : []
      if (files.length > 0 && useAiSessionStore.getState().conversations.some(item => item.id === conv.id)) {
        useAiSessionStore.getState().attachUploadDirectory(conv, files)
      }
    }
    input.click()
  }

  // Lazy per-provider model lists: fetched the first time the menu opens, cached
  // in component state for the session (the menu re-reads on registry reload).
  useEffect(() => {
    if (!modelMenuOpen) return
    for (const p of providers) {
      if (providerModelLists[p.id]) continue
      setProviderModelLists(prev => ({ ...prev, [p.id]: { loading: true, models: [] } }))
      void services.aiConfig.providerModels(p.id)
        .then(out => setProviderModelLists(prev =>
          ({ ...prev, [p.id]: { loading: false, models: out.models } })))
        .catch(() => setProviderModelLists(prev =>
          ({ ...prev, [p.id]: { loading: false, models: [] } })))
    }
  }, [modelMenuOpen, providers, providerModelLists])

  /** Point the chat at (provider, model): persist the model, then activate —
   *  activation rebuilds the backend from the definition, so it must run after
   *  the model write. Re-activating the same provider is the model-change path. */
  async function applyModel(providerId: string, model: string) {
    if (modelSwitching) { setModelMenuOpen(false); return }
    if (providerId === activeProviderId && model === activeEntry?.model) {
      setModelMenuOpen(false)
      return
    }
    setModelSwitching(true)
    try {
      await services.aiConfig.updateProvider(providerId, { model })
      await services.aiConfig.activateProvider(providerId)
      await reloadRegistry()
      await loadAi()
      setCustomEntry(null)
      setCustomValue('')
      setModelMenuOpen(false)
    } catch (error) {
      useAiSessionStore.setState({ error: error instanceof Error ? error.message : t('aichat.modelSwitchFailed') })
    } finally {
      setModelSwitching(false)
    }
  }

  async function applyThinking(level: string) {
    if (level === thinkingCfg.level) return
    setThinkingCfg(prev => ({ ...prev, level }))
    try {
      await services.aiConfig.update({ thinkingLevel: level })
    } catch {
      // optimistic undo — reload restores the backend's truth
    }
    await reloadRegistry()
  }

  function toggleVoiceInput() {
    const recognition = recognitionRef.current
    if (recognition && listening) {
      recognition.stop()
      return
    }
    const SpeechRecognition = (window as unknown as {
      SpeechRecognition?: new () => SpeechRecognitionLike
      webkitSpeechRecognition?: new () => SpeechRecognitionLike
    }).SpeechRecognition ?? (window as unknown as { webkitSpeechRecognition?: new () => SpeechRecognitionLike }).webkitSpeechRecognition
    if (!SpeechRecognition) {
      useAiSessionStore.setState({ error: t('aichat.voiceUnavailable') })
      return
    }
    const instance = new SpeechRecognition()
    recognitionRef.current = instance
    instance.lang = i18n.language?.startsWith('zh') ? 'zh-CN' : 'en-US'
    instance.interimResults = false
    instance.continuous = false
    instance.onresult = (event) => {
      const transcript = event.results[0]?.[0]?.transcript?.trim()
      const editor = editorRef.current
      if (transcript && editor) {
        editor.update(() => {
          const anchor = anchorTextOf($getSelection())
          if (anchor) anchor.node.spliceText(anchor.offset, 0, transcript, true)
          else ($getRoot().getFirstChildOrThrow() as import('lexical').ElementNode).append($createTextNode(transcript))
        })
      }
    }
    instance.onerror = () => setListening(false)
    instance.onend = () => {
      setListening(false)
      recognitionRef.current = null
    }
    setListening(true)
    instance.start()
  }

  // A recognition left running must stop when the composer unmounts (route switch):
  // the recognizer instance and the mic state are this component's alone.
  useEffect(() => () => { recognitionRef.current?.stop() }, [])

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

  const hasError = error !== null

  // Context header (ZCode pattern): the coding-workspace pill + branch selector live
  // INSIDE the composer card above the input — for every conversation with a
  // workspace attached, plus the draft screen's empty "attach workspace" affordance.
  const workspaceRoot = activeConv?.workspaceRoot ?? ''
  const workspaceName = workspaceRoot.replace(/[\\/]+$/, '').split(/[\\/]/).pop() ?? ''
  const workspaceBranch = activeConv?.workspaceBranch ?? null
  const conversationEmpty = (activeConv?.turns.length ?? 0) === 0
  const showWorkspacePill = conversationEmpty || Boolean(workspaceRoot)
  const pickWorkspace = () => {
    setAttachMenuOpen(false)
    if (onAttachWorkspace) {
      onAttachWorkspace()
      return
    }
    void chooseWorkspace()
  }
  const clearActiveWorkspace = async () => {
    const conv = useAiSessionStore.getState().active()
    if (!conv) return
    try {
      await useAiSessionStore.getState().setWorkspace(conv, null)
    } catch {
      useAiSessionStore.setState({ error: t('aichat.workspaceSetFailed') })
    }
  }
  const toggleBranchMenu = () => {
    setAttachMenuOpen(false)
    setBranchMenuOpen(open => !open)
  }

  return (
    <div ref={zoneRef} className={cn('composer-zone', { 'composer-zone--centered': centered })}>
      {hasError && (
        <div className="cx-alert cx-alert--error cx-conversation composer-error">
          <span className="cx-alert__body">{error}</span>
          <button className="cx-iconbtn cx-iconbtn--sm" onClick={() => useAiSessionStore.setState({ error: null })}>×</button>
        </div>
      )}

      {/*
        onDragOver MUST preventDefault or the browser never fires onDrop (HTML5 DnD) —
        without it dropped files navigate the page away instead of attaching, and the
        workspace panel's drag-out @-chips die entirely.
      */}
      <div className="cx-composer composer-card" onDragOver={event => event.preventDefault()} onDrop={onCardDrop}>
        {mentionState && (
          <div className="composer-mention-anchor">
            <MentionPanel
              sections={sections}
              selectedIndex={selectedIndex}
              loading={false}
              emptyLabel={mentionEmptyLabel}
              onSelect={insertMention}
              onHover={setSelectedIndex}
            />
          </div>
        )}

        {composerConfirmations.map(item => (
          <ConfirmationCard key={item.confirmationId} item={item} />
        ))}

        {composerQuestions.map(item => (
          <QuestionCard key={item.questionId} item={item} />
        ))}

        {(activeConv?.queue.length ?? 0) > 0 && (
          <div className="composer-queue">
            {activeConv!.queue.map(item => (
              <QueueRow
                key={item.id}
                item={item}
                onRemove={() => useAiSessionStore.getState().removeQueuedSend(activeConv!, item.id)}
                onEdit={text => useAiSessionStore.getState().editQueuedSend(activeConv!, item.id, text)}
              />
            ))}
          </div>
        )}

        {showWorkspacePill && (
          <div className="composer-context">
            <button
              className={cn('composer-context-pill', !workspaceName && 'composer-context-pill--empty')}
              title={workspaceName ? workspaceRoot : t('aichat.setWorkspace')}
              onClick={pickWorkspace}
            >
              <Folder size={16} />
              <span>{workspaceName || t('aichat.setWorkspace')}</span>
              <ChevronDown size={14} className="composer-context-pill__chevron" />
            </button>
            {/* Branch switcher as its own pill beside the workspace pill (upstream
                workspace-header pattern) — one icon + label + chevron per control. */}
            {workspaceBranch && (
              <button
                className="composer-context-pill composer-context-pill--branch"
                title={t('aichat.workspaceBranchSwitchTitle')}
                onClick={toggleBranchMenu}
              >
                <GitBranch size={14} />
                <span>{workspaceBranch}</span>
                <ChevronDown size={14} className="composer-context-pill__chevron" />
              </button>
            )}
            {branchMenuOpen && activeConv && (
              <WorkspaceBranchMenu conv={activeConv} onClose={() => setBranchMenuOpen(false)} />
            )}
            {workspaceRoot && (
              <button
                className="cx-iconbtn cx-iconbtn--sm composer-context__clear"
                title={t('aichat.clearWorkspace')}
                onClick={() => void clearActiveWorkspace()}
              >
                <X size={13} />
              </button>
            )}
          </div>
        )}

        {/* Draft attachments: chips inside the card above the input,
            media-first ordering is moot (files/dirs only), hover reveals the remove X.
            Pasted screenshots preview as thumbnails (ZCode-style), not icon chips. */}
        {(activeConv?.draftAttachments.length ?? 0) > 0 && (
          <div className="composer-attach-row">
            {activeConv!.draftAttachments.map(attachment => {
              const imagePreview = attachment.source === 'pasted-image'
                ? inlineImagePreview(attachment.attachmentId)
                : null
              return (
                <span
                  key={attachment.attachmentId}
                  className={cn('composer-attach-chip',
                    imagePreview && 'composer-attach-chip--image')}
                  title={attachment.displayPath ?? attachment.name}
                >
                  {imagePreview ? (
                    <img className="composer-attach-chip__thumb" src={imagePreview} alt="" draggable={false} />
                  ) : (
                    <span className="composer-attach-chip__icon">
                      {attachmentIcon(attachment)}
                    </span>
                  )}
                  <span className="composer-attach-chip__text">
                    <span className="composer-attach-chip__name">{attachment.name}</span>
                    {attachment.displayPath && (
                      <span className="composer-attach-chip__sub">{attachment.displayPath}</span>
                    )}
                  </span>
                  <button
                    className="composer-attach-chip__remove"
                    title={t('aichat.removeAttachment')}
                    onClick={() => useAiSessionStore.getState().removeDraftAttachment(activeConv!, attachment.attachmentId)}
                  >
                    <X size={12} />
                  </button>
                </span>
              )
            })}
          </div>
        )}

        <LexicalComposer initialConfig={initialConfig}>
          <div className="composer-input-row">
            <PlainTextPlugin
              contentEditable={<ContentEditable className="composer-editor" ariaLabel={t('aichat.placeholder')} spellCheck={false} />}
              placeholder={<span className="composer-editor__placeholder">{t('aichat.placeholder')}</span>}
              ErrorBoundary={LexicalErrorBoundary}
            />
            <HistoryPlugin />
            <OnChangePlugin onChange={onEditorChange} />
            <BootstrapPlugin
              onReady={editor => { editorRef.current = editor }}
              registerKeyCommands={registerKeyCommands}
              onPasteImages={onPasteImages}
            />
          </div>
        </LexicalComposer>

        <div className="composer-toolbar">
          <div className="composer-toolbar__group">
            <div style={{ position: 'relative' }} data-menu="attach">
              <button className="cx-iconbtn cx-iconbtn--round" title={t('aichat.addContext')} onClick={toggleAttachMenu}>
                <Plus size={18} />
              </button>
              {attachMenuOpen && (
                <div className="cx-card composer-menu composer-menu--attach" data-menu="attach">
                  <MenuItem icon={<Plus size={15} />} title={t('aichat.attachFile')} hint={t('aichat.attachFileHint')} onClick={attachFile} />
                  <MenuItem icon={<Plus size={15} />} title={t('aichat.attachDirectory')} hint={t('aichat.attachDirectoryHint')} onClick={attachDirectory} />
                  <MenuItem icon={<Plus size={15} />} title={t('aichat.setWorkspace')} hint={t('aichat.setWorkspaceHint')} onClick={pickWorkspace} />
                  {getPlatform().capabilities.nativeFileDialogs && <MenuItem icon={<Plus size={15} />} title={t('aichat.setOutputFolder')} hint={t('aichat.setOutputFolderHint')} onClick={chooseOutputLocation} />}
                </div>
              )}
            </div>

            <button
              className="cx-btn cx-btn--text cx-btn--sm composer-trigger"
              disabled={activeStreaming}
              onClick={togglePermissionMenu}
            >
              {permissionMode === 'ask-for-approval' ? t('aichat.permissionAsk')
                : permissionMode === 'approve-for-me' ? t('aichat.permissionAuto')
                : permissionMode === 'plan' ? t('aichat.permissionPlan')
                : t('aichat.permissionFullAccess')}
              <ChevronDown size={13} className="cx-muted" />
            </button>
            {permissionMenuOpen && !activeStreaming && (
              <div className="cx-card composer-menu composer-menu--permission" data-menu="permission">
                <div className="cx-muted composer-menu__hint">{t('aichat.permissionQuestion')}</div>
                {([
                  { id: 'ask-for-approval', title: t('aichat.permissionAsk'), hint: t('aichat.permissionAskHint') },
                  { id: 'approve-for-me', title: t('aichat.permissionAuto'), hint: t('aichat.permissionAutoHint') },
                  { id: 'plan', title: t('aichat.permissionPlan'), hint: t('aichat.permissionPlanHint') },
                  { id: 'full-access', title: t('aichat.permissionFullAccess'), hint: t('aichat.permissionFullHint') },
                ] as const).map(option => (
                  <button
                    key={option.id}
                    className="cx-btn composer-menu__option"
                    style={option.id === 'full-access' ? { color: 'rgb(var(--v-theme-error))' } : undefined}
                    onClick={() => {
                      useAiSessionStore.setState({ permissionMode: option.id })
                      setPermissionMenuOpen(false)
                    }}
                  >
                    <span style={{ display: 'grid', gap: 2, flex: 1 }}>
                      <span style={{ fontWeight: 650 }}>{option.title}</span>
                      <span className="cx-muted" style={{ fontSize: 12, whiteSpace: 'normal' }}>{option.hint}</span>
                    </span>
                    {permissionMode === option.id && <Check size={14} />}
                  </button>
                ))}
              </div>
            )}

            {activeConv?.usage && activeConv.usage.contextWindowTokens > 0 && (
              <span
                className="composer-usage"
                title={t('aichat.contextUsageTitle', {
                  used: activeConv.usage.contextTokens,
                  window: activeConv.usage.contextWindowTokens,
                })}
              >
                {activeConv.usage.compacted && <span className="composer-usage__flag" title={t('aichat.contextCompacted')}>⇧</span>}
                <span className="composer-usage__bar">
                  <span
                    className="composer-usage__fill"
                    style={{ width: `${Math.min(100, Math.round(activeConv.usage.contextTokens / activeConv.usage.contextWindowTokens * 100))}%` }}
                  />
                </span>
                <span className="cx-muted composer-usage__label">
                  {formatTokens(activeConv.usage.contextTokens)}/{formatTokens(activeConv.usage.contextWindowTokens)}
                </span>
              </span>
            )}
          </div>

          <div className="composer-toolbar__group">
            <button
              className="cx-btn cx-btn--text cx-btn--sm composer-trigger--model"
              disabled={modelSwitching || providers.length === 0 || activeStreaming}
              title={t('aichat.chooseModel')}
              onClick={toggleModelMenu}
            >
              <Zap size={14} />
              <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>
                {activeEntry?.model ?? (providers.length ? t('aichat.selectModelShort') : t('aichat.noConfiguredModelsShort'))}
              </span>
              {activeEntry && <span className="cx-muted">{activeEntry.displayName}</span>}
              <ChevronDown size={13} className="cx-muted" />
            </button>
            {modelMenuOpen && (
              <div className="cx-card composer-menu composer-menu--model" data-menu="model">
                <div className="cx-muted composer-menu__hint">{t('aichat.configuredModels')}</div>
                <div className="composer-menu__scroll">
                {providers.map(p => {
                  const list = providerModelLists[p.id]
                  const usable = p.protocol === 'OLLAMA' || p.apiKeySet
                  // Live vendor list when it answered; curated fallback otherwise.
                  const loading = !list || list.loading
                  const { rows: models, fallback } = providerModelRows(
                    loading ? [] : (list?.models ?? []), p.model, p.id)
                  return (
                    <div key={p.id} className="composer-menu__provider">
                      <div className="composer-menu__group">
                        <span>{p.displayName}</span>
                        {!usable
                          ? <span className="cx-muted composer-menu__group-note">{t('aichat.keyMissing')}</span>
                          : null}
                        {p.id === activeProviderId
                          ? <span className="cx-chip cx-chip--success"><Check size={12} />{t('aiSettings.registry.inUse')}</span>
                          : null}
                      </div>
                      {models.map(m => (
                        <button
                          key={`${p.id}/${m}`}
                          className="cx-btn composer-menu__option composer-menu__option--model"
                          disabled={modelSwitching || !usable}
                          onClick={() => void applyModel(p.id, m)}
                        >
                          <span style={{ display: 'grid', flex: 1, textAlign: 'left' }}>
                            <span style={{ fontWeight: 650 }}>{m}</span>
                          </span>
                          {p.id === activeProviderId && m === activeEntry?.model ? <Check size={14} /> : null}
                        </button>
                      ))}
                      {loading
                        ? <div className="cx-muted composer-menu__note">{t('aichat.modelsLoading')}</div>
                        : fallback
                          ? <div className="cx-muted composer-menu__note">{t('aichat.modelsFallbackNote')}</div>
                          : !models.length
                            ? <div className="cx-muted composer-menu__note">{t('aichat.modelsEmpty')}</div>
                            : null}
                      {customEntry === p.id ? (
                        <input
                          className="cx-input composer-menu__custom-input"
                          autoFocus
                          value={customValue}
                          placeholder={t('aichat.modelInputPh')}
                          disabled={modelSwitching}
                          onChange={e => setCustomValue(e.target.value)}
                          onKeyDown={e => {
                            if (e.key === 'Enter') {
                              // Read the live input value, not the state snapshot —
                              // the freshest truth at the moment the user commits.
                              const value = (e.currentTarget as HTMLInputElement).value.trim()
                              if (value) void applyModel(p.id, value)
                            } else if (e.key === 'Escape') {
                              setCustomEntry(null)
                              setCustomValue('')
                            }
                          }}
                        />
                      ) : (
                        <button
                          className="cx-btn cx-btn--text composer-menu__custom-trigger"
                          disabled={modelSwitching || !usable}
                          onClick={() => { setCustomEntry(p.id); setCustomValue('') }}
                        >
                          <Pencil size={13} />
                          {t('aichat.otherModel')}
                        </button>
                      )}
                    </div>
                  )
                })}
                </div>
                {thinkingCfg.levels.length > 1 ? (
                  <>
                    <div className="composer-menu__divider" />
                    <div className="composer-menu__levels">
                      <span className="cx-muted composer-menu__hint" style={{ padding: 0 }}>
                        {t('aichat.thinkingLabel')}
                      </span>
                      {thinkingCfg.levels.map(level => (
                        <button
                          key={level}
                          className={cn('composer-menu__levelchip', { 'composer-menu__levelchip--on': level === thinkingCfg.level })}
                          onClick={() => void applyThinking(level)}
                        >
                          {t(`aiSettings.registry.thinkingLevel.${level}`, level)}
                        </button>
                      ))}
                    </div>
                  </>
                ) : null}
                <div className="composer-menu__divider" />
                <button
                  className="cx-btn cx-btn--text composer-menu__option"
                  onClick={() => { setModelMenuOpen(false); navigate('/settings?section=providers') }}
                >
                  <SlidersHorizontal size={14} />
                  {t('aichat.manageProviders')}
                </button>
              </div>
            )}

            <button
              className={cn('cx-iconbtn cx-iconbtn--round', { 'cx-iconbtn--primary': listening })}
              title={t('aichat.voiceInput')}
              onClick={toggleVoiceInput}
            >
              {listening ? <Mic size={17} /> : <MicOff size={17} />}
            </button>
            {activeStreaming ? (
              <button className="composer-send" title={t('aichat.stop')} onClick={() => useAiSessionStore.getState().stop()}>
                <Square size={14} />
              </button>
            ) : (
              <button
                className="composer-send"
                disabled={!(!editorEmpty || activeConv?.draftMentions.length) || modelSwitching || !activeEntry}
                title={t('aichat.send')}
                onClick={submit}
              >
                <ArrowUp size={16} />
              </button>
            )}
          </div>
        </div>
      </div>
      <div className="cx-conversation cx-muted composer-hint">{t('aichat.hint')}</div>
    </div>
  )
}

function MenuItem({ icon, title, hint, onClick }: { icon: React.ReactNode; title: string; hint: string; onClick: () => void }) {
  return (
    <button className="cx-btn cx-btn--text composer-menu__item" onClick={onClick}>
      {icon}
      <span style={{ display: 'grid', textAlign: 'left' }}>
        <span>{title}</span>
        <span className="cx-muted" style={{ fontSize: 11 }}>{hint}</span>
      </span>
    </button>
  )
}

/** Chip icon for a draft attachment (MentionPanel's file heuristics: dir → image → file). */
function attachmentIcon(attachment: import('@/services/types').DraftAttachment) {
  if (attachment.kind === 'directory') return <Folder size={16} />
  if (attachment.kind === 'image') return <FileImage size={16} />
  const ext = attachment.name.split('.').pop()?.toLowerCase() ?? ''
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'ico', 'pdf'].includes(ext)) return <FileImage size={16} />
  return <FileText size={16} />
}

function ConfirmationCard({ item }: { item: import('@/lib/aiConfirmation').ToolConfirmation }) {
  const { t } = useTranslation()
  const resolve = useAiSessionStore(state => state.resolveConfirmation)
  const status = item.status
  const [feedbackOpen, setFeedbackOpen] = useState(false)
  const [feedback, setFeedback] = useState('')
  const [always, setAlways] = useState(false)
  /** Error-card actions mutate the item in place (store pattern) — republish after. */
  const republish = () =>
    useAiSessionStore.setState(state => ({ conversations: [...state.conversations] }))
  return (
    <div className="composer-confirmation">
      <div className="composer-confirmation__title">
        {item.sandboxEscape ? <ShieldAlert size={14} /> : <Shield size={14} />}
        <span>{item.sandboxEscape ? t('aichat.confirmEscapeTitle') : t('aichat.confirmTitle')}</span>
      </div>
      {item.summary.map(row => (
        <div key={row.label} className="composer-confirmation__row">
          <span className="cx-muted composer-confirmation__label">{row.label}</span>
          <code className="composer-confirmation__value">{row.value}</code>
        </div>
      ))}
      {status === 'pending' && (
        <>
          <div className="composer-confirmation__actions">
            <button
              className="cx-btn cx-btn--primary cx-btn--sm"
              onClick={() => void resolve(item, true, { always })}
            >
              {always ? t('aichat.approveAlways') : t('aichat.approveOnce')}
            </button>
            <button className="cx-btn cx-btn--text cx-btn--sm" onClick={() => void resolve(item, false)}>
              {t('aichat.rejectSend')}
            </button>
            <button
              className="cx-btn cx-btn--text cx-btn--sm"
              onClick={() => setFeedbackOpen(open => !open)}
            >
              {t('aichat.rejectWithFeedback')}
            </button>
          </div>
          {/* "Always this conversation" only exists on host-approval cards — the plugin
              confirmation channel has no session-grant semantics to wire it to — and never
              for sandbox escapes (a per-conversation unfenced grant is too broad). */}
          {item.source === 'host' && !item.sandboxEscape && (
            <label className="composer-confirmation__always">
              <input type="checkbox" checked={always} onChange={event => setAlways(event.target.checked)} />
              {/* Honest labels: only workspace_exec approvals amend a PERSISTENT exec-policy
                  prefix rule (ChatToolApprovalGate.amendTokensOf) — everything else grants
                  for this conversation only. */}
              <span className="cx-muted">
                {item.toolName === 'workspace_exec'
                  ? t('aichat.alwaysAndRememberPrefix')
                  : t('aichat.alwaysThisConversation')}
              </span>
            </label>
          )}
          {feedbackOpen && (
            <div className="composer-confirmation__feedback">
              <textarea
                className="composer-confirmation__feedback-input"
                value={feedback}
                placeholder={t('aichat.feedbackPlaceholder')}
                rows={2}
                onChange={event => setFeedback(event.target.value)}
              />
              <button
                className="cx-btn cx-btn--text cx-btn--sm"
                disabled={!feedback.trim()}
                onClick={() => void resolve(item, false, { feedback: feedback.trim() })}
              >
                {t('aichat.rejectWithFeedbackSend')}
              </button>
            </div>
          )}
        </>
      )}
      {status === 'submitting' && <div className="cx-muted"><span className="cx-spin" /> {t('aichat.submittingApproval')}</div>}
      {status === 'error' && (
        <div className="cx-alert cx-alert--error">
          <span className="cx-alert__body">{item.error}</span>
          {/* A failed resolve is recoverable while the gate is still open; a dismiss
              retires the card so one bad approval never pins the composer forever. */}
          {confirmationRetryable(item) && (
            <button
              className="cx-btn cx-btn--text cx-btn--sm"
              onClick={() => { if (retryConfirmation(item)) republish() }}
            >
              {t('common.retry')}
            </button>
          )}
          <button
            className="cx-btn cx-btn--text cx-btn--sm"
            onClick={() => { if (dismissConfirmation(item)) republish() }}
          >
            {t('common.dismiss')}
          </button>
        </div>
      )}
    </div>
  )
}

/** An ask_user question card: the model's structured questions with option chips. */
function QuestionCard({ item }: { item: import('@/lib/aiQuestion').QuestionCardState }) {
  const { t } = useTranslation()
  const submit = useAiSessionStore(state => state.submitQuestion)
  /** Selection/error actions mutate the card in place (store pattern) — republish after. */
  const republish = () =>
    useAiSessionStore.setState(state => ({ conversations: [...state.conversations] }))
  const toggle = (index: number, label: string) => {
    const current = item.selected[index] ?? []
    if (item.items[index]?.multiSelect) {
      item.selected[index] = current.includes(label)
        ? current.filter(value => value !== label)
        : [...current, label]
    } else {
      item.selected[index] = current.includes(label) ? [] : [label]
    }
    republish()
  }
  const answerable = questionAnswerable(item)
  return (
    <div className="composer-confirmation">
      <div className="composer-confirmation__title">
        <HelpCircle size={14} />
        <span>{t('aichat.questionTitle')}</span>
      </div>
      {item.items.map((question, index) => (
        <div key={index} className="composer-question">
          {question.header && (
            <span className="cx-muted composer-question__header">{question.header}</span>
          )}
          <div className="composer-question__text">{question.question}</div>
          <div className="composer-question__options">
            {question.options.map((option, optionIndex) => {
              const selected = (item.selected[index] ?? []).includes(option.label)
              return (
                <button
                  key={`${index}:${optionIndex}`}
                  type="button"
                  title={option.description}
                  aria-pressed={selected}
                  className={cn('composer-question__option', selected && 'composer-question__option--selected')}
                  onClick={() => toggle(index, option.label)}
                >
                  {option.label}
                </button>
              )
            })}
          </div>
          <input
            className="composer-question__other"
            type="text"
            placeholder={t('aichat.questionOther')}
            value={item.other[index] ?? ''}
            onChange={event => {
              item.other[index] = event.target.value
              republish()
            }}
          />
        </div>
      ))}
      {item.status === 'pending' && (
        <div className="composer-confirmation__actions">
          <button
            className="cx-btn cx-btn--primary cx-btn--sm"
            disabled={!answerable}
            onClick={() => void submit(item)}
          >
            {t('aichat.questionSubmit')}
          </button>
        </div>
      )}
      {item.status === 'submitting' && <div className="cx-muted"><span className="cx-spin" /> {t('aichat.submittingApproval')}</div>}
      {item.status === 'error' && (
        <div className="cx-alert cx-alert--error">
          <span className="cx-alert__body">{item.error}</span>
          {/* Retry only while the gate is still open; dismiss retires the card so a
              failed submit never leaves an unanswerable error pinned to the composer. */}
          {questionRetryable(item) && (
            <button
              className="cx-btn cx-btn--text cx-btn--sm"
              onClick={() => { if (retryQuestion(item)) republish() }}
            >
              {t('common.retry')}
            </button>
          )}
          <button
            className="cx-btn cx-btn--text cx-btn--sm"
            onClick={() => { if (dismissQuestion(item)) republish() }}
          >
            {t('common.dismiss')}
          </button>
        </div>
      )}
    </div>
  )
}

/** One queued send: click-to-edit prompt text (parked entries re-queue locally on save). */
function QueueRow({ item, onRemove, onEdit }: {
  item: import('@/stores/aiSession').QueuedSend
  onRemove: () => void
  onEdit: (text: string) => void
}) {
  const { t } = useTranslation()
  const [editing, setEditing] = useState(false)
  const [text, setText] = useState(item.prompt)
  if (editing) {
    return (
      <div className="composer-queue__row composer-queue__row--editing">
        <Clock size={12} />
        <input
          className="composer-queue__input"
          value={text}
          autoFocus
          onChange={event => setText(event.target.value)}
          onKeyDown={event => {
            if (event.key === 'Enter') { setEditing(false); onEdit(text) }
            if (event.key === 'Escape') { setText(item.prompt); setEditing(false) }
          }}
        />
        <button
          className="composer-queue__remove"
          title={t('common.confirm')}
          onClick={() => { setEditing(false); onEdit(text) }}
        ><Check size={12} /></button>
        <button
          className="composer-queue__remove"
          title={t('common.cancel')}
          onClick={() => { setText(item.prompt); setEditing(false) }}
        ><X size={12} /></button>
      </div>
    )
  }
  return (
    <div className="composer-queue__row">
      <Clock size={12} />
      <span className="composer-queue__text">{item.prompt}</span>
      <button
        className="composer-queue__remove"
        title={t('aichat.queueEdit')}
        onClick={() => setEditing(true)}
      ><Pencil size={12} /></button>
      <button
        className="composer-queue__remove"
        title={t('aichat.queueRemove')}
        onClick={onRemove}
      ><X size={12} /></button>
    </div>
  )
}

// ── inline-image helpers ────────────────────────────────────────────────────────

const MAX_INLINE_IMAGE_BYTES = 4 * 1024 * 1024

/** Reads clipboard/drag image Files into base64 inline-image records (bounded). */
async function readImageFiles(files: File[]): Promise<Array<{ name: string; mimeType: string; base64Data: string }>> {
  const out: Array<{ name: string; mimeType: string; base64Data: string }> = []
  for (const file of files.slice(0, 4)) {
    if (file.size > MAX_INLINE_IMAGE_BYTES) continue
    try {
      const base64Data = await readFileAsBase64(file)
      out.push({ name: file.name || 'image', mimeType: file.type || 'image/png', base64Data })
    } catch {
      /* unreadable file drops out */
    }
  }
  return out
}

function readFileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const result = String(reader.result ?? '')
      const comma = result.indexOf(',')
      resolve(comma >= 0 ? result.slice(comma + 1) : result)
    }
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}

/**
 * Token-count label for the context meter. One decimal below 100k so per-turn
 * growth (typically tens–hundreds of tokens over a ~13k base) stays visible;
 * whole numbers from 100k up where the decimal is noise.
 */
function formatTokens(value: number): string {
  if (value < 1000) return String(value)
  const k = value / 1000
  return `${k < 100 ? k.toFixed(1) : Math.round(k)}k`
}

/** Grabs the Lexical editor instance and installs the key-command registrations. */
function BootstrapPlugin({ onReady, registerKeyCommands, onPasteImages }: {
  onReady: (editor: LexicalEditor) => void
  registerKeyCommands: (editor: LexicalEditor) => () => void
  /** Clipboard-image interception: return true when the paste was fully handled. */
  onPasteImages: (files: File[]) => boolean
}) {
  const [editor] = useLexicalComposerContextShim()
  useEffect(() => {
    onReady(editor)
    const unregisterCommands = registerKeyCommands(editor)
    const unregisterPaste = editor.registerCommand(
      PASTE_COMMAND,
      (event: ClipboardEvent) => {
        const images = Array.from(event.clipboardData?.files ?? [])
          .filter(file => file.type.startsWith('image/'))
        if (images.length === 0) return false
        event.preventDefault()
        // A clipboard can carry images AND text (a copied rich snippet): attach the
        // images and keep the text instead of silently dropping it.
        const text = event.clipboardData?.getData('text/plain') ?? ''
        if (text.trim()) {
          editor.update(() => {
            $insertNodes([$createTextNode(text)])
          })
        }
        return onPasteImages(images)
      },
      COMMAND_PRIORITY_CRITICAL,
    )
    return () => {
      unregisterCommands()
      unregisterPaste()
    }
  }, [editor, onReady, registerKeyCommands, onPasteImages])
  return null
}

// Local import indirection keeps the hook import tree-shakable in one place.
import { useLexicalComposerContext } from '@lexical/react/LexicalComposerContext'
function useLexicalComposerContextShim() {
  return useLexicalComposerContext()
}

const initialConfig = {
  namespace: 'fengyu-chat-composer',
  nodes: [PromptMentionNode],
  onError(error: Error) {
    console.error('[composer]', error)
  },
}

// ── editor-state helpers ────────────────────────────────────────────────────────────

/** Serialize the editor: plain text (chips excluded) + mention payloads in flow order. */
function serializeEditorState(editor: LexicalEditor): { text: string; mentions: PromptMentionPayload[] } {
  return editor.getEditorState().read(() => {
    let text = ''
    const mentions: PromptMentionPayload[] = []
    for (const paragraph of $getRoot().getChildren()) {
      if (!$isElementNode(paragraph)) continue
      if (text.length > 0) text += '\n'
      for (const child of paragraph.getChildren()) {
        if ($isPromptMentionNode(child)) {
          mentions.push(child.getMention())
        } else if ($isTextNode(child)) {
          text += child.getTextContent()
        } else if ($isLineBreakNode(child)) {
          text += '\n'
        }
      }
    }
    return { text, mentions }
  })
}

/** Caret-relative plain-text prefix (mention labels count as text). */
function textBeforeCaret(editor: LexicalEditor): string {
  return editor.getEditorState().read(() => {
    const selection = $getSelection()
    const anchor = anchorTextOf(selection)
    if (!anchor) return ''
    // Walk every text-bearing node in order, accumulating up to the anchor's offset.
    let prefix = ''
    for (const paragraph of $getRoot().getChildren()) {
      if (!$isElementNode(paragraph)) continue
      for (const child of paragraph.getChildren()) {
        if ($isTextNode(child)) {
          if (child.getKey() === anchor.node.getKey()) {
            return prefix + child.getTextContent().slice(0, anchor.offset)
          }
          prefix += child.getTextContent()
        } else if ($isLineBreakNode(child)) {
          prefix += '\n'
        }
      }
      prefix += '\n'
    }
    return ''
  })
}

/** The anchor as a TextNode + caret offset, when the caret sits inside text. */
function anchorTextOf(selection: import('lexical').BaseSelection | RangeSelection | null): { node: TextNode; offset: number } | null {
  if (!selection || !$isRangeSelection(selection) || !selection.isCollapsed() || selection.anchor.type !== 'text') return null
  const node = selection.anchor.getNode()
  if (!$isTextNode(node)) return null
  return { node, offset: selection.anchor.offset }
}
