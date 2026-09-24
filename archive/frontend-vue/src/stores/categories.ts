import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { CategoryDescriptor } from '@/api/types'

/**
 * Backend-driven sidebar categories. The sidebar renders from this store instead
 * of a hardcoded list, so adding a category to the `ToolCategory` enum surfaces
 * it in the UI automatically (the `/api/plugin-categories` endpoint is the
 * source of truth).
 */
export const useCategoriesStore = defineStore('categories', () => {
  const categories = ref<CategoryDescriptor[]>([])
  const loaded = ref(false)

  async function load() {
    categories.value = await api.getPluginCategories()
    loaded.value = true
  }

  return { categories, loaded, load }
})
