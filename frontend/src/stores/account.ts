import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getAccountProvider, type AccountUser } from '@/auth/accountProvider'

export const useAccountStore = defineStore('account', () => {
  const user = ref<AccountUser | null>(null)
  const loaded = ref(false)
  const loading = ref(false)

  const displayName = computed(() => user.value?.username || 'Summer')
  const initials = computed(() => displayName.value.trim().charAt(0).toUpperCase() || 'S')
  const isAuthenticated = computed(() => user.value?.authenticated === true)

  async function load() {
    if (loading.value) return
    loading.value = true
    try {
      user.value = await getAccountProvider().getCurrentUser()
      loaded.value = true
    } finally {
      loading.value = false
    }
  }

  async function signIn() {
    user.value = await getAccountProvider().signIn()
    loaded.value = true
  }

  async function signOut() {
    await getAccountProvider().signOut()
    user.value = await getAccountProvider().getCurrentUser()
    loaded.value = true
  }

  return {
    user,
    loaded,
    loading,
    displayName,
    initials,
    isAuthenticated,
    load,
    signIn,
    signOut,
  }
})
