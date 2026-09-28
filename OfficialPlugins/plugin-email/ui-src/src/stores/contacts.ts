import { create } from 'zustand'
import { checked, rpc } from '../sdk'

export interface Contact { id: number; email: string; nickname?: string; notes?: string; tagIds?: number[] }
export interface Tag { id: number; name: string }

export interface ContactsState {
  contacts: Contact[]
  tags: Tag[]
  selectedTagIds: number[]
  query: string
  update: (partial: Partial<ContactsState>) => void
  load: () => Promise<void>
}

export const useContactsStore = create<ContactsState>((set, get) => ({
  contacts: [],
  tags: [],
  selectedTagIds: [],
  query: '',
  update: partial => set(partial),
  load: async () => {
    const [contactResult, tagResult] = await Promise.all([
      checked(rpc.email_contacts_query({ query: get().query, tagIds: get().selectedTagIds, limit: 100 })),
      checked(rpc.email_tags_list({})),
    ])
    set({ contacts: contactResult.contacts ?? [], tags: tagResult.tags ?? [] })
  },
}))

export const selectRecipientPreview = (state: ContactsState): string[] =>
  [...new Set(state.contacts.filter(contact => state.selectedTagIds.some(id => contact.tagIds?.includes(id))).map(contact => contact.email))]
