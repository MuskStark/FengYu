import { IconTable, IconColumns, IconSitemap, IconInfoCircle, IconPlus, IconX } from '@tabler/icons-react'
import { Combobox, ErrorState, useFengYuI18n } from '@infinia/plugin-ui'
import type { SplitMode, SplitterContext } from './types'

/** Field shell: hairline panel + label + control (the Infinia take on the old v-field). */
function Field({ label, children, htmlFor }: { label: string; children: React.ReactNode; htmlFor?: string }) {
  return (
    <div className="grid gap-1.5">
      <label htmlFor={htmlFor} className="text-xs" style={{ color: 'var(--c-ink-2)' }}>
        {label}
      </label>
      {children}
    </div>
  )
}

const fieldClass =
  'h-9 w-full min-w-0 rounded-lg border border-line bg-panel px-3 text-[13px] text-ink transition-colors focus:outline-none focus:border-line-strong'

const modeIcons: Record<SplitMode, typeof IconTable> = {
  BY_SHEET: IconTable,
  BY_COLUMN: IconColumns,
  COMPLEX: IconSitemap,
}

/** Step 2 — Mode: three mode cards + the per-mode configuration form. */
export function ModeStep({ context, showStepError }: { context: SplitterContext; showStepError: boolean }) {
  const { t } = useFengYuI18n()
  const options: Array<{ value: SplitMode; label: string; hint: string }> = [
    { value: 'BY_SHEET', label: t('exui.mode.bySheet.label'), hint: t('exui.mode.bySheet.hint') },
    { value: 'BY_COLUMN', label: t('exui.mode.byColumn.label'), hint: t('exui.mode.byColumn.hint') },
    { value: 'COMPLEX', label: t('exui.mode.complex.label'), hint: t('exui.mode.complex.hint') },
  ]

  return (
    <section data-step="mode">
      <div className="mb-4 text-[15px] font-semibold tracking-tight">{t('exui.mode.cardTitle')}</div>
      <div role="radiogroup" aria-label={t('exui.source.ariaSplitMode')} className="mb-5 grid gap-3" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))' }}>
        {options.map((option) => {
          const Icon = modeIcons[option.value]
          const active = context.mode === option.value
          return (
            <button
              key={option.value}
              type="button"
              role="radio"
              aria-checked={active}
              data-mode={option.value}
              onClick={() => context.changeMode(option.value)}
              className="grid items-center gap-3 rounded-xl p-4 text-left transition-colors"
              style={{
                border: `2px solid ${active ? 'var(--c-gold)' : 'var(--c-line)'}`,
                background: active ? 'color-mix(in oklab, var(--c-gold) 7%, var(--c-panel))' : 'var(--c-panel)',
              }}
            >
              <Icon size={22} stroke={1.6} style={{ color: active ? 'var(--c-gold)' : 'var(--c-ink-2)' }} />
              <span className="grid" style={{ gap: '0.125rem' }}>
                <span className="text-[14px] font-semibold">{option.label}</span>
                <span className="text-xs" style={{ color: 'var(--c-ink-2)' }}>
                  {option.hint}
                </span>
              </span>
            </button>
          )
        })}
      </div>

      {context.mode === 'BY_COLUMN' ? (
        <div className="mb-4 flex items-center gap-2 rounded-lg px-3.5 py-2 text-[13px]" style={{ background: 'var(--c-tag)', color: 'var(--c-ink-2)' }}>
          <IconInfoCircle size={16} stroke={1.6} className="shrink-0" style={{ color: 'var(--c-gold)' }} />
          {t('exui.mode.noteColumn')}
        </div>
      ) : null}
      {context.mode === 'COMPLEX' ? (
        <div className="mb-4 flex items-center gap-2 rounded-lg px-3.5 py-2 text-[13px]" style={{ background: 'var(--c-tag)', color: 'var(--c-ink-2)' }}>
          <IconInfoCircle size={16} stroke={1.6} className="shrink-0" style={{ color: 'var(--c-gold)' }} />
          {t('exui.mode.noteComplex')}
        </div>
      ) : null}

      {context.mode === 'BY_SHEET' ? (
        <>
          <Field label={t('exui.mode.sheets')}>
            <div className="flex flex-wrap gap-2" data-field="selectedSheets">
              {context.sheetNames.map((name) => {
                const selected = context.selectedSheets.includes(name)
                return (
                  <button
                    key={name}
                    type="button"
                    data-sheet={name}
                    aria-pressed={selected}
                    onClick={() => context.toggleSheet(name)}
                    className="rounded-full px-3 py-1 text-xs transition-colors"
                    style={{
                      border: `1px solid ${selected ? 'var(--c-gold)' : 'var(--c-line-strong)'}`,
                      background: selected ? 'var(--c-gold)' : 'var(--c-panel)',
                      color: selected ? 'var(--c-gold-ink)' : 'var(--c-ink-2)',
                    }}
                  >
                    {name}
                  </button>
                )
              })}
            </div>
          </Field>
          <div className="mt-4 max-w-[460px]">
            <Field label={t('exui.mode.filePrefix')} htmlFor="exui-file-prefix">
              <input
                id="exui-file-prefix"
                data-field="filePrefix"
                type="text"
                className={fieldClass}
                value={context.filePrefix}
                onChange={(event) => context.changeFilePrefix(event.target.value)}
              />
            </Field>
          </div>
        </>
      ) : null}

      {context.mode === 'BY_COLUMN' ? (
        <>
          <div className="grid gap-4" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))' }}>
            <Field label={t('exui.mode.sheet')} htmlFor="exui-split-sheet">
              <Combobox
                id="exui-split-sheet"
                data-field="splitSheet"
                className="w-full"
                value={context.splitSheet ?? ''}
                options={context.sheetNames.map((name) => ({ value: name, label: name }))}
                onCommit={context.changeSplitSheet}
              />
            </Field>
            <Field label={t('exui.mode.column')} htmlFor="exui-split-column">
              <Combobox
                id="exui-split-column"
                data-field="splitColumn"
                className="w-full"
                disabled={!context.splitSheet}
                value={context.splitColumn ?? ''}
                options={context.columnsForSplitSheet.map((name) => ({ value: name, label: name }))}
                onCommit={context.changeSplitColumn}
              />
            </Field>
          </div>
          <div className="mt-4 max-w-[460px]">
            <Field label={t('exui.mode.filePrefix')} htmlFor="exui-file-prefix">
              <input
                id="exui-file-prefix"
                data-field="filePrefix"
                type="text"
                className={fieldClass}
                value={context.filePrefix}
                onChange={(event) => context.changeFilePrefix(event.target.value)}
              />
            </Field>
          </div>
        </>
      ) : null}

      {context.mode === 'COMPLEX' ? (
        <>
          <div className="rounded-xl border border-line bg-panel" style={{ overflowX: 'auto' }}>
            <table data-complex-rules className="w-full text-[13px]">
              <thead>
                <tr className="text-left text-xs" style={{ color: 'var(--c-ink-2)', background: 'var(--c-muted-surface)' }}>
                  <th className="px-3 py-2 font-medium">{t('exui.complex.sheet')}</th>
                  <th className="px-3 py-2 font-medium">{t('exui.complex.headerRow')}</th>
                  <th className="px-3 py-2 font-medium">{t('exui.complex.column')}</th>
                  <th className="px-3 py-2 font-medium">{t('exui.complex.copyEntire')}</th>
                  <th className="px-3 py-2" aria-label="" />
                </tr>
              </thead>
              <tbody>
                {context.complexEntries.map((entry, index) => (
                  <tr key={index} className="border-t border-line">
                    <td className="px-3 py-2">
                      <input
                        data-field="complexSheet"
                        data-index={index}
                        type="text"
                        list="exui-sheet-options"
                        className={fieldClass}
                        style={{ minWidth: '140px' }}
                        value={entry.sheetName}
                        onChange={(event) => context.updateComplexEntry(index, { sheetName: event.target.value })}
                      />
                    </td>
                    <td className="px-3 py-2">
                      <input
                        data-field="headerIndex"
                        data-index={index}
                        type="number"
                        className={fieldClass}
                        style={{ width: '88px' }}
                        disabled={entry.copyAll}
                        value={Number.isNaN(entry.headerIndex) ? '' : entry.headerIndex}
                        onChange={(event) =>
                          context.updateComplexEntry(index, {
                            headerIndex: event.target.value.trim() === '' ? Number.NaN : Number(event.target.value),
                          })
                        }
                      />
                    </td>
                    <td className="px-3 py-2">
                      <input
                        data-field="columnIndex"
                        data-index={index}
                        type="number"
                        className={fieldClass}
                        style={{ width: '88px' }}
                        disabled={entry.copyAll}
                        value={Number.isNaN(entry.columnIndex) ? '' : entry.columnIndex}
                        onChange={(event) =>
                          context.updateComplexEntry(index, {
                            columnIndex: event.target.value.trim() === '' ? Number.NaN : Number(event.target.value),
                          })
                        }
                      />
                    </td>
                    <td className="px-3 py-2">
                      <input
                        data-field="copyAll"
                        data-index={index}
                        type="checkbox"
                        checked={entry.copyAll}
                        onChange={() => context.toggleCopyAll(index)}
                        className="size-4"
                      />
                    </td>
                    <td className="px-3 py-2 text-right">
                      <button
                        type="button"
                        data-action="remove-rule"
                        data-index={index}
                        onClick={() => context.removeComplexEntry(index)}
                        className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs transition-colors"
                        style={{ color: 'var(--c-ink-3)' }}
                      >
                        <IconX size={14} stroke={1.8} />
                        {t('exui.complex.remove')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="flex justify-center" style={{ marginTop: '0.875rem' }}>
            <button
              type="button"
              data-action="add-rule"
              onClick={() => context.addComplexEntry()}
              className="inline-flex h-8 items-center gap-1.5 rounded-full border border-line-strong px-3.5 text-[13px] transition-colors"
              style={{ color: 'var(--c-ink)' }}
            >
              <IconPlus size={15} stroke={1.7} />
              {t('exui.complex.addRule')}
            </button>
          </div>
          <div className="mt-4 max-w-[460px]">
            <Field label={t('exui.mode.filePrefix')} htmlFor="exui-file-prefix">
              <input
                id="exui-file-prefix"
                data-field="filePrefix"
                type="text"
                className={fieldClass}
                value={context.filePrefix}
                onChange={(event) => context.changeFilePrefix(event.target.value)}
              />
            </Field>
          </div>
        </>
      ) : null}
      {showStepError && context.configureError ? (
        <ErrorState className="mt-3" title={t('exui.step.mode')} message={context.configureError} />
      ) : null}
    </section>
  )
}