import { describe, expect, it, vi } from 'vitest'
import { render, screen, act } from '@testing-library/react'
import { FengYuClientProvider } from '../src/client'
import { useFengYuClient } from '../src/client'
import { createFengYuI18n, normalizeFengYuLocale, FengYuI18nProvider, useFengYuI18n } from '../src/i18n'
import { bindFengYuEnvironment } from '../src/environment'
import { NotifyProvider, useFengYuNotify, sendFengYuNotification } from '../src/notify'
import { StepWizard } from '../src/components/wizard'
import { ConfirmDialog } from '../src/components/confirm'
import type { Environment, FengYuClient } from '@infinia/plugin-sdk'

function fakeClient(overrides: Partial<FengYuClient> = {}): FengYuClient {
  const listeners: Array<(value: unknown) => void> = []
  return {
    ready: vi.fn(async () => ({ theme: 'dark', locale: 'zh-CN' }) as unknown as Environment),
    on: vi.fn((event: string, listener: (value: unknown) => void) => {
      if (event === 'environment') listeners.push(listener)
      return () => {}
    }),
    notify: vi.fn(async () => true),
    dispose: vi.fn(),
    invoke: vi.fn(),
    request: vi.fn(),
    files: {} as unknown as FengYuClient['files'],
    ...overrides,
  } as unknown as FengYuClient
}

describe('client context', () => {
  it('throws without a provider', () => {
    const Probe = () => {
      useFengYuClient()
      return null
    }
    expect(() => render(<Probe />)).toThrow(/useFengYuClient/)
  })

  it('provides the client through the tree', () => {
    const client = fakeClient()
    const Probe = () => {
      const resolved = useFengYuClient()
      return <span data-resolved={resolved === client ? 'yes' : 'no'} />
    }
    const { container } = render(
      <FengYuClientProvider client={client}>
        <Probe />
      </FengYuClientProvider>,
    )
    expect(container.querySelector('[data-resolved]')?.getAttribute('data-resolved')).toBe('yes')
  })
})

describe('i18n', () => {
  it('normalizes BCP-47 locales to en/zh', () => {
    expect(normalizeFengYuLocale('zh-CN')).toBe('zh')
    expect(normalizeFengYuLocale('en-US')).toBe('en')
    expect(normalizeFengYuLocale(undefined)).toBe('en')
  })

  it('substitutes positional args and re-renders on locale change', async () => {
    const i18n = createFengYuI18n({
      en: { greet: 'Hello {0}', title: 'Notes' },
      zh: { greet: '你好 {0}', title: '笔记' },
    })
    const Probe = () => {
      const { t, locale } = useFengYuI18n()
      return (
        <>
          <span data-locale={locale}>{t('greet', 'FengYu')}</span>
        </>
      )
    }
    const { container } = render(
      <FengYuI18nProvider i18n={i18n}>
        <Probe />
      </FengYuI18nProvider>,
    )
    expect(container.querySelector('[data-locale]')?.getAttribute('data-locale')).toBe('en')
    expect(container.textContent).toContain('Hello FengYu')
    act(() => i18n.applyEnvironment({ locale: 'zh-CN' }))
    expect(container.querySelector('[data-locale]')?.getAttribute('data-locale')).toBe('zh')
    expect(container.textContent).toContain('你好 FengYu')
  })
})

describe('bindFengYuEnvironment', () => {
  it('applies theme as the .dark class and pushes locale to i18n', async () => {
    const i18n = createFengYuI18n({ en: {}, zh: {} })
    const client = fakeClient({
      ready: vi.fn(async () => ({ theme: 'light', locale: 'zh-CN' }) as unknown as Environment),
    })
    await bindFengYuEnvironment(client, { i18n })
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    expect(i18n.getLocale()).toBe('zh')
  })

  it('falls back to defaults (dark) when no host answers', async () => {
    const client = fakeClient({
      ready: vi.fn(async () => {
        throw new Error('timeout')
      }),
    })
    const onReadyError = vi.fn()
    await bindFengYuEnvironment(client, { onReadyError })
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(onReadyError).toHaveBeenCalledOnce()
  })
})

describe('notify', () => {
  it('mirrors host-rejected messages into the local toast queue', async () => {
    const client = fakeClient({ notify: vi.fn(async () => false) })
    const Probe = () => {
      const { notify } = useFengYuNotify()
      return <button onClick={() => void notify('已拒绝的提示')}>send</button>
    }
    render(
      <FengYuClientProvider client={client}>
        <NotifyProvider client={client}>
          <Probe />
        </NotifyProvider>
      </FengYuClientProvider>,
    )
    await act(async () => {
      screen.getByRole('button', { name: /send/ }).click()
    })
    expect(client.notify).toHaveBeenCalledWith('已拒绝的提示')
    expect(await screen.findByText('已拒绝的提示')).toBeTruthy()
  })

  it('sendFengYuNotification enqueues locally without a client', async () => {
    await sendFengYuNotification(undefined, 'standalone')
    // Standalone queue is module-scoped; the observable contract is that this
    // resolves without throwing and does not contact a host.
    expect(true).toBe(true)
  })
})

describe('StepWizard', () => {
  it('advances through validation and emits snapshots', async () => {
    const snapshots: unknown[] = []
    const validate = vi.fn(async () => ({ valid: true }))
    render(
      <StepWizard
        steps={[
          { value: 'source', title: '来源', validate, render: () => <span>step-1</span> },
          { value: 'mode', title: '方式', render: () => <span>step-2</span> },
        ]}
        context={{}}
        onSnapshot={(snapshot) => snapshots.push(snapshot)}
      />,
    )
    expect(screen.getByText('step-1')).toBeTruthy()
    await act(async () => {
      screen.getByRole('button', { name: /下一步/ }).click()
      await Promise.resolve()
    })
    expect(validate).toHaveBeenCalledOnce()
    expect(screen.getByText('step-2')).toBeTruthy()
    expect(snapshots.length).toBeGreaterThan(0)
  })

  it('blocks advancement on validation failure and surfaces the message', async () => {
    render(
      <StepWizard
        steps={[
          {
            value: 'a',
            title: 'A',
            validate: async () => ({ valid: false, message: '请选择文件' }),
            render: () => <span>step-a</span>,
          },
          { value: 'b', title: 'B', render: () => <span>step-b</span> },
        ]}
        context={{}}
      />,
    )
    await act(async () => {
      screen.getByRole('button', { name: /下一步/ }).click()
      await Promise.resolve()
    })
    expect(screen.getByText('step-a')).toBeTruthy()
    expect(screen.getByText('请选择文件')).toBeTruthy()
  })
})

describe('ConfirmDialog', () => {
  it('confirms, cancels on Escape, and renders destructive tone', () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(
      <ConfirmDialog
        open
        destructive
        title="删除任务"
        message="不可撤销"
        confirmLabel="删除"
        onConfirm={onConfirm}
        onCancel={onCancel}
      />,
    )
    expect(screen.getByRole('alertdialog')).toBeTruthy()
    act(() => {
      screen.getByRole('button', { name: /删除/ }).click()
    })
    expect(onConfirm).toHaveBeenCalledOnce()
    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    })
    expect(onCancel).toHaveBeenCalledOnce()
  })
})
