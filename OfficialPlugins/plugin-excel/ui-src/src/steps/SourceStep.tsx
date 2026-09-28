import { IconFileSpreadsheet, IconCheck } from '@tabler/icons-react'
import { ErrorState, FilePicker, Progress, useFengYuI18n } from '@infinia/plugin-ui'
import type { SplitterContext } from './types'

/** Step 1 — Source: T2-style dashed import zone wrapping the host grant-backed FilePicker. */
export function SourceStep({ context, showStepError }: { context: SplitterContext; showStepError: boolean }) {
  const { t } = useFengYuI18n()
  return (
    <section data-step="source">
      <div className="mb-4 text-[15px] font-semibold tracking-tight">{t('exui.source.cardTitle')}</div>
      <div className="grid items-stretch gap-4" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))' }}>
        {/* 导入区：虚线拖放区视觉（文件选择仍走宿主授权 FilePicker） */}
        <div
          data-source-drop-zone=""
          className="flex items-center gap-4 rounded-xl px-6 py-6"
          style={{ border: '2px dashed var(--c-line-strong)', background: 'color-mix(in oklab, var(--c-panel) 65%, transparent)' }}
        >
          <IconFileSpreadsheet size={34} stroke={1.5} className="shrink-0" style={{ color: 'var(--c-gold)' }} />
          <div className="grid min-w-0 flex-1" style={{ gap: '0.125rem' }}>
            <div className="text-[14px] font-semibold">{t('exui.source.zoneTitle')}</div>
            <div className="text-xs" style={{ color: 'var(--c-ink-2)' }}>
              {t('exui.source.zoneSub')}
            </div>
          </div>
          <FilePicker
            value={context.sourceFile}
            onChange={(ref) => context.pickSource(ref)}
            extensions={['xlsx', 'xls']}
            filters={[{ name: 'Excel', extensions: ['xlsx', 'xls'] }]}
            label={t('exui.source.browse')}
          />
        </div>
        {/* 提示卡：源文件不会被修改 */}
        <div
          className="grid gap-1.5 rounded-xl px-4 py-4 text-[13px] leading-relaxed"
          style={{ background: 'var(--c-tag)', alignContent: 'center' }}
        >
          <strong className="text-[13.5px]">{t('exui.source.tipsTitle')}</strong>
          {[t('exui.source.tip1'), t('exui.source.tip2'), t('exui.source.tip3')].map((tip) => (
            <span key={tip} className="flex items-start gap-2">
              <IconCheck size={15} stroke={2} className="mt-0.5 shrink-0" style={{ color: 'var(--c-success)' }} />
              {tip}
            </span>
          ))}
        </div>
      </div>
      {context.analyzing ? <Progress className="mt-4" status="indeterminate" label={t('exui.source.analyzing')} /> : null}
      {showStepError && context.analyzeError ? (
        <ErrorState className="mt-3" title={t('exui.step.source')} message={context.analyzeError} />
      ) : null}
    </section>
  )
}
