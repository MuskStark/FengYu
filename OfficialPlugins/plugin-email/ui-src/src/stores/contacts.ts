import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { checked, rpc } from '../sdk'

export interface Contact { id: number; email: string; nickname?: string; notes?: string; tagIds?: number[] }
export interface Tag { id: number; name: string }

export const useContactsStore = defineStore('email-contacts', () => {
  const contacts = ref<Contact[]>([])
  const tags = ref<Tag[]>([])
  const selectedTagIds = ref<number[]>([])
  const query = ref('')
  const recipientPreview = computed(() => [...new Set(contacts.value.filter(contact => selectedTagIds.value.some(id => contact.tagIds?.includes(id))).map(contact => contact.email))])
  async function load() {
    const [contactResult, tagResult] = await Promise.all([
      checked(rpc.email_contacts_query({ query: query.value, tagIds: selectedTagIds.value, limit: 100 })),
      checked(rpc.email_tags_list({})),
    ])
    contacts.value = contactResult.contacts ?? []; tags.value = tagResult.tags ?? []
  }
  return { contacts, tags, selectedTagIds, query, recipientPreview, load }
})
