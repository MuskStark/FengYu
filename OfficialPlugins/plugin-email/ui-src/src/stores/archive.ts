import { create } from 'zustand'

export interface Progress { processed: number; successful: number; failed: number; newArchived: number; duplicates: number }

export interface ArchiveState {
  offset: number
  limit: number
  messages: Record<string, unknown>[]
  progress: Progress
  update: (partial: Partial<ArchiveState>) => void
  nextPage: () => void
  previousPage: () => void
  updateProgress: (value: Partial<Progress>) => void
}

export const useArchiveStore = create<ArchiveState>(set => ({
  offset: 0,
  limit: 25,
  messages: [],
  progress: { processed: 0, successful: 0, failed: 0, newArchived: 0, duplicates: 0 },
  update: partial => set(partial),
  nextPage: () => set(state => ({ offset: state.offset + state.limit })),
  previousPage: () => set(state => ({ offset: Math.max(0, state.offset - state.limit) })),
  updateProgress: value => set(state => ({ progress: { ...state.progress, ...value } })),
}))
