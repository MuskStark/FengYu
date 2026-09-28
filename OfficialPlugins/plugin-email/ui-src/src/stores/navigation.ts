import { create } from 'zustand'

export type WorkspaceId = 'compose' | 'batch' | 'contacts' | 'archive' | 'records' | 'accounts'
export interface WorkspaceItem { id: WorkspaceId; labelKey: string }

interface NavigationState {
  active: WorkspaceId
  items: WorkspaceItem[]
  setActive: (id: WorkspaceId) => void
}

/** Workspace ids in T3 tab-strip order (收集/撰写/批量/通讯录/记录/设置). */
export const WORKSPACE_ORDER: WorkspaceId[] = ['archive', 'compose', 'batch', 'contacts', 'records', 'accounts']

export const useNavigationStore = create<NavigationState>(set => ({
  active: 'compose',
  items: [
    { id: 'compose', labelKey: 'nav.compose' },
    { id: 'batch', labelKey: 'nav.batch' },
    { id: 'contacts', labelKey: 'nav.contacts' },
    { id: 'archive', labelKey: 'nav.archive' },
    { id: 'records', labelKey: 'nav.records' },
    { id: 'accounts', labelKey: 'nav.accounts' },
  ],
  setActive: active => set({ active }),
}))
