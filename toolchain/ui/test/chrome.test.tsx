import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { PluginShell, PluginBar, PluginHeader, StatusBar, HexMark, Chip, StatusChip, GoldButton, GhostButton } from '../src/components/chrome'
import { Page, PageHeader } from '../src/components/page'
import { EmptyState, ErrorState, LoadingState, PermissionNotice, Progress } from '../src/components/states'

describe('PluginShell（聚焦工作台，无侧边栏）', () => {
  it('renders a full-bleed column with no navigation regions', () => {
    render(
      <PluginShell>
        <main>content</main>
      </PluginShell>,
    )
    const shell = document.querySelector('[data-plugin-shell]')
    expect(shell).not.toBeNull()
    expect(shell?.className).toContain('flex-col')
    expect(document.querySelector('nav')).toBeNull()
    expect(document.querySelector('[data-nav]')).toBeNull()
    expect(screen.getByText('content')).toBeTruthy()
  })
})

describe('PluginBar', () => {
  it('switches views through gold pills with aria-current', async () => {
    const { rerender } = render(
      <PluginBar
        tabs={[
          { value: 'tasks', title: '拆分任务' },
          { value: 'history', title: '历史' },
        ]}
        defaultActive="tasks"
        right={<GoldButton>新建</GoldButton>}
      />,
    )
    const active = document.querySelector('[data-tab="tasks"]')
    expect(active?.getAttribute('aria-current')).toBe('page')
    expect(active?.className).toContain('bg-gold')
    const inactive = document.querySelector('[data-tab="history"]')
    expect(inactive?.getAttribute('aria-current')).toBeNull()
    screen.getByRole('button', { name: /新建/ })
    rerender(
      <PluginBar
        tabs={[
          { value: 'tasks', title: '拆分任务' },
          { value: 'history', title: '历史' },
        ]}
        active="history"
      />,
    )
    expect(document.querySelector('[data-tab="history"]')?.getAttribute('aria-current')).toBe('page')
  })

  it('emits onNavigate on tab click', async () => {
    const onNavigate = vi.fn()
    render(
      <PluginBar
        tabs={[{ value: 'a', title: 'A' }, { value: 'b', title: 'B' }]}
        onNavigate={onNavigate}
      />,
    )
    screen.getByRole('button', { name: /B/ }).click()
    expect(onNavigate).toHaveBeenCalledWith('b')
  })

  it('renders a bare action bar without tabs', () => {
    render(<PluginBar title="文档" right={<Chip>已保存</Chip>} />)
    expect(document.querySelector('[data-plugin-tabs]')).toBeNull()
    expect(screen.getByText('文档')).toBeTruthy()
  })
})

describe('chrome primitives', () => {
  it('PluginHeader composes mark, name, category, version and actions', () => {
    render(
      <PluginHeader
        icon={<span>icon</span>}
        name="Markdown 编辑器"
        category="text"
        version="v4.1.0"
        right={<GhostButton>导出</GhostButton>}
      />,
    )
    expect(screen.getByRole('heading', { name: /Markdown 编辑器/ })).toBeTruthy()
    expect(screen.getByText('text')).toBeTruthy()
    expect(screen.getByText('v4.1.0')).toBeTruthy()
    expect(screen.getByRole('button', { name: /导出/ })).toBeTruthy()
  })

  it('StatusBar renders mono status regions', () => {
    render(<StatusBar left={<span>工作进程 运行中</span>} right={<span>fan.summer.markdown</span>} />)
    expect(screen.getByText('工作进程 运行中')).toBeTruthy()
    expect(screen.getByText('fan.summer.markdown')).toBeTruthy()
  })

  it('HexMark renders the hive clip shape with gold fill', () => {
    const { container } = render(<HexMark size={24}>M</HexMark>)
    const mark = container.firstElementChild as HTMLElement
    expect(mark.className).toContain('infinia-hex')
    expect(mark.style.background).toBe('var(--c-gold)')
  })

  it('StatusChip conveys tone through the semantic dot', () => {
    const { container } = render(<StatusChip tone="success">已连接</StatusChip>)
    const chip = container.firstElementChild as HTMLElement
    const dot = chip.firstElementChild as HTMLElement
    expect(dot.style.background).toBe('var(--c-success)')
  })

  it('buttons expose native disabled semantics', () => {
    render(
      <>
        <GoldButton disabled>导出</GoldButton>
        <GhostButton>取消</GhostButton>
      </>,
    )
    expect((screen.getByRole('button', { name: /导出/ }) as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByRole('button', { name: /取消/ }) as HTMLButtonElement).disabled).toBe(false)
  })
})

describe('states', () => {
  it('LoadingState renders skeleton rows with a status role', () => {
    const { container } = render(<LoadingState rows={4} label="载入中" />)
    expect(container.querySelectorAll('[data-loading-state] .animate-pulse').length).toBe(4)
  })

  it('EmptyState shows one way out via the action slot', () => {
    render(<EmptyState title="还没有文档" message="从模板开始" action={<GoldButton>新建</GoldButton>} />)
    expect(screen.getByRole('button', { name: /新建/ })).toBeTruthy()
  })

  it('ErrorState wires retry to the data-action hook', () => {
    const onRetry = vi.fn()
    render(<ErrorState title="出错了" message="连接失败" onRetry={onRetry} />)
    screen.getByRole('alert')
    screen.getByRole('button', { name: /重试/ }).click()
    expect(onRetry).toHaveBeenCalledOnce()
  })

  it('PermissionNotice renders a non-error note', () => {
    render(<PermissionNotice message="目录访问被拒绝" />)
    expect(screen.getByRole('note')).toBeTruthy()
  })

  it('Progress shows the rounded percentage', () => {
    render(<Progress value={0.621} />)
    expect(screen.getByText('62%')).toBeTruthy()
  })
})

describe('Page', () => {
  it('caps width by default and goes fluid on demand', () => {
    const { container, rerender } = render(<Page>body</Page>)
    const page = container.firstElementChild as HTMLElement
    expect(page.style.maxWidth).toBe('980px')
    rerender(<Page fluid>body</Page>)
    expect((container.firstElementChild as HTMLElement).style.maxWidth).toBe('')
  })

  it('PageHeader renders title/description/right', () => {
    render(<PageHeader title="拆分任务" description="按工作表或列值" right={<Chip>file</Chip>} />)
    expect(screen.getByText('拆分任务')).toBeTruthy()
    expect(screen.getByText('按工作表或列值')).toBeTruthy()
    expect(screen.getByText('file')).toBeTruthy()
  })
})
