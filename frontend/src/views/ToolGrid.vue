<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { usePluginsStore } from '@/stores/plugins'
import { useNavStore } from '@/stores/nav'
import type { PluginDescriptor, PluginSource } from '@/api/types'

const store = usePluginsStore()
const nav = useNavStore()
const router = useRouter()

onMounted(() => {
  if (store.plugins.length === 0) void store.load()
})

const filtered = computed<PluginDescriptor[]>(() => {
  const cat = nav.category
  if (cat === 'all') return store.plugins
  if (cat === 'favorites') return store.plugins.filter((p) => store.favorites.has(p.id))
  return store.plugins.filter((p) => p.category.toLowerCase() === cat)
})

function open(p: PluginDescriptor) {
  void router.push(`/plugin/${encodeURIComponent(p.id)}`)
}

function initials(name: string): string {
  return name.trim().charAt(0).toUpperCase() || '?'
}

function sourceLabelKey(source: PluginSource): string {
  return source === 'OFFICIAL' ? 'source.official' : 'source.third_party'
}
</script>

<template>
  <v-container>
    <h1 class="text-h5 mb-4">{{ $t('grid.title') }}</h1>

    <div v-if="store.loading" class="text-medium-emphasis">{{ $t('grid.loading') }}</div>
    <v-alert v-else-if="store.error" type="error" variant="tonal" class="mb-4">{{ store.error }}</v-alert>
    <div v-else-if="filtered.length === 0" class="text-medium-emphasis">{{ $t('grid.empty') }}</div>

    <v-row>
      <v-col v-for="p in filtered" :key="p.id" cols="12" sm="6" md="4" lg="3">
        <v-card variant="tonal" rounded="lg" class="h-100" @click="open(p)">
          <v-card-item>
            <template #prepend>
              <v-avatar color="primary" rounded="md" size="40">
                <span class="font-weight-bold">{{ initials(p.name) }}</span>
              </v-avatar>
            </template>
            <v-card-title class="text-body-1 d-flex align-center flex-wrap ga-2">
              {{ p.name }}
              <v-chip
                size="x-small"
                :color="p.source === 'OFFICIAL' ? 'primary' : 'default'"
                variant="outlined"
              >{{ $t(sourceLabelKey(p.source)) }}</v-chip>
              <v-chip v-if="p.supportsAi" size="x-small" color="success" variant="outlined">
                {{ $t('badge.ai') }}
              </v-chip>
            </v-card-title>
            <v-card-subtitle class="text-wrap">{{ p.description }}</v-card-subtitle>
          </v-card-item>

          <v-card-actions>
            <v-spacer />
            <v-btn
              :icon="store.favorites.has(p.id) ? 'mdi-star' : 'mdi-star-outline'"
              :color="store.favorites.has(p.id) ? 'warning' : 'default'"
              variant="text"
              size="small"
              :title="$t('grid.toggleFavorite')"
              @click.stop="store.toggleFavorite(p.id)"
            />
          </v-card-actions>
        </v-card>
      </v-col>
    </v-row>
  </v-container>
</template>
