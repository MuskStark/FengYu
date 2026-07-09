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

// Nav keys are lowercase category ids (from /api/plugin-categories), but
// plugin.category serialises as the enum NAME (uppercase, e.g. "DEV"). Normalise
// at this filter boundary so both worlds match without touching either source.
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
  <div class="grid-page">
    <h1 class="section-header page-title">{{ $t('grid.title') }}</h1>

    <div v-if="store.loading" class="hint">{{ $t('grid.loading') }}</div>
    <div v-else-if="store.error" class="hint error">{{ store.error }}</div>
    <div v-else-if="filtered.length === 0" class="hint">{{ $t('grid.empty') }}</div>

    <div class="grid">
      <button v-for="p in filtered" :key="p.id" class="tool-card" @click="open(p)">
        <span class="chip" :data-style="p.iconStyle">{{ initials(p.name) }}</span>
        <span class="card-body">
          <span class="card-name">
            {{ p.name }}
            <span
              class="card-badge"
              :class="p.source === 'OFFICIAL' ? 'badge-official' : 'badge-third'"
              >{{ $t(sourceLabelKey(p.source)) }}</span
            >
            <span v-if="p.supportsAi" class="card-badge badge-ai">{{ $t('badge.ai') }}</span>
          </span>
          <span class="card-desc">{{ p.description }}</span>
        </span>
        <span
          class="fav"
          :class="{ on: store.favorites.has(p.id) }"
          :title="$t('grid.toggleFavorite')"
          @click.stop="store.toggleFavorite(p.id)"
          >★</span
        >
      </button>
    </div>
  </div>
</template>

<style scoped>
.grid-page {
  padding: 20px 24px;
}
.page-title {
  margin: 0 0 16px;
}
.hint {
  color: var(--sk-text-secondary);
  padding: 12px 0;
}
.hint.error {
  color: var(--sk-danger);
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}
.tool-card {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px;
  text-align: left;
  background: var(--sk-bg-elevated);
  border: 1px solid var(--sk-border);
  border-radius: 10px;
  cursor: pointer;
}
.tool-card:hover {
  border-color: var(--sk-accent);
  background: var(--sk-bg-hover);
}
.chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: var(--sk-accent);
  color: #fff;
  font-weight: 700;
  flex-shrink: 0;
}
.card-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.card-name {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-weight: 600;
  color: var(--sk-text);
}
.card-desc {
  font-size: 12px;
  color: var(--sk-text-secondary);
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
}
.fav {
  position: absolute;
  top: 10px;
  right: 12px;
  color: var(--sk-text-disabled);
  font-size: 14px;
}
.fav.on {
  color: var(--sk-warning);
}
/* ── Card badges (source + AI capability) ── */
.card-badge {
  display: inline-flex;
  align-items: center;
  font-size: 10px;
  font-weight: 600;
  line-height: 1;
  padding: 3px 6px;
  border-radius: 999px;
  letter-spacing: 0.02em;
  border: 1px solid transparent;
  white-space: nowrap;
}
/* Official = accent chip (trusted first-party). */
.badge-official {
  background: var(--sk-accent-soft);
  color: var(--sk-accent);
  border-color: var(--sk-accent);
}
/* Third-party = neutral chip. */
.badge-third {
  background: var(--sk-bg-hover);
  color: var(--sk-text-secondary);
  border-color: var(--sk-border);
}
/* AI capability badge — distinct accent for AI-ready plugins. */
.badge-ai {
  background: var(--sk-success-soft, var(--sk-accent-soft));
  color: var(--sk-success, var(--sk-accent));
  border-color: var(--sk-success, var(--sk-accent));
}
</style>
