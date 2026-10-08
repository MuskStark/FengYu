import { describe, expect, it, vi } from 'vitest'
import { useState } from 'react'
import { render, screen, act, waitFor } from '@testing-library/react'
import { FengYuClientProvider } from '../src/client'
import { FilePicker, DirectoryPicker } from '../src/components/pickers'
import type { FileRef, FengYuClient } from '@infinia/plugin-sdk'

const fileRef: FileRef = { id: 'ref_1', name: 'ledger.xlsx', kind: 'file', access: 'read', size: 128 }
const dirRef: FileRef = { id: 'ref_2', name: 'reports', kind: 'directory', access: 'read-write', size: 0 }

function fakeClient(files: Partial<FengYuClient['files']> = {}): FengYuClient {
  return {
    ready: vi.fn(async () => ({}) as never),
    on: vi.fn(() => () => {}),
    notify: vi.fn(async () => true),
    dispose: vi.fn(),
    invoke: vi.fn(),
    request: vi.fn(),
    files: {
      open: vi.fn(async () => fileRef),
      inputDirectory: vi.fn(async () => dirRef),
      workspaceDirectory: vi.fn(async () => dirRef),
      outputDirectory: vi.fn(async () => dirRef),
      export: vi.fn(async () => true),
      ...files,
    } as unknown as FengYuClient['files'],
  } as unknown as FengYuClient
}

function withClient(client: FengYuClient, ui: React.ReactElement) {
  return render(<FengYuClientProvider client={client}>{ui}</FengYuClientProvider>)
}

/** A realistic consumer: feeds the picked ref back through `value` (controlled usage). */
function Harness({ client, kind }: { client: FengYuClient; kind: 'file' | 'directory' }) {
  const [value, setValue] = useState<FileRef | null>(null)
  if (kind === 'file') return <FilePicker value={value} onChange={setValue} />
  return <DirectoryPicker value={value} onChange={setValue} />
}

describe('FilePicker', () => {
  it('emits the host FileRef verbatim and renders it once fed back', async () => {
    const client = fakeClient()
    withClient(client, <Harness client={client} kind="file" />)
    await act(async () => {
      screen.getByRole('button', { name: /选择文件/ }).click()
    })
    expect(client.files.open).toHaveBeenCalledWith({ extensions: [], filters: [] })
    expect(await screen.findByText('ledger.xlsx')).toBeTruthy()
  })

  it('forwards extensions and filters to files.open', async () => {
    const client = fakeClient()
    withClient(client,
      <FilePicker value={null} onChange={vi.fn()} extensions={['xlsx']} filters={[{ name: 'Books', extensions: ['csv'] }]} />)
    await act(async () => {
      screen.getByRole('button', { name: /选择文件/ }).click()
    })
    expect(client.files.open).toHaveBeenCalledWith({
      extensions: ['xlsx'],
      filters: [{ name: 'Books', extensions: ['csv'] }],
    })
  })

  it('a clean null result is a host-side cancellation (onChange(null) + onCancel)', async () => {
    const client = fakeClient({ open: vi.fn(async () => null) })
    const onChange = vi.fn()
    const onCancel = vi.fn()
    withClient(client, <FilePicker value={null} onChange={onChange} onCancel={onCancel} />)
    await act(async () => {
      screen.getByRole('button', { name: /选择文件/ }).click()
    })
    expect(onChange).toHaveBeenCalledWith(null)
    expect(onCancel).toHaveBeenCalledOnce()
  })

  it('a rejected open surfaces the error (and clears the value) without a retry loop', async () => {
    const client = fakeClient({ open: vi.fn(async () => { throw new Error('permission denied') }) })
    const onChange = vi.fn()
    const onError = vi.fn()
    withClient(client, <FilePicker value={null} onChange={onChange} onError={onError} />)
    await act(async () => {
      screen.getByRole('button', { name: /选择文件/ }).click()
    })
    await waitFor(() => expect(onError).toHaveBeenCalledOnce())
    expect(client.files.open).toHaveBeenCalledTimes(1)
    expect(await screen.findByText(/permission denied/)).toBeTruthy()
  })
})

describe('DirectoryPicker', () => {
  it('emits the host FileRef verbatim — never a flattened path or a wrapped object', async () => {
    const client = fakeClient()
    const onChange = vi.fn()
    withClient(client, <DirectoryPicker value={null} onChange={onChange} />)
    await act(async () => {
      screen.getByRole('button', { name: /选择目录/ }).click()
    })
    expect(client.files.inputDirectory).toHaveBeenCalled()
    // The exact FileRef the host returned must reach the consumer untouched.
    expect(onChange).toHaveBeenCalledWith(dirRef)
    expect(onChange.mock.calls[0][0]).toBe(dirRef)
  })

  it('renders the picked FileRef by name/access once fed back through value', async () => {
    const client = fakeClient()
    withClient(client, <Harness client={client} kind="directory" />)
    await act(async () => {
      screen.getByRole('button', { name: /选择目录/ }).click()
    })
    expect(await screen.findByText('reports')).toBeTruthy()
    expect(screen.getByText('read-write')).toBeTruthy()
    expect(document.body.textContent).not.toContain('[object Object]')
    expect(document.body.textContent).not.toContain('[object Promise]')
  })

  it('a clean null result is a host-side cancellation (onChange(null) + onCancel)', async () => {
    const client = fakeClient({ inputDirectory: vi.fn(async () => null) })
    const onChange = vi.fn()
    const onCancel = vi.fn()
    withClient(client, <DirectoryPicker value={null} onChange={onChange} onCancel={onCancel} />)
    await act(async () => {
      screen.getByRole('button', { name: /选择目录/ }).click()
    })
    expect(onChange).toHaveBeenCalledWith(null)
    expect(onCancel).toHaveBeenCalledOnce()
  })

  it('a rejected open surfaces the error without a retry loop', async () => {
    const client = fakeClient({ inputDirectory: vi.fn(async () => { throw new Error('permission denied') }) })
    const onChange = vi.fn()
    withClient(client, <DirectoryPicker value={null} onChange={onChange} />)
    await act(async () => {
      screen.getByRole('button', { name: /选择目录/ }).click()
    })
    expect(await screen.findByText(/permission denied/)).toBeTruthy()
    expect(client.files.inputDirectory).toHaveBeenCalledTimes(1)
  })
})
