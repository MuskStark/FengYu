import { ref } from 'vue'
import { defineStore } from 'pinia'

export interface Progress { processed: number; successful: number; failed: number; newArchived: number; duplicates: number }
export const useArchiveStore = defineStore('email-archive', () => {
  const offset = ref(0), limit = ref(25)
  const messages = ref<Record<string, unknown>[]>([])
  const progress = ref<Progress>({ processed: 0, successful: 0, failed: 0, newArchived: 0, duplicates: 0 })
  function nextPage() { offset.value += limit.value }
  function previousPage() { offset.value = Math.max(0, offset.value - limit.value) }
  function updateProgress(value: Partial<Progress>) { progress.value = { ...progress.value, ...value } }
  return { offset, limit, messages, progress, nextPage, previousPage, updateProgress }
})
