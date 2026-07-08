<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { usePluginsStore } from '@/stores/plugins'
import { useNavStore } from '@/stores/nav'
import type { PluginDescriptor } from '@/api/types'

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
</script>

<template>
  <div class="grid-page">
    <h1 class="section-header page-title">Tools</h1>

    <div v-if="store.loading" class="hint">Loading plugins…</div>
    <div v-else-if="store.error" class="hint error">{{ store.error }}</div>
    <div v-else-if="filtered.length === 0" class="hint">No tools in this category.</div>

    <div class="grid">
      <button v-for="p in filtered" :key="p.id" class="tool-card" @click="open(p)">
        <span class="chip" :data-style="p.iconStyle">{{ initials(p.name) }}</span>
        <span class="card-body">
          <span class="card-name">{{ p.name }}</span>
          <span class="card-desc">{{ p.description }}</span>
        </span>
        <span
          class="fav"
          :class="{ on: store.favorites.has(p.id) }"
          title="Toggle favorite"
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
</style>
