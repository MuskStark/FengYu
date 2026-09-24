import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { LexicalComposer } from '@lexical/react/LexicalComposer'
import { ContentEditable } from '@lexical/react/LexicalContentEditable'
import { HistoryPlugin } from '@lexical/react/LexicalHistoryPlugin'
import { OnChangePlugin } from '@lexical/react/LexicalOnChangePlugin'
import { PlainTextPlugin } from '@lexical/react/LexicalPlainTextPlugin'
import { LexicalErrorBoundary } from '@lexical/react/LexicalErrorBoundary'
import { $createParagraphNode, $createTextNode, $getRoot, $insertNodes, $isElementNode, $isLineBreakNode, $isTextNode, $getSelection, $isRangeSelection, COMMAND_PRIORITY_CRITICAL, KEY_ARROW_DOWN_COMMAND, KEY_ARROW_UP_COMMAND, KEY_ENTER_COMMAND, KEY_ESCAPE_COMMAND, KEY_TAB_COMMAND, type LexicalEditor, type TextNode, type RangeSelection } from 'lexical'
import { ChevronDown, Plus, ArrowUp, Square, Mic, MicOff, Folder, FileImage, FileText, X } from 'lucide-react'
import { useAiSessionStore } from '@/stores/aiSession'
import { useSettingsStore } from '@/stores/settings'
import { configuredChatModels } from '@/lib/chatModels'
import { extractActiveMention, type MentionTrigger } from '@/lib/mentionTriggers'
import { buildMentionSections, flattenMentionSections, type MentionOption, type MentionSection } from '@/lib/mentionSearch'
import { FLOW_CHAT_SEED_KEY } from '@/lib/flowSeed'
import { getPlatform } from '@/platform'
import { appPrompt } from '@/lib/appDialogs'
import MentionPanel from './MentionPanel'
import { PromptMentionNode, $createPromptMentionNode, $isPromptMentionNode, type PromptMentionPayload } from './PromptMentionNode'
import { useMentionPools, toPayload } from './mentionPools'
import { cn } from '@/lib/utils'
import '@/styles/composer.css'

/**
 * Lexical-based chat composer (ZCode's architecture): plain-text editing with inline atomic
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
  const ai = useAiSessionStore()
  const settings = useSettingsStore()
  const pools = useMentionPools()

  const [mentionState, setMentionState] = useState<{ trigger: MentionTrigger; query: string } | null>(null)
  const [sections, setSections] = useState<MentionSection[]>([])
  const [selectedIndex, setSelectedIndex] = useState(0)
  const [dismissedSignature, setDismissedSignature] = useState<string | null>(null)
  const [attachMenuOpen, setAttachMenuOpen] = useState(false)
  const [permissionMenuOpen, setPermissionMenuOpen] = useState(false)
  const [modelMenuOpen, setModelMenuOpen] = useState(false)
  const [modelSwitching, setModelSwitching] = useState(false)
  const [listening, setListening] = useState(false)
  const [editorEmpty, setEditorEmpty] = useState(true)
  const editorRef = useRef<LexicalEditor | null>(null)
  const recognitionRef = useRef<{ stop: () => void } | null>(null)
  const activeId = useAiSessionStore(state => state.activeId)

  // The composer menus (attach / permission / model) are mutually exclusive, close on
  // outside pointer-down and on Escape — ZCode popover behavior.
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

  const activeConv = ai.active()
  const modelOptions = useMemo(() => configuredChatModels(settings.aiSettings), [settings.aiSettings])
  const activeModel = modelOptions.find(option =>
    option.mode === (settings.aiSettings?.activeMode ?? settings.aiSettings?.mode)) ?? null
  const composerConfirmations = useMemo(() =>
    ai.conversations.flatMap(conv => conv.turns.flatMap(turn => turn.confirmations))
      .filter((item): item is import('@/lib/aiConfirmation').ToolConfirmation =>
        ['pending', 'submitting', 'error'].includes(String((item as { status: string }).status))),
    [ai.conversations])

  const flatOptions = useMemo(() => flattenMentionSections(sections), [sections])
  const mentionEmptyLabel = mentionState?.query
    ? t('aichat.mentionEmpty')
    : t(mentionState?.trigger === '$' ? 'aichat.mentionSkillHint' : 'aichat.mentionSearchHint')

  // Rebuild sections whenever the active token or pools change (ZCode's reconcile loop).
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
      // ZCode's whiteboard special case: flows hand off instead of inserting a token.
      sendInputToFlow(option)
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
  const registerKeyCommands = useCallback((editor: LexicalEditor) => {
    const unregister = [
      editor.registerCommand(KEY_ARROW_DOWN_COMMAND, () => {
        if (!mentionState || flatOptions.length === 0) return false
        setSelectedIndex(index => (index + 1) % flatOptions.length)
        return true
      }, COMMAND_PRIORITY_CRITICAL),
      editor.registerCommand(KEY_ARROW_UP_COMMAND, () => {
        if (!mentionState || flatOptions.length === 0) return false
        setSelectedIndex(index => (index - 1 + flatOptions.length) % flatOptions.length)
        return true
      }, COMMAND_PRIORITY_CRITICAL),
      editor.registerCommand(KEY_ENTER_COMMAND, event => {
        if (mentionState && flatOptions.length > 0) {
          event?.preventDefault()
          insertMention(flatOptions[selectedIndex])
          return true
        }
        if (!event?.shiftKey) {
          event?.preventDefault()
          submit()
          return true
        }
        return false
      }, COMMAND_PRIORITY_CRITICAL),
      editor.registerCommand(KEY_TAB_COMMAND, event => {
        if (mentionState && flatOptions.length > 0) {
          event?.preventDefault()
          insertMention(flatOptions[selectedIndex])
          return true
        }
        return false
      }, COMMAND_PRIORITY_CRITICAL),
      editor.registerCommand(KEY_ESCAPE_COMMAND, () => {
        if (mentionState) {
          setDismissedSignature(`${mentionState.trigger}:${mentionState.query}`)
          setMentionState(null)
          return true
        }
        return false
      }, COMMAND_PRIORITY_CRITICAL),
    ]
    return () => unregister.forEach(fn => fn())
  }, [mentionState, flatOptions, selectedIndex, insertMention])

  const submit = useCallback(() => {
    const editor = editorRef.current
    const store = useAiSessionStore.getState()
    if (!editor || store.busy || modelSwitching) return
    const serialized = serializeEditorState(editor)
    const mentionBlock = serialized.mentions.map(item => item.markdown).join('\n')
    const full = serialized.text + (mentionBlock ? `\n\n${mentionBlock}` : '')
    if (!full.trim()) return
    if (!activeModel) {
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
    void store.send(full)
  }, [modelSwitching, activeModel, t])

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
      const text = (event as CustomEvent<{ text?: string }>).detail?.text
      const editor = editorRef.current
      if (!text || !editor) return
      editor.update(() => {
        const root = $getRoot()
        root.clear()
        const paragraph = $createParagraphNode()
        paragraph.append($createTextNode(text))
        root.append(paragraph)
        paragraph.getLastChild()?.selectEnd()
      })
      const conv = useAiSessionStore.getState().active()
      if (conv) {
        conv.draft = text
        conv.draftMentions = []
      }
      setEditorEmpty(false)
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
        await ai.setWorkspace(conv, path)
      } catch {
        useAiSessionStore.setState({ error: t('aichat.workspaceSetFailed') })
      }
      return
    }
    const path = await appPrompt(t('aichat.workspacePathPrompt'))
    if (path && path.trim()) {
      try {
        await ai.setWorkspace(conv, path.trim())
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
      await ai.setOutputTarget(conv, path)
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

  async function selectModel(mode: import('@/services/types').AiMode) {
    if (modelSwitching || mode === settings.aiSettings?.activeMode) {
      setModelMenuOpen(false)
      return
    }
    setModelSwitching(true)
    try {
      await settings.updateAi({ mode })
      setModelMenuOpen(false)
    } catch (error) {
      useAiSessionStore.setState({ error: error instanceof Error ? error.message : t('aichat.modelSwitchFailed') })
    } finally {
      setModelSwitching(false)
    }
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

  const hasError = ai.error !== null

  // Context header (ZCode's composer contextHeader): the coding-workspace pill sits above
  // the input on the draft screen. Ongoing conversations show the workspace in the chip
  // strip above the composer instead (it carries the change/clear actions).
  const workspaceRoot = activeConv?.workspaceRoot ?? ''
  const workspaceName = workspaceRoot.replace(/[\\/]+$/, '').split(/[\\/]/).pop() ?? ''
  const conversationEmpty = (activeConv?.turns.length ?? 0) === 0
  const showWorkspacePill = conversationEmpty
  const pickWorkspace = () => {
    setAttachMenuOpen(false)
    if (onAttachWorkspace) {
      onAttachWorkspace()
      return
    }
    void chooseWorkspace()
  }

  return (
    <div ref={zoneRef} className={cn('composer-zone', { 'composer-zone--centered': centered })}>
      {hasError && (
        <div className="cx-alert cx-alert--error cx-conversation composer-error">
          <span className="cx-alert__body">{ai.error}</span>
          <button className="cx-iconbtn cx-iconbtn--sm" onClick={() => useAiSessionStore.setState({ error: null })}>×</button>
        </div>
      )}

      <div className="cx-composer composer-card">
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
          </div>
        )}

        {/* Draft attachments (ZCode topContent): chips inside the card above the input,
            media-first ordering is moot (files/dirs only), hover reveals the remove X. */}
        {(activeConv?.draftAttachments.length ?? 0) > 0 && (
          <div className="composer-attach-row">
            {activeConv!.draftAttachments.map(attachment => (
              <span
                key={attachment.attachmentId}
                className="composer-attach-chip"
                title={attachment.displayPath ?? attachment.name}
              >
                <span className="composer-attach-chip__icon">
                  {attachmentIcon(attachment)}
                </span>
                <span className="composer-attach-chip__text">
                  <span className="composer-attach-chip__name">{attachment.name}</span>
                  {attachment.displayPath && (
                    <span className="composer-attach-chip__sub">{attachment.displayPath}</span>
                  )}
                </span>
                <button
                  className="composer-attach-chip__remove"
                  title={t('aichat.removeAttachment')}
                  onClick={() => ai.removeDraftAttachment(activeConv!, attachment.attachmentId)}
                >
                  <X size={12} />
                </button>
              </span>
            ))}
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
            <BootstrapPlugin onReady={editor => { editorRef.current = editor }} registerKeyCommands={registerKeyCommands} />
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
              disabled={ai.busy}
              onClick={togglePermissionMenu}
            >
              {ai.permissionMode === 'ask-for-approval' ? t('aichat.permissionAsk') : ai.permissionMode === 'approve-for-me' ? t('aichat.permissionAuto') : t('aichat.permissionFullAccess')} ▾
            </button>
            {permissionMenuOpen && !ai.busy && (
              <div className="cx-card composer-menu composer-menu--permission" data-menu="permission">
                <div className="cx-muted composer-menu__hint">{t('aichat.permissionQuestion')}</div>
                {([
                  { id: 'ask-for-approval', title: t('aichat.permissionAsk'), hint: t('aichat.permissionAskHint') },
                  { id: 'approve-for-me', title: t('aichat.permissionAuto'), hint: t('aichat.permissionAutoHint') },
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
                    {ai.permissionMode === option.id && <span>✓</span>}
                  </button>
                ))}
              </div>
            )}
          </div>

          <div className="composer-toolbar__group">
            <button
              className="cx-btn cx-btn--text cx-btn--sm composer-trigger--model"
              disabled={modelSwitching || modelOptions.length === 0 || ai.busy}
              title={t('aichat.chooseModel')}
              onClick={toggleModelMenu}
            >
              ⚡ <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>
                {activeModel?.model ?? (modelOptions.length ? t('aichat.selectModelShort') : t('aichat.noConfiguredModelsShort'))}
              </span>
              {activeModel && <span className="cx-muted">{activeModel.provider}</span>} ▾
            </button>
            {modelMenuOpen && (
              <div className="cx-card composer-menu composer-menu--model" data-menu="model">
                <div className="cx-muted composer-menu__hint">{t('aichat.configuredModels')}</div>
                {modelOptions.map(option => (
                  <button key={option.mode} className="cx-btn composer-menu__option composer-menu__option--model" onClick={() => void selectModel(option.mode)}>
                    <span style={{ display: 'grid', flex: 1, textAlign: 'left' }}>
                      <span style={{ fontWeight: 650 }}>{option.model}</span>
                      <span className="cx-muted" style={{ fontSize: 12 }}>{option.provider}</span>
                    </span>
                    {settings.aiSettings?.activeMode === option.mode && <span>✓</span>}
                  </button>
                ))}
              </div>
            )}

            <button
              className={cn('cx-iconbtn cx-iconbtn--round', { 'cx-iconbtn--primary': listening })}
              title={t('aichat.voiceInput')}
              onClick={toggleVoiceInput}
            >
              {listening ? <Mic size={17} /> : <MicOff size={17} />}
            </button>
            {ai.busy ? (
              <button className="composer-send" title={t('aichat.stop')} onClick={ai.stop}>
                <Square size={14} />
              </button>
            ) : (
              <button
                className="composer-send"
                disabled={!(!editorEmpty || activeConv?.draftMentions.length) || modelSwitching || !activeModel}
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

/** Chip icon for a draft attachment (MentionPanel's file heuristics: dir → image ext → file). */
function attachmentIcon(attachment: import('@/services/types').DraftAttachment) {
  if (attachment.kind === 'directory') return <Folder size={16} />
  const ext = attachment.name.split('.').pop()?.toLowerCase() ?? ''
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'ico', 'pdf'].includes(ext)) return <FileImage size={16} />
  return <FileText size={16} />
}

function ConfirmationCard({ item }: { item: import('@/lib/aiConfirmation').ToolConfirmation }) {
  const { t } = useTranslation()
  const resolve = useAiSessionStore(state => state.resolveConfirmation)
  const status = (item as unknown as { status: string }).status
  return (
    <div className="composer-confirmation">
      <div className="composer-confirmation__title">🛡 {t('aichat.confirmTitle')}</div>
      {item.summary.map(row => (
        <div key={row.label} className="composer-confirmation__row">
          <span className="cx-muted composer-confirmation__label">{row.label}</span>
          <code className="composer-confirmation__value">{row.value}</code>
        </div>
      ))}
      {status === 'pending' && (
        <div className="composer-confirmation__actions">
          <button className="cx-btn cx-btn--primary cx-btn--sm" onClick={() => void resolve(item, true)}>{t('aichat.approveOnce')}</button>
          <button className="cx-btn cx-btn--text cx-btn--sm" onClick={() => void resolve(item, false)}>{t('aichat.rejectSend')}</button>
        </div>
      )}
      {status === 'submitting' && <div className="cx-muted"><span className="cx-spin" /> {t('aichat.submittingApproval')}</div>}
      {status === 'error' && <div className="cx-alert cx-alert--error">{(item as unknown as { error?: string }).error}</div>}
    </div>
  )
}

/** Grabs the Lexical editor instance and installs the key-command registrations. */
function BootstrapPlugin({ onReady, registerKeyCommands }: {
  onReady: (editor: LexicalEditor) => void
  registerKeyCommands: (editor: LexicalEditor) => () => void
}) {
  const [editor] = useLexicalComposerContextShim()
  useEffect(() => {
    onReady(editor)
    return registerKeyCommands(editor)
  }, [editor, onReady, registerKeyCommands])
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

/** Caret-relative plain-text prefix (mention labels count as text — ZCode semantics). */
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
