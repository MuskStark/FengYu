import { defineStore } from 'pinia'
import { ref } from 'vue'

export type NavCategory =
  | 'all'
  | 'text'
  | 'image'
  | 'dev'
  | 'net'
  | 'other'
  | 'favorites'

export const useNavStore = defineStore('nav', () => {
  const category = ref<NavCategory>('all')
  function setCategory(c: NavCategory) {
    category.value = c
  }
  return { category, setCategory }
})
