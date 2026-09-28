import { useState } from 'react'
import {
  Page,
  PageHeader,
  PluginBar,
  PluginShell,
  StatusBar,
  StatusChip,
  StepWizard,
  GoldButton,
  EmptyState,
  Progress,
  type FyWizardStepState,
  type StepWizardStep,
} from '../src'

type FixtureState = 'normal' | 'error' | 'skipped' | 'validating' | 'complete'

interface WorkbenchContext {
  sourceFile?: string
}

/**
 * E2E workbench: the complete Infinia plugin shell with a two-step wizard
 * driven by `?state=` (see workbench.spec.ts case matrix). `?theme=` flips the
 * `.dark` class the way bindFengYuEnvironment does in real apps.
 */
export default function Workbench({ state }: { state: FixtureState }) {
  const [context, setContext] = useState<WorkbenchContext>({})

  const steps: StepWizardStep<WorkbenchContext>[] = [
    {
      value: 'source',
      title: 'Source file',
      description: 'Pick the workbook to split',
      validate:
        state === 'error'
          ? async () => ({ valid: false, message: 'Choose a workbook first' })
          : state === 'validating'
            ? () => new Promise<never>(() => {}) // hangs: keeps the busy state for the shot
            : undefined,
      render: ({ actions }) => (
        <div className="grid gap-3">
          <GoldButton data-action="pick-source" onClick={() => { setContext((c) => ({ ...c, sourceFile: 'ledger.xlsx' })); actions.invalidate('source') }}>
            Choose workbook
          </GoldButton>
          {context.sourceFile ? <Progress value={1} label="ready" /> : <EmptyState title="No workbook yet" message="Pick an .xlsx file to continue" />}
        </div>
      ),
    },
    {
      value: 'mode',
      title: 'Import mode',
      description: 'Sheet, column, or complex rules',
      optional: state === 'skipped',
      render: () => (
        <div className="grid gap-2 text-[13px] text-ink-2">
          <label className="flex items-center gap-2">
            <input type="radio" name="mode" defaultChecked /> By sheet
          </label>
          <label className="flex items-center gap-2">
            <input type="radio" name="mode" /> By column value
          </label>
          <label className="flex items-center gap-2">
            <input type="radio" name="mode" /> Complex rules
          </label>
        </div>
      ),
    },
  ]

  const seedStates =
    state === 'skipped'
      ? ({ source: { status: 'complete' }, mode: { status: 'skipped' } } as Record<string, FyWizardStepState>)
      : undefined

  return (
    <PluginShell>
      <PluginBar
        tabs={[
          { value: 'tasks', title: 'Tasks' },
          { value: 'history', title: 'History' },
        ]}
        defaultActive="tasks"
        right={<StatusChip tone={state === 'error' ? 'danger' : 'success'}>{state}</StatusChip>}
      />
      <Page fluid fullHeight>
        <PageHeader title="Split a workbook" description="Visual-regression fixture for the Infinia kit." />
        <StepWizard
          steps={steps}
          context={context}
          completed={state === 'complete'}
          snapshot={seedStates ? { version: 1, activeStep: 'mode', visitedPath: ['source', 'mode'], states: seedStates, completed: false } : undefined}
          labels={{ status: { pending: '待办', active: '当前', validating: '校验中', complete: '完成', error: '错误', skipped: '跳过' }, next: '下一步', back: '上一步', finish: '完成' }}
        />
      </Page>
      <StatusBar left={<span>worker idle</span>} right={<span>e2e.workbench</span>} />
    </PluginShell>
  )
}
