import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * Selected sidebar nav key. Relaxed to `string` so backend-driven category ids
 * (lowercase, e.g. "dev"/"text"/"ai" from /api/plugin-categories) plus the
 * pseudo-categories "all" and "favorites" all fit without an enum to maintain.
 */
export type NavCategory = string

export const useNavStore = defineStore('nav', () => {
  const category = ref<NavCategory>('all')
  function setCategory(c: NavCategory) {
    category.value = c
  }
  return { category, setCategory }
})
