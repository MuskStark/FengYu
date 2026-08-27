<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useAccountStore } from '@/stores/account'

const account = useAccountStore()
const busy = ref(false)

onMounted(() => {
  if (!account.loaded) void account.load()
})

async function signIn() {
  if (busy.value) return
  busy.value = true
  try {
    await account.signIn()
  } finally {
    busy.value = false
  }
}

async function signOut() {
  if (busy.value) return
  busy.value = true
  try {
    await account.signOut()
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="account-page-scroll">
    <div class="cx-page">
      <h1 class="cx-page-title">{{ $t('account.title') }}</h1>
      <div class="cx-card account-profile-card">
        <div class="account-profile-avatar">
          <img v-if="account.user?.avatarUrl" :src="account.user.avatarUrl" alt="" />
          <span v-else>{{ account.initials }}</span>
        </div>
        <div class="cx-grow">
          <div class="account-profile-name">{{ account.displayName }}</div>
          <div v-if="account.user?.email" class="cx-muted">{{ account.user.email }}</div>
          <div class="account-profile-mode cx-muted">
            {{ account.isAuthenticated ? $t('account.signedIn') : $t('account.localAccount') }}
          </div>
        </div>
        <v-btn
          v-if="!account.isAuthenticated"
          color="primary"
          variant="tonal"
          :loading="busy"
          @click="signIn"
        >
          {{ $t('account.signIn') }}
        </v-btn>
        <v-btn
          v-else
          color="default"
          variant="tonal"
          :loading="busy"
          @click="signOut"
        >
          {{ $t('account.signOut') }}
        </v-btn>
      </div>
    </div>
  </div>
</template>

<style scoped>
.account-page-scroll { flex: 1 1 auto; min-height: 0; overflow-y: auto; }
.account-profile-card { display: flex; align-items: center; gap: 14px; }
.account-profile-avatar {
  width: 46px;
  height: 46px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: rgb(var(--v-theme-surface-container-highest));
  font-size: 18px;
  font-weight: 650;
}
.account-profile-avatar img { width: 100%; height: 100%; object-fit: cover; }
.account-profile-name { font-size: 16px; font-weight: 650; }
.account-profile-mode { margin-top: 4px; font-size: 12px; }
</style>
