import { beforeEach, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { config } from '@vue/test-utils'
import { i18n } from '../i18n'
import ImportContactsDialog from './ImportContactsDialog.vue'

// Bridge that captures invoke calls and simulates file picking, mirroring the ComposeTab test pattern.
const bridge = vi.hoisted(() => ({
  invoke: vi.fn(),
  files: { open: vi.fn(), inputDirectory: vi.fn(), outputDirectory: vi.fn() },
}))
vi.mock('../sdk', () => ({
  ...bridge,
  actionable: (_error: unknown, action: string) => action,
  invoke: bridge.invoke,
  files: bridge.files,
}))

const stubs = {
  VDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
  VCard: { template: '<section><slot /></section>' },
  VCardTitle: { template: '<h2><slot /></h2>' },
  VCardText: { template: '<div><slot /></div>' },
  VCardActions: { template: '<footer><slot /></footer>' },
  VAlert: { template: '<div><slot /></div>' },
  VRadioGroup: { props: ['modelValue'], template: '<div><slot /></div>' },
  VRadio: true,
  VSpacer: true,
  VBtn: { emits: ['click'], template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>' },
}

beforeEach(() => {
  bridge.invoke.mockReset()
  bridge.files.open.mockReset()
  config.global.renderStubDefaultSlot = true
})

// jsdom lacks a real click() that downloads — stub the anchor + URL.createObjectURL so the
// template-download path can be asserted without a browser.
const download = vi.hoisted(() => ({ clicked: false, filename: '', url: '' }))
beforeEach(() => {
  download.clicked = false; download.filename = ''; download.url = ''
  vi.stubGlobal('URL', Object.assign(vi.fn(() => 'blob:template'), URL, {
    createObjectURL: vi.fn(() => 'blob:template'),
    revokeObjectURL: vi.fn(),
  }))
  const realCreateElement = document.createElement.bind(document)
  vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
    const el = realCreateElement(tag)
    if (tag === 'a') {
      el.click = () => { download.clicked = true; download.filename = (el as HTMLAnchorElement).download; download.url = (el as HTMLAnchorElement).href }
    }
    return el
  })
})

it('previews the import then commits with the selected duplicate mode', async () => {
  bridge.files.open.mockResolvedValueOnce({ id: 'ref_1', name: 'contacts.csv', kind: 'file', access: 'read', size: 100 })
  bridge.invoke
    .mockResolvedValueOnce({ success: true, preview: { rowsTotal: 5, rowsValid: 4, createdContacts: 3, mergedContacts: 1, skippedContacts: 0, createdTags: ['VIP'], errors: [{ row: 8, message: 'bad email' }] } })
    .mockResolvedValueOnce({ success: true, result: { created: 3, merged: 1, skipped: 0, tagsCreated: 1, tagsAssigned: 4, errors: [{ row: 8, message: 'bad email' }] } })

  const wrapper = mount(ImportContactsDialog, {
    props: { modelValue: true },
    global: { plugins: [i18n], stubs },
  })

  // Step 1 — pick a file and preview.
  await wrapper.get('[data-testid="import-choose-file"]').trigger('click')
  await wrapper.get('[data-testid="import-preview-btn"]').trigger('click')
  await vi.waitFor(() => expect(bridge.invoke).toHaveBeenCalledTimes(1))
  expect(bridge.invoke.mock.calls[0][0]).toBe('email_contacts_import_preview')
  expect(bridge.invoke.mock.calls[0][1]).toMatchObject({ duplicateMode: 'merge' })

  // Step 2 — preview rendered, then confirm.
  expect(wrapper.find('[data-testid="import-preview-step"]').exists()).toBe(true)
  await wrapper.get('[data-testid="import-confirm-btn"]').trigger('click')
  await vi.waitFor(() => expect(bridge.invoke).toHaveBeenCalledTimes(2))
  expect(bridge.invoke.mock.calls[1][0]).toBe('email_contacts_import_commit')
  // The selected duplicate mode is forwarded to commit, keeping the two calls symmetric.
  expect(bridge.invoke.mock.calls[1][1]).toMatchObject({ duplicateMode: 'merge' })

  // On success the dialog closes and emits 'imported' so the parent can reload.
  await vi.waitFor(() => {
    expect(wrapper.emitted('imported')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')).toContainEqual([false])
  })
})

it('prevents preview when no file is picked', async () => {
  const wrapper = mount(ImportContactsDialog, {
    props: { modelValue: true },
    global: { plugins: [i18n], stubs },
  })
  // No file picked → preview button click surfaces the "no file" error, no RPC.
  await wrapper.get('[data-testid="import-preview-btn"]').trigger('click')
  expect(bridge.invoke).not.toHaveBeenCalled()
})

it('passes skip mode through when the radio changes', async () => {
  bridge.files.open.mockResolvedValueOnce({ id: 'ref_2', name: 'c.xlsx', kind: 'file', access: 'read', size: 200 })
  bridge.invoke.mockResolvedValueOnce({ success: true, preview: { rowsTotal: 1, rowsValid: 1, createdContacts: 1, mergedContacts: 0, skippedContacts: 0, createdTags: [], errors: [] } })

  const wrapper = mount(ImportContactsDialog, {
    props: { modelValue: true },
    global: { plugins: [i18n], stubs },
  })
  await wrapper.get('[data-testid="import-choose-file"]').trigger('click')
  await wrapper.vm.$nextTick()
  // Switch duplicate mode by setting the radio group model value directly (VRadioGroup is stubbed).
  wrapper.findAllComponents(stubs.VRadioGroup)[0].vm.$emit('update:modelValue', 'skip')
  await wrapper.vm.$nextTick()
  await wrapper.get('[data-testid="import-preview-btn"]').trigger('click')
  await vi.waitFor(() => expect(bridge.invoke).toHaveBeenCalledTimes(1))
  expect(bridge.invoke.mock.calls[0][1]).toMatchObject({ duplicateMode: 'skip' })
})

it('downloads a CSV template named contacts-template.csv on click', async () => {
  const wrapper = mount(ImportContactsDialog, {
    props: { modelValue: true },
    global: { plugins: [i18n], stubs },
  })
  await wrapper.get('[data-testid="import-download-template"]').trigger('click')
  expect(download.clicked).toBe(true)
  expect(download.filename).toBe('contacts-template.csv')
})
