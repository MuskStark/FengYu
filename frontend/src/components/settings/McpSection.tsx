import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Pencil, Plus, Search, Trash2 } from 'lucide-react'
import type { McpServer, McpServerRequest, McpStatus, McpTransportType } from '@/services/types'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import { useToastStore } from '@/stores/toasts'

/**
 * Section 5 — MCP: live server list with enable/disable switches, plus create/edit/test/
 * delete via the runtime management endpoints (gated on McpStatus.dynamicManagement —
 * older backends expose only the diagnostics endpoint, and the section degrades to the
 * read-only summary). Imported (plugin-declared) servers cannot be adopted from the row
 * switch: enabling them always goes through the editor + explicit confirmation.
 */

interface McpForm {
  name: string
  type: McpTransportType
  command: string
  args: string
  url: string
  endpoint: string
  env: string
  headers: string
  enabled: boolean
}

const EMPTY_FORM: McpForm = {
  name: '', type: 'STDIO', command: '', args: '', url: '', endpoint: '',
  env: '', headers: '', enabled: true,
}

function formFromServer(server: McpServer): McpForm {
  return {
    name: server.name,
    type: server.type,
    command: server.command ?? '',
    args: server.args.join('\n'),
    url: server.url ?? '',
    endpoint: server.endpoint ?? '',
    env: '',
    headers: '',
    enabled: server.enabled,
  }
}

export default function McpSection() {
  const { t } = useTranslation()
  const pushToast = useToastStore(state => state.push)
  const [status, setStatus] = useState<McpStatus | null>(null)
  const [servers, setServers] = useState<McpServer[]>([])
  const [view, setView] = useState<'list' | 'detail'>('list')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [form, setForm] = useState<McpForm>(EMPTY_FORM)
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<'all' | 'enabled' | 'disabled' | 'stdio' | 'sse' | 'streamableHttp'>('all')
  const [saving, setSaving] = useState(false)
  const [testingId, setTestingId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const loadServers = useCallback(async () => {
    try {
      setServers(await services.mcp.servers())
    } catch {
      setServers([])
    }
  }, [])

  useEffect(() => {
    void services.mcp.status()
      .then(value => {
        setStatus(value)
        // dynamicManagement=true marks backends with the runtime management endpoints;
        // without it the section stays a read-only summary (no noisy 404s).
        if (value.dynamicManagement === true) void loadServers()
      })
      .catch(() => { /* diagnostics unavailable — placeholder stays */ })
  }, [loadServers])

  const selected = servers.find(server => server.id === selectedId) ?? null

  const filtered = servers.filter(server => {
    if (filter === 'enabled' && !server.enabled) return false
    if (filter === 'disabled' && server.enabled) return false
    if (filter === 'stdio' && server.type !== 'STDIO') return false
    if (filter === 'sse' && server.type !== 'SSE') return false
    if (filter === 'streamableHttp' && server.type !== 'STREAMABLE_HTTP') return false
    const q = query.trim().toLowerCase()
    return !q || `${server.name} ${server.type} ${server.serverVersion}`.toLowerCase().includes(q)
  })

  function openCreate(): void {
    setSelectedId(null)
    setForm(EMPTY_FORM)
    setError(null)
    setView('detail')
  }

  function openServer(server: McpServer): void {
    setSelectedId(server.id)
    setForm(formFromServer(server))
    setError(null)
    setView('detail')
  }

  function typeLabel(type: McpTransportType): string {
    return type === 'STDIO' ? t('settings.mcp.typeStdio')
      : type === 'SSE' ? t('settings.mcp.typeSse')
        : t('settings.mcp.typeStreamableHttp')
  }

  function statusLabel(server: McpServer): string {
    if (!server.enabled) return t('settings.mcp.statusDisabled')
    if (server.status === 'connecting') return t('status.connecting')
    if (server.status === 'connected') return t('settings.mcp.statusConnected')
    if (server.status === 'error') return t('settings.mcp.statusError')
    return t('settings.mcp.statusDisconnected')
  }

  async function toggleServer(server: McpServer): Promise<void> {
    setError(null)
    if (server.source) {
      // Imported executable/network declarations must be inspected and explicitly
      // confirmed in the editor — never adopted from the summary-row switch.
      openServer(server)
      return
    }
    try {
      await services.mcp.updateServer(server.id, {
        name: server.name,
        type: server.type,
        command: server.command ?? undefined,
        args: server.args,
        url: server.url ?? undefined,
        endpoint: server.endpoint ?? undefined,
        enabled: !server.enabled,
      })
      await loadServers()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  /** Parses the env/headers JSON editors; empty keeps the stored values (never re-sent). */
  function parseMap(text: string, label: string): Record<string, string> | undefined {
    if (!text.trim()) return undefined
    const parsed: unknown = JSON.parse(text)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error(t('settings.mcp.jsonObjectError', { label }))
    }
    return Object.fromEntries(Object.entries(parsed as Record<string, unknown>).map(([key, value]) => {
      if (typeof value !== 'string') throw new Error(t('settings.mcp.jsonValueError', { label, key }))
      return [key, value]
    }))
  }

  async function save(): Promise<void> {
    setSaving(true)
    setError(null)
    try {
      const request: McpServerRequest = {
        name: form.name.trim(),
        type: form.type,
        command: form.command.trim() || undefined,
        args: form.args.split('\n').map(value => value.trim()).filter(Boolean),
        url: form.url.trim() || undefined,
        endpoint: form.endpoint.trim() || undefined,
        env: parseMap(form.env, t('settings.mcp.environment')),
        headers: parseMap(form.headers, t('settings.mcp.headers')),
        enabled: form.enabled,
        disabledTools: selected?.disabledTools,
        confirmImported: Boolean(selected?.source && form.enabled),
      }
      const saved = selectedId
        ? await services.mcp.updateServer(selectedId, request)
        : await services.mcp.createServer(request)
      await loadServers()
      openServer(saved)
      pushToast({ level: 'success', title: t('common.save'), body: saved.name })
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  async function test(server: McpServer): Promise<void> {
    setTestingId(server.id)
    setError(null)
    try {
      const tested = await services.mcp.testServer(server.id)
      await loadServers()
      openServer(tested)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setTestingId(null)
    }
  }

  async function remove(server: McpServer): Promise<void> {
    if (!await getPlatform().confirm(t('settings.mcp.deleteConfirm', { name: server.name }), { danger: true })) return
    setError(null)
    try {
      await services.mcp.deleteServer(server.id)
      if (selectedId === server.id) {
        setSelectedId(null)
        setView('list')
      }
      await loadServers()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  const manageable = status?.dynamicManagement === true

  return (
    <section>
      {view === 'list' ? (
        <div>
          <h2 className="set-h">{t('settings.mcp.allServers')}</h2>
          <div className="cx-muted" style={{ fontSize: 12, margin: '-10px 0 16px' }}>
            {status
              ? t('settings.mcpSummary', { connections: status.connectionCount, tools: status.toolCount })
              : t('settings.mcp.subtitle')}
          </div>

          {!manageable ? (
            <div className="cx-alert cx-alert--warn">
              <span className="cx-alert__body">{t('settings.mcp.subtitle')}</span>
            </div>
          ) : (
            <>
              <div className="mcp-toolbar">
                <div className="mcp-search">
                  <Search size={15} className="mcp-search-icon" />
                  <input
                    className="cx-input"
                    style={{ paddingLeft: 32 }}
                    placeholder={t('settings.mcp.search')}
                    value={query}
                    onChange={event => setQuery(event.target.value)}
                  />
                </div>
                <select
                  className="cx-input mcp-filter"
                  aria-label={t('settings.mcp.filter')}
                  value={filter}
                  onChange={event => setFilter(event.target.value as typeof filter)}
                >
                  <option value="all">{t('settings.mcp.filterAll')}</option>
                  <option value="enabled">{t('settings.mcp.filterEnabled')}</option>
                  <option value="disabled">{t('settings.mcp.filterDisabled')}</option>
                  <option value="stdio">{t('settings.mcp.typeStdio')}</option>
                  <option value="sse">{t('settings.mcp.typeSse')}</option>
                  <option value="streamableHttp">{t('settings.mcp.typeStreamableHttp')}</option>
                </select>
              </div>

              <div className="mcp-table">
                <div className="mcp-table-head">
                  <span>{t('settings.mcp.server')}</span>
                  <span>{t('settings.mcp.version')}</span>
                  <span>{t('settings.mcp.transport')}</span>
                  <span>{t('settings.mcp.tools')}</span>
                  <span />
                </div>
                {filtered.map(server => (
                  <div
                    key={server.id}
                    className="mcp-server-row"
                    role="button"
                    tabIndex={0}
                    onClick={() => openServer(server)}
                    onKeyDown={event => {
                      if (event.key === 'Enter') openServer(server)
                    }}
                  >
                    <span className="mcp-server-name">
                      <span className="mcp-avatar">{server.name.slice(0, 1).toUpperCase()}</span>
                      <span className={`mcp-status-dot mcp-status-dot--${server.enabled ? server.status : 'disabled'}`} />
                      <span className="mcp-server-name-text">
                        <strong>{server.name}</strong>
                        <small>{statusLabel(server)}</small>
                        {server.source ? <small>{t('settings.mcp.pluginSource', { source: server.source })}</small> : null}
                      </span>
                    </span>
                    <span className="mcp-table-muted">{server.serverVersion || '—'}</span>
                    <span>
                      <span className={`mcp-type-badge mcp-type-badge--${server.type.toLowerCase()}`}>
                        {typeLabel(server.type)}
                      </span>
                    </span>
                    <span className="mcp-table-muted">{server.tools.length}</span>
                    <span className="mcp-row-actions" onClick={event => event.stopPropagation()}>
                      <button
                        type="button"
                        className="mcp-icon-btn"
                        title={t('settings.mcp.edit')}
                        onClick={() => openServer(server)}
                      >
                        <Pencil size={14} />
                      </button>
                      <label className="mcp-switch" title={t('settings.mcp.toggle')}>
                        <input
                          type="checkbox"
                          checked={server.enabled}
                          onChange={() => void toggleServer(server)}
                        />
                        <span />
                      </label>
                    </span>
                  </div>
                ))}
                {filtered.length === 0 && (
                  <div className="mcp-empty">
                    <strong>
                      {servers.length === 0 ? t('settings.mcp.empty') : t('settings.mcp.noResults')}
                    </strong>
                    <span>
                      {servers.length === 0 ? t('settings.mcp.emptyHint') : t('settings.mcp.noResultsHint')}
                    </span>
                  </div>
                )}
              </div>

              <div className="set-save-row">
                <button type="button" className="cx-btn cx-btn--primary" onClick={openCreate}>
                  <Plus size={16} />
                  {t('settings.mcp.addServer')}
                </button>
              </div>
            </>
          )}
        </div>
      ) : (
        <div>
          <button
            type="button"
            className="cx-btn cx-btn--text cx-btn--sm cx-setup-back"
            onClick={() => setView('list')}
          >
            {t('common.back')}
          </button>
          <h2 className="set-h">{selectedId ? (selected?.name ?? form.name) : t('settings.mcp.newServer')}</h2>

          <div className="cx-card">
            <div className="mcp-form-section">
              <div className="mcp-section-title">{t('settings.mcp.identity')}</div>
              <div className="mcp-form-grid">
                <div className="cx-field">
                  <label className="cx-label" htmlFor="cx-mcp-name">{t('settings.mcp.name')}</label>
                  <input
                    id="cx-mcp-name"
                    className="cx-input"
                    placeholder={t('settings.mcp.placeholderName')}
                    value={form.name}
                    onChange={event => setForm(current => ({ ...current, name: event.target.value }))}
                  />
                </div>
                <div className="cx-field">
                  <label className="cx-label" htmlFor="cx-mcp-type">{t('settings.mcp.transport')}</label>
                  <select
                    id="cx-mcp-type"
                    className="cx-input"
                    value={form.type}
                    onChange={event => setForm(current => ({ ...current, type: event.target.value as McpTransportType }))}
                  >
                    <option value="STDIO">{t('settings.mcp.typeStdio')}</option>
                    <option value="STREAMABLE_HTTP">{t('settings.mcp.typeStreamableHttp')}</option>
                    <option value="SSE">{t('settings.mcp.typeSse')}</option>
                  </select>
                </div>
              </div>
            </div>

            <div className="mcp-form-section">
              <div className="mcp-section-title">{t('settings.mcp.connection')}</div>
              <div className="mcp-form-grid">
                {form.type === 'STDIO' ? (
                  <>
                    <div className="cx-field">
                      <label className="cx-label" htmlFor="cx-mcp-command">{t('settings.mcp.command')}</label>
                      <input
                        id="cx-mcp-command"
                        className="cx-input"
                        placeholder={t('settings.mcp.placeholderCommand')}
                        value={form.command}
                        onChange={event => setForm(current => ({ ...current, command: event.target.value }))}
                      />
                    </div>
                    <div className="cx-field">
                      <label className="cx-label" htmlFor="cx-mcp-args">{t('settings.mcp.arguments')}</label>
                      <textarea
                        id="cx-mcp-args"
                        className="cx-input mcp-textarea"
                        placeholder={'-y\n@modelcontextprotocol/server-filesystem\n/tmp'}
                        value={form.args}
                        onChange={event => setForm(current => ({ ...current, args: event.target.value }))}
                      />
                    </div>
                  </>
                ) : (
                  <>
                    <div className="cx-field">
                      <label className="cx-label" htmlFor="cx-mcp-url">{t('settings.mcp.url')}</label>
                      <input
                        id="cx-mcp-url"
                        className="cx-input"
                        placeholder={t('settings.mcp.placeholderUrl')}
                        value={form.url}
                        onChange={event => setForm(current => ({ ...current, url: event.target.value }))}
                      />
                    </div>
                    <div className="cx-field">
                      <label className="cx-label" htmlFor="cx-mcp-endpoint">{t('settings.mcp.endpoint')}</label>
                      <input
                        id="cx-mcp-endpoint"
                        className="cx-input"
                        placeholder={form.type === 'SSE'
                          ? t('settings.mcp.placeholderSseEndpoint')
                          : t('settings.mcp.placeholderHttpEndpoint')}
                        value={form.endpoint}
                        onChange={event => setForm(current => ({ ...current, endpoint: event.target.value }))}
                      />
                    </div>
                  </>
                )}
              </div>
            </div>

            <div className="mcp-form-section">
              <div className="mcp-section-title">
                {t('settings.mcp.credentials')}
                <span>{t('settings.mcp.credentialsHint')}</span>
              </div>
              <div className="mcp-form-grid">
                <div className="cx-field">
                  <label className="cx-label" htmlFor="cx-mcp-env">{t('settings.mcp.environment')}</label>
                  <textarea
                    id="cx-mcp-env"
                    className="cx-input mcp-textarea"
                    spellCheck={false}
                    placeholder='{"API_KEY":"..."}'
                    value={form.env}
                    onChange={event => setForm(current => ({ ...current, env: event.target.value }))}
                  />
                </div>
                {form.type !== 'STDIO' && (
                  <div className="cx-field">
                    <label className="cx-label" htmlFor="cx-mcp-headers">{t('settings.mcp.headers')}</label>
                    <textarea
                      id="cx-mcp-headers"
                      className="cx-input mcp-textarea"
                      spellCheck={false}
                      placeholder='{"Authorization":"Bearer ..."}'
                      value={form.headers}
                      onChange={event => setForm(current => ({ ...current, headers: event.target.value }))}
                    />
                  </div>
                )}
              </div>
            </div>

            <div className="cx-setting-row">
              <div className="cx-setting-row__label"><span>{t('aiSettings.enabled')}</span></div>
              <div className="cx-segment">
                <button
                  type="button"
                  className={!form.enabled ? 'active' : ''}
                  onClick={() => setForm(current => ({ ...current, enabled: false }))}
                >
                  {t('common.off')}
                </button>
                <button
                  type="button"
                  className={form.enabled ? 'active' : ''}
                  onClick={() => setForm(current => ({ ...current, enabled: true }))}
                >
                  {t('common.on')}
                </button>
              </div>
            </div>
          </div>

          {error && (
            <div className="cx-alert cx-alert--error" style={{ marginTop: 12 }}>
              <span className="cx-alert__body">{error}</span>
            </div>
          )}

          <div className="set-save-row">
            {selectedId && (
              <button type="button" className="cx-btn cx-btn--danger" onClick={() => selected && void remove(selected)}>
                <Trash2 size={15} />
                {t('common.delete')}
              </button>
            )}
            <span style={{ flex: 1 }} />
            {selectedId && (
              <button
                type="button"
                className="cx-btn"
                disabled={testingId === selectedId}
                onClick={() => selected && void test(selected)}
              >
                {testingId === selectedId ? <span className="cx-spin" /> : null}
                {testingId === selectedId ? t('settings.mcp.testing') : t('settings.mcp.test')}
              </button>
            )}
            <button type="button" className="cx-btn cx-btn--primary" disabled={saving} onClick={() => void save()}>
              {saving ? <span className="cx-spin" /> : null}
              {saving ? t('settings.mcp.saving') : t('common.save')}
            </button>
          </div>
        </div>
      )}
    </section>
  )
}
