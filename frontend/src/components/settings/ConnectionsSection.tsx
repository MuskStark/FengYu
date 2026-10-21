import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { KeyRound, Pencil, Plug, Plus, Puzzle, Settings2, Trash2 } from 'lucide-react'
import { services } from '@/services'
import type { McpServer, PluginDescriptor } from '@/services/types'
import type { ConnectionProfile, ConnectionProfileDraft } from '@/services/connections'
import { appConfirm } from '@/lib/appDialogs'

/**
 * Connections — the one place that lists every external capability a flow may need to
 * sign in to or configure: the saved connection profiles (fill in once, pick from any
 * node), MCP servers (with their secrets/headers) and installed plugins (with their own
 * settings pages). Profiles are edited here; MCP/plugin credentials keep their existing
 * editors, so there is exactly one write path per credential.
 */
export default function ConnectionsSection() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [servers, setServers] = useState<McpServer[]>([])
  const [plugins, setPlugins] = useState<PluginDescriptor[]>([])
  const [profiles, setProfiles] = useState<ConnectionProfile[]>([])
  const [loading, setLoading] = useState(true)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  /** null = closed; 'new' = creating; otherwise the profile being edited. */
  const [editing, setEditing] = useState<'new' | ConnectionProfile | null>(null)

  const loadProfiles = useCallback(async () => {
    try {
      setProfiles(await services.connections.list())
    } catch {
      /* the list keeps its last copy — the alert surfaces the real failure on save */
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const [serverList, pluginList, profileList] = await Promise.all([
          services.mcp.servers().catch(() => [] as McpServer[]),
          services.plugin.list().catch(() => [] as PluginDescriptor[]),
          services.connections.list().catch(() => [] as ConnectionProfile[]),
        ])
        if (cancelled) return
        setServers(serverList ?? [])
        setPlugins(pluginList ?? [])
        setProfiles(profileList ?? [])
      } catch (e) {
        if (!cancelled) setErrorMsg(e instanceof Error ? e.message : t('common.unexpectedError'))
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    void load()
    return () => { cancelled = true }
  }, [t])

  const saveProfile = async (draft: ConnectionProfileDraft): Promise<boolean> => {
    try {
      if (editing && editing !== 'new') await services.connections.update(editing.id, draft)
      else await services.connections.create(draft)
      await loadProfiles()
      setEditing(null)
      setErrorMsg(null)
      return true
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : t('common.unexpectedError'))
      return false
    }
  }

  const deleteProfile = async (profile: ConnectionProfile) => {
    if (!await appConfirm(t('settings.connections.profileDeleteConfirm', { name: profile.name }), { danger: true })) return
    try {
      await services.connections.remove(profile.id)
      await loadProfiles()
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : t('common.unexpectedError'))
    }
  }

  const serverState = (server: McpServer): { label: string; className: string } => {
    if (!server.enabled) return { label: t('settings.connections.stateDisabled'), className: '' }
    if (server.status === 'connected') return { label: t('settings.connections.stateConnected'), className: ' cx-chip--success' }
    if (server.status === 'error') return { label: t('settings.connections.stateError'), className: ' cx-chip--danger' }
    return { label: t('settings.connections.stateDisconnected'), className: '' }
  }

  return (
    <section>
      <h2 className="set-h">{t('settings.connections.title')}</h2>
      <div className="cx-muted text-ui-sm -mt-2.5 mb-4">{t('settings.connections.intro')}</div>

      {errorMsg && (
        <div className="cx-alert cx-alert--error">
          <span className="cx-alert__body">{errorMsg}</span>
          <button className="cx-iconbtn cx-iconbtn--sm" onClick={() => setErrorMsg(null)}>×</button>
        </div>
      )}

      <h3 className="set-h text-ui-sm mt-4"><KeyRound size={14} /> {t('settings.connections.profilesTitle')}</h3>
      <div className="cx-muted text-ui-sm mb-2">{t('settings.connections.profilesIntro')}</div>
      {editing ? (
        <ProfileEditor
          profile={editing === 'new' ? null : editing}
          onCancel={() => setEditing(null)}
          onSave={saveProfile}
        />
      ) : (
        <>
          {!loading && !profiles.length ? (
            <p className="cx-muted">{t('settings.connections.profilesEmpty')}</p>
          ) : (
            <div className="flex flex-col gap-2">
              {profiles.map((profile) => (
                <article key={profile.id} className="cx-card flex items-center gap-3">
                  <span className="flex min-w-0 flex-1 flex-col">
                    <strong className="truncate">{profile.name}</strong>
                    <small className="cx-muted truncate">
                      {profile.type} · {Object.keys(profile.fields).join(', ')}
                    </small>
                  </span>
                  <span className="cx-row">
                    <button
                      className="cx-iconbtn cx-iconbtn--sm"
                      title={t('common.edit')}
                      onClick={() => setEditing(profile)}
                    >
                      <Pencil size={13} />
                    </button>
                    <button
                      className="cx-iconbtn cx-iconbtn--sm"
                      title={t('common.delete')}
                      onClick={() => void deleteProfile(profile)}
                    >
                      <Trash2 size={13} />
                    </button>
                  </span>
                </article>
              ))}
            </div>
          )}
          <button className="cx-btn cx-btn--outline mt-2" onClick={() => setEditing('new')}>
            <Plus size={13} /> {t('settings.connections.profileNew')}
          </button>
        </>
      )}

      <h3 className="set-h text-ui-sm mt-6"><Plug size={14} /> {t('settings.connections.mcpTitle')}</h3>
      {loading ? (
        <p className="cx-muted">{t('common.loading')}</p>
      ) : !servers.length ? (
        <p className="cx-muted">{t('settings.connections.mcpEmpty')}</p>
      ) : (
        <div className="flex flex-col gap-2">
          {servers.map((server) => {
            const state = serverState(server)
            return (
              <article key={server.id} className="cx-card flex items-center gap-3">
                <span className="flex min-w-0 flex-1 flex-col">
                  <strong className="truncate">{server.name}</strong>
                  <small className="cx-muted">
                    {server.type}
                    {server.tools.length ? ` · ${t('settings.connections.toolCount', { count: server.tools.length })}` : ''}
                    {server.envKeys.length + server.headerNames.length
                      ? ` · ${t('settings.connections.secretCount', { count: server.envKeys.length + server.headerNames.length })}`
                      : ''}
                  </small>
                </span>
                <span className={`cx-chip${state.className}`}>{state.label}</span>
                <button
                  className="cx-btn cx-btn--outline"
                  onClick={() => navigate('/settings?section=mcp')}
                >
                  <Settings2 size={13} /> {t('settings.connections.configure')}
                </button>
              </article>
            )
          })}
        </div>
      )}

      <h3 className="set-h text-ui-sm mt-6"><Puzzle size={14} /> {t('settings.connections.pluginsTitle')}</h3>
      {loading ? (
        <p className="cx-muted">{t('common.loading')}</p>
      ) : !plugins.length ? (
        <p className="cx-muted">{t('settings.connections.pluginsEmpty')}</p>
      ) : (
        <div className="flex flex-col gap-2">
          {plugins.map((plugin) => (
            <article key={plugin.id} className="cx-card flex items-center gap-3">
              <span className="flex min-w-0 flex-1 flex-col">
                <strong className="truncate">{plugin.name}</strong>
                <small className="cx-muted truncate">{plugin.description}</small>
              </span>
              <span className={`cx-chip${plugin.enabled === false ? '' : ' cx-chip--success'}`}>
                {plugin.enabled === false
                  ? t('settings.connections.stateDisabled')
                  : t('settings.connections.stateInstalled')}
              </span>
              <button className="cx-btn cx-btn--outline" onClick={() => navigate(`/plugin/${encodeURIComponent(plugin.id)}`)}>
                <Settings2 size={13} /> {t('settings.connections.open')}
              </button>
            </article>
          ))}
        </div>
      )}

      <p className="cx-muted text-ui-sm mt-6">{t('settings.connections.storeHint')} </p>
      <button className="cx-btn cx-btn--outline" onClick={() => navigate('/store')}>
        {t('settings.connections.openStore')}
      </button>
    </section>
  )
}

/** Create/edit form for one connection profile: name, type, and key-value credential rows. */
function ProfileEditor(props: {
  profile: ConnectionProfile | null
  onCancel: () => void
  onSave: (draft: ConnectionProfileDraft) => Promise<boolean>
}) {
  const { t } = useTranslation()
  const [name, setName] = useState(props.profile?.name ?? '')
  const [type, setType] = useState(props.profile?.type ?? '')
  const [rows, setRows] = useState<Array<{ key: string; value: string }>>(
    props.profile
      ? Object.entries(props.profile.fields).map(([key, value]) => ({ key, value }))
      : [{ key: 'value', value: '' }],
  )
  const [busy, setBusy] = useState(false)

  const save = async () => {
    if (busy) return
    setBusy(true)
    const fields: Record<string, string> = {}
    for (const row of rows) {
      if (row.key.trim()) fields[row.key.trim()] = row.value
    }
    const ok = await props.onSave({ name: name.trim(), type: type.trim(), fields })
    setBusy(false)
    if (ok) props.onCancel()
  }

  return (
    <div className="flex flex-col gap-3">
      <label className="flex flex-col gap-1 text-ui-sm">
        <span>{t('settings.connections.profileName')}</span>
        <input className="cx-input" value={name} disabled={busy}
          onChange={(event) => setName(event.target.value)} />
      </label>
      <label className="flex flex-col gap-1 text-ui-sm">
        <span>{t('settings.connections.profileType')}</span>
        <input className="cx-input mono" value={type} disabled={busy} list="fengyu-connection-types"
          placeholder={t('settings.connections.profileTypeHint')}
          onChange={(event) => setType(event.target.value)} />
        <datalist id="fengyu-connection-types">
          <option value="email" /><option value="api-key" /><option value="bearer" />
          <option value="database" /><option value="webhook" />
        </datalist>
      </label>
      {rows.map((row, index) => (
        <div key={index} className="flex items-center gap-2">
          <input className="cx-input mono conn-profile-row__key" value={row.key} disabled={busy}
            placeholder={t('settings.connections.profileFieldKey')}
            onChange={(event) => setRows((current) => current.map((item, i) =>
              i === index ? { ...item, key: event.target.value } : item))} />
          <input className="cx-input mono" type="password" value={row.value} disabled={busy}
            placeholder={t('settings.connections.profileFieldValue')}
            onChange={(event) => setRows((current) => current.map((item, i) =>
              i === index ? { ...item, value: event.target.value } : item))} />
          <button className="cx-iconbtn cx-iconbtn--sm" disabled={busy}
            title={t('common.delete')}
            onClick={() => setRows((current) => current.filter((_, i) => i !== index))}>
            <Trash2 size={13} />
          </button>
        </div>
      ))}
      <span className="cx-row">
        <button className="cx-btn cx-btn--outline" disabled={busy}
          onClick={() => setRows((current) => [...current, { key: '', value: '' }])}>
          <Plus size={13} /> {t('settings.connections.profileAddField')}
        </button>
        <span className="flex-grow" />
        <button className="cx-btn cx-btn--outline" disabled={busy} onClick={props.onCancel}>
          {t('common.cancel')}
        </button>
        <button className="cx-btn cx-btn--primary" disabled={busy || !name.trim() || !type.trim() || !rows.some((row) => row.key.trim())}
          onClick={() => void save()}>
          {t('common.save')}
        </button>
      </span>
    </div>
  )
}
