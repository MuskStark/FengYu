import { IconFolderCheck, IconDownload } from '@tabler/icons-react'
import { useFengYuI18n } from '@infinia/plugin-ui'
import { OutputDirectoryPicker } from '../OutputDirectoryPicker'
import type { SplitterContext } from './types'

const panelClass = 'rounded-xl border border-line bg-panel px-4 py-4'
const panelTitleStyle = { marginBottom: '0.75rem', letterSpacing: '0.02em' }

function KeyValue({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex gap-3 py-1 text-[13px]">
      <span className="shrink-0" style={{ flexBasis: '104px', color: 'var(--c-ink-2)' }}>
        {label}
      </span>
      <span className="min-w-0">{children}</span>
    </div>
  )
}

/** Step 3 — Output: split configuration summary + the output-folder grant picker. */
export function OutputStep({ context }: { context: SplitterContext }) {
  const { t } = useFengYuI18n()
  return (
    <section data-step="output">
      <div className="mb-4 text-[15px] font-semibold tracking-tight">{t('exui.output.cardTitle')}</div>
      <div className="grid gap-4" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))' }}>
        <div className={panelClass} data-output-config>
          <div className="text-xs font-semibold tracking-wide" style={{ ...panelTitleStyle, color: 'var(--c-ink-2)' }}>
            {t('exui.output.configTitle')}
          </div>
          <KeyValue label={t('exui.output.mode')}>
            <span className="font-medium">{context.modeLabel}</span>
          </KeyValue>
          {context.configDetails.length > 0 ? (
            <KeyValue label={t('exui.output.rules')}>
              <span className="flex flex-wrap gap-1.5">
                {context.configDetails.map((detail, index) => (
                  <span
                    key={index}
                    className="rounded-full px-2.5 py-0.5 text-xs"
                    style={{
                      border: '1px solid color-mix(in oklab, var(--c-gold) 45%, transparent)',
                      background: 'color-mix(in oklab, var(--c-gold) 10%, transparent)',
                    }}
                  >
                    {detail}
                  </span>
                ))}
              </span>
            </KeyValue>
          ) : null}
          <KeyValue label={t('exui.output.expectedFiles')}>
            {!context.estimating && context.estimatedFileCount !== null ? (
              <strong className="font-mono text-[14px]" style={{ color: 'var(--c-gold)' }}>
                {context.estimatedFileCount}
              </strong>
            ) : (
              <span style={{ color: 'var(--c-ink-3)' }}>
                {context.estimating ? t('exui.output.estimating') : '—'}
              </span>
            )}
          </KeyValue>
          <KeyValue label={t('exui.output.prefixLabel')}>
            <span className="font-mono text-[12.5px]">{context.filePrefix || '—'}</span>
          </KeyValue>
        </div>
        <div className={panelClass} data-output-dir>
          <div className="text-xs font-semibold tracking-wide" style={{ ...panelTitleStyle, color: 'var(--c-ink-2)' }}>
            {t('exui.output.dirPanel')}
          </div>
          <OutputDirectoryPicker value={context.outputDir} onChange={(ref) => context.pickOutput(ref)} label={t('exui.output.chooseFolder')} />
          <div
            className="flex items-start gap-2.5 rounded-lg px-3.5 py-3 text-[13px] leading-relaxed"
            style={{ marginTop: '0.875rem', background: 'var(--c-tag)', color: 'var(--c-ink-2)' }}
          >
            {context.platform === 'desktop' ? (
              <IconFolderCheck size={18} stroke={1.6} className="mt-0.5 shrink-0" style={{ color: 'var(--c-gold)' }} />
            ) : (
              <IconDownload size={18} stroke={1.6} className="mt-0.5 shrink-0" style={{ color: 'var(--c-gold)' }} />
            )}
            <span>{t(context.platform === 'desktop' ? 'exui.output.desktopHint' : 'exui.output.webHint')}</span>
          </div>
        </div>
      </div>
    </section>
  )
}
