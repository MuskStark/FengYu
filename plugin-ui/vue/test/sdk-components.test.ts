/**
 * SDK-integrated component tests: file/directory pickers and notification center.
 *
 * These exercises the host SDK surface (`files.open`, `files.inputDirectory`,
 * `files.outputDirectory`, `notify`) through a fake client. Behavioral rules:
 * - Cancellation (SDK resolves `null`) is a normal empty result — no alert.
 * - Permission-denied rejections render `FyPermissionNotice` and do NOT auto-retry.
 * - Other rejections render `FyErrorState` (which exposes a retry button).
 * - Concurrent clicks are guarded by a `loading` state.
 * - Notifications rejected or thrown by the host fall back to a local queue.
 */
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  createFengYuVuetify,
  FENGYU_CLIENT_KEY,
  FyDirectoryPicker,
  FyFilePicker,
  FyNotificationCenter,
  sendFengYuNotification,
  useFengYuNotify,
} from '../src'
import type { FengYuClient } from '@fengyu/plugin-sdk'

/**
 * Overrides accepted by {@link fakeClient}. `files` is merged field-by-field so
 * a test can stub a single branch (e.g. `files.open`) without losing the other
 * file methods. Other top-level methods replace their default wholesale.
 */
type FakeClientOverrides = Omit<Partial<FengYuClient>, 'files'> & {
  files?: Partial<FengYuClient['files']>
}

/**
 * Build a fake {@link FengYuClient} satisfying the SDK interface. Every method
 * is a vitest mock with a sensible default; pass `overrides` to swap any branch
 * (e.g. `files: { open }`) for the scenario under test.
 */
function fakeClient(overrides: FakeClientOverrides = {}): FengYuClient {
  const { files: fileOverrides, ...rest } = overrides
  return {
    ready: vi.fn().mockResolvedValue({ theme: 'dark', locale: 'en' }),
    on: vi.fn().mockReturnValue(() => {}),
    notify: vi.fn().mockResolvedValue(true),
    files: {
      open: vi.fn(),
      inputDirectory: vi.fn(),
      outputDirectory: vi.fn(),
      export: vi.fn(),
      ...fileOverrides,
    },
    invoke: vi.fn(),
    dispose: vi.fn(),
    request: vi.fn(),
    ...rest,
  } as unknown as FengYuClient
}

/**
 * Mount a component with the Vuetify plugin installed and the given fake client
 * injected under the SDK's `FENGYU_CLIENT_KEY`, mirroring what
 * `provideFengYuClient(app, client)` sets up in a real app.
 */
function mountWithClient(
  component: Parameters<typeof mount>[0],
  client: FengYuClient,
  mountOptions: Parameters<typeof mount>[1] = {},
) {
  return mount(component, {
    ...mountOptions,
    global: {
      plugins: [createFengYuVuetify()],
      provide: { [FENGYU_CLIENT_KEY]: client },
      ...(mountOptions.global ?? {}),
    },
  })
}

describe('FyFilePicker', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('selects a file and treats cancellation as a normal empty result', async () => {
    const open = vi
      .fn()
      .mockResolvedValueOnce({ id: '1', name: 'a.xlsx', kind: 'file', access: 'read', size: 10 })
      .mockResolvedValueOnce(null)
    const client = fakeClient({ files: { open } })
    const wrapper = mountWithClient(FyFilePicker, client)
    await wrapper.get('[data-action="pick-file"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toMatchObject({ name: 'a.xlsx' })
    await wrapper.get('[data-action="pick-file"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('shows permission errors without automatically retrying', async () => {
    const open = vi.fn().mockRejectedValue(new Error('Permission denied'))
    const client = fakeClient({ files: { open } })
    const wrapper = mountWithClient(FyFilePicker, client)
    await wrapper.get('[data-action="pick-file"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Permission denied')
    expect(open).toHaveBeenCalledOnce()
  })

  it('forwards extensions and filters to files.open', async () => {
    const open = vi.fn().mockResolvedValue(null)
    const client = fakeClient({ files: { open } })
    const wrapper = mountWithClient(FyFilePicker, client, {
      props: {
        extensions: ['xlsx'],
        filters: [{ name: 'Spreadsheets', extensions: ['xlsx', 'csv'] }],
      },
    })
    await wrapper.get('[data-action="pick-file"]').trigger('click')
    await flushPromises()
    expect(open).toHaveBeenCalledWith({ extensions: ['xlsx'], filters: [{ name: 'Spreadsheets', extensions: ['xlsx', 'csv'] }] })
  })

  it('renders a generic error state with a retry action for non-permission errors', async () => {
    const open = vi.fn().mockRejectedValueOnce(new Error('Permission denied')).mockResolvedValueOnce(null)
    const client = fakeClient({ files: { open } })
    const wrapper = mountWithClient(FyFilePicker, client)
    await wrapper.get('[data-action="pick-file"]').trigger('click')
    await flushPromises()
    // Permission notice: no retry button, open called once.
    expect(wrapper.find('[data-action="retry"]').exists()).toBe(false)
    // Now cause a generic failure by changing the client behaviour is not possible;
    // instead mount a fresh picker that rejects with a non-permission error.
    const open2 = vi.fn().mockRejectedValue(new Error('Disk full'))
    const client2 = fakeClient({ files: { open: open2 } })
    const wrapper2 = mountWithClient(FyFilePicker, client2)
    await wrapper2.get('[data-action="pick-file"]').trigger('click')
    await flushPromises()
    expect(wrapper2.text()).toContain('Disk full')
    expect(wrapper2.find('[data-action="retry"]').exists()).toBe(true)
    await wrapper2.get('[data-action="retry"]').trigger('click')
    await flushPromises()
    expect(open2).toHaveBeenCalledTimes(2)
  })

  it('guards concurrent clicks with a loading state', async () => {
    let resolveOpen: (value: unknown) => void = () => {}
    const open = vi.fn().mockReturnValue(new Promise((resolve) => { resolveOpen = resolve }))
    const client = fakeClient({ files: { open } })
    const wrapper = mountWithClient(FyFilePicker, client)
    const button = wrapper.get('[data-action="pick-file"]')
    await button.trigger('click')
    // While the first request is still pending, the button should be disabled.
    expect((button.element as HTMLButtonElement).disabled).toBe(true)
    await button.trigger('click')
    expect(open).toHaveBeenCalledOnce()
    resolveOpen({ id: '1', name: 'a.txt', kind: 'file', access: 'read', size: 1 })
    await flushPromises()
  })
})

describe('FyDirectoryPicker', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('calls inputDirectory for mode="input" and emits the selected directory', async () => {
    const inputDirectory = vi.fn().mockResolvedValue({ id: 'd1', name: 'in', kind: 'directory', access: 'read', size: 0 })
    const client = fakeClient({ files: { inputDirectory } })
    const wrapper = mountWithClient(FyDirectoryPicker, client, { props: { mode: 'input' } })
    await wrapper.get('[data-action="pick-directory"]').trigger('click')
    await flushPromises()
    expect(inputDirectory).toHaveBeenCalledOnce()
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toMatchObject({ name: 'in', kind: 'directory' })
  })

  it('calls outputDirectory for mode="output"', async () => {
    const outputDirectory = vi.fn().mockResolvedValue(null)
    const client = fakeClient({ files: { outputDirectory } })
    const wrapper = mountWithClient(FyDirectoryPicker, client, { props: { mode: 'output' } })
    await wrapper.get('[data-action="pick-directory"]').trigger('click')
    await flushPromises()
    expect(outputDirectory).toHaveBeenCalledOnce()
    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })
})

describe('useFengYuNotify', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('keeps the queue empty when the host accepts the notification', async () => {
    const client = fakeClient({ notify: vi.fn().mockResolvedValue(true) })
    const { localMessages, notify } = useFengYuNotify(client)
    await notify('hello')
    expect(localMessages.value).toEqual([])
  })

  it('falls back to the local queue when the host rejects the notification', async () => {
    const client = fakeClient({ notify: vi.fn().mockResolvedValue(false) })
    const { localMessages, notify } = useFengYuNotify(client)
    await notify('queued')
    expect(localMessages.value).toEqual(['queued'])
  })

  it('falls back to the local queue when the host throws', async () => {
    const client = fakeClient({ notify: vi.fn().mockRejectedValue(new Error('boom')) })
    const { localMessages, notify } = useFengYuNotify(client)
    await notify('queued')
    expect(localMessages.value).toEqual(['queued'])
  })

  it('sendFengYuNotification appends only on reject or throw', async () => {
    const local: { value: string[] } = { value: [] }
    const accepted = fakeClient({ notify: vi.fn().mockResolvedValue(true) })
    await sendFengYuNotification(accepted, 'ok', local as never)
    expect(local.value).toEqual([])

    const rejected = fakeClient({ notify: vi.fn().mockResolvedValue(false) })
    await sendFengYuNotification(rejected, 'no', local as never)
    expect(local.value).toEqual(['no'])

    const thrown = fakeClient({ notify: vi.fn().mockRejectedValue(new Error('x')) })
    await sendFengYuNotification(thrown, 'boom', local as never)
    expect(local.value).toEqual(['no', 'boom'])
  })

  it('FyNotificationCenter renders and dismisses local messages', async () => {
    const client = fakeClient({ notify: vi.fn().mockResolvedValue(false) })
    const wrapper = mountWithClient(FyNotificationCenter, client)
    const vm = wrapper.vm as unknown as { notify: (m: string) => Promise<void> }
    await vm.notify('a problem occurred')
    await flushPromises()
    expect(wrapper.text()).toContain('a problem occurred')
    expect(wrapper.find('[aria-live="polite"]').exists()).toBe(true)
    await wrapper.get('[data-action="dismiss"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('a problem occurred')
  })
})
