<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { usePluginStore } from '@/stores/pluginStore'
import type { StoreSourceType } from '@/api/types'
import UnifiedSourceBadge from '@/components/store/UnifiedSourceBadge.vue'

const { t } = useI18n()
const store = usePluginStore()

const dialog = ref(false)
const name = ref('')
const type = ref<StoreSourceType>('CLAUDE')
const url = ref('')
const error = ref<string | null>(null)

async function submit() {
  error.value = null
  try {
    await store.addSource(name.value, type.value, url.value)
    dialog.value = false
    name.value = ''
    url.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}
</script>

<template>
  <div>
    <div class="d-flex align-center ga-2 mb-3">
      <v-btn variant="tonal" prepend-icon="mdi-plus" @click="dialog = true">
        {{ t('market.store.addSource') }}
      </v-btn>
    </div>

    <v-list v-if="store.sources.length" density="compact">
      <v-list-item v-for="s in store.sources" :key="s.origin" :title="s.name" :subtitle="s.catalogUrl">
        <template #prepend><UnifiedSourceBadge :type="s.sourceType" /></template>
        <template #append>
          <v-btn icon="mdi-refresh" size="small" variant="text"
                 @click="store.refreshSource(s.origin)" />
          <v-btn icon="mdi-delete" size="small" variant="text"
                 @click="store.deleteSource(s.origin)" />
        </template>
      </v-list-item>
    </v-list>
    <p v-else class="text-medium-emphasis">{{ t('market.store.noSources') }}</p>

    <v-dialog v-model="dialog" max-width="500">
      <v-card :title="t('market.store.addSource')">
        <v-card-text>
          <v-text-field v-model="name" :label="t('market.store.sourceName')" />
          <v-select v-model="type" :items="['FENGYU','CLAUDE','CODEX']"
                    :label="t('market.store.sourceType')" />
          <v-text-field v-model="url" :label="t('market.store.catalogUrl')"
                        placeholder="https://…" />
          <v-alert v-if="error" type="error" density="compact">{{ error }}</v-alert>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="dialog = false">Cancel</v-btn>
          <v-btn variant="tonal" @click="submit">OK</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>
