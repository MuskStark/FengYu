import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowLeft } from 'lucide-react'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import { pluginAssetIsolated, pluginAssetUrl } from '@/platform/url'
import { usePluginsStore } from '@/stores/plugins'
import { useSettingsStore } from '@/stores/settings'
import { createPluginNotification } from '@/stores/notifications'
import { usePluginBackgroundJobsStore } from '@/stores/pluginBackgroundJobs'
import {
  HOST_CAPABILITIES, HOST_MESSAGE_SOURCE, HOST_METHODS, PROTOCOL_VERSION, hostError, isPluginMessage,
  isPluginMessageLoose,
} from '@infinia/plugin-sdk/protocol'
import type { PluginRequestMessage } from '@infinia/plugin-sdk/protocol'
import '@/styles/plugin-host.css'

/**
 * Plugin iframe host — React port of the Vue PluginView. The wire contract is the
 * postMessage bridge from @infinia/plugin-sdk: sandbox/origin policy per uiEntry isolation,
 * one-time uiTicket URL bootstrap, invoke/notify/file-capability dispatch, and environment
 * (theme/locale) pushes. The bridge answers each plugin in the wire version that plugin
 * speaks (legacy 3.0.0 and current 4.0.0 are wire-identical), and refuses versions outside
 * that window with INCOMPATIBLE_PROTOCOL instead of silently dropping them.
 */
export default function PluginPage({ id }: { id: string }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const plugins = usePluginsStore()
  const theme = useSettingsStore(state => state.theme)
  const language = useSettingsStore(state => state.language)
  const platform = getPlatform()

  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [frameKey, setFrameKey] = useState(0)
  const [frameUrl, setFrameUrl] = useState('about:blank')
  const frameRef = useRef<HTMLIFrameElement | null>(null)
  const activeFrameWindow = useRef<Window | null>(null)
  const activeInvokes = useRef(new Map<string, AbortController>())
  const generationRef = useRef(0)
  const disposedRef = useRef(false)
  const pluginIdRef = useRef(id)
  pluginIdRef.current = id
  /** Wire version negotiated with the current frame; responses/events are stamped with it. */
  const protocolRef = useRef<string>(PROTOCOL_VERSION)

  const descriptor = plugins.plugins.find(plugin => plugin.id === id)
  const pluginUrl = () => (descriptor?.uiEntry ? pluginAssetUrl(descriptor.uiEntry) : undefined)
  const pluginSandboxed = () => (descriptor?.uiEntry ? !pluginAssetIsolated(descriptor.uiEntry) : false)
  const pluginOrigin = () => {
    const url = pluginUrl()
    return url ? new URL(url, window.location.href).origin : undefined
  }
  const pluginTargetOrigin = () => (pluginSandboxed() ? '*' : pluginOrigin())
  const frameSandbox = pluginSandboxed()
    ? 'allow-scripts allow-forms allow-downloads'
    : 'allow-scripts allow-same-origin allow-forms allow-downloads'
  const frameAllow = descriptor?.permissions?.includes('screen.capture') ? 'display-capture' : undefined

  const respond = useCallback((messageId: string, result?: unknown, errorPayload?: unknown, target: Window | null = activeFrameWindow.current) => {
    const targetOrigin = pluginTargetOrigin()
    if (!targetOrigin || !target) return
    target.postMessage(
      { source: HOST_MESSAGE_SOURCE, type: 'response', protocolVersion: protocolRef.current, id: messageId, result, error: errorPayload },
      targetOrigin,
    )
  }, // eslint-disable-line react-hooks/exhaustive-deps
  [descriptor?.uiEntry])

  const onMessage = useCallback(async (event: MessageEvent) => {
    const expectedOrigin = pluginSandboxed() ? 'null' : pluginOrigin()
    if (event.origin !== expectedOrigin) return
    if (!isPluginMessage(event.data)) {
      // Structurally a plugin message but outside the supported protocol window (older or
      // newer than this host): refuse the handshake explicitly, stamped in the plugin's own
      // version so its gate accepts the refusal. A silent drop would leave the plugin UI
      // hanging until its ready() timeout and then rendering on default theme/locale.
      const candidate = isPluginMessageLoose(event.data) ? event.data as PluginRequestMessage : null
      const version = typeof candidate?.protocolVersion === 'string' ? candidate.protocolVersion : ''
      const refusalOrigin = pluginTargetOrigin()
      if (candidate?.type !== 'request' || candidate.method !== HOST_METHODS.ready || !version || !event.source || !refusalOrigin) return
      const frameWindow = event.source as Window
      activeFrameWindow.current = frameWindow
      frameWindow.postMessage(
        {
          source: HOST_MESSAGE_SOURCE, type: 'response', protocolVersion: version, id: candidate.id,
          error: { code: 'INCOMPATIBLE_PROTOCOL', message: `Host speaks protocol ${PROTOCOL_VERSION}; plugin speaks ${version}` },
        },
        refusalOrigin,
      )
      setError(t('plugin.incompatibleProtocol', { plugin: version, host: PROTOCOL_VERSION }))
      setLoading(false)
      return
    }
    const request = event.data
    protocolRef.current = request.protocolVersion
    const pluginId = pluginIdRef.current
    if (event.source !== activeFrameWindow.current) {
      if (request.type !== 'request' || request.method !== HOST_METHODS.ready || !event.source) return
      activeFrameWindow.current = event.source as Window
    }
    if (request.type === 'cancel') {
      activeInvokes.current.get(request.id)?.abort()
      activeInvokes.current.delete(request.id)
      void services.plugin.cancelInvoke(pluginId, request.id).catch(() => {})
      return
    }
    const requestId = request.id
    try {
      if (request.method === HOST_METHODS.invoke) {
        const method = String(request.params?.method ?? '')
        const params = (request.params?.params ?? {}) as Record<string, unknown>
        if (!method) throw new Error('rpc.invoke requires a method')
        const controller = new AbortController()
        activeInvokes.current.set(request.id, controller)
        try {
          const result = await services.plugin.invoke(pluginId, method, params, { callId: request.id, signal: controller.signal })
          usePluginBackgroundJobsStore.getState().add(pluginId, method, result)
          respond(request.id, result)
        } finally {
          activeInvokes.current.delete(request.id)
        }
      } else if (request.method === HOST_METHODS.ready) {
        respond(request.id, {
          // Legacy SDKs equality-check this field against their own constant, so the
          // environment itself carries the negotiated version, like every envelope.
          protocolVersion: protocolRef.current,
          pluginId,
          pluginVersion: descriptor?.version ?? '',
          permissions: descriptor?.permissions ?? [],
          theme, locale: language,
          platform: platform.os === 'unknown' ? 'web' : 'desktop',
          capabilities: HOST_CAPABILITIES,
        }, undefined, event.source as Window)
        setLoading(false)
      } else if (request.method === HOST_METHODS.notify) {
        const message = String(request.params?.message ?? '')
        if (!message) {
          respond(request.id, false)
        } else {
          const delivered = await createPluginNotification(pluginId, descriptor?.name || pluginId, message)
          respond(request.id, delivered)
        }
      } else if (request.method === HOST_METHODS.filesOpen) {
        if (platform.capabilities.nativeFileDialogs) {
          const path = await platform.pickFile((request.params?.filters ?? []) as { name: string; extensions: string[] }[])
          respond(request.id, path ? await services.plugin.grantNativePath(pluginId, path, 'file', 'read') : null)
        } else {
          const input = document.createElement('input')
          input.type = 'file'
          input.accept = ((request.params?.extensions ?? []) as string[]).map(x => `.${x}`).join(',')
          input.onchange = async () => {
            try { respond(requestId, input.files?.[0] ? await services.plugin.uploadFile(pluginId, input.files[0]) : null) }
            catch (e) { respond(requestId, undefined, hostError(e)) }
          }
          input.addEventListener('cancel', () => respond(requestId, null))
          input.click()
        }
      } else if (request.method === HOST_METHODS.filesInputDirectory) {
        if (platform.capabilities.nativeFileDialogs) {
          const path = await platform.pickDirectory()
          respond(request.id, path ? await services.plugin.grantNativePath(pluginId, path, 'directory', 'read') : null)
        } else {
          const input = document.createElement('input')
          input.type = 'file'
          input.multiple = true
          input.setAttribute('webkitdirectory', '')
          input.onchange = async () => {
            try {
              const selected = Array.from(input.files ?? [])
              respond(requestId, selected.length ? await services.plugin.uploadDirectory(pluginId, selected) : null)
            } catch (e) { respond(requestId, undefined, hostError(e)) }
          }
          input.addEventListener('cancel', () => respond(requestId, null))
          input.click()
        }
      } else if (request.method === HOST_METHODS.filesWorkspaceDirectory) {
        if (platform.capabilities.nativeFileDialogs) {
          const path = await platform.pickDirectory()
          respond(request.id, path ? await services.plugin.grantNativePath(pluginId, path, 'directory', 'read-write') : null)
        } else {
          const input = document.createElement('input')
          input.type = 'file'
          input.multiple = true
          input.setAttribute('webkitdirectory', '')
          input.onchange = async () => {
            try {
              const selected = Array.from(input.files ?? [])
              respond(requestId, selected.length ? await services.plugin.uploadDirectory(pluginId, selected, 'read-write') : null)
            } catch (e) { respond(requestId, undefined, hostError(e)) }
          }
          input.addEventListener('cancel', () => respond(requestId, null))
          input.click()
        }
      } else if (request.method === HOST_METHODS.filesOutputDirectory) {
        if (platform.capabilities.nativeFileDialogs) {
          const path = await platform.pickDirectory()
          respond(request.id, path ? await services.plugin.grantNativePath(pluginId, path, 'directory', 'write') : null)
        } else respond(request.id, await services.plugin.createOutput(pluginId))
      } else if (request.method === HOST_METHODS.filesExport) {
        await services.plugin.exportOutput(pluginId, String(request.params?.id ?? ''))
        respond(request.id, true)
      } else {
        throw new Error(`Unsupported host capability: ${request.method}`)
      }
    } catch (e) {
      if (!(e instanceof DOMException && e.name === 'AbortError')) respond(request.id, undefined, hostError(e))
    }
  }, [descriptor, language, platform, respond, t, theme])

  const sendEnvironment = useCallback(() => {
    const targetOrigin = pluginTargetOrigin()
    if (!targetOrigin || !activeFrameWindow.current) return
    activeFrameWindow.current.postMessage(
      { source: HOST_MESSAGE_SOURCE, type: 'event', protocolVersion: protocolRef.current, event: 'environment', data: { theme, locale: language } },
      targetOrigin,
    )
  }, [language, theme])

  const retryPlugin = useCallback(async () => {
    const generation = ++generationRef.current
    const pluginId = pluginIdRef.current
    activeInvokes.current.forEach(controller => controller.abort())
    activeInvokes.current.clear()
    protocolRef.current = PROTOCOL_VERSION
    setError(null)
    setLoading(true)
    setFrameUrl('about:blank')
    setFrameKey(key => key + 1)
    await new Promise(resolve => window.setTimeout(resolve, 0))
    if (disposedRef.current || generation !== generationRef.current) return
    activeFrameWindow.current = frameRef.current?.contentWindow ?? null
    try {
      const targetUrl = pluginUrl()
      if (!targetUrl) throw new Error(t('plugin.unknown', { id: pluginId }))
      const ticket = await services.plugin.uiTicket(pluginId)
      if (disposedRef.current || generation !== generationRef.current) return
      const url = new URL(targetUrl)
      url.searchParams.set('uiTicket', ticket)
      setFrameUrl(url.toString())
    } catch (e) {
      if (disposedRef.current || generation !== generationRef.current) return
      setError(e instanceof Error ? e.message : String(e))
      setLoading(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [descriptor?.uiEntry, t])

  // The listener must survive onMessage identity changes (theme/locale re-create the
  // callback): tearing it down on every change also nulled activeFrameWindow and bumped
  // the retry generation, so the environment push after a theme flip never posted and an
  // in-flight retry aborted silently. Route through a ref instead; the teardown below then
  // runs only on real unmount, which is what the disposed/generation guards are for.
  const onMessageRef = useRef(onMessage)
  onMessageRef.current = onMessage

  useEffect(() => {
    disposedRef.current = false
    const handler = (event: MessageEvent) => { void onMessageRef.current(event) }
    window.addEventListener('message', handler)
    return () => {
      disposedRef.current = true
      generationRef.current++
      activeInvokes.current.forEach(controller => controller.abort())
      activeInvokes.current.clear()
      activeFrameWindow.current = null
      window.removeEventListener('message', handler)
    }
  }, [])

  useEffect(() => {
    void (async () => {
      if (plugins.plugins.length === 0) await plugins.load()
      if (!disposedRef.current) await retryPlugin()
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  useEffect(() => { sendEnvironment() }, [sendEnvironment])

  return (
    <div className="plugin-host">
      <div className="cx-topbar">
        <button className="cx-btn cx-btn--text cx-btn--sm" onClick={() => navigate('/tools')}>
          <ArrowLeft size={16} />{t('common.back')}
        </button>
        <span style={{ fontWeight: 600 }}>{descriptor?.name ?? id}</span>
      </div>
      {error ? (
        <div className="cx-alert cx-alert--error" style={{ margin: 16 }}>
          <div>
            <div style={{ fontWeight: 650, marginBottom: 4 }}>{t('plugin.failedTitle')}</div>
            <div>{error}</div>
          </div>
          <button className="cx-btn cx-btn--outline cx-btn--sm" style={{ marginTop: 12 }} onClick={() => void retryPlugin()}>
            {t('plugin.retry')}
          </button>
        </div>
      ) : (
        <div className="frame-wrap">
          <iframe
            key={frameKey}
            ref={frameRef}
            className="plugin-frame"
            src={frameUrl}
            sandbox={frameSandbox}
            allow={frameAllow}
            referrerPolicy="no-referrer"
            onLoad={() => { if (frameUrl !== 'about:blank') { sendEnvironment(); setLoading(false) } }}
          />
          {loading && <div className="frame-loading"><span className="cx-spin lg" /></div>}
        </div>
      )}
    </div>
  )
}
