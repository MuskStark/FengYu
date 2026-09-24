import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useLocation, useNavigate } from 'react-router-dom'
import { ArrowLeft, Bot, Database, Plug, Search, SlidersHorizontal, Palette, ShieldCheck, RefreshCw } from 'lucide-react'
import '@/styles/settings.css'
import type { AppSettings, PartialSettings } from '@/services/types'
import { services } from '@/services'
import { aiFormFromSettings, aiFormToPartial, type AiFormState } from '@/components/settings/aiForm'
import AiProviderSection from '@/components/settings/AiProviderSection'
import GenerationSection from '@/components/settings/GenerationSection'
import AppearanceSection from '@/components/settings/AppearanceSection'
import RuntimeSection from '@/components/settings/RuntimeSection'
import McpSection from '@/components/settings/McpSection'
import DatabaseSection from '@/components/settings/DatabaseSection'
import UpdateSection from '@/components/settings/UpdateSection'
import { useSettingsStore } from '@/stores/settings'
import { useToastStore } from '@/stores/toasts'

/**
 * Settings — React port of the Vue shell's Settings.vue: an in-page section nav
 * (AI / Personalize / System groups) over seven sections:
 *   1. AI providers    — mode + endpoint/model/apiKey + connection test   (AiProviderSection)
 *   2. Generation      — every AiSettings knob                            (GenerationSection)
 *   3. Appearance      — theme / language / sidebar shape                 (AppearanceSection)
 *   4. Runtime         — every remaining writable AppSettings field       (RuntimeSection)
 *   5. MCP             — server list + enable/disable (+ CRUD)            (McpSection)
 *   6. Database        — read-only configuration status                   (DatabaseSection)
 *   7. Update channel  — version check + channel proxy fields             (UpdateSection)
 */
type SectionId = 'providers' | 'generate' | 'appearance' | 'runtime' | 'mcp' | 'database' | 'update'
type NavGroup = 'ai' | 'personalize' | 'system'

export default function SettingsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const location = useLocation()
  const pushToast = useToastStore(state => state.push)
  const aiSettings = useSettingsStore(state => state.aiSettings)
  const updateAi = useSettingsStore(state => state.updateAi)

  /** Back exits to wherever the user came from; a fresh load on /settings has no in-app
   * history to pop (React Router marks that entry key === 'default'), so go home instead. */
  const backToApp = () => {
    if (location.key === 'default') navigate('/')
    else navigate(-1)
  }

  const [appSettings, setAppSettings] = useState<AppSettings | null>(null)
  const [aiForm, setAiForm] = useState<AiFormState | null>(null)
  const [aiSaving, setAiSaving] = useState(false)
  const [aiSaveError, setAiSaveError] = useState<string | null>(null)
  const [activeSection, setActiveSection] = useState<SectionId>('appearance')
  const [query, setQuery] = useState('')

  // Full AppSettings snapshot (the store mirrors only theme/language/sidebar); every
  // PUT response re-applies through the store so theme/i18n stay in sync everywhere.
  async function loadSettings(): Promise<void> {
    const settings = await services.settings.get()
    useSettingsStore.getState().apply(settings)
    setAppSettings(settings)
  }

  useEffect(() => {
    void loadSettings().catch(() => {})
    void useSettingsStore.getState().loadAi().catch(() => {})
  }, [])

  // Adopt the AI config once (and after each save — the store swaps the object).
  useEffect(() => {
    if (aiSettings) setAiForm(aiFormFromSettings(aiSettings))
  }, [aiSettings])

  const sections = useMemo(() => [
    { id: 'providers' as const, icon: <Bot size={16} />, label: t('aiSettings.providers'), group: 'ai' as NavGroup },
    { id: 'generate' as const, icon: <SlidersHorizontal size={16} />, label: t('aiSettings.generate'), group: 'ai' as NavGroup },
    { id: 'appearance' as const, icon: <Palette size={16} />, label: t('settings.general'), group: 'personalize' as NavGroup },
    { id: 'runtime' as const, icon: <ShieldCheck size={16} />, label: t('settings.runtimeSecurity'), group: 'system' as NavGroup },
    { id: 'mcp' as const, icon: <Plug size={16} />, label: 'MCP', group: 'system' as NavGroup },
    { id: 'database' as const, icon: <Database size={16} />, label: t('settings.pluginDbSection'), group: 'system' as NavGroup },
    { id: 'update' as const, icon: <RefreshCw size={16} />, label: t('settings.updateChannelSection'), group: 'system' as NavGroup },
  ], [t])

  const groupLabel = (group: NavGroup) =>
    group === 'ai' ? t('settings.groupAI')
      : group === 'personalize' ? t('settings.groupPersonalize')
        : t('settings.groupSystem')

  const q = query.trim().toLowerCase()
  const visible = sections.filter(section =>
    `${section.label} ${groupLabel(section.group)}`.toLowerCase().includes(q))

  const navRows: Array<{ type: 'group'; label: string } | { type: 'item'; id: SectionId; icon: ReactNode; label: string }> = []
  let lastGroup = ''
  for (const section of visible) {
    if (section.group !== lastGroup) {
      navRows.push({ type: 'group', label: groupLabel(section.group) })
      lastGroup = section.group
    }
    navRows.push({ type: 'item', id: section.id, icon: section.icon, label: section.label })
  }

  async function updateAppSettings(partial: PartialSettings): Promise<void> {
    const updated = await services.settings.update(partial)
    useSettingsStore.getState().apply(updated)
    setAppSettings(updated)
  }

  function patchAiForm(patch: Partial<AiFormState>): void {
    setAiForm(current => (current ? { ...current, ...patch } : current))
  }

  async function saveAi(): Promise<void> {
    if (!aiForm) return
    setAiSaving(true)
    setAiSaveError(null)
    try {
      await updateAi(aiFormToPartial(aiForm))
      pushToast({ level: 'success', title: t('aiSettings.saved') })
    } catch (error) {
      setAiSaveError(error instanceof Error ? error.message : String(error))
      pushToast({ level: 'error', title: t('common.unexpectedError'), body: error instanceof Error ? error.message : String(error) })
    } finally {
      setAiSaving(false)
    }
  }

  const aiProps = aiForm ? {
    form: aiForm,
    saving: aiSaving,
    saveError: aiSaveError,
    onPatch: patchAiForm,
    onSave: () => { void saveAi() },
  } : null

  return (
    <div className="set-shell">
      <aside className="set-nav">
        {/* The app shell hides the sidebar on this route (full-page surface), so this
            back button is the primary way out — Vue Settings parity. */}
        <div className="set-nav-back">
          <button className="set-nav-back-btn" onClick={backToApp}>
            <ArrowLeft size={15} />
            <span>{t('settings.backToApp')}</span>
          </button>
        </div>
        <div className="set-nav-search">
          <Search size={14} className="set-nav-search-icon" />
          <input
            placeholder={t('settings.searchSettings')}
            aria-label={t('settings.searchSettings')}
            value={query}
            onChange={event => setQuery(event.target.value)}
          />
        </div>
        {navRows.map((row, index) => row.type === 'group' ? (
          <div key={`group-${index}`} className="set-nav-grp">{row.label}</div>
        ) : (
          <button
            key={row.id}
            type="button"
            className={`set-nav-item ${activeSection === row.id ? 'active' : ''}`}
            onClick={() => setActiveSection(row.id)}
          >
            {row.icon}
            <span>{row.label}</span>
          </button>
        ))}
      </aside>

      <div className="set-content">
        <div className="set-inner">
          {activeSection === 'providers' && (aiProps
            ? <AiProviderSection {...aiProps} ai={aiSettings} />
            : <p className="cx-muted">{t('common.loading')}</p>)}
          {activeSection === 'generate' && (aiProps
            ? <GenerationSection {...aiProps} ai={aiSettings} />
            : <p className="cx-muted">{t('common.loading')}</p>)}
          {activeSection === 'appearance' && (
            <AppearanceSection
              appSettings={appSettings}
              onLanguage={language => void updateAppSettings({ language }).catch(() => {})}
            />
          )}
          {activeSection === 'runtime' && (
            <RuntimeSection
              appSettings={appSettings}
              update={updateAppSettings}
              reload={loadSettings}
            />
          )}
          {activeSection === 'mcp' && <McpSection />}
          {activeSection === 'database' && <DatabaseSection />}
          {activeSection === 'update' && <UpdateSection appSettings={appSettings} update={updateAppSettings} />}
        </div>
      </div>
    </div>
  )
}
