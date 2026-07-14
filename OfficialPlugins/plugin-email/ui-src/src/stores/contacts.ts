import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { invoke } from '../sdk'

export interface Contact { id: number; email: string; nickname?: string; tagIds?: number[] }
export interface Tag { id: number; name: string }

export const useContactsStore = defineStore('email-contacts', () => {
  const contacts = ref<Contact[]>([])
  const tags = ref<Tag[]>([])
  const selectedTagIds = ref<number[]>([])
  const query = ref('')
  const recipientPreview = computed(() => [...new Set(contacts.value.filter(contact => selectedTagIds.value.some(id => contact.tagIds?.includes(id))).map(contact => contact.email))])
  async function load() {
    const [contactResult, tagResult] = await Promise.all([
      invoke<{ contacts: Contact[] }>('email_contacts_query', { query: query.value, limit: 100 }),
      invoke<{ tags: Tag[] }>('email_tags_list'),
    ])
    contacts.value = contactResult.contacts ?? []; tags.value = tagResult.tags ?? []
  }
  return { contacts, tags, selectedTagIds, query, recipientPreview, load }
})
