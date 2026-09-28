import { create } from 'zustand'
import type { FileRef } from '@infinia/plugin-sdk'
import { sanitizeEmailHtml } from '../richText'

export type ComposeMode = 'DIRECT' | 'CONTACT_TAGS'
export interface SummaryRow { label: string; value: string; group?: string }
export interface Confirmation { confirmationId: string; summary: SummaryRow[]; expiresAt: string; approveMethod?: string; rejectMethod?: string }
export interface SendResult { status: string; succeeded: number; failed: number; failedRecipients?: string[] }

export const DRAFT_KEY = 'fengyu.email.compose.v1'
const memoryDraft = new Map<string, string>()

function draftStorage(): Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> {
  try {
    if (window.localStorage) return window.localStorage
  } catch { /* non-persistent test/privacy context */ }
  return {
    getItem: key => memoryDraft.get(key) ?? null,
    setItem: (key, value) => { memoryDraft.set(key, value) },
    removeItem: key => { memoryDraft.delete(key) },
  }
}

export function normalizeAddresses(values: string[]): string[] {
  const normalized = new Map<string, string>()
  for (const value of values ?? []) {
    const trimmed = value?.trim()
    if (trimmed) normalized.set(trimmed.toLowerCase(), trimmed.toLowerCase())
  }
  return [...normalized.values()].sort()
}

export interface ComposeState {
  mode: ComposeMode
  recipientTagIds: number[]
  to: string[]
  cc: string[]
  subject: string
  plainText: string
  htmlText: string
  attachments: FileRef[]
  confirmation?: Confirmation
  sendResult?: SendResult
  draftSavedAt?: string
  update: (partial: Partial<ComposeState>) => void
  setConfirmation: (value: Confirmation) => void
  clearTransient: () => void
  persistDraft: () => void
  restoreDraft: () => void
}

export const initialComposeState: Omit<ComposeState, 'update' | 'setConfirmation' | 'clearTransient' | 'persistDraft' | 'restoreDraft'> = {
  mode: 'DIRECT',
  recipientTagIds: [],
  to: [],
  cc: [],
  subject: '',
  plainText: '',
  htmlText: '',
  attachments: [],
  confirmation: undefined,
  sendResult: undefined,
  draftSavedAt: undefined,
}

export const useComposeStore = create<ComposeState>((set, get) => ({
  ...initialComposeState,
  update: partial => set(partial),
  setConfirmation: confirmation => set({ confirmation }),
  clearTransient: () => set({ confirmation: undefined, sendResult: undefined }),
  persistDraft: () => {
    const state = get()
    const draft = {
      mode: state.mode,
      recipientTagIds: state.recipientTagIds,
      to: selectNormalizedTo(state),
      cc: selectNormalizedCc(state),
      subject: state.subject,
      htmlText: sanitizeEmailHtml(state.htmlText),
      plainText: state.plainText,
    }
    draftStorage().setItem(DRAFT_KEY, JSON.stringify(draft))
    set({ draftSavedAt: new Date().toISOString() })
  },
  restoreDraft: () => {
    const serialized = draftStorage().getItem(DRAFT_KEY)
    if (!serialized) return
    try {
      const draft = JSON.parse(serialized) as Partial<{
        mode: ComposeMode; recipientTagIds: number[]; to: string[]; cc: string[];
        subject: string; htmlText: string; plainText: string
      }>
      set({
        mode: draft.mode === 'CONTACT_TAGS' ? 'CONTACT_TAGS' : 'DIRECT',
        recipientTagIds: Array.isArray(draft.recipientTagIds) ? draft.recipientTagIds : [],
        to: Array.isArray(draft.to) ? draft.to : [],
        cc: Array.isArray(draft.cc) ? draft.cc : [],
        subject: draft.subject ?? '',
        htmlText: sanitizeEmailHtml(draft.htmlText ?? ''),
        plainText: draft.plainText ?? '',
      })
    } catch { draftStorage().removeItem(DRAFT_KEY) }
  },
}))

export const selectNormalizedTo = (state: Pick<ComposeState, 'to'>): string[] => normalizeAddresses(state.to)
export const selectNormalizedCc = (state: Pick<ComposeState, 'to' | 'cc'>): string[] => {
  const primary = new Set(selectNormalizedTo(state))
  return normalizeAddresses(state.cc).filter(address => !primary.has(address))
}
export const selectConfirmationSummary = (state: Pick<ComposeState, 'confirmation'>): string => state.confirmation
  ? `${state.confirmation.summary.map(row => `${row.label}: ${row.value}`).join(' · ')} · expires ${state.confirmation.expiresAt}`
  : ''
