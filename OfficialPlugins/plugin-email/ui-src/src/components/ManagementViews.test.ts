import { beforeEach, expect, it, vi } from 'vitest'
import { config, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { i18n } from '../i18n'
import SendRecordsView from './SendRecordsView.vue'
import AccountSettingsView from './AccountSettingsView.vue'
import AddressBookTab from './AddressBookTab.vue'
import CollectTab from './CollectTab.vue'
import { useArchiveStore } from '../stores/archive'

const bridge = vi.hoisted(() => ({ invoke: vi.fn(), files: { open: vi.fn(), inputDirectory: vi.fn(), outputDirectory: vi.fn() } }))
vi.mock('../sdk', () => ({ ...bridge, actionable: (_error: unknown, action: string) => action }))

const stubs = {
  VBtn: { emits: ['click'], template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>' },
  VTextField: { props: ['modelValue', 'type', 'autocomplete'], emits: ['update:modelValue'], template: '<input v-bind="$attrs" :type="type" :autocomplete="autocomplete" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  VAlert: true, VCard: true, VCardTitle: true, VCardText: true, VCardActions: true, VCheckbox: true,
  VCheckboxBtn: true, VChip: true, VDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' }, VList: true, VListItem: true, VSelect: true,
  VSheet: true, VSpacer: true, VTable: { template: '<table><slot /></table>' },
}
function options(pinia = createPinia()) { setActivePinia(pinia); return { global: { plugins: [pinia, i18n], stubs } } }

beforeEach(() => { bridge.invoke.mockReset(); config.global.renderStubDefaultSlot = true })

it('renders structured send records without retry controls or raw JSON', async () => {
  bridge.invoke.mockResolvedValue({ success: true, tasks: [{ confirmationId: 'c1', status: 'PARTIAL_FAILED', mode: 'ATTACHMENT_TAGS', updatedAt: '2026-07-14T10:00:00Z' }], messages: [{ id: 1, confirmationId: 'c1', subject: 'Quarterly', status: 'FAILED', accountEmail: 'mail@example.com', errorMessage: 'Mailbox rejected' }] })
  const wrapper = mount(SendRecordsView, options())
  await wrapper.get('[data-testid="record-search"]').setValue('c1')
  await wrapper.get('[data-testid="record-search-submit"]').trigger('click')
  await vi.waitFor(() => expect(wrapper.text()).toContain('PARTIAL_FAILED'))
  expect(bridge.invoke).toHaveBeenCalledWith('email_send_records_query', expect.objectContaining({ query: 'c1', offset: 0 }))
  expect(wrapper.find('pre').exists()).toBe(false)
  expect(wrapper.text().toLowerCase()).not.toContain('retry')
})

it('keeps account passwords write-only and separates test from save', async () => {
  bridge.invoke.mockResolvedValue({ success: true, accounts: [] })
  const wrapper = mount(AccountSettingsView, options())
  expect(wrapper.get('input[type="password"]').attributes('autocomplete')).toBe('new-password')
  await wrapper.get('[data-testid="smtp-test"]').trigger('click')
  expect(bridge.invoke).toHaveBeenLastCalledWith('email_account_test', expect.any(Object))
  // SMTP and IMAP each have their own test button dispatching distinct methods.
  expect(wrapper.get('[data-testid="imap-test"]').element).toBeTruthy()
  await wrapper.get('[data-testid="imap-test"]').trigger('click')
  expect(bridge.invoke).toHaveBeenLastCalledWith('email_account_test_imap', expect.any(Object))
  await wrapper.get('[data-testid="account-save"]').trigger('click')
  // save dispatches email_account_save, then refreshes the list via email_accounts_list. The mocked
  // invoke drops the default params arg, so assert on the method name only for the refresh call.
  expect(bridge.invoke).toHaveBeenCalledWith('email_account_save', expect.any(Object))
  await vi.waitFor(() => expect(bridge.invoke).toHaveBeenLastCalledWith('email_accounts_list'))
})

it('keeps bulk contact actions separate from tag management', () => {
  bridge.invoke.mockResolvedValue({ success: true, contacts: [], tags: [] })
  const wrapper = mount(AddressBookTab, options())
  expect(wrapper.get('[data-testid="contact-bulk-tags"]').element).toBeTruthy()
  // tag manager is now a always-visible card, not a dialog opened by a button
  expect(wrapper.get('[data-testid="tag-manager-card"]').element).toBeTruthy()
  expect(wrapper.find('[data-testid="tag-manager-dialog"]').exists()).toBe(false)
  expect(wrapper.find('[data-testid="tag-manager-open"]').exists()).toBe(false)
})

it('folds contact tag pills beyond the first two', async () => {
  bridge.invoke.mockResolvedValue({ success: true, contacts: [{ id: 1, email: 'a@example.com', tagIds: [10, 20, 30] }], tags: [{ id: 10, name: '客户' }, { id: 20, name: 'VIP' }, { id: 30, name: '内部' }] })
  const wrapper = mount(AddressBookTab, options())
  // onMounted triggers an async store.load(); wait for the row to render before asserting the fold.
  await vi.waitFor(() => expect(wrapper.findAll('[data-testid="contact-row"]')).toHaveLength(1))
  expect(wrapper.get('[data-testid="contact-row"]').text()).toContain('+1')
})

it('filters the tag manager list by the search query', async () => {
  bridge.invoke.mockResolvedValue({ success: true, contacts: [], tags: [{ id: 1, name: '客户' }, { id: 2, name: 'VIP' }, { id: 3, name: '供应商' }] })
  const wrapper = mount(AddressBookTab, options())
  // onMounted triggers an async store.load(); wait for the tag rows to render before searching.
  await vi.waitFor(() => expect(wrapper.findAll('[data-testid="tag-manager-row"]')).toHaveLength(3))
  // VTextField is stubbed to render a real <input> with data-testid bound directly to it (v-bind="$attrs").
  await wrapper.get('input[data-testid="tag-search"]').setValue('vi')
  const rows = wrapper.findAll('[data-testid="tag-manager-row"]')
  expect(rows).toHaveLength(1)
  expect(rows[0].text()).toContain('VIP')
})

it('saves the contact with tags selected in the contact form', async () => {
  bridge.invoke.mockResolvedValue({ success: true, contacts: [], tags: [] })
  const pinia = createPinia(); setActivePinia(pinia)
  const wrapper = mount(AddressBookTab, { global: { plugins: [pinia, i18n], stubs: {
    ...stubs,
    VSelect: { props: ['modelValue'], emits: ['update:modelValue'], template: '<div v-bind="$attrs"><button data-testid="select-values" @click="$emit(\'update:modelValue\', [7])">select</button></div>' },
  } } })
  await wrapper.get('[data-testid="contact-tags"] [data-testid="select-values"]').trigger('click')
  await wrapper.get('[data-testid="contact-email"]').setValue('tagged@example.com')
  await wrapper.get('[data-testid="contact-save"]').trigger('click')
  await vi.waitFor(() => expect(bridge.invoke).toHaveBeenCalledWith('email_contact_save',
    expect.objectContaining({ email: 'tagged@example.com', tagIds: [7] })))
})

it('shows collection counters and archive pagination together', () => {
  const pinia = createPinia(); setActivePinia(pinia)
  useArchiveStore().updateProgress({ processed: 8, successful: 5, failed: 1, newArchived: 5, duplicates: 2 })
  const wrapper = mount(CollectTab, options(pinia))
  expect(wrapper.get('[data-testid="archive-progress"]').text()).toContain('8')
  expect(wrapper.get('[data-testid="archive-results"]').element).toBeTruthy()
  expect(wrapper.get('[data-testid="archive-next-page"]').element).toBeTruthy()
})
