import { expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { i18n } from '../i18n'
import ActionDialog from './ActionDialog.vue'

const stubs = {
  VDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
  VCard: { template: '<section><slot /></section>' },
  VCardTitle: { template: '<h2><slot /></h2>' },
  VCardText: { template: '<div><slot /></div>' },
  VCardActions: { template: '<footer><slot /></footer>' },
  VSpacer: true,
  VBtn: { emits: ['click'], template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>' },
}

it('cancels or confirms a destructive action without a native browser popup', async () => {
  const wrapper = mount(ActionDialog, {
    props: { modelValue: true, title: 'Delete contact', message: 'Delete this contact?', destructive: true },
    global: { plugins: [i18n], stubs },
  })

  await wrapper.get('[data-testid="action-dialog-cancel"]').trigger('click')
  expect(wrapper.emitted('update:modelValue')).toEqual([[false]])

  await wrapper.setProps({ modelValue: true })
  await wrapper.get('[data-testid="action-dialog-confirm"]').trigger('click')
  expect(wrapper.emitted('confirm')).toHaveLength(1)
  expect(wrapper.emitted('update:modelValue')).toEqual([[false], [false]])
})
