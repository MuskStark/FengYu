import { beforeEach, expect, it, vi } from 'vitest'
import { config, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { i18n } from '../i18n'
import ComposeTab from './ComposeTab.vue'
import { useAccountsStore } from '../stores/accounts'
import { useComposeStore } from '../stores/compose'
import { useContactsStore } from '../stores/contacts'

const bridge = vi.hoisted(() => ({
  invoke: vi.fn(),
  files: { open: vi.fn(), inputDirectory: vi.fn(), outputDirectory: vi.fn() },
}))
vi.mock('../sdk', () => ({ ...bridge, actionable: (_error: unknown, action: string) => action }))

beforeEach(() => { bridge.invoke.mockReset(); bridge.files.open.mockReset(); config.global.renderStubDefaultSlot = true })

it('prepares tag Compose and dispatches only after confirmation', async () => {
  bridge.invoke
    .mockResolvedValueOnce({ success: true, confirmation_required: true,
      confirmation: { confirmationId: 'c1', expiresAt: '2026-07-14T12:00:00Z', summary: [] } })
    .mockResolvedValueOnce({ success: true, send: { status: 'COMPLETED', succeeded: 2, failed: 0 } })
  const pinia = createPinia(); setActivePinia(pinia)
  // App.vue populates the account list on mount; simulate that so the tab does not re-fetch.
  const accounts = useAccountsStore()
  accounts.accounts = [{ id: 7, displayName: 'Sender', email: 'sender@example.com' }]
  accounts.selectedId = 7
  useContactsStore().tags = [{ id: 4, name: 'Customers' }]
  const wrapper = mount(ComposeTab, { global: { plugins: [pinia, i18n], stubs: {
    RichTextEditor: { emits: ['update:modelValue', 'update:plainText'], template: '<div data-testid="rich-editor" />' },
    VBtn: { emits: ['click'], template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>' },
    VDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
    VAlert: true, VCard: true, VCardTitle: true, VCardText: true, VCardActions: true, VChip: true, VList: true, VListItem: true,
    VCombobox: true, VSelect: true, VSpacer: true, VTextField: true,
  } } })
  await wrapper.get('[data-testid="compose-mode-tags"]').trigger('click')
  useComposeStore().recipientTagIds = [4]
  useComposeStore().cc = ['manager@example.com']
  useComposeStore().plainText = 'Hello'
  await wrapper.vm.$nextTick()
  await wrapper.get('[data-testid="compose-review"]').trigger('click')
  await vi.waitFor(() => expect(bridge.invoke).toHaveBeenCalledTimes(1))
  expect(bridge.invoke).toHaveBeenCalledWith('email_send_single',
    expect.objectContaining({ recipientTagIds: [4], cc: ['manager@example.com'] }))
  await wrapper.get('[data-testid="confirmation-approve"]').trigger('click')
  await vi.waitFor(() => expect(bridge.invoke).toHaveBeenCalledTimes(2))
  expect(bridge.invoke.mock.calls.map(call => call[0])).toEqual(['email_send_single', 'confirm_send'])
})
