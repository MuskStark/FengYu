import { IconFileSpreadsheet, IconCircleCheck, IconDownload, IconArrowLeft, IconSlashes } from '@tabler/icons-react'
import {
  ErrorState,
  GhostButton,
  GoldButton,
  Meteors,
  MultiStepLoader,
  Progress,
  useFengYuI18n,
} from '@infinia/plugin-ui'
import type { SplitterContext } from './types'

const panelClass = 'rounded-xl border border-line bg-panel px-4 py-4'
const panelTitleStyle = { marginBottom: '0.75rem', letterSpacing: '0.02em' }

/**
 * Step 4 — Run. Advancing Output → Run executes the split immediately (auto-advance wired in
 * `ExcelSplitter`), so this screen is the T2 processing view: pipeline card (MultiStepLoader)
 * + Meteors 处理中 card while running, and the completed result screen once the wizard
 * finishes. A split failure stays here as the wizard's inline error with a Retry (= 下一步).
 */
export function RunStep({ context }: { context: SplitterContext }) {
  const { t } = useFengYuI18n()
  const pipeline = [
    { text: t('exui.pipeline.parse') },
    { text: t('exui.pipeline.slice') },
    { text: t('exui.pipeline.generate') },
    { text: t('exui.pipeline.write') },
  ]

  if (context.completed && context.result) {
    return (
      <section data-step="run" data-run-screen="complete">
        {context.runError ? <ErrorState className="mb-4" title={context.runError} /> : null}
        <div
          className="mb-4 flex items-center gap-3 rounded-xl px-4 py-3.5 text-[13.5px]"
          style={{ background: 'var(--c-success-bg)', color: 'var(--c-ink)' }}
        >
          <IconCircleCheck size={22} stroke={1.6} className="shrink-0" style={{ color: 'var(--c-success)' }} />
          <span>
            <strong className="text-[14px]">{t('exui.complete.title')}</strong>
            {' · '}
            {t('exui.complete.written', context.result.fileCount, context.outputDir?.name ?? t('exui.complete.outputFolderFallback'))}
          </span>
        </div>
        <div className="grid gap-4" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))' }}>
          <div className={panelClass}>
            <div className="text-xs font-semibold tracking-wide" style={{ ...panelTitleStyle, color: 'var(--c-ink-2)' }}>
              {t('exui.complete.filesPanel')}
            </div>
            <ul
              className="overflow-y-auto"
              style={{ margin: 0, padding: 0, maxHeight: 220, listStyle: 'none' }}
              data-result-files
            >
              {context.result.files.map((file) => (
                <li key={file} className="flex items-center gap-2.5 border-b border-line py-2 text-[13px]">
                  <IconFileSpreadsheet size={16} stroke={1.6} className="shrink-0" style={{ color: 'var(--c-gold)' }} />
                  <span className="min-w-0 truncate font-mono text-[12.5px]">{file}</span>
                </li>
              ))}
            </ul>
          </div>
          <div className={panelClass}>
            <div className="text-xs font-semibold tracking-wide" style={{ ...panelTitleStyle, color: 'var(--c-ink-2)' }}>
              {t('exui.complete.actionsPanel')}
            </div>
            <div className="flex gap-3 py-1 text-[13px]">
              <span className="shrink-0" style={{ flexBasis: '104px', color: 'var(--c-ink-2)' }}>
                {t('exui.complete.outputFolderLabel')}
              </span>
              <span className="min-w-0 font-mono text-[12.5px]">
                {context.outputDir?.name ?? t('exui.complete.outputFolderFallback')}
              </span>
            </div>
            <div className="flex flex-wrap gap-2.5" style={{ marginTop: '0.875rem' }}>
              {context.result && context.outputDir && context.platform === 'web' ? (
                <GoldButton data-action="export-results" disabled={context.downloading} onClick={() => void context.downloadResult()}>
                  <IconDownload size={16} stroke={1.7} />
                  {t('exui.complete.download')}
                </GoldButton>
              ) : null}
              <GhostButton data-action="adjust-mode" onClick={context.adjustMode}>
                <IconSlashes size={16} stroke={1.7} />
                {t('exui.complete.adjust')}
              </GhostButton>
              <GhostButton data-action="restart" onClick={context.restartWizard}>
                <IconArrowLeft size={16} stroke={1.7} />
                {t('exui.complete.restart')}
              </GhostButton>
            </div>
          </div>
        </div>
      </section>
    )
  }

  return (
    <section data-step="run" data-run-screen="progress">
      <div className="grid gap-4" style={{ gridTemplateColumns: 'minmax(0, 1.15fr) minmax(0, 1fr)' }}>
        {/* 左：旧版执行视图（T2 化） */}
        <div className="grid gap-4 py-8 text-center" style={{ justifyItems: 'center' }}>
          <div className="text-[14px] font-semibold">
            {context.running ? t('exui.run.splitting') : t('exui.run.starting')}
          </div>
          <div className="w-full" style={{ maxWidth: '520px' }}>
            <Progress status="indeterminate" />
          </div>
          <div className="text-[13px]" style={{ color: 'var(--c-ink-2)' }}>
            {t('exui.run.detail', context.sourceFile?.name ?? '', context.modeLabel, context.outputDir?.name ?? '')}
          </div>
        </div>
        {/* 右：流水线卡（官方 MultiStepLoader 嵌卡片）+ 处理中（Meteors 流星） */}
        <div className="grid gap-4">
          <div className={panelClass}>
            <div className="mb-1 flex items-center justify-between">
              <span className="text-[13px] font-semibold">{t('exui.pipeline.title')}</span>
              <span className="truncate font-mono text-[11px]" style={{ color: 'var(--c-ink-3)' }}>
                {context.sourceFile?.name ?? ''}
              </span>
            </div>
            <div className="relative overflow-hidden" style={{ height: '192px' }}>
              <div style={{ marginTop: '-132px' }}>
                <MultiStepLoader loadingStates={pipeline} loading={context.running} duration={1500} loop={false} />
              </div>
            </div>
          </div>
          <div className="relative overflow-hidden rounded-xl border border-line bg-panel" style={{ minHeight: 120 }}>
            <Meteors number={14} />
            <div className="relative z-10 flex h-full flex-col justify-center px-4 py-4">
              <span className="text-[13px] font-semibold">{t('exui.run.splitting')}</span>
              <span className="mt-1 text-xs" style={{ color: 'var(--c-ink-2)' }}>
                {t('exui.run.detail', context.sourceFile?.name ?? '', context.modeLabel, context.outputDir?.name ?? '')}
              </span>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
