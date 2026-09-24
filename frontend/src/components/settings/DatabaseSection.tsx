import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Database } from 'lucide-react'
import type { PluginDescriptor, PluginDbProvisionResult, SetupStatus } from '@/services/types'
import { services } from '@/services'
import SettingRow from './SettingRow'

/**
 * Section 6 — database: strictly read-only. Shows whether the backend is initialized
 * (SETUP vs APP mode truth from /api/setup/status), the supported engines, and the
 * per-plugin database-isolation provisioning state. No migration or re-init actions.
 */
interface DbPluginRow extends PluginDbProvisionResult {
  name: string
}

export default function DatabaseSection() {
  const { t } = useTranslation()
  const [setup, setSetup] = useState<SetupStatus | null>(null)
  const [dbPlugins, setDbPlugins] = useState<DbPluginRow[]>([])

  useEffect(() => {
    let cancelled = false
    void services.system.setupStatus().then(value => {
      if (!cancelled) setSetup(value)
    }).catch(() => {})
    void (async () => {
      try {
        const all: PluginDescriptor[] = await services.plugin.list()
        const dbOnes = all.filter(plugin => plugin.permissions?.includes('database'))
        const rows = await Promise.all(dbOnes.map(async plugin => {
          const status = await services.plugin.dbStatus(plugin.id).catch(() => null)
          return {
            provisioned: status?.provisioned ?? false,
            status: status?.status ?? 'unknown',
            pluginId: plugin.id,
            name: plugin.name,
          }
        }))
        if (!cancelled) setDbPlugins(rows)
      } catch {
        if (!cancelled) setDbPlugins([])
      }
    })()
    return () => { cancelled = true }
  }, [])

  return (
    <section>
      <h2 className="set-h">{t('settings.pluginDbSection')}</h2>
      <div className="cx-card">
        <SettingRow icon={<Database size={16} />} label={t('aiSettings.status')}>
          <span className={`cx-chip ${setup?.initialized ? 'cx-chip--success' : 'cx-chip--warn'}`}>
            {setup?.initialized ? t('aiSettings.ready') : t('aiSettings.notReady')}
          </span>
        </SettingRow>

        {setup && (
          <div className="cx-setting-row">
            <div className="cx-setting-row__label">
              <span className="set-row-text">
                {t('setup.fields.database')}
                <small>{t('about.dep.jdbc')}</small>
              </span>
            </div>
            <div className="cx-row" style={{ flexWrap: 'wrap', justifyContent: 'flex-end' }}>
              {(setup.supportedTypes ?? []).map(type => (
                <span
                  key={type}
                  className="cx-chip"
                  title={setup.embeddedTypes?.includes(type) ? t('setup.local') : t('setup.remote')}
                >
                  {type}
                </span>
              ))}
            </div>
          </div>
        )}

        <div className="set-hint">{t('settings.pluginDbSectionHint')}</div>

        {dbPlugins.length === 0 ? (
          <div className="cx-muted" style={{ fontSize: 13 }}>{t('settings.pluginDbNoPlugins')}</div>
        ) : (
          dbPlugins.map(plugin => (
            <div key={plugin.pluginId} className="cx-setting-row">
              <div className="cx-setting-row__label">
                <Database size={16} />
                <span>
                  {plugin.name}
                  <span className="cx-muted" style={{ fontSize: 12, marginLeft: 6 }}>{plugin.pluginId}</span>
                </span>
              </div>
              {plugin.provisioned ? (
                <span className="cx-chip cx-chip--success">{t('settings.pluginDbAuthorized')}</span>
              ) : (
                <span className="cx-chip">{plugin.status}</span>
              )}
            </div>
          ))
        )}
      </div>
    </section>
  )
}
