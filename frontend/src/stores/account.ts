import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getAccountProvider, type AccountUser } from '@/auth/accountProvider'
import { i18n, localeRef } from '@/i18n'

export const useAccountStore = defineStore('account', () => {
  const user = ref<AccountUser | null>(null)
  const loaded = ref(false)
  const loading = ref(false)

  // Identity fallback order: username → the e-mail local part ("jane@x" → "jane") → a
  // localized generic name (no more hardcoded persona). Touching localeRef registers a
  // reactive dependency so the generic fallback tracks language switches.
  const displayName = computed(() => {
    const current = user.value
    if (current?.username) return current.username
    const emailLocal = current?.email?.split('@')[0]
    if (emailLocal) return emailLocal
    void localeRef.value
    return i18n.global.t('account.defaultName')
  })
  const initials = computed(() => displayName.value.trim().charAt(0).toUpperCase())
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

  async function signIn(options?: { signal?: AbortSignal }) {
    user.value = await getAccountProvider().signIn(options)
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
