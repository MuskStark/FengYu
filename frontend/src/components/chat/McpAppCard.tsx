import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AppWindow, Loader2, Maximize2, Minimize2, ShieldAlert, X } from 'lucide-react'
import type { CallToolResult } from '@modelcontextprotocol/client'
import { cn } from '@/lib/utils'
import { services } from '@/services'
import { mcpAppsEvents } from '@/services/mcpAppsEvents'
import type { McpAppPrepareResult, McpToolUiInfo } from '@/services/mcpApps'
import { hostContextRefresh, startMcpAppSession } from '@/lib/mcpAppsBridge'
import type { McpAppDisplayMode, McpAppSession } from '@/lib/mcpAppsBridge'
import { mcpWidgetStateKey, useMcpAppsUiStore } from '@/stores/mcpAppsUi'
import { useSettingsStore } from '@/stores/settings'
import { instance as i18nInstance } from '@/i18n'

/**
 * Host card for an MCP Apps tool result: mounts the tool's `ui://` page in a sandboxed
 * iframe on the dedicated sandbox origin and bridges it with the official AppBridge.
 * Collapsed by default (each expansion re-prepares the page server-side); pages marked
 * `showInline` mount themselves. Page-initiated tool calls require an explicit per-call
 * consent here — the bridge refuses until the user grants one. The page's widget state
 * rides the host memory store (same logical scope remounts with it), and backend
 * resource-updated events are forwarded into the page as spec notifications.
 *
 * Conversation surfaces pass `chat`, which wires `ui/message` (inject a user turn) and
 * `ui/update-model-context` (park context for the next turn); other surfaces omit it and
 * the session simply does not declare those capabilities, so pages degrade per the spec.
 */

const CARD_MIN_HEIGHT = 80
const CARD_MAX_HEIGHT = 1200
const CARD_DEFAULT_HEIGHT = 240

/** Resource-declared permissions → iframe Permission Policy features. */
const PERMISSION_FEATURES: Record<string, string> = {
  camera: 'camera',
  microphone: 'microphone',
  geolocation: 'geolocation',
  clipboardWrite: 'clipboard-write',
}

/** Conversation wiring for `ui/message` and `ui/update-model-context`. */
export interface McpAppChatContext {
  conversationId: number
  /** Injects a user message into the conversation (starts a turn). */
  send(text: string): void
  /** Parks model context that rides the next outgoing turn (last write wins). */
  saveModelContext(text: string, structured?: unknown): void
}

interface PendingCall {
  id: number
  tool: string
  args: Record<string, unknown>
  resolve: (result: CallToolResult | null) => void
}

export function McpAppCard({ ui, scopeId, defaultOpen = false, toolArgs, cancelled, chat }: {
  ui: McpToolUiInfo
  scopeId: string
  defaultOpen?: boolean
  /** Complete tool arguments, delivered as the spec `tool-input` notification. */
  toolArgs?: Record<string, unknown>
  /** Flips true when the owning tool call was cancelled → notify the page once. */
  cancelled?: boolean
  /** Present only on conversation surfaces; gates the message/context capabilities. */
  chat?: McpAppChatContext
}) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(defaultOpen)
  const [prepared, setPrepared] = useState<McpAppPrepareResult | null>(null)
  const [error, setError] = useState('')
  const [height, setHeight] = useState(() => clampHeight(ui.preferredDisplayMode === 'fullscreen' ? 560 : CARD_DEFAULT_HEIGHT))
  const [displayMode, setDisplayMode] = useState<'inline' | 'fullscreen'>(ui.preferredDisplayMode === 'fullscreen' ? 'fullscreen' : 'inline')
  const [pendingCall, setPendingCall] = useState<PendingCall | null>(null)
  const [frameEl, setFrameEl] = useState<HTMLIFrameElement | null>(null)
  const sessionRef = useRef<McpAppSession | null>(null)
  const forwarderRef = useRef<((event: { serverId: string; uri: string }) => void) | null>(null)
  const sandboxIdRef = useRef<string | null>(null)
  const chatRef = useRef(chat)
  chatRef.current = chat
  const callSeq = useRef(0)
  const widgetStateKey = mcpWidgetStateKey(ui.serverId, scopeId)

  // Auto-mount pages that declared showInline, exactly once.
  const autoMounted = useRef(false)
  useEffect(() => {
    if (!autoMounted.current && ui.showInline && !open) {
      autoMounted.current = true
      setOpen(true)
    }
  }, [ui.showInline, open])

  const closePage = useCallback(() => {
    const session = sessionRef.current
    sessionRef.current = null
    const forwarder = forwarderRef.current
    forwarderRef.current = null
    if (forwarder) mcpAppsEvents.release(forwarder)
    void services.mcpApps.unsubscribe(ui.serverId, ui.resourceUri).catch(() => undefined)
    if (session) void session.dispose()
    // Release the server-side sandbox eagerly instead of waiting for its TTL sweep
    // (this also un-claims app-only tools as soon as the last page closes).
    const sandboxId = sandboxIdRef.current
    sandboxIdRef.current = null
    if (sandboxId) void services.mcpApps.dispose(sandboxId).catch(() => undefined)
  }, [ui.serverId, ui.resourceUri])

  useEffect(() => () => {
    closePage()
  }, [closePage])

  const toggle = () => {
    if (open) {
      setOpen(false)
      setPrepared(null)
      setError('')
      closePage()
      return
    }
    setOpen(true)
  }

  useEffect(() => {
    if (!open || prepared || error) return
    let cancelledPrepare = false
    services.mcpApps.prepare({ serverId: ui.serverId, resourceUri: ui.resourceUri })
      .then((result) => {
        if (cancelledPrepare) {
          void services.mcpApps.dispose(result.sandboxId)
          return
        }
        sandboxIdRef.current = result.sandboxId
        setPrepared(result)
        if (result.resourceMeta?.heightHint) setHeight(clampHeight(result.resourceMeta.heightHint))
      })
      .catch((e: unknown) => {
        if (!cancelledPrepare) setError(e instanceof Error ? e.message : String(e))
      })
    return () => {
      cancelledPrepare = true
      sandboxIdRef.current = null
    }
  }, [open, prepared, error, ui.serverId, ui.resourceUri])

  // Bridge the frame once both the iframe and its prepared sandboxId exist; the callback
  // ref re-renders on mount, which is what flips readyToBridge after `prepared` lands.
  const readyToBridge = Boolean(prepared && frameEl)
  const frameUrl = prepared?.sandboxUrl ?? null
  const initialWidgetState = useMcpAppsUiStore((state) => state.widgetState[widgetStateKey])
  const setWidgetState = useMcpAppsUiStore((state) => state.setWidgetState)
  useEffect(() => {
    if (!readyToBridge || !prepared || !frameEl || sessionRef.current) return
    const session = startMcpAppSession({
      frame: frameEl,
      ui,
      prepared,
      displayMode,
      scopeId,
      toolArgs,
      initialWidgetState,
      callbacks: {
        // The session itself delivers tool-input + tool-result at handshake completion;
        // the card has nothing further to push here.
        onInitialized: () => {},
        onCallTool: async ({ tool, args }) => {
          // Host-enforced consent: the page never reaches the backend until granted here.
          return await new Promise((resolve) => {
            const id = ++callSeq.current
            setPendingCall({
              id, tool, args,
              resolve: (result) => {
                setPendingCall((current) => (current && current.id === id ? null : current))
                resolve(result)
              },
            })
          })
        },
        onOpenLink: (url) => {
          if (/^https?:\/\//i.test(url)) window.open(url, '_blank', 'noopener,noreferrer')
        },
        onSizeChange: (nextHeight) => setHeight(clampHeight(nextHeight)),
        onRequestTeardown: () => {
          setOpen(false)
          setPrepared(null)
          closePage()
        },
        ...(chatRef.current ? {
          onSendMessage: (text: string) => {
            chatRef.current?.send(text)
            return {}
          },
          onUpdateModelContext: (text: string, structured?: unknown) => {
            const conversationId = chatRef.current?.conversationId
            if (conversationId != null) {
              useMcpAppsUiStore.getState().setModelContext(conversationId, { text, structured })
            }
          },
        } : {}),
        onRequestDisplayMode: async (mode: McpAppDisplayMode) => {
          // The host has the final say: inline/fullscreen are real modes here, pip
          // degrades to inline. The class flip below keeps the iframe mounted, so
          // the bridge session survives the mode change.
          const applied = mode === 'fullscreen' ? 'fullscreen' : 'inline'
          setDisplayMode(applied)
          return applied
        },
        onSampling: (messages, options) => services.mcpApps.sampling(messages, options),
        onWidgetState: (state) => setWidgetState(widgetStateKey, state),
        onSubscribeResource: (uri) => services.mcpApps.subscribe(ui.serverId, uri),
        onUnsubscribeResource: (uri) => services.mcpApps.unsubscribe(ui.serverId, uri).then(() => undefined),
      },
    })
    sessionRef.current = session
    // Resource updates: the page auto-subscribes its own UI resource and any resource it
    // asks for; backend events fan back in as spec notifications over the bridge. The
    // forwarder is kept in a ref so closePage releases exactly the acquired listener.
    const forwarder = (event: { serverId: string; uri: string }) => {
      if (event.serverId !== ui.serverId) return
      void session.notifyResourceUpdated(event.uri).catch(() => undefined)
    }
    forwarderRef.current = forwarder
    void services.mcpApps.subscribe(ui.serverId, ui.resourceUri).catch(() => undefined)
    void mcpAppsEvents.acquire(forwarder)
    return () => {
      // Single teardown path: the same closePage the toggle and teardown-request use.
      closePage()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [readyToBridge])

  // Spec `tool-cancelled`: fired once when the owning tool call is cancelled, so live
  // pages can drop their pending state instead of waiting for a result that never comes.
  const cancelNotified = useRef(false)
  useEffect(() => {
    if (!cancelled || cancelNotified.current) return
    cancelNotified.current = true
    void sessionRef.current?.notifyToolCancelled().catch(() => undefined)
  }, [cancelled])

  // Host-context pushes: display-mode changes and theme/locale switches re-reach live
  // pages as `host-context-changed` (the SDK diffs, so no-op updates emit nothing).
  useEffect(() => {
    if (displayMode !== 'inline' && displayMode !== 'fullscreen') return
    sessionRef.current?.updateHostContext({ displayMode })
  }, [displayMode])
  const themeName = useSettingsStore((state) => state.theme)
  useEffect(() => {
    const push = () => sessionRef.current?.updateHostContext(hostContextRefresh())
    push()
    i18nInstance.on('languageChanged', push)
    return () => {
      i18nInstance.off('languageChanged', push)
    }
  }, [themeName])

  // Esc leaves fullscreen (the same class-flip path the protocol handler uses).
  useEffect(() => {
    if (displayMode !== 'fullscreen') return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setDisplayMode('inline')
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [displayMode])

  const grantCall = async () => {
    if (!pendingCall || !prepared) return
    const pending = pendingCall
    try {
      const result = await services.mcpApps.call(ui.serverId, pending.tool, pending.args)
      pending.resolve({
        content: (Array.isArray(result.content) ? result.content : []) as CallToolResult['content'],
        structuredContent: result.structuredContent,
        isError: Boolean(result.isError),
      })
    } catch (e) {
      pending.resolve({
        content: [{ type: 'text', text: e instanceof Error ? e.message : String(e) }],
        isError: true,
      })
    }
  }

  const denyCall = () => {
    pendingCall?.resolve(null)
  }

  const allowFeatures = (prepared?.resourceMeta?.permissions ?? [])
    .map((permission) => PERMISSION_FEATURES[permission])
    .filter(Boolean)
    .join('; ')

  return (
    <div className={cn('mcpapp', ui.prefersBorder && 'mcpapp--bordered')}>
      <div className="mcpapp__bar">
        <AppWindow size={13} className="mcpapp__glyph" />
        <span className="mcpapp__title">
          {ui.serverName || ui.serverId}
          <span className="mcpapp__dot">·</span>
          {ui.tool}
        </span>
        {ui.truncated && (
          <span className="mcpapp__truncated" title={t('aichat.mcpAppTruncated', { bytes: ui.truncatedBytes ?? 0 })}>
            {t('aichat.mcpAppTruncatedShort')}
          </span>
        )}
        {open && prepared && (
          <button
            className="cx-btn cx-btn--text cx-btn--sm"
            title={displayMode === 'fullscreen' ? t('aichat.mcpAppExitFullscreen') : t('aichat.mcpAppFullscreen')}
            onClick={() => setDisplayMode(displayMode === 'fullscreen' ? 'inline' : 'fullscreen')}
          >
            {displayMode === 'fullscreen' ? <Minimize2 size={12} /> : <Maximize2 size={12} />}
          </button>
        )}
        <button className="cx-btn cx-btn--text cx-btn--sm" onClick={toggle}>
          {open ? t('aichat.mcpAppHide') : t('aichat.mcpAppOpen')}
        </button>
        {open && <X size={13} className="mcpapp__close" onClick={toggle} />}
      </div>

      {open && (
        <div className={cn('mcpapp__stage', displayMode === 'fullscreen' && 'mcpapp__stage--fullscreen')}>
          {error && (
            <div className="cx-alert cx-alert--error mcpapp__alert">
              <ShieldAlert size={14} />
              <div className="cx-alert__body">{error}</div>
            </div>
          )}
          {!error && !prepared && (
            <div className="mcpapp__loading">
              <Loader2 size={14} className="cx-spin" />
              {t('aichat.mcpAppPreparing')}
            </div>
          )}
          {!error && prepared && (
            <div className="mcpapp__framebox" style={{ height: displayMode === 'fullscreen' ? '100%' : height }}>
              {frameUrl ? (
                <iframe
                  ref={setFrameEl}
                  title={`MCP app: ${ui.tool}`}
                  className="mcpapp__frame"
                  src={frameUrl}
                  // No allow-same-origin on purpose: the page runs under an opaque origin,
                  // isolated from the host DOM/storage. The document URL still lives on the
                  // token-free sandbox origin as defense in depth.
                  sandbox="allow-scripts allow-forms"
                  referrerPolicy="no-referrer"
                  {...(allowFeatures ? { allow: allowFeatures } : {})}
                />
              ) : (
                <div className="mcpapp__loading">{t('aichat.mcpAppUnavailable')}</div>
              )}
            </div>
          )}
          {pendingCall && (
            <div className="mcpapp__consent">
              <div className="mcpapp__consent-text">
                {t('aichat.mcpAppCallPrompt', { tool: pendingCall.tool })}
              </div>
              <div className="mcpapp__consent-actions">
                <button className="cx-btn cx-btn--tonal cx-btn--sm" onClick={() => void grantCall()}>
                  {t('aichat.mcpAppCallAllow')}
                </button>
                <button className="cx-btn cx-btn--text cx-btn--sm" onClick={denyCall}>
                  {t('aichat.mcpAppCallDeny')}
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function clampHeight(value: number): number {
  if (!Number.isFinite(value)) return CARD_DEFAULT_HEIGHT
  return Math.min(CARD_MAX_HEIGHT, Math.max(CARD_MIN_HEIGHT, Math.round(value)))
}
