import { beforeEach, expect, it, vi } from 'vitest'
import { config, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import BatchTab from '../components/BatchTab.vue'
import { useAccountsStore } from './accounts'
import { useComposeStore } from './compose'

const bridge = vi.hoisted(() => ({
  invoke: vi.fn(),
  files: { open: vi.fn(), inputDirectory: vi.fn(), outputDirectory: vi.fn() },
}))
vi.mock('../sdk', () => ({ ...bridge, actionable: (error: unknown, action: string) => `${action}: ${String(error)}` }))

beforeEach(() => { bridge.invoke.mockReset(); config.global.renderStubDefaultSlot = true })

it('renders the Worker confirmation rows and only sends after visual confirmation', async () => {
  bridge.invoke
    .mockResolvedValueOnce({ success: true, summary: 'ready', confirmation: { confirmationId: 'c1', expiresAt: '2026-07-14T12:00:00Z', summary: [{ label: 'Recipients', value: '2' }] } })
    .mockResolvedValueOnce({ success: true, summary: 'sent', send: { status: 'SENT', succeeded: 2, failed: 0, failedRecipients: [] } })
  const pinia=createPinia(); setActivePinia(pinia)
  const accounts = useAccountsStore(); accounts.selectedId = 7
  const wrapper = mount(BatchTab, { global: { plugins:[pinia], stubs: {
    VBtn: { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
    VDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
    VAlert: true, VBtnToggle: true, VCard: true, VCardTitle: true, VCardText: true, VCardActions: true,
    VList: true, VListItem: true, VSelect: true, VSpacer: true, VTextarea: true, VTextField: true,
  } }, attachTo: document.body })
  const review = wrapper.findAll('button').find(button => button.text().includes('Review batch'))!
  await review.trigger('click'); await vi.waitFor(() => expect(bridge.invoke).toHaveBeenCalledTimes(1))
  await vi.waitFor(() => expect(useComposeStore().confirmationSummary).toContain('Recipients: 2'))
  expect(bridge.invoke).toHaveBeenCalledTimes(1)
  const confirm = wrapper.findAll('button').find(button => button.text().includes('Confirm batch'))!
  await confirm.trigger('click'); await vi.waitFor(() => expect(wrapper.text()).toContain('succeeded 2'))
  expect(bridge.invoke.mock.calls.map(call => call[0])).toEqual(['email_send_batch', 'confirm_send'])
  wrapper.unmount()
})
