import { useTranslation } from 'react-i18next'
import type { BootStage } from '@/platform'
import logoUrl from '@/assets/infinia-logo.svg'

const STAGE_LABEL: Record<BootStage, string> = {
  spawning: 'boot.stage.spawning',
  'port-ready': 'boot.stage.port-ready',
  'health-ready': 'boot.stage.health-ready',
  'loading-ui': 'boot.stage.loading-ui',
}

/**
 * In-app startup screen — the whole boot surface since the standalone splash
 * window was retired: the main window opens before the backend spawn and this
 * screen (behind the boot gate) carries the startup progress the splash used
 * to show. Visually it continues the static HTML shell (frontend/index.html
 * #boot-loading) — the same 72px ribbon mark at the same center position, plus
 * the same traveling progress track, so HTML shell → this screen read as one
 * continuous boot. The logo itself does NOT re-pop here — it mounts exactly
 * when the HTML shell cross-fades out; the stage label re-fades on every stage
 * push (spawning → port-ready → health-ready → loading-ui) and a late breathing
 * loop keeps long boots alive (reduced-motion: none).
 */
export default function StartupScreen({ stage }: { stage?: BootStage }) {
  const { t } = useTranslation()
  const label = (stage && t(STAGE_LABEL[stage])) || t('boot.waiting')
  return (
    <div className="fx-startup" role="status" aria-busy="true" aria-label={label}>
      <img className="fx-startup__logo" src={logoUrl} alt="" width={72} height={72} />
      <span className="fx-startup__track" aria-hidden="true">
        <span className="fx-startup__bar" />
      </span>
      <p key={stage ?? 'waiting'} className="fx-startup__label">{label}</p>
    </div>
  )
}
