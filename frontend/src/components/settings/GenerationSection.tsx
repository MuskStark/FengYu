import { useTranslation } from 'react-i18next'
import type { AiSettings } from '@/services/types'
import type { AiFormState } from './aiForm'

/**
 * Section 2 — generation parameters. Every numeric/knob field of AiSettings lands here:
 * temperature, topP, maxTokens, maxToolRounds, contextWindowTokens, toolLoadingMode,
 * toolLoadingThreshold (auto mode only) and the systemPrompt override. Shares the one
 * save action with the provider section (single PUT /api/ai/config).
 */
export default function GenerationSection({
  form,
  ai,
  saving,
  saveError,
  onPatch,
  onSave,
}: {
  form: AiFormState
  ai: AiSettings | null
  saving: boolean
  saveError: string | null
  onPatch: (patch: Partial<AiFormState>) => void
  onSave: () => void
}) {
  const { t } = useTranslation()

  /** Number inputs: parse on change; an emptied field falls back to 0 (backend re-validates). */
  function num(field: 'temperature' | 'topP' | 'maxTokens' | 'maxToolRounds'
  | 'contextWindowTokens' | 'toolLoadingThreshold', value: string) {
    const patch: Partial<AiFormState> = {}
    patch[field] = value === '' ? 0 : Number(value)
    onPatch(patch)
  }

  return (
    <section>
      <h2 className="set-h">{t('aiSettings.generate')}</h2>
      <div className="cx-card">
        <div className="cx-setting-row">
          <div className="cx-setting-row__label">
            <span>{t('aiSettings.status')}</span>
          </div>
          <span className={`cx-chip ${ai?.ready ? 'cx-chip--success' : ''}`}>
            {ai?.ready ? t('aiSettings.ready') : t('aiSettings.notReady')}
          </span>
          <span className="cx-muted" style={{ fontSize: 13 }}>({ai?.activeMode ?? '—'})</span>
        </div>

        <div className="cx-setting-row">
          <div className="cx-setting-row__label"><span>{t('aiSettings.temperature')}</span></div>
          <input
            className="cx-input cx-input--narrow" type="number" step="0.1" min="0" max="2"
            value={form.temperature}
            onChange={event => num('temperature', event.target.value)}
          />
        </div>
        <div className="cx-setting-row">
          <div className="cx-setting-row__label"><span>{t('aiSettings.topP')}</span></div>
          <input
            className="cx-input cx-input--narrow" type="number" step="0.05" min="0" max="1"
            value={form.topP}
            onChange={event => num('topP', event.target.value)}
          />
        </div>
        <div className="cx-setting-row">
          <div className="cx-setting-row__label"><span>{t('aiSettings.maxTokens')}</span></div>
          <input
            className="cx-input cx-input--narrow" type="number" step="1" min="1"
            value={form.maxTokens}
            onChange={event => num('maxTokens', event.target.value)}
          />
        </div>
        <div className="cx-setting-row">
          <div className="cx-setting-row__label">
            <span className="set-row-text">
              {t('aiSettings.maxToolRounds')}
              <small>{t('aiSettings.maxToolRoundsHint')}</small>
            </span>
          </div>
          <input
            className="cx-input cx-input--narrow" type="number" step="1" min="0" max="10000"
            value={form.maxToolRounds}
            onChange={event => num('maxToolRounds', event.target.value)}
          />
        </div>
        <div className="cx-setting-row">
          <div className="cx-setting-row__label">
            <span className="set-row-text">
              {t('aiSettings.contextWindowTokens')}
              <small>{t('aiSettings.contextWindowTokensHint')}</small>
            </span>
          </div>
          <input
            className="cx-input cx-input--narrow" type="number" step="1024" min="0" max="2000000"
            value={form.contextWindowTokens}
            onChange={event => num('contextWindowTokens', event.target.value)}
          />
        </div>

        <div className="cx-setting-row">
          <div className="cx-setting-row__label">
            <span className="set-row-text">
              {t('aiSettings.toolLoadingMode')}
              <small>{t('aiSettings.toolLoadingModeHint')}</small>
            </span>
          </div>
          <select
            className="cx-input cx-input--narrow"
            value={form.toolLoadingMode}
            onChange={event =>
              onPatch({ toolLoadingMode: event.target.value as AiFormState['toolLoadingMode'] })}
          >
            <option value="auto">{t('aiSettings.toolLoadingModeAuto')}</option>
            <option value="always">{t('aiSettings.toolLoadingModeAlways')}</option>
            <option value="off">{t('aiSettings.toolLoadingModeOff')}</option>
          </select>
        </div>
        {form.toolLoadingMode === 'auto' && (
          <div className="cx-setting-row">
            <div className="cx-setting-row__label">
              <span className="set-row-text">
                {t('aiSettings.toolLoadingThreshold')}
                <small>{t('aiSettings.toolLoadingThresholdHint')}</small>
              </span>
            </div>
            <input
              className="cx-input cx-input--narrow" type="number" step="1" min="5" max="500"
              value={form.toolLoadingThreshold}
              onChange={event => num('toolLoadingThreshold', event.target.value)}
            />
          </div>
        )}

        <div className="cx-field" style={{ marginTop: 8 }}>
          <label className="cx-label" htmlFor="cx-ai-system-prompt">{t('aiSettings.systemPrompt')}</label>
          <textarea
            id="cx-ai-system-prompt"
            className="cx-textarea"
            rows={3}
            value={form.systemPrompt}
            onChange={event => onPatch({ systemPrompt: event.target.value })}
          />
        </div>

        <div className="set-save-row">
          <button type="button" className="cx-btn cx-btn--primary" disabled={saving} onClick={onSave}>
            {saving ? <span className="cx-spin" /> : null}
            {t('aiSettings.save')}
          </button>
          {saveError ? <span className="cx-chip cx-chip--error">{saveError}</span> : null}
        </div>
      </div>
    </section>
  )
}
