import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { FengYuClientProvider, type FengYuClient } from '@infinia/plugin-ui'
import type { RenderOutput } from './generated/fengyu-rpc'
import MarkdownEditor from './MarkdownEditor'

/** Minimal host stand-in: only the invoke surface the generated RPC client touches. */
function hostClient(result: RenderOutput | Error): FengYuClient {
  return {
    invoke: vi.fn(async () => {
      if (result instanceof Error) throw result
      return result
    }),
  } as unknown as FengYuClient
}

afterEach(() => {
  vi.useRealTimers()
})

describe('MarkdownEditor', () => {
  it('renders the initial document through the worker immediately', async () => {
    const client = hostClient({ success: true, html: '<h1>Hi</h1><p><em>FengYu</em></p>', summary: 'ok' })
    const { container } = render(
      <FengYuClientProvider client={client}>
        <MarkdownEditor />
      </FengYuClientProvider>,
    )
    await waitFor(() => {
      expect(container.querySelector('[data-preview-body] em')?.textContent).toBe('FengYu')
    })
    expect(container.querySelector('[data-preview-body]')?.classList.contains('mde-error')).toBe(false)
  })

  it('debounces worker renders by 250 ms after an edit', async () => {
    vi.useFakeTimers()
    const client = hostClient({ success: true, html: '<p>ok</p>', summary: 'ok' })
    const invoke = client.invoke as ReturnType<typeof vi.fn>
    render(
      <FengYuClientProvider client={client}>
        <MarkdownEditor />
      </FengYuClientProvider>,
    )
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(invoke).toHaveBeenCalledTimes(1) // mount render, undebounced

    const textarea = screen.getByLabelText('Markdown')
    await act(async () => {
      fireEvent.change(textarea, { target: { value: '# Changed' } })
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(249)
    })
    expect(invoke).toHaveBeenCalledTimes(1) // still inside the debounce window

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(invoke).toHaveBeenCalledTimes(2)
    expect(invoke).toHaveBeenLastCalledWith('render', { markdown: '# Changed' }, undefined)
  })

  it('styles a failed worker render as an error', async () => {
    const client = hostClient({ success: false, html: null, summary: 'boom' })
    const { container } = render(
      <FengYuClientProvider client={client}>
        <MarkdownEditor />
      </FengYuClientProvider>,
    )
    await waitFor(() => {
      expect(screen.getByText('boom')).toBeTruthy()
    })
    const body = container.querySelector('[data-preview-body]')
    expect(body?.classList.contains('mde-error')).toBe(true)
  })

  it('styles a thrown worker error as an error', async () => {
    const client = hostClient(new Error('worker died'))
    const { container } = render(
      <FengYuClientProvider client={client}>
        <MarkdownEditor />
      </FengYuClientProvider>,
    )
    await waitFor(() => {
      expect(screen.getByText('worker died')).toBeTruthy()
    })
    expect(container.querySelector('[data-preview-body]')?.classList.contains('mde-error')).toBe(true)
  })

  it('falls back to the escaped raw source without a host', async () => {
    const { container } = render(<MarkdownEditor />)
    await waitFor(() => {
      expect(container.querySelector('[data-preview-body] pre')?.textContent).toContain('# Hello FengYu')
    })
    const body = container.querySelector('[data-preview-body]')
    expect(body?.classList.contains('mde-error')).toBe(false)
  })

  it('inserts markdown syntax at the caret from the format dock', async () => {
    vi.useFakeTimers()
    const client = hostClient({ success: true, html: '<p>ok</p>', summary: 'ok' })
    const invoke = client.invoke as ReturnType<typeof vi.fn>
    const { container } = render(
      <FengYuClientProvider client={client}>
        <MarkdownEditor />
      </FengYuClientProvider>,
    )
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })

    const bold = container.querySelector('[data-format="bold"]')
    expect(bold).toBeTruthy()
    await act(async () => {
      fireEvent.click(bold as HTMLElement)
    })
    const textarea = screen.getByLabelText('Markdown') as HTMLTextAreaElement
    expect(textarea.value.startsWith('**')).toBe(true)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(250)
    })
    expect(invoke).toHaveBeenLastCalledWith('render', { markdown: textarea.value }, undefined)
  })
})
