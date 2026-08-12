import { beforeEach, expect, it, vi } from 'vitest'
import { config, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import BatchTab from '../components/BatchTab.vue'
import { i18n } from '../i18n'
import { useAccountsStore } from './accounts'
import { useBatchStore } from './batch'
import { useContactsStore } from './contacts'

const bridge = vi.hoisted(() => ({
  invoke: vi.fn(),
  files: { open: vi.fn(), inputDirectory: vi.fn(), outputDirectory: vi.fn() },
}))
vi.mock('../sdk', () => ({
  ...bridge,
  actionable: (_error: unknown, action: string) => action,
  rpc: new Proxy({}, {
    get: (_t, prop) => typeof prop === 'string' && prop !== 'then'
      ? (input?: unknown, options?: unknown) => bridge.invoke(prop, input, options)
      : undefined,
  }),
  checked: async (p: Promise<{ success: boolean; summary: string }>) => {
    const r = await p
    if (!r.success) throw new Error(r.summary || 'Email operation failed')
    return r
  },
}))

beforeEach(() => { bridge.invoke.mockReset(); config.global.renderStubDefaultSlot = true })

it('previews the exact batch parameters before preparing and only sends after confirmation', async () => {
  bridge.invoke
    .mockResolvedValueOnce({ success: true, preview: { messages: [{ attachmentTag: 'East', to: ['a@example.com'], cc: [], tagAttachments: ['report_East.pdf'], commonAttachments: ['terms.pdf'] }], ignoredFiles: [], skippedTags: [], messageCount: 1 } })
    .mockResolvedValueOnce({ success: true, confirmation: { confirmationId: 'c1', expiresAt: '2026-07-14T12:00:00Z', summary: [] } })
    .mockResolvedValueOnce({ success: true, send: { status: 'COMPLETED', succeeded: 1, failed: 0 } })
  const pinia = createPinia(); setActivePinia(pinia)
  useAccountsStore().selectedId = 7
  useContactsStore().tags = [{ id: 10, name: 'Customers' }, { id: 11, name: 'Managers' }]
  const batch = useBatchStore()
  batch.inputDirectory = { id: 'd1', name: 'reports', kind: 'directory', access: 'read', size: 0 }
  batch.recipientGroupTagIds = [10]
  batch.ccGroupTagIds = [11]
  batch.commonAttachments = [{ id: 'f1', name: 'terms.pdf', kind: 'file', access: 'read', size: 10 }]
  batch.plainText = 'Please review'

  const wrapper = mount(BatchTab, { global: { plugins: [pinia, i18n], stubs: {
    RichTextEditor: { template: '<div />' },
    VBtn: { emits: ['click'], template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>' },
    VDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
    VAlert: true, VCard: true, VCardTitle: true, VCardText: true, VCardActions: true, VChip: true,
    VList: true, VListItem: true, VProgressLinear: true, VSelect: true, VSpacer: true, VTextField: true,
    VExpansionPanels: true, VExpansionPanel: true, VExpansionPanelTitle: true, VExpansionPanelText: true,
  } } })
  await wrapper.vm.$nextTick()
  await wrapper.get('[data-testid="batch-refresh"]').trigger('click')
  await vi.waitFor(() => expect(bridge.invoke).toHaveBeenCalledTimes(1))
  const previewParams = bridge.invoke.mock.calls[0][1]
  expect(bridge.invoke.mock.calls[0][0]).toBe('email_batch_preview')
  expect(previewParams).toMatchObject({ recipientGroupTagIds: [10], ccGroupTagIds: [11], inputDirectory: batch.inputDirectory, commonAttachments: batch.commonAttachments })

  await wrapper.get('[data-testid="batch-review"]').trigger('click')
  await vi.waitFor(() => expect(bridge.invoke).toHaveBeenCalledTimes(2))
  expect(bridge.invoke.mock.calls[1]).toEqual(['email_send_batch', previewParams, undefined])
  expect(bridge.invoke).toHaveBeenCalledTimes(2)

  await wrapper.get('[data-testid="confirmation-approve"]').trigger('click')
  await vi.waitFor(() => expect(bridge.invoke).toHaveBeenCalledTimes(3))
  expect(bridge.invoke.mock.calls.map(call => call[0])).toEqual(['email_batch_preview', 'email_send_batch', 'confirm_send'])
})
