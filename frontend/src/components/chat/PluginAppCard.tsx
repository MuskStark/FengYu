import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2, Puzzle, ShieldAlert, X } from 'lucide-react'
import { usePluginHostFrame } from '@/lib/usePluginHostFrame'
import { usePluginsStore } from '@/stores/plugins'
import type { PluginToolUiInfo } from '@/services/types'

/**
 * Host card for a plugin tool result carrying an `aiTools[].app` view: mounts the plugin's
 * own page (served under /plugin-runtime/{id}) in the standard sandboxed plugin iframe with
 * the FULL sdk-ts bridge — the same contract as the Tools-page console, so the view keeps
 * rpc.invoke, file pickers, and theme/locale pushes. Collapsed by default; `dialog` mode
 * opens the same frame host inside a modal instead of the inline stage. The one-time
 * uiTicket is minted path-bound at mount (never persisted with the descriptor). `hosted`
 * (an already-modal host such as the flow inspector's dialog) demotes `dialog` views to the
 * inline stage, avoiding nested modals.
 */
export function PluginAppCard({ ui, defaultOpen = false, hosted = false }: {
  /** Accepted for dispatcher uniformity; plugin views keep no per-scope host state. */
  scopeId: string
  ui: PluginToolUiInfo
  defaultOpen?: boolean
  hosted?: boolean
}) {
  const { t } = useTranslation()
  const plugins = usePluginsStore()
  const [open, setOpen] = useState(defaultOpen)
  const pluginName = plugins.plugins.find(plugin => plugin.id === ui.pluginId)?.name

  const toggle = () => setOpen(value => !value)
  const asDialog = ui.mode === 'dialog' && !hosted

  const frame = open ? <PluginAppFrame pluginId={ui.pluginId} path={ui.path} /> : null

  return (
    <div className="mcpapp">
      <div className="mcpapp__bar">
        <Puzzle size={13} className="mcpapp__glyph" />
        <span className="mcpapp__title">
          {ui.title || pluginName || ui.pluginId}
        </span>
        <button className="cx-btn cx-btn--text cx-btn--sm" onClick={toggle}>
          {open ? t('aichat.mcpAppHide') : t('aichat.mcpAppOpen')}
        </button>
        {open && <X size={13} className="mcpapp__close" onClick={toggle} />}
      </div>
      {open && asDialog ? (
        <div className="app-dialog-backdrop" onClick={toggle}>
          <div
            className="app-dialog app-dialog--wide"
            role="dialog"
            aria-modal="true"
            aria-label={ui.title || pluginName || ui.pluginId}
            onClick={event => event.stopPropagation()}
          >
            <div className="app-dialog__title">{ui.title || pluginName || ui.pluginId}</div>
            {frame}
            <div className="app-dialog__actions">
              <button className="cx-btn cx-btn--text cx-btn--sm" onClick={toggle}>
                {t('common.close')}
              </button>
            </div>
          </div>
        </div>
      ) : open ? (
        <div className="mcpapp__stage">{frame}</div>
      ) : null}
    </div>
  )
}

/** The mounted plugin view; a child component so collapsing unmounts (and tears down) the bridge. */
function PluginAppFrame({ pluginId, path }: { pluginId: string; path: string }) {
  const { t } = useTranslation()
  const { error, loading, frameUrl, frameKey, frameSandbox, frameAllow, frameRef, retry, handleFrameLoad }
    = usePluginHostFrame(pluginId, path)

  if (error) {
    return (
      <div className="cx-alert cx-alert--error mcpapp__alert">
        <ShieldAlert size={14} />
        <div className="cx-alert__body">{error}</div>
        <button className="cx-btn cx-btn--outline cx-btn--sm" onClick={() => void retry()}>
          {t('plugin.retry')}
        </button>
      </div>
    )
  }
  return (
    <div className="mcpapp__framebox mcpapp__framebox--plugin">
      <iframe
        key={frameKey}
        ref={frameRef}
        title={`Plugin app: ${pluginId}`}
        className="mcpapp__frame"
        src={frameUrl}
        sandbox={frameSandbox}
        allow={frameAllow}
        referrerPolicy="no-referrer"
        onLoad={handleFrameLoad}
      />
      {loading && (
        <div className="mcpapp__loading mcpapp__loading--overlay">
          <Loader2 size={14} className="cx-spin" />
          {t('aichat.mcpAppPreparing')}
        </div>
      )}
    </div>
  )
}
