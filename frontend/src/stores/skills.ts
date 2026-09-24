import { create } from 'zustand'
import { services } from '@/services'
import type { SkillSummary } from '@/services/types'

/** Runtime skills mirror (Codex-style progressive disclosure) — React port, backend-driven. */
interface SkillsState {
  skills: SkillSummary[]
  loaded: boolean
  load: () => Promise<void>
}

export const useSkillsStore = create<SkillsState>((set, get) => ({
  skills: [],
  loaded: false,
  load: async () => {
    if (get().loaded) return
    try {
      const skills = await services.skill.list()
      set({ skills, loaded: true })
    } catch {
      /* surfaces stay empty; the mention group simply hides */
    }
  },
}))
