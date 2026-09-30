import { ref } from 'vue'
import { defineStore } from 'pinia'
import {
  mdiAccountMultipleOutline,
  mdiArchiveArrowDownOutline,
  mdiCogOutline,
  mdiEmailEditOutline,
  mdiEmailMultipleOutline,
  mdiHistory,
} from '@mdi/js'

export type WorkspaceId = 'compose' | 'batch' | 'contacts' | 'archive' | 'records' | 'accounts'
export interface WorkspaceItem { id: WorkspaceId; labelKey: string; icon: string; bottom?: boolean }

export const useNavigationStore = defineStore('email-navigation', () => {
  const active = ref<WorkspaceId>('compose')
  const items: WorkspaceItem[] = [
    { id: 'compose', labelKey: 'nav.compose', icon: mdiEmailEditOutline },
    { id: 'batch', labelKey: 'nav.batch', icon: mdiEmailMultipleOutline },
    { id: 'contacts', labelKey: 'nav.contacts', icon: mdiAccountMultipleOutline },
    { id: 'archive', labelKey: 'nav.archive', icon: mdiArchiveArrowDownOutline },
    { id: 'records', labelKey: 'nav.records', icon: mdiHistory },
    { id: 'accounts', labelKey: 'nav.accounts', icon: mdiCogOutline },
  ]
  return { active, items }
})
