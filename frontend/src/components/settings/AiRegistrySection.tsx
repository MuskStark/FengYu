import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, CloudDownload, Eye, EyeOff, Plug, Plus, Trash2, Zap } from 'lucide-react'
import type { AiConfigTestResult, AiProviderEntry } from '@/services/types'
import { services } from '@/services'
import { appConfirm } from '@/lib/appDialogs'
import { toastError } from '@/stores/toasts'
import {
  PROVIDER_PRESETS,
  isValidProviderId,
  protocolLabel,
  slugifyId,
  type ProviderPreset,
} from './aiRegistry'

/**
 * Section 1 — the provider ROSTER (B4 redesign). Read-first: every provider is a
 * seat card showing identity (name, protocol tag, model, credential state) with
 * quiet actions; editing unfolds inside the seat on demand, one editor at a time.
 * The active seat carries a rail + tint so "who is answering" is scanned, not
 * hunted. Adding a provider is a focused dialog driven by vendor presets; model
 * thinking level and catalog refresh live on one compact strip.
 */
export default function AiRegistrySection({ onActivation }: { onActivation?: () => void }) {
  const { t } = useTranslation()
  const [providers, setProviders] = useState<AiProviderEntry[]>([])
  const [activeId, setActiveId] = useState('')
  const [editingId, setEditingId] = useState<string | null>(null)
  const [thinkingLevel, setThinkingLevel] = useState('off')
  const [thinkingLevels, setThinkingLevels] = useState<string[]>(['off'])
  const [catalogNote, setCatalogNote] = useState<string | null>(null)
  const [addOpen, setAddOpen] = useState(false)

  const reload = useCallback(async () => {
    const list = await services.aiConfig.listProviders().catch(() => null)
    if (list) {
      setProviders(list.providers)
      setActiveId(list.activeProvider)
    }
    const cfg = await services.aiConfig.get().catch(() => null)
    if (cfg) {
      setThinkingLevel(cfg.thinkingLevel ?? 'off')
      setThinkingLevels(cfg.thinkingLevels ?? ['off'])
    }
  }, [])

  useEffect(() => {
    void reload()
  }, [reload])

  async function activate(id: string): Promise<void> {
    try {
      await services.aiConfig.activateProvider(id)
      setEditingId(null)
      await reload()
      onActivation?.()
    } catch (e) {
      // Activation rebuilds the backend from the definition — a failure leaves the
      // roster untouched and the user without a model. Reload (so the seat shows the
      // backend's truth) AND say so; silence read as "it worked".
      await reload()
      toastError(e instanceof Error && e.message
        ? e.message
        : t('aiSettings.registry.activateFailed'))
    }
  }

  async function refreshCatalog(): Promise<void> {
    setCatalogNote(t('aiSettings.registry.catalogRefreshing'))
    try {
      const out = await services.aiConfig.refreshModelCatalog()
      setCatalogNote(t('aiSettings.registry.catalogResult', { result: out.result }))
      await reload()
    } catch {
      setCatalogNote(t('aiSettings.registry.catalogFailed'))
    }
  }

  return (
    <section>
      <div className="set-h-row">
        <h2 className="set-h">{t('aiSettings.registry.title')}</h2>
        <button type="button" className="cx-btn cx-btn--outline cx-btn--sm" onClick={() => setAddOpen(true)}>
          <Plus size={15} />
          {t('aiSettings.registry.add')}
        </button>
      </div>

      <div className="prov-roster">
        {providers.map(provider => (
          <ProviderSeat
            key={provider.id}
            provider={provider}
            active={provider.id === activeId}
            editing={editingId === provider.id}
            onEdit={() => setEditingId(editingId === provider.id ? null : provider.id)}
            onCancelEdit={() => setEditingId(null)}
            onActivate={() => void activate(provider.id)}
            onSaved={() => { setEditingId(null); void reload() }}
            onDeleted={() => { setEditingId(null); void reload() }}
          />
        ))}

        <button type="button" className="prov-seat prov-seat--ghost" onClick={() => setAddOpen(true)}>
          <Plus size={16} />
          {t('aiSettings.registry.ghostHint')}
        </button>
      </div>

      <div className="prov-strip prov-strip--bare">
        <div className="prov-strip__group">
          <span className="prov-strip__label">{t('aiSettings.registry.thinking')}</span>
          <select
            className="cx-input"
            style={{ width: 150 }}
            value={thinkingLevel}
            onChange={event => {
              const next = event.target.value
              setThinkingLevel(next)
              void services.aiConfig
                .update({ thinkingLevel: next })
                .then(() => reload())
                .catch(() => reload())
            }}
          >
            {thinkingLevels.map(level => (
              <option key={level} value={level}>
                {t(`aiSettings.registry.thinkingLevel.${level}`, level)}
              </option>
            ))}
          </select>
        </div>
        <div className="prov-strip__group">
          <span className="prov-strip__label">{t('aiSettings.registry.catalogTitle')}</span>
          <button type="button" className="cx-btn cx-btn--sm" onClick={() => void refreshCatalog()}>
            <CloudDownload size={15} />
            {t('aiSettings.registry.catalogRefresh')}
          </button>
          {catalogNote ? <small className="cx-muted">{catalogNote}</small> : null}
        </div>
      </div>

      {addOpen ? <AddProviderDialog onClose={() => setAddOpen(false)} onCreated={() => { setAddOpen(false); void reload() }} /> : null}
    </section>
  )
}

// ── one seat ────────────────────────────────────────────────────────────────

function ProviderSeat({
  provider, active, editing, onEdit, onCancelEdit, onActivate, onSaved, onDeleted,
}: {
  provider: AiProviderEntry
  active: boolean
  editing: boolean
  onEdit: () => void
  onCancelEdit: () => void
  onActivate: () => void
  onSaved: () => void
  onDeleted: () => void
}) {
  const { t } = useTranslation()
  const [busy, setBusy] = useState(false)
  const [testNote, setTestNote] = useState<{ ok: boolean; text: string } | null>(null)

  async function test(): Promise<void> {
    setBusy(true)
    setTestNote(null)
    try {
      const result: AiConfigTestResult = await services.aiConfig.testProvider(provider.id)
      setTestNote(result.success
        ? { ok: true, text: result.warning ?? t('aiSettings.registry.testOk') }
        : { ok: false, text: result.error ?? t('aiSettings.testFailed') })
    } catch (e) {
      setTestNote({ ok: false, text: e instanceof Error ? e.message : String(e) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={`prov-seat${active ? ' prov-seat--active' : ''}`}>
      <div className="prov-seat__head">
        {active ? <Zap size={14} style={{ flex: 'none', color: 'rgb(var(--v-theme-primary))' }} /> : null}
        <span className="prov-seat__name">{provider.displayName}</span>
        <span className="prov-seat__tag" title={t('aiSettings.registry.protocolHint')}>{protocolLabel(provider.protocol)}</span>
        <div className="prov-seat__actions">
          <button type="button" className="cx-btn cx-btn--text cx-btn--sm" disabled={busy} onClick={() => void test()}>
            {busy ? <span className="cx-spin" /> : <Plug size={14} />}
            {t('aiSettings.registry.test')}
          </button>
          {editing ? null : (
            <button type="button" className="cx-btn cx-btn--text cx-btn--sm" disabled={busy} onClick={onEdit}>
              {t('aiSettings.registry.edit')}
            </button>
          )}
          {active ? (
            <span className="cx-chip cx-chip--success" style={{ marginLeft: 4 }}>
              <Check size={13} />
              {t('aiSettings.registry.inUse')}
            </span>
          ) : (
            <button type="button" className="cx-btn cx-btn--tonal cx-btn--sm" disabled={busy} onClick={onActivate}>
              {t('aiSettings.registry.use')}
            </button>
          )}
        </div>
      </div>

      {editing ? (
        <SeatEditor provider={provider} busy={busy} onCancel={onCancelEdit} onSaved={onSaved} onDeleted={onDeleted} />
      ) : (
        <div className="prov-seat__meta">
          <span
            className={`prov-seat__meta-model${provider.model ? '' : ' cx-muted'}`}
            title={provider.model || undefined}
          >
            {provider.model || t('aiSettings.registry.noModel')}
          </span>
          <span className="prov-dot" />
          <span className={provider.protocol === 'OLLAMA' || provider.apiKeySet ? '' : 'prov-seat__meta-key--missing'}>
            {provider.protocol !== 'OLLAMA' && !provider.apiKeySet ? '⚠ ' : ''}
            {provider.protocol === 'OLLAMA'
              ? t('aiSettings.registry.keyLocal')
              : provider.apiKeySet
                ? t('aiSettings.registry.keySet')
                : t('aiSettings.registry.keyMissing')}
          </span>
          {provider.builtin ? (<><span className="prov-dot" /><span>{t('aiSettings.registry.builtin')}</span></>) : null}
        </div>
      )}

      <div className="prov-seat__status">
        {testNote ? (
          <span className={testNote.ok ? 'cx-chip cx-chip--success' : 'cx-chip cx-chip--error'}>{testNote.text}</span>
        ) : null}
      </div>
    </div>
  )
}

// ── seat editor (unfolds in place) ──────────────────────────────────────────

function SeatEditor({
  provider, busy, onCancel, onSaved, onDeleted,
}: {
  provider: AiProviderEntry
  busy: boolean
  onCancel: () => void
  onSaved: () => void
  onDeleted: () => void
}) {
  const { t } = useTranslation()
  const [displayName, setDisplayName] = useState(provider.displayName)
  const [baseUrl, setBaseUrl] = useState(provider.baseUrl)
  const [model, setModel] = useState(provider.model)
  const [apiKey, setApiKey] = useState('')
  const [showKey, setShowKey] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function save(): Promise<void> {
    setSaving(true)
    setError(null)
    try {
      await services.aiConfig.updateProvider(provider.id, {
        displayName, baseUrl, model,
        ...(apiKey.trim() ? { apiKey: apiKey.trim() } : {}),
      })
      onSaved()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  async function remove(): Promise<void> {
    // Deleting a provider is destructive (its credential and model drop with the
    // seat) — same in-app danger confirm as every other destructive action.
    const ok = await appConfirm(t('aiSettings.registry.deleteConfirm', { name: provider.displayName }), { danger: true })
    if (!ok) return
    setSaving(true)
    setError(null)
    try {
      await services.aiConfig.deleteProvider(provider.id)
      onDeleted()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setSaving(false)
    }
  }

  const disabled = saving || busy
  return (
    <div className="prov-seat__edit">
      <div className="prov-edit-row">
        <label>{t('aiSettings.registry.name')}</label>
        <input className="cx-input" value={displayName} onChange={e => setDisplayName(e.target.value)} />
      </div>
      <div className="prov-edit-row">
        <label>{t('aiSettings.endpoint')}</label>
        <input className="cx-input" value={baseUrl} placeholder="https://…" onChange={e => setBaseUrl(e.target.value)} />
      </div>
      <div className="prov-edit-row">
        <label>{t('aiSettings.model')}</label>
        <input className="cx-input" value={model} placeholder={t('aiSettings.modelNamePh')} onChange={e => setModel(e.target.value)} />
      </div>
      <div className="prov-edit-row">
        <label>{t('aiSettings.apiKey')}</label>
        <div className="prov-key-row">
          <input
            className="cx-input"
            type={showKey ? 'text' : 'password'}
            value={apiKey}
            placeholder={provider.protocol === 'OLLAMA'
              ? t('aiSettings.registry.keyLocal')
              : provider.apiKeySet ? t('aiSettings.apiKeyHint') : t('aiSettings.registry.keyPh')}
            style={{ borderRadius: 'var(--cx-radius) 0 0 var(--cx-radius)' }}
            onChange={e => setApiKey(e.target.value)}
          />
          <button
            type="button"
            className="prov-key-mini"
            aria-label={showKey ? t('aiSettings.hideKey') : t('aiSettings.showKey')}
            onClick={() => setShowKey(v => !v)}
          >
            {showKey ? <EyeOff size={15} /> : <Eye size={15} />}
          </button>
        </div>
      </div>
      <div className="prov-edit-row" style={{ justifyContent: 'flex-end', gap: 8 }}>
        {error ? <span className="cx-chip cx-chip--error" style={{ marginRight: 'auto' }}>{error}</span> : null}
        {provider.builtin ? null : (
          <button type="button" className="cx-btn cx-btn--sm" disabled={disabled} onClick={() => void remove()}>
            <Trash2 size={14} />
            {t('aiSettings.registry.delete')}
          </button>
        )}
        <button type="button" className="cx-btn cx-btn--sm" disabled={disabled} onClick={onCancel}>
          {t('aiSettings.registry.cancel')}
        </button>
        <button type="button" className="cx-btn cx-btn--primary cx-btn--sm" disabled={disabled} onClick={() => void save()}>
          {saving ? <span className="cx-spin" /> : null}
          {t('aiSettings.registry.save')}
        </button>
      </div>
    </div>
  )
}

// ── add dialog ──────────────────────────────────────────────────────────────

function AddProviderDialog({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const { t } = useTranslation()
  const [preset, setPreset] = useState<ProviderPreset>(PROVIDER_PRESETS[0])
  const [displayName, setDisplayName] = useState('')
  const [baseUrl, setBaseUrl] = useState(PROVIDER_PRESETS[0].baseUrl)
  const [model, setModel] = useState(PROVIDER_PRESETS[0].model)
  const [apiKey, setApiKey] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)

  const id = slugifyId(displayName)
  const canCreate = isValidProviderId(id) && baseUrl.trim().length > 0 && !creating

  function choose(next: ProviderPreset): void {
    setPreset(next)
    setBaseUrl(next.baseUrl)
    setModel(next.model)
  }

  async function create(): Promise<void> {
    setCreating(true)
    setError(null)
    try {
      await services.aiConfig.createProvider({
        id, displayName, protocol: preset.protocol, baseUrl, model, apiKey,
      })
      onCreated()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setCreating(false)
    }
  }

  return (
    <div className="prov-modal-backdrop" role="presentation" onClick={onClose}>
      <div
        className="prov-add-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={t('aiSettings.registry.add')}
        onClick={e => e.stopPropagation()}
      >
        <button type="button" className="cx-iconbtn cx-iconbtn--sm prov-add-dialog__close" aria-label={t('aiSettings.registry.cancel')} onClick={onClose}>
          ✕
        </button>
        <div>
          <h2>{t('aiSettings.registry.addTitle')}</h2>
          <p className="prov-add-dialog__sub">{t('aiSettings.registry.addSub')}</p>
        </div>

        <div className="prov-presets">
          {PROVIDER_PRESETS.map(p => (
            <button
              key={p.key}
              type="button"
              className={`prov-preset${preset.key === p.key ? ' prov-preset--on' : ''}`}
              onClick={() => choose(p)}
            >
              {t(`aiSettings.registry.preset.${p.key}`, p.key)}
              <small>{protocolLabel(p.protocol)}</small>
            </button>
          ))}
        </div>

        <div className="prov-edit-row">
          <label>{t('aiSettings.registry.name')}</label>
          <input className="cx-input" value={displayName} placeholder={t('aiSettings.registry.namePh')} onChange={e => setDisplayName(e.target.value)} />
        </div>
        {displayName ? (
          <div className="prov-edit-row" style={{ marginTop: -6 }}>
            <label />
            <small className={isValidProviderId(id) ? 'cx-muted' : 'prov-seat__meta-key--missing'}>
              {isValidProviderId(id) ? `id: ${id}` : t('aiSettings.registry.badId')}
            </small>
          </div>
        ) : null}
        <div className="prov-edit-row">
          <label>{t('aiSettings.endpoint')}</label>
          <input className="cx-input" value={baseUrl} placeholder="https://…" onChange={e => setBaseUrl(e.target.value)} />
        </div>
        <div className="prov-edit-row">
          <label>{t('aiSettings.model')}</label>
          <input className="cx-input" value={model} placeholder={t('aiSettings.modelNamePh')} onChange={e => setModel(e.target.value)} />
        </div>
        <div className="prov-edit-row">
          <label>{t('aiSettings.apiKey')}</label>
          <input className="cx-input" type="password" value={apiKey} onChange={e => setApiKey(e.target.value)} />
        </div>

        {error ? <div className="cx-alert cx-alert--error"><span className="cx-alert__body">{error}</span></div> : null}

        <div className="prov-add-dialog__actions">
          <button type="button" className="cx-btn" onClick={onClose}>{t('aiSettings.registry.cancel')}</button>
          <button type="button" className="cx-btn cx-btn--primary" disabled={!canCreate} onClick={() => void create()}>
            {creating ? <span className="cx-spin" /> : null}
            {t('aiSettings.registry.create')}
          </button>
        </div>
      </div>
    </div>
  )
}
