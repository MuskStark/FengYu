import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AppWindow, Loader2, ShieldAlert, X } from 'lucide-react'
import type { CallToolResult } from '@modelcontextprotocol/client'
import { cn } from '@/lib/utils'
import { services } from '@/services'
import { mcpAppsEvents } from '@/services/mcpAppsEvents'
import type { McpAppPrepareResult, McpToolUiInfo } from '@/services/mcpApps'
import { startMcpAppSession } from '@/lib/mcpAppsBridge'
import type { McpAppSession } from '@/lib/mcpAppsBridge'
import { mcpWidgetStateKey, useMcpAppsUiStore } from '@/stores/mcpAppsUi'

/**
 * Host card for an MCP Apps tool result: mounts the tool's `ui://` page in a sandboxed
 * iframe on the dedicated sandbox origin and bridges it with the official AppBridge.
 * Collapsed by default (each expansion re-prepares the page server-side); pages marked
 * `showInline` mount themselves. Page-initiated tool calls require an explicit per-call
 * consent here — the bridge refuses until the user grants one. The page's widget state
 * rides the host memory store (same logical scope remounts with it), and backend
 * resource-updated events are forwarded into the page as spec notifications.
 */

const CARD_MIN_HEIGHT = 80
const CARD_MAX_HEIGHT = 1200
const CARD_DEFAULT_HEIGHT = 240

interface PendingCall {
  id: number
  tool: string
  args: Record<string, unknown>
  resolve: (result: CallToolResult | null) => void
}

export function McpAppCard({ ui, scopeId }: { ui: McpToolUiInfo; scopeId: string }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [prepared, setPrepared] = useState<McpAppPrepareResult | null>(null)
  const [error, setError] = useState('')
  const [height, setHeight] = useState(() => clampHeight(ui.preferredDisplayMode === 'fullscreen' ? 560 : CARD_DEFAULT_HEIGHT))
  const [pendingCall, setPendingCall] = useState<PendingCall | null>(null)
  const [frameEl, setFrameEl] = useState<HTMLIFrameElement | null>(null)
  const sessionRef = useRef<McpAppSession | null>(null)
  const forwarderRef = useRef<((event: { serverId: string; uri: string }) => void) | null>(null)
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
    let cancelled = false
    services.mcpApps.prepare({ serverId: ui.serverId, resourceUri: ui.resourceUri })
      .then((result) => {
        if (cancelled) {
          void services.mcpApps.dispose(result.sandboxId)
          return
        }
        setPrepared(result)
        if (result.resourceMeta?.heightHint) setHeight(clampHeight(result.resourceMeta.heightHint))
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e))
      })
    return () => {
      cancelled = true
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
      displayMode: ui.preferredDisplayMode === 'fullscreen' ? 'fullscreen' : 'inline',
      scopeId,
      initialWidgetState,
      callbacks: {
        onInitialized: () => {
          void sessionRef.current?.sendToolResultFromUi(ui)
        },
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
        <button className="cx-btn cx-btn--text cx-btn--sm" onClick={toggle}>
          {open ? t('aichat.mcpAppHide') : t('aichat.mcpAppOpen')}
        </button>
        {open && <X size={13} className="mcpapp__close" onClick={toggle} />}
      </div>

      {open && (
        <div className="mcpapp__stage">
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
            <div className="mcpapp__framebox" style={{ height }}>
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
