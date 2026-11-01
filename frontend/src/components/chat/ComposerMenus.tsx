/**
 * Self-contained composer menu panels, extracted verbatim from ChatComposer's toolbar
 * (behavior/style-neutral split): the attach menu, the permission-mode menu, and the
 * model picker (whose custom-entry input state lives HERE now — it was menu-local all
 * along). Each panel takes the exact closure values it used inline as props.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Pencil, Plus, SlidersHorizontal } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useAiSessionStore } from '@/stores/aiSession'
import { getPlatform } from '@/platform'
import { providerModelRows } from '@/lib/aiProviders'
import { MenuItem } from './ComposerCards'
import type { AiProviderEntry } from '@/services/types'

export function AttachMenu({ attachFile, attachDirectory, pickWorkspace, chooseOutputLocation }: {
  attachFile: () => void
  attachDirectory: () => void
  pickWorkspace: () => void
  chooseOutputLocation: () => void
}) {
  const { t } = useTranslation()
  return (
                <div className="cx-card composer-menu composer-menu--attach" data-menu="attach">
                  <MenuItem icon={<Plus size={15} />} title={t('aichat.attachFile')} hint={t('aichat.attachFileHint')} onClick={attachFile} />
                  <MenuItem icon={<Plus size={15} />} title={t('aichat.attachDirectory')} hint={t('aichat.attachDirectoryHint')} onClick={attachDirectory} />
                  <MenuItem icon={<Plus size={15} />} title={t('aichat.setWorkspace')} hint={t('aichat.setWorkspaceHint')} onClick={pickWorkspace} />
                  {getPlatform().capabilities.nativeFileDialogs && <MenuItem icon={<Plus size={15} />} title={t('aichat.setOutputFolder')} hint={t('aichat.setOutputFolderHint')} onClick={chooseOutputLocation} />}
                </div>
  )
}

export function PermissionMenu({ permissionMode, onClose }: {
  permissionMode: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  return (
              <div className="cx-card composer-menu composer-menu--permission" data-menu="permission">
                <div className="cx-muted composer-menu__hint">{t('aichat.permissionQuestion')}</div>
                {([
                  { id: 'ask-for-approval', title: t('aichat.permissionAsk'), hint: t('aichat.permissionAskHint') },
                  { id: 'approve-for-me', title: t('aichat.permissionAuto'), hint: t('aichat.permissionAutoHint') },
                  { id: 'plan', title: t('aichat.permissionPlan'), hint: t('aichat.permissionPlanHint') },
                  { id: 'full-access', title: t('aichat.permissionFullAccess'), hint: t('aichat.permissionFullHint') },
                ] as const).map(option => (
                  <button
                    key={option.id}
                    className={cn('cx-btn composer-menu__option', { 'composer-menu__option--danger': option.id === 'full-access' })}
                    onClick={() => {
                      useAiSessionStore.setState({ permissionMode: option.id })
                      onClose()
                    }}
                  >
                    <span className="grid gap-0.5 flex-1">
                      <span className="font-[650]">{option.title}</span>
                      <span className="cx-muted text-ui-sm whitespace-normal">{option.hint}</span>
                    </span>
                    {permissionMode === option.id && <Check size={14} />}
                  </button>
                ))}
              </div>
  )
}

export function ModelMenuPanel({ providers, providerModelLists, activeProviderId, activeEntry,
    modelSwitching, thinkingCfg, applyModel, applyThinking, onManageProviders }: {
  providers: AiProviderEntry[]
  providerModelLists: Record<string, { loading: boolean; models: string[] }>
  activeProviderId: string
  activeEntry: AiProviderEntry | null
  modelSwitching: boolean
  thinkingCfg: { level: string; levels: string[] }
  applyModel: (providerId: string, model: string) => Promise<void>
  applyThinking: (level: string) => Promise<void>
  onManageProviders: () => void
}) {
  const { t } = useTranslation()
  // Menu-local custom-model input state (moved here from the composer — only this panel used it).
  const [customEntry, setCustomEntry] = useState<string | null>(null)
  const [customValue, setCustomValue] = useState('')
  return (
              <div className="cx-card composer-menu composer-menu--model" data-menu="model">
                <div className="cx-muted composer-menu__hint">{t('aichat.configuredModels')}</div>
                <div className="composer-menu__scroll">
                {providers.map(p => {
                  const list = providerModelLists[p.id]
                  const usable = p.protocol === 'OLLAMA' || p.apiKeySet
                  // Live vendor list when it answered; curated fallback otherwise.
                  const loading = !list || list.loading
                  const { rows: models, fallback } = providerModelRows(
                    loading ? [] : (list?.models ?? []), p.model, p.id)
                  return (
                    <div key={p.id} className="composer-menu__provider">
                      <div className="composer-menu__group">
                        <span>{p.displayName}</span>
                        {!usable
                          ? <span className="cx-muted composer-menu__group-note">{t('aichat.keyMissing')}</span>
                          : null}
                        {p.id === activeProviderId
                          ? <span className="cx-chip cx-chip--success"><Check size={12} />{t('aiSettings.registry.inUse')}</span>
                          : null}
                      </div>
                      {models.map(m => (
                        <button
                          key={`${p.id}/${m}`}
                          className="cx-btn composer-menu__option composer-menu__option--model"
                          disabled={modelSwitching || !usable}
                          onClick={() => void applyModel(p.id, m)}
                        >
                          <span className="grid flex-1 text-left">
                            <span className="font-[650]">{m}</span>
                          </span>
                          {p.id === activeProviderId && m === activeEntry?.model ? <Check size={14} /> : null}
                        </button>
                      ))}
                      {loading
                        ? <div className="cx-muted composer-menu__note">{t('aichat.modelsLoading')}</div>
                        : fallback
                          ? <div className="cx-muted composer-menu__note">{t('aichat.modelsFallbackNote')}</div>
                          : !models.length
                            ? <div className="cx-muted composer-menu__note">{t('aichat.modelsEmpty')}</div>
                            : null}
                      {customEntry === p.id ? (
                        <input
                          className="cx-input composer-menu__custom-input"
                          autoFocus
                          value={customValue}
                          placeholder={t('aichat.modelInputPh')}
                          disabled={modelSwitching}
                          onChange={e => setCustomValue(e.target.value)}
                          onKeyDown={e => {
                            if (e.key === 'Enter') {
                              // Read the live input value, not the state snapshot —
                              // the freshest truth at the moment the user commits.
                              const value = (e.currentTarget as HTMLInputElement).value.trim()
                              if (value) void applyModel(p.id, value)
                            } else if (e.key === 'Escape') {
                              setCustomEntry(null)
                              setCustomValue('')
                            }
                          }}
                        />
                      ) : (
                        <button
                          className="cx-btn cx-btn--text composer-menu__custom-trigger"
                          disabled={modelSwitching || !usable}
                          onClick={() => { setCustomEntry(p.id); setCustomValue('') }}
                        >
                          <Pencil size={13} />
                          {t('aichat.otherModel')}
                        </button>
                      )}
                    </div>
                  )
                })}
                </div>
                {thinkingCfg.levels.length > 1 ? (
                  <>
                    <div className="composer-menu__divider" />
                    <div className="composer-menu__levels">
                      <span className="cx-muted composer-menu__hint composer-menu__hint--bare">
                        {t('aichat.thinkingLabel')}
                      </span>
                      {thinkingCfg.levels.map(level => (
                        <button
                          key={level}
                          className={cn('composer-menu__levelchip', { 'composer-menu__levelchip--on': level === thinkingCfg.level })}
                          onClick={() => void applyThinking(level)}
                        >
                          {t(`aiSettings.registry.thinkingLevel.${level}`, level)}
                        </button>
                      ))}
                    </div>
                  </>
                ) : null}
                <div className="composer-menu__divider" />
                <button
                  className="cx-btn cx-btn--text composer-menu__option"
                  onClick={() => onManageProviders()}
                >
                  <SlidersHorizontal size={14} />
                  {t('aichat.manageProviders')}
                </button>
              </div>
  )
}
