<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { usePluginsStore } from '@/stores/plugins'
import { useCategoriesStore } from '@/stores/categories'
import type { PluginDescriptor } from '@/api/types'

const store = usePluginsStore()
const cats = useCategoriesStore()
const router = useRouter()
const { t } = useI18n()

const category = ref('all')
const query = ref('')

onMounted(() => {
  if (store.plugins.length === 0) void store.load()
  if (!cats.loaded) void cats.load()
})

const chips = computed(() => [
  { key: 'all', label: t('sidebar.all') },
  ...cats.categories.map((c) => ({ key: c.id, label: t(c.labelKey) })),
  { key: 'favorites', label: t('sidebar.favorites') },
])

const filtered = computed<PluginDescriptor[]>(() => {
  let list = store.plugins
  if (category.value === 'favorites') list = list.filter((p) => store.favorites.has(p.id))
  else if (category.value !== 'all') list = list.filter((p) => p.category.toLowerCase() === category.value)
  const q = query.value.trim().toLowerCase()
  if (q) list = list.filter((p) => p.name.toLowerCase().includes(q) || p.description.toLowerCase().includes(q))
  return list
})

function open(p: PluginDescriptor) {
  void router.push(`/plugin/${encodeURIComponent(p.id)}`)
}
function initials(name: string): string {
  return name.trim().charAt(0).toUpperCase() || '?'
}
</script>

<template>
  <div style="flex: 1 1 auto; min-height: 0; overflow-y: auto">
    <div class="cx-page" style="max-width: 900px">
      <h1 class="cx-page-title">{{ $t('grid.title') }}</h1>

      <!-- Search + category chips -->
      <div style="display: flex; flex-direction: column; gap: 12px; margin-bottom: 20px">
        <div class="cx-input-wrap" style="max-width: 340px">
          <i class="mdi sm mdi-magnify" style="position: absolute; left: 11px; color: rgb(var(--v-theme-secondary)); pointer-events: none" />
          <input v-model="query" class="cx-input" style="padding-left: 34px" :placeholder="$t('grid.search')" />
        </div>
        <div class="cx-row" style="gap: 6px; flex-wrap: wrap">
          <button
            v-for="c in chips"
            :key="c.key"
            class="cx-chip"
            :class="category === c.key ? 'cx-chip--solid' : ''"
            style="cursor: pointer; height: 28px"
            @click="category = c.key"
          >{{ c.label }}</button>
        </div>
      </div>

      <div v-if="store.loading" class="cx-muted">{{ $t('grid.loading') }}</div>
      <div v-else-if="store.error" class="cx-alert cx-alert--error">{{ store.error }}</div>
      <div v-else-if="filtered.length === 0" class="cx-muted" style="text-align: center; padding: 40px 0">
        {{ $t('grid.empty') }}
      </div>

      <div v-else class="cx-tool-grid">
        <div v-for="p in filtered" :key="p.id" class="cx-card cx-card--hover cx-tool-card" @click="open(p)">
          <div class="cx-row" style="align-items: center; gap: 12px; margin-bottom: 10px">
            <span class="cx-avatar cx-tool-icon">
              <i v-if="p.icon" class="mdi" :class="'mdi-' + p.icon.replace(/^mdi-/, '')" />
              <span v-else style="font-weight: 700">{{ initials(p.name) }}</span>
            </span>
            <span class="cx-grow" style="font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">{{ p.name }}</span>
            <button
              class="cx-iconbtn cx-iconbtn--sm cx-tool-fav"
              :class="{ faved: store.favorites.has(p.id) }"
              :title="$t('grid.toggleFavorite')"
              @click.stop="store.toggleFavorite(p.id)"
            >
              <i class="mdi" :class="store.favorites.has(p.id) ? 'mdi-star' : 'mdi-star-outline'" />
            </button>
          </div>
          <div class="cx-muted cx-tool-desc">{{ p.description }}</div>
          <div class="cx-row" style="gap: 6px; flex-wrap: wrap; margin-top: 12px">
            <span class="cx-chip" :class="p.source === 'OFFICIAL' ? 'cx-chip--primary' : ''">
              {{ p.source === 'OFFICIAL' ? $t('source.official') : $t('source.third_party') }}
            </span>
            <span v-if="p.supportsAi" class="cx-chip cx-chip--success">{{ $t('badge.ai') }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cx-tool-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(268px, 1fr));
  gap: 12px;
}
.cx-tool-card { display: flex; flex-direction: column; }
.cx-tool-icon { width: 38px; height: 38px; border-radius: 11px; font-size: 15px; }
.cx-tool-icon .mdi { font-size: 20px; }
.cx-tool-desc {
  font-size: 13px;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 38px;
  flex: 1 1 auto;
}
/* Star: dim until hovered/faved */
.cx-tool-fav { opacity: 0; color: rgb(var(--v-theme-secondary)); }
.cx-tool-card:hover .cx-tool-fav { opacity: 1; }
.cx-tool-fav.faved { opacity: 1; color: #d9a441; }
</style>
