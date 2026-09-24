import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { FileCheck, FileCode2, MemoryStick, MonitorSmartphone, ScrollText, ShieldAlert, ShieldCheck } from 'lucide-react'
import type { AppSettings, LogLevel, PermissionRuleTable, ProcessIsolationStatus } from '@/services/types'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import { useToastStore } from '@/stores/toasts'
import SettingRow from './SettingRow'

/**
 * Section 4 — runtime & security: every remaining writable AppSettings field.
 *
 * AppSettings field census (types.ts):
 * - theme / language / sidebarCollapsed → appearance section
 * - logLevel                    → here (select, shared with plugin workers)
 * - unsandboxedPlugins          → here (guarded allow segment)
 * - computerUseEnabled          → here (guarded allow; `computerUse` is a GET-only probe)
 * - memoryEnabled               → here (on/off segment)
 * - marketplaceRequireChecksum  → here (on/off segment)
 * - updateApiBase / storeAllowPrivateNetwork → update section (channel semantics)
 * - permissionRules             → here (AI guard rules; saved via PUT /api/settings/permission-rules)
 * - hooks                       → here (AI guard hooks JSON; saved via PUT /api/settings/hooks)
 * - invalidPermissionRules      → GET-only (rendered as the ignored-rules warning)
 */
const LOG_LEVELS: LogLevel[] = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'OFF']
const RULE_KINDS = ['allow', 'ask', 'deny'] as const

export default function RuntimeSection({
  appSettings,
  update,
  reload,
}: {
  appSettings: AppSettings | null
  /** PUT /api/settings funnel — optimistic where the caller wants it, always re-applies. */
  update: (partial: Partial<AppSettings>) => Promise<void>
  /** Re-fetches settings after the rule/hook endpoints (they answer {ok}, not AppSettings). */
  reload: () => Promise<void>
}) {
  const { t } = useTranslation()
  const pushToast = useToastStore(state => state.push)
  const [isolation, setIsolation] = useState<ProcessIsolationStatus | null>(null)
  const [runtimeError, setRuntimeError] = useState<string | null>(null)
  const [guardSaving, setGuardSaving] = useState(false)
  const [guardError, setGuardError] = useState<string | null>(null)
  const [rules, setRules] = useState<Record<'allow' | 'ask' | 'deny', string>>({ allow: '', ask: '', deny: '' })
  const [hooksJson, setHooksJson] = useState('[]')

  useEffect(() => {
    void services.mcp.processIsolation().then(setIsolation).catch(() => {})
  }, [])

  // Textareas hold newline-joined rule lists; re-sync whenever the settings snapshot changes.
  useEffect(() => {
    const table = appSettings?.permissionRules as PermissionRuleTable | undefined
    setRules({
      allow: (Array.isArray(table?.allow) ? table!.allow : []).join('\n'),
      ask: (Array.isArray(table?.ask) ? table!.ask : []).join('\n'),
      deny: (Array.isArray(table?.deny) ? table!.deny : []).join('\n'),
    })
    setHooksJson(typeof appSettings?.hooks === 'string' ? appSettings.hooks : '[]')
  }, [appSettings])

  /** Optimistic-looking toggle funnel: surface PUT failures instead of failing silently. */
  async function runToggle(action: () => Promise<void>): Promise<void> {
    setRuntimeError(null)
    try {
      await action()
    } catch (error) {
      setRuntimeError(error instanceof Error ? error.message : String(error))
    }
  }

  async function enableUnsandboxed(): Promise<void> {
    if (!await getPlatform().confirm(t('settings.unsandboxedPluginsConfirm'))) return
    await runToggle(() => update({ unsandboxedPlugins: true }))
  }

  async function enableComputerUse(): Promise<void> {
    if (!await getPlatform().confirm(t('settings.computerUseConfirm'))) return
    await runToggle(() => update({ computerUseEnabled: true }))
  }

  async function saveRules(): Promise<void> {
    setGuardSaving(true)
    setGuardError(null)
    try {
      await services.settings.putPermissionRules({
        allow: rules.allow.split('\n').map(line => line.trim()).filter(Boolean),
        ask: rules.ask.split('\n').map(line => line.trim()).filter(Boolean),
        deny: rules.deny.split('\n').map(line => line.trim()).filter(Boolean),
      })
      await reload()
      pushToast({ level: 'success', title: t('settings.guardSaveRules') })
    } catch (error) {
      setGuardError(error instanceof Error ? error.message : String(error))
    } finally {
      setGuardSaving(false)
    }
  }

  async function saveHooks(): Promise<void> {
    setGuardSaving(true)
    setGuardError(null)
    try {
      await services.settings.putHooks(hooksJson)
      await reload()
      pushToast({ level: 'success', title: t('settings.guardSaveHooks') })
    } catch (error) {
      setGuardError(error instanceof Error ? error.message : String(error))
    } finally {
      setGuardSaving(false)
    }
  }

  const isolationChip = !isolation ? null : (
    <span className={`cx-chip ${isolation.compatibilityMode ? 'cx-chip--warn' : 'cx-chip--success'}`}>
      {isolation.compatibilityMode
        ? t('settings.compatibilityApproval')
        : isolation.sandboxed
          ? t('settings.sandboxActive', { backend: isolation.backend })
          : isolation.reduced
            ? t('settings.sandboxReduced', { backend: isolation.backend })
            : t('settings.compatibilityApproval')}
    </span>
  )

  const computerUseStatus = appSettings?.computerUse ?? null

  return (
    <section>
      <h2 className="set-h">{t('settings.runtimeSecurity')}</h2>

      {runtimeError && (
        <div className="cx-alert cx-alert--error" style={{ marginBottom: 12 }}>
          <span className="cx-alert__body">{runtimeError}</span>
        </div>
      )}

      <div className="cx-card">
        <SettingRow icon={<ShieldCheck size={16} />} label={t('settings.processIsolation')}>
          {isolationChip}
        </SettingRow>
        {isolation?.reduced && (
          <div className="set-hint">{t('settings.sandboxReducedHint')}</div>
        )}

        <SettingRow
          icon={<ScrollText size={16} />}
          label={t('settings.logLevel')}
          hint={t('settings.logLevelHint')}
        >
          <select
            className="cx-select"
            style={{ width: 140 }}
            value={appSettings?.logLevel ?? 'INFO'}
            onChange={event => void runToggle(() => update({ logLevel: event.target.value as LogLevel }))}
          >
            {LOG_LEVELS.map(level => <option key={level} value={level}>{level}</option>)}
          </select>
        </SettingRow>

        <SettingRow icon={<ShieldAlert size={16} />} label={t('settings.unsandboxedPluginsTitle')}>
          <div className="cx-segment">
            <button
              type="button"
              className={!appSettings?.unsandboxedPlugins ? 'active' : ''}
              onClick={() => void runToggle(() => update({ unsandboxedPlugins: false }))}
            >
              {t('settings.unsandboxedOff')}
            </button>
            <button
              type="button"
              className={appSettings?.unsandboxedPlugins ? 'active' : ''}
              onClick={() => void enableUnsandboxed()}
            >
              {t('settings.unsandboxedOn')}
            </button>
          </div>
        </SettingRow>
        {isolation?.compatibilityMode && (
          <div className="set-hint set-hint--danger">{t('settings.unsandboxedPluginsWarn')}</div>
        )}
      </div>

      {/* AI action guard: permission rules + lifecycle hooks */}
      <div className="cx-card" style={{ marginTop: 16 }}>
        <SettingRow icon={<FileCode2 size={16} />} label={t('settings.guardTitle')} />
        <div className="set-hint">{t('settings.guardOverview')}</div>

        <details className="cx-details">
          <summary>{t('settings.guardAdvancedRules')}</summary>
          <div className="cx-details__body">
            <p className="guard-help">{t('settings.guardHint')}</p>
            <div className="guard-grid">
              {RULE_KINDS.map(kind => (
                <label key={kind} className="guard-field" htmlFor={`cx-rule-${kind}`}>
                  <span className="guard-field__label">
                    {t(`settings.guardRules.${kind}`)}
                    <em className="guard-field__badge">{t(`settings.guardRules.${kind}Desc`)}</em>
                  </span>
                  <textarea
                    id={`cx-rule-${kind}`}
                    className="cx-textarea"
                    rows={4}
                    spellCheck={false}
                    value={rules[kind]}
                    onChange={event => setRules(current => ({ ...current, [kind]: event.target.value }))}
                  />
                </label>
              ))}
            </div>
            {(appSettings?.invalidPermissionRules?.length ?? 0) > 0 && (
              <div className="cx-alert cx-alert--error" style={{ margin: '8px 0' }}>
                <span className="cx-alert__body">
                  {t('settings.guardInvalid', { rules: appSettings!.invalidPermissionRules!.join('; ') })}
                </span>
              </div>
            )}
            <div className="set-save-row">
              <button type="button" className="cx-btn cx-btn--primary" disabled={guardSaving} onClick={() => void saveRules()}>
                {guardSaving ? <span className="cx-spin" /> : null}
                {t('settings.guardSaveRules')}
              </button>
              <span className="cx-muted" style={{ fontSize: 11 }}>{t('settings.guardRuleSyntax')}</span>
            </div>
          </div>
        </details>

        <details className="cx-details" style={{ marginTop: 12 }}>
          <summary>{t('settings.guardAdvancedHooks')}</summary>
          <div className="cx-details__body">
            <p className="guard-help">{t('settings.guardHooksOverview')}</p>
            <label className="guard-field" htmlFor="cx-guard-hooks">
              <span className="guard-field__label">{t('settings.guardHooks')}</span>
              <textarea
                id="cx-guard-hooks"
                className="cx-textarea"
                rows={5}
                spellCheck={false}
                value={hooksJson}
                onChange={event => setHooksJson(event.target.value)}
              />
            </label>
            <div className="set-save-row">
              <button type="button" className="cx-btn cx-btn--primary" disabled={guardSaving} onClick={() => void saveHooks()}>
                {guardSaving ? <span className="cx-spin" /> : null}
                {t('settings.guardSaveHooks')}
              </button>
              <span className="cx-muted" style={{ fontSize: 11 }}>{t('settings.guardHooksHint')}</span>
            </div>
          </div>
        </details>

        {guardError && (
          <div className="cx-alert cx-alert--error" style={{ marginTop: 8 }} role="alert">
            <span className="cx-alert__body">{guardError}</span>
          </div>
        )}
      </div>

      {/* Cross-session memory */}
      <div className="cx-card" style={{ marginTop: 16 }}>
        <SettingRow icon={<MemoryStick size={16} />} label={t('settings.memoryTitle')}>
          <div className="cx-segment">
            <button
              type="button"
              className={!appSettings?.memoryEnabled ? 'active' : ''}
              onClick={() => void runToggle(() => update({ memoryEnabled: false }))}
            >
              {t('common.off')}
            </button>
            <button
              type="button"
              className={appSettings?.memoryEnabled ? 'active' : ''}
              onClick={() => void runToggle(() => update({ memoryEnabled: true }))}
            >
              {t('common.on')}
            </button>
          </div>
        </SettingRow>
        <div className="set-hint">{t('settings.memoryHint')}</div>
      </div>

      {/* Marketplace checksum pinning */}
      <div className="cx-card" style={{ marginTop: 16 }}>
        <SettingRow icon={<FileCheck size={16} />} label={t('settings.checksumTitle')}>
          <div className="cx-segment">
            <button
              type="button"
              className={!appSettings?.marketplaceRequireChecksum ? 'active' : ''}
              onClick={() => void runToggle(() => update({ marketplaceRequireChecksum: false }))}
            >
              {t('common.off')}
            </button>
            <button
              type="button"
              className={appSettings?.marketplaceRequireChecksum ? 'active' : ''}
              onClick={() => void runToggle(() => update({ marketplaceRequireChecksum: true }))}
            >
              {t('common.on')}
            </button>
          </div>
        </SettingRow>
        <div className="set-hint">{t('settings.checksumHint')}</div>
      </div>

      {/* Computer use (AI screen control) */}
      <div className="cx-card" style={{ marginTop: 16 }}>
        <SettingRow icon={<MonitorSmartphone size={16} />} label={t('settings.computerUseTitle')}>
          <span className={`cx-chip ${!computerUseStatus || !computerUseStatus.available
            ? 'cx-chip--error'
            : appSettings?.computerUseEnabled ? 'cx-chip--success' : 'cx-chip--warn'}`}
          >
            {!computerUseStatus || !computerUseStatus.available
              ? t('settings.computerUseUnavailableShort')
              : appSettings?.computerUseEnabled
                ? t('settings.computerUseReady')
                : t('settings.computerUseDisabledShort')}
          </span>
        </SettingRow>
        <div className="set-hint">{t('settings.computerUseHint')}</div>
        {computerUseStatus && !computerUseStatus.available && (
          <div className="set-hint set-hint--danger">
            {t('settings.computerUseUnavailable', { reason: computerUseStatus.reason ?? '' })}
          </div>
        )}
        <SettingRow icon={<MonitorSmartphone size={16} />} label={t('settings.computerUseAllowAi')}>
          <div className="cx-segment">
            <button
              type="button"
              className={!appSettings?.computerUseEnabled ? 'active' : ''}
              disabled={Boolean(computerUseStatus) && !computerUseStatus!.available}
              onClick={() => void runToggle(() => update({ computerUseEnabled: false }))}
            >
              {t('settings.computerUseOff')}
            </button>
            <button
              type="button"
              className={appSettings?.computerUseEnabled ? 'active' : ''}
              disabled={Boolean(computerUseStatus) && !computerUseStatus!.available}
              onClick={() => void enableComputerUse()}
            >
              {t('settings.computerUseOn')}
            </button>
          </div>
        </SettingRow>
      </div>
    </section>
  )
}
