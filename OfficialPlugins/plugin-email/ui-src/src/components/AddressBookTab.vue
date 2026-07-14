<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useContactsStore, type Contact } from '../stores/contacts'
import { actionable, invoke } from '../sdk'
import ActionDialog from './ActionDialog.vue'

const { t } = useI18n(), store = useContactsStore()
const contactId = ref<number>(), email = ref(''), nickname = ref(''), tagName = ref('')
const contactTagIds = ref<number[]>([])
const selectedContacts = ref<number[]>([]), assignTagIds = ref<number[]>([]), error = ref(''), tagDialog = ref(false)
const pendingDelete = ref<{ kind: 'contact' | 'tag'; id: number }>()
const deleteMessage = computed(() => pendingDelete.value?.kind === 'tag'
  ? t('contacts.tagDeleteConfirm') : t('contacts.deleteConfirm'))
const deleteTitle = computed(() => pendingDelete.value?.kind === 'tag'
  ? t('contacts.tagDeleteAction') : t('contacts.deleteAction'))
onMounted(() => store.load().catch(value => { error.value = actionable(value, t('contacts.loadAction')) }))
function edit(item: Contact) { contactId.value = item.id; email.value = item.email; nickname.value = item.nickname ?? ''; contactTagIds.value = [...(item.tagIds ?? [])] }
function reset() { contactId.value = undefined; email.value = ''; nickname.value = ''; contactTagIds.value = [] }
async function run(action: string, task: () => Promise<unknown>) {
  try { error.value = ''; await task(); await store.load() } catch (value) { error.value = actionable(value, action) }
}
const saveContact = () => run(t('contacts.saveAction'), async () => { await invoke('email_contact_save', {
  id: contactId.value, email: email.value, nickname: nickname.value, tagIds: [...contactTagIds.value],
}); reset() })
const deleteContact = (id: number) => { pendingDelete.value = { kind: 'contact', id } }
const addTag = () => run(t('contacts.tagSaveAction'), async () => { await invoke('email_tag_save', { name: tagName.value }); tagName.value = '' })
const deleteTag = (id: number) => { pendingDelete.value = { kind: 'tag', id } }
const assign = () => run(t('contacts.assignAction'), () => invoke('email_tags_assign', { contactIds: selectedContacts.value, tagIds: assignTagIds.value }))
function confirmDelete(): void {
  const target = pendingDelete.value
  pendingDelete.value = undefined
  if (!target) return
  if (target.kind === 'contact') void run(t('contacts.deleteAction'), () => invoke('email_contact_delete', { id: target.id }))
  else void run(t('contacts.tagDeleteAction'), () => invoke('email_tag_delete', { id: target.id }))
}
</script>

<template>
  <section class="panel-grid">
    <v-card class="surface" variant="flat"><v-card-title>{{ t('contacts.title') }}</v-card-title><v-card-text>
      <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
      <div class="inline-fields"><v-text-field v-model="store.query" data-testid="contact-search" :label="t('common.search')" @keyup.enter="store.load" /><v-select v-model="store.selectedTagIds" :items="store.tags" item-title="name" item-value="id" multiple chips :label="t('contacts.filterTag')" /><v-btn @click="store.load">{{ t('common.search') }}</v-btn></div>
      <v-list lines="two"><v-list-item v-for="item in store.contacts" :key="item.id" :title="item.nickname || item.email" :subtitle="item.email" @click="edit(item)"><template #prepend><v-checkbox-btn v-model="selectedContacts" :value="item.id" @click.stop /></template><template #append><v-btn variant="text" color="error" @click.stop="deleteContact(item.id)">{{ t('common.delete') }}</v-btn></template></v-list-item></v-list>
      <div data-testid="contact-bulk-tags" class="inline-fields mt-4"><v-select v-model="assignTagIds" :items="store.tags" item-title="name" item-value="id" multiple chips :label="t('contacts.assignTags')" /><v-btn :disabled="!selectedContacts.length" @click="assign">{{ t('contacts.assignTags') }}</v-btn></div>
    </v-card-text></v-card>
    <v-card class="surface" variant="flat"><v-card-title>{{ contactId ? t('contacts.editContact') : t('contacts.newContact') }}</v-card-title><v-card-text><v-text-field v-model="email" data-testid="contact-email" :label="t('contacts.email')" /><v-text-field v-model="nickname" :label="t('contacts.name')" /><v-select v-model="contactTagIds" data-testid="contact-tags" :items="store.tags" item-title="name" item-value="id" multiple chips :label="t('contacts.assignTags')" /></v-card-text><v-card-actions><v-btn v-if="contactId" @click="reset">{{ t('contacts.newContact') }}</v-btn><v-btn data-testid="tag-manager-open" variant="text" @click="tagDialog = true">{{ t('contacts.manageTags') }}</v-btn><v-spacer /><v-btn data-testid="contact-save" color="primary" @click="saveContact">{{ t('common.save') }}</v-btn></v-card-actions></v-card>
  </section>
  <v-dialog v-model="tagDialog" max-width="520"><v-card data-testid="tag-manager-dialog"><v-card-title>{{ t('contacts.manageTags') }}</v-card-title><v-card-text><v-chip v-for="tag in store.tags" :key="tag.id" closable class="mr-2 mb-2" @click:close="deleteTag(tag.id)">{{ tag.name }}</v-chip><div class="inline-fields"><v-text-field v-model="tagName" :label="t('contacts.newTag')" /><v-btn @click="addTag">{{ t('common.add') }}</v-btn></div></v-card-text><v-card-actions><v-spacer /><v-btn @click="tagDialog = false">{{ t('common.close') }}</v-btn></v-card-actions></v-card></v-dialog>
  <ActionDialog :model-value="Boolean(pendingDelete)" :title="deleteTitle" :message="deleteMessage"
    :confirm-text="t('common.delete')" destructive @update:model-value="value => { if (!value) pendingDelete = undefined }" @confirm="confirmDelete" />
</template>
