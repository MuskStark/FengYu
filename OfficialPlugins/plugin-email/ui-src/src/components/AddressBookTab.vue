<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useContactsStore, type Contact } from '../stores/contacts'
import { actionable, checked, rpc } from '../sdk'
import ActionDialog from './ActionDialog.vue'
import ImportContactsDialog from './ImportContactsDialog.vue'

const { t } = useI18n(), store = useContactsStore()
const contactId = ref<number>(), email = ref(''), nickname = ref(''), notes = ref(''), tagName = ref('')
const contactTagIds = ref<number[]>([])
const selectedContacts = ref<number[]>([]), assignTagIds = ref<number[]>([]), error = ref(''), tagQuery = ref('')
const showImport = ref(false)
const pendingDelete = ref<{ kind: 'contact' | 'tag'; id: number }>()
const filteredTags = computed(() => {
  const q = tagQuery.value.trim().toLowerCase()
  return q ? store.tags.filter(tag => tag.name.toLowerCase().includes(q)) : store.tags
})
const deleteMessage = computed(() => pendingDelete.value?.kind === 'tag'
  ? t('contacts.tagDeleteConfirm') : t('contacts.deleteConfirm'))
const deleteTitle = computed(() => pendingDelete.value?.kind === 'tag'
  ? t('contacts.tagDeleteAction') : t('contacts.deleteAction'))
onMounted(() => store.load().catch(value => { error.value = actionable(value, t('contacts.loadAction')) }))
function edit(item: Contact) { contactId.value = item.id; email.value = item.email; nickname.value = item.nickname ?? ''; notes.value = item.notes ?? ''; contactTagIds.value = [...(item.tagIds ?? [])] }
function reset() { contactId.value = undefined; email.value = ''; nickname.value = ''; notes.value = ''; contactTagIds.value = [] }
// NOTE: helper named `tagLabel` (not `tagName`) to avoid colliding with the `tagName` ref
// above, which is bound to the tag-manager card's new-tag input.
const initials = (item: Contact) => (item.nickname || item.email || '?').charAt(0)
function tagLabel(id: number): string { return store.tags.find(tag => tag.id === id)?.name ?? '' }
function visibleTags(item: Contact): number[] { return (item.tagIds ?? []).slice(0, 2) }
function hiddenTagCount(item: Contact): number { return Math.max(0, (item.tagIds ?? []).length - 2) }
async function run(action: string, task: () => Promise<unknown>) {
  try { error.value = ''; await task(); await store.load() } catch (value) { error.value = actionable(value, action) }
}
const saveContact = () => run(t('contacts.saveAction'), async () => { await checked(rpc.email_contact_save({
  id: contactId.value, email: email.value, nickname: nickname.value, notes: notes.value, tagIds: [...contactTagIds.value],
})); reset() })
const deleteContact = (id: number) => { pendingDelete.value = { kind: 'contact', id } }
const addTag = () => run(t('contacts.tagSaveAction'), async () => { await checked(rpc.email_tag_save({ name: tagName.value })); tagName.value = '' })
const deleteTag = (id: number) => { pendingDelete.value = { kind: 'tag', id } }
const assign = () => run(t('contacts.assignAction'), () => checked(rpc.email_tags_assign({ contactIds: selectedContacts.value, tagIds: assignTagIds.value })))
function confirmDelete(): void {
  const target = pendingDelete.value
  pendingDelete.value = undefined
  if (!target) return
  if (target.kind === 'contact') void run(t('contacts.deleteAction'), () => checked(rpc.email_contact_delete({ id: target.id })))
  else void run(t('contacts.tagDeleteAction'), () => checked(rpc.email_tag_delete({ id: target.id })))
}
</script>

<template>
  <section class="panel-grid contact-layout">
    <v-card class="fy-surface contact-list-card" variant="flat"><v-card-title>{{ t('contacts.title') }}</v-card-title><v-card-text>
      <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
      <div class="inline-fields"><v-text-field v-model="store.query" data-testid="contact-search" hide-details :label="t('common.search')" @keyup.enter="store.load" /><v-select v-model="store.selectedTagIds" :items="store.tags" item-title="name" item-value="id" multiple chips hide-details :label="t('contacts.filterTag')" /><v-btn @click="store.load">{{ t('common.search') }}</v-btn></div>
      <div class="mt-4"><v-btn data-testid="contact-import" variant="outlined" @click="showImport = true">{{ t('contacts.importButton') }}</v-btn></div>
      <div class="contact-list-scroll" data-testid="contact-list-scroll">
        <div class="contact-row" v-for="item in store.contacts" :key="item.id" data-testid="contact-row" @click="edit(item)">
          <v-checkbox-btn v-model="selectedContacts" :value="item.id" @click.stop />
          <div class="contact-avatar">{{ initials(item) }}</div>
          <div class="contact-row__main">
            <div class="contact-row__name">{{ item.nickname || item.email }}</div>
            <div class="contact-row__email">{{ item.email }}</div>
            <div class="tag-overflow" v-if="(item.tagIds ?? []).length">
              <v-chip v-for="id in visibleTags(item)" :key="id" size="x-small" label>{{ tagLabel(id) }}</v-chip>
              <v-chip v-if="hiddenTagCount(item) > 0" size="x-small" label :title="(item.tagIds ?? []).slice(2).map(tagLabel).join(', ')">
                {{ t('contacts.tagsMore', { count: hiddenTagCount(item) }) }}
              </v-chip>
            </div>
          </div>
          <v-btn variant="text" color="error" size="small" @click.stop="deleteContact(item.id)">{{ t('common.delete') }}</v-btn>
        </div>
      </div>
      <div data-testid="contact-bulk-tags" class="inline-fields mt-4"><v-select v-model="assignTagIds" :items="store.tags" item-title="name" item-value="id" multiple chips hide-details :label="t('contacts.assignTags')" /><v-btn :disabled="!selectedContacts.length" @click="assign">{{ t('contacts.assignTags') }}</v-btn></div>
    </v-card-text></v-card>
    <div class="d-flex flex-column ga-4 contact-side-column">
      <v-card class="fy-surface" variant="flat"><v-card-title>{{ contactId ? t('contacts.editContact') : t('contacts.newContact') }}</v-card-title><v-card-text>
        <v-text-field v-model="email" data-testid="contact-email" :label="t('contacts.email')" />
        <v-text-field v-model="nickname" :label="t('contacts.name')" />
        <v-select v-model="contactTagIds" data-testid="contact-tags" :items="store.tags" item-title="name" item-value="id" multiple chips :label="t('contacts.assignTags')" />
        <v-textarea v-model="notes" data-testid="contact-notes" :label="t('contacts.notes')" rows="2" auto-grow />
      </v-card-text><v-card-actions><v-btn v-if="contactId" variant="text" @click="reset">{{ t('contacts.newContact') }}</v-btn><v-spacer /><v-btn data-testid="contact-save" color="primary" @click="saveContact">{{ t('common.save') }}</v-btn></v-card-actions></v-card>
      <v-card class="fy-surface" variant="flat" data-testid="tag-manager-card"><v-card-title>{{ t('contacts.manageTags') }}</v-card-title><v-card-text>
        <v-text-field v-model="tagQuery" data-testid="tag-search" density="compact" :label="t('contacts.tagSearch')" />
        <div class="tag-manager-list">
          <div class="tag-manager-row" v-for="tag in filteredTags" :key="tag.id" data-testid="tag-manager-row">
            <span>{{ tag.name }}</span><v-btn variant="text" color="error" size="small" @click="deleteTag(tag.id)">{{ t('common.delete') }}</v-btn>
          </div>
        </div>
        <div class="inline-fields mt-4"><v-text-field v-model="tagName" hide-details :label="t('contacts.newTag')" /><v-btn @click="addTag">{{ t('common.add') }}</v-btn></div>
      </v-card-text></v-card>
    </div>
  </section>
  <ActionDialog :model-value="Boolean(pendingDelete)" :title="deleteTitle" :message="deleteMessage"
    :confirm-text="t('common.delete')" destructive @update:model-value="value => { if (!value) pendingDelete = undefined }" @confirm="confirmDelete" />
  <ImportContactsDialog v-model="showImport" @imported="store.load" />
</template>
